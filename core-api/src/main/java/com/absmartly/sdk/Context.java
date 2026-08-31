package com.absmartly.sdk;

import java.io.Closeable;
import java.util.*;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java8.util.concurrent.CompletableFuture;
import java8.util.concurrent.CompletionException;
import java8.util.function.Consumer;
import java8.util.function.Function;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.absmartly.sdk.internal.Algorithm;
import com.absmartly.sdk.internal.Concurrency;
import com.absmartly.sdk.internal.VariantAssigner;
import com.absmartly.sdk.internal.hashing.Hashing;
import com.absmartly.sdk.java.nio.charset.StandardCharsets;
import com.absmartly.sdk.java.time.Clock;
import com.absmartly.sdk.json.*;

public class Context implements Closeable {
	public static Context create(@Nonnull final Clock clock, @Nonnull final ContextConfig config,
			@Nonnull final ScheduledExecutorService scheduler,
			@Nonnull final CompletableFuture<ContextData> dataFuture, @Nonnull final ContextDataProvider dataProvider,
			@Nonnull final ContextEventHandler eventHandler, @Nullable final ContextEventLogger eventLogger,
			@Nonnull final VariableParser variableParser, @Nonnull AudienceMatcher audienceMatcher) {
		return new Context(clock, config, scheduler, dataFuture, dataProvider, eventHandler, eventLogger,
				variableParser, audienceMatcher);
	}

	private Context(Clock clock, ContextConfig config, ScheduledExecutorService scheduler,
			CompletableFuture<ContextData> dataFuture, ContextDataProvider dataProvider,
			ContextEventHandler eventHandler, ContextEventLogger eventLogger, VariableParser variableParser,
			AudienceMatcher audienceMatcher) {
		clock_ = clock;
		publishDelay_ = config.getPublishDelay();
		refreshInterval_ = config.getRefreshInterval();
		eventHandler_ = eventHandler;
		eventLogger_ = config.getEventLogger() != null ? config.getEventLogger() : eventLogger;
		dataProvider_ = dataProvider;
		variableParser_ = variableParser;
		audienceMatcher_ = audienceMatcher;
		scheduler_ = scheduler;

		units_ = new HashMap<String, String>();

		final Map<String, String> units = config.getUnits();
		if (units != null) {
			setUnits(units);
		}

		assigners_ = new HashMap<String, VariantAssigner>(units_.size());
		hashedUnits_ = new HashMap<String, byte[]>(units_.size());

		final Map<String, Object> attributes = config.getAttributes();
		if (attributes != null) {
			setAttributes(attributes);
		}

		final Map<String, Integer> overrides = config.getOverrides();
		overrides_ = (overrides != null) ? new HashMap<String, Integer>(overrides) : new HashMap<String, Integer>();

		final Map<String, Integer> cassignments = config.getCustomAssignments();
		cassignments_ = (cassignments != null) ? new HashMap<String, Integer>(cassignments)
				: new HashMap<String, Integer>();

		if (dataFuture.isDone()) {
			dataFuture.thenAccept(new Consumer<ContextData>() {
				@Override
				public void accept(ContextData data) {
					Context.this.setData(data);
					Context.this.logEvent(ContextEventLogger.EventType.Ready, data);
				}
			}).exceptionally(new Function<Throwable, Void>() {
				@Override
				public Void apply(Throwable exception) {
					Context.this.setDataFailed(exception);
					Context.this.logError(exception);
					return null;
				}
			});
		} else {
			readyFuture_ = new CompletableFuture<Void>();
			dataFuture.thenAccept(new Consumer<ContextData>() {
				@Override
				public void accept(ContextData data) {
					Context.this.setData(data);
					readyFuture_.complete(null);
					readyFuture_ = null;

					Context.this.logEvent(ContextEventLogger.EventType.Ready, data);

					if (Context.this.getPendingCount() > 0) {
						Context.this.setTimeout();
					}
				}
			}).exceptionally(new Function<Throwable, Void>() {
				@Override
				public Void apply(Throwable exception) {
					Context.this.setDataFailed(exception);
					readyFuture_.complete(null);
					readyFuture_ = null;

					Context.this.logError(exception);

					return null;
				}
			});
		}
	}

	public boolean isReady() {
		return data_ != null;
	}

	public boolean isFailed() {
		return failed_;
	}

	public boolean isClosed() {
		return closed_.get();
	}

	public boolean isClosing() {
		return !closed_.get() && closing_.get();
	}

	public CompletableFuture<Context> waitUntilReadyAsync() {
		if (data_ != null) {
			return CompletableFuture.completedFuture(this);
		} else {
			return readyFuture_.thenApply(new Function<Void, Context>() {
				@Override
				public Context apply(Void k) {
					return Context.this;
				}
			});
		}
	}

	public Context waitUntilReady() {
		if (data_ == null) {
			final CompletableFuture<Void> future = readyFuture_; // cache here to avoid locking
			if (future != null && !future.isDone()) {
				future.join();
			}
		}
		return this;
	}

	public String[] getExperiments() {
		checkReady(true);

		try {
			dataLock_.readLock().lock();
			final String[] experimentNames = new String[data_.experiments.length];

			int index = 0;
			for (final Experiment experiment : data_.experiments) {
				experimentNames[index++] = experiment.name;
			}

			return experimentNames;
		} finally {
			dataLock_.readLock().unlock();
		}
	}

	public String[] getCustomFieldKeys() {
		final Set<String> keys = new HashSet<String>();

		try {
			dataLock_.readLock().lock();
			for (final Experiment experiment : data_.experiments) {
				if (experiment.customFieldValues != null) {
					for (final CustomFieldValue customFieldValue : experiment.customFieldValues) {
						keys.add(customFieldValue.getName());
					}
				}
			}

			return keys.toArray(new String[0]);
		} finally {
			dataLock_.readLock().unlock();
		}
	}

	public Object getCustomFieldValue(@Nonnull final String experimentName, @Nonnull final String key) {
		try {
			dataLock_.readLock().lock();
			final ContextExperiment experiment = index_.get(experimentName);
			if (experiment != null) {
				final ContextCustomFieldValue field = experiment.customFieldValues.get(key);
				if (field != null) {
					return field.value;
				}
			}
			return null;
		} finally {
			dataLock_.readLock().unlock();
		}
	}

	public Object getCustomFieldValueType(@Nonnull final String experimentName, @Nonnull final String key) {
		try {
			dataLock_.readLock().lock();
			final ContextExperiment experiment = index_.get(experimentName);
			if (experiment != null) {
				final ContextCustomFieldValue field = experiment.customFieldValues.get(key);
				if (field != null) {
					return field.type;
				}
			}
			return null;
		} finally {
			dataLock_.readLock().unlock();
		}
	}

	public ContextData getData() {
		checkReady(true);

		try {
			dataLock_.readLock().lock();
			return data_;
		} finally {
			dataLock_.readLock().unlock();
		}
	}

	public void setOverride(@Nonnull final String experimentName, final int variant) {
		checkNotClosed();

		Concurrency.putRW(contextLock_, overrides_, experimentName, variant);
	}

	public Integer getOverride(@Nonnull final String experimentName) {
		return Concurrency.getRW(contextLock_, overrides_, experimentName);
	}

	public void setOverrides(@Nonnull final Map<String, Integer> overrides) {
		for (Map.Entry<String, Integer> entry : overrides.entrySet()) {
			String key = entry.getKey();
			Integer value = entry.getValue();
			setOverride(key, value);
		}
	}

	public void setCustomAssignment(@Nonnull final String experimentName, final int variant) {
		checkNotClosed();

		Concurrency.putRW(contextLock_, cassignments_, experimentName, variant);
	}

	public Integer getCustomAssignment(@Nonnull final String experimentName) {
		return Concurrency.getRW(contextLock_, cassignments_, experimentName);
	}

	public void setCustomAssignments(@Nonnull final Map<String, Integer> customAssignments) {
		for (Map.Entry<String, Integer> entry : customAssignments.entrySet()) {
			String key = entry.getKey();
			Integer value = entry.getValue();
			setCustomAssignment(key, value);
		}
	}

	public String getUnit(@Nonnull final String unitType) {
		final ReentrantReadWriteLock.ReadLock readLock = contextLock_.readLock();
		try {
			readLock.lock();
			return units_.get(unitType);
		} finally {
			readLock.unlock();
		}
	}

	public void setUnit(@Nonnull final String unitType, @Nonnull final String uid) {
		checkNotClosed();

		final ReentrantReadWriteLock.WriteLock writeLock = contextLock_.writeLock();
		try {
			writeLock.lock();

			final String previous = units_.get(unitType);
			if ((previous != null) && !previous.equals(uid)) {
				throw new IllegalArgumentException(String.format("Unit '%s' already set.", unitType));
			}

			final String trimmed = uid.trim();
			if (trimmed.isEmpty()) {
				throw new IllegalArgumentException(String.format("Unit '%s' UID must not be blank.", unitType));
			}

			units_.put(unitType, trimmed);

			invalidateAssignmentsPinnedWithMissingUnit(unitType);
		} finally {
			writeLock.unlock();
		}
	}

	// holdoutAssignments pins the holdout decisions used to compute suppression. A null entry means
	// the covered experiment's unit was unavailable; resolving it live later could publish a holdout
	// verdict inconsistent with the cached experiment decision, so setUnit evicts the assignment and
	// lets getAssignment recompute the decision and exposures together.
	//
	// Holdouts in this snapshot are resolved using assignment.unitType, not each holdout's declared
	// unitType, so only that unit installation invalidates a null entry.
	//
	// Only unexposed assignments are evicted. Eviction creates a new exposed flag, so evicting an
	// already-exposed assignment could publish a duplicate or contradictory experiment exposure.
	// This is not protecting a pristine record: publish() reads event.units from the live units_
	// map, so an exposure queued before this setUnit call may already carry the late unit. The
	// decision behind it was still made without that unit, and recomputation cannot repair a
	// record already queued - it can only add a second, conflicting one. The guard avoids
	// compounding a degraded record, rather than pretending it is correct.
	private void invalidateAssignmentsPinnedWithMissingUnit(final String unitType) {
		final Iterator<Assignment> it = assignmentCache_.values().iterator();
		while (it.hasNext()) {
			final Assignment assignment = it.next();
			if (assignment.exposed.get()) {
				continue;
			}

			final Assignment[] holdoutAssignments = assignment.holdoutAssignments;
			if ((holdoutAssignments != null) && unitType.equals(assignment.unitType)) {
				for (final Assignment holdoutAssignment : holdoutAssignments) {
					if (holdoutAssignment == null) {
						it.remove();
						break;
					}
				}
			}
		}
	}

	public Map<String, String> getUnits() {
		final ReentrantReadWriteLock.ReadLock readLock = contextLock_.readLock();
		try {
			readLock.lock();
			return new HashMap<String, String>(units_);
		} finally {
			readLock.unlock();
		}
	}

	public void setUnits(@Nonnull final Map<String, String> units) {
		for (Map.Entry<String, String> entry : units.entrySet()) {
			String key = entry.getKey();
			String value = entry.getValue();
			setUnit(key, value);
		}
	}

	public Object getAttribute(@Nonnull final String name) {
		final ReentrantReadWriteLock.ReadLock readLock = contextLock_.readLock();
		try {
			readLock.lock();
			for (int i = attributes_.size(); i-- > 0;) {
				final Attribute attr = attributes_.get(i);
				if (name.equals(attr.name)) {
					return attr.value;
				}
			}

			return null;
		} finally {
			readLock.unlock();
		}
	}

	public void setAttribute(@Nonnull final String name, @Nullable final Object value) {
		checkNotClosed();

		Concurrency.addRW(contextLock_, attributes_, new Attribute(name, value, clock_.millis()));
	}

	public Map<String, Object> getAttributes() {
		final HashMap<String, Object> result = new HashMap<String, Object>(attributes_.size());
		final ReentrantReadWriteLock.ReadLock readLock = contextLock_.readLock();
		try {
			readLock.lock();
			for (final Attribute attr : attributes_) {
				result.put(attr.name, attr.value);
			}
			return result;
		} finally {
			readLock.unlock();
		}
	}

	public void setAttributes(@Nonnull final Map<String, Object> attributes) {
		for (Map.Entry<String, Object> entry : attributes.entrySet()) {
			String key = entry.getKey();
			Object value = entry.getValue();
			setAttribute(key, value);
		}
	}

	public int getTreatment(@Nonnull final String experimentName) {
		checkReady(true);

		final Assignment assignment = getAssignment(experimentName);
		if (!assignment.exposed.get()) {
			triggerExposure(assignment);
		}

		return assignment.variant;
	}

	// A suppressed assignment (held out by an applicable holdout) never publishes its own
	// exposure - the holdout experiment's own exposure is the sole membership record. Firing
	// either exposure still triggers evaluation of every holdout applicable to this unit type,
	// keeping both holdout arms symmetric regardless of which covered experiment triggered it.
	// The trigger loop below must run even if the exposure above throws (e.g. a
	// ContextEventLogger implementation that throws): `exposed` is already CAS'd true by the
	// time we get here, so a skipped holdout trigger would never be retried for this context's
	// life. Failures are collected and re-thrown once every holdout has had a chance to fire,
	// rather than swallowed or allowed to abort the loop early.
	private void triggerExposure(final Assignment assignment) {
		if (assignment.exposed.compareAndSet(false, true)) {
			RuntimeException failure = null;
			try {
				if (!assignment.suppressed) {
					enqueueExposure(assignment);
				}
			} catch (final RuntimeException e) {
				failure = e;
			}

			final RuntimeException holdoutFailure = triggerApplicableHoldoutExposures(assignment);
			if (failure == null) {
				failure = holdoutFailure;
			}

			if (failure != null) {
				throw failure;
			}
		}
	}

	// Fires every holdout applicable to the given assignment, independent of whether the covered
	// experiment that surfaced them is the one ultimately selected for a treatment/variable
	// lookup: the contract fires on first evaluation, not first selection. When the assignment
	// carries a pinned holdoutAssignments snapshot (see Assignment.holdoutAssignments), each
	// entry is fired directly rather than re-resolved, so the exposure always reflects the exact
	// epoch the suppression decision was made from - a refresh landing between decision and
	// trigger can never publish a holdout exposure from a different iteration than the one that
	// governed this assignment. The override path never takes that snapshot, so it falls back to
	// a live-by-id resolution. One throwing logger must not stop siblings, so failures are
	// collected and the first one re-thrown only after every holdout has had a chance to fire.
	private RuntimeException triggerApplicableHoldoutExposures(final Assignment assignment) {
		RuntimeException failure = null;
		final Experiment[] holdouts = assignment.holdouts;
		if (holdouts != null) {
			final Assignment[] pinned = assignment.holdoutAssignments;
			for (int i = 0; i < holdouts.length; ++i) {
				try {
					if (pinned != null) {
						triggerHoldoutExposure(pinned[i]);
					} else {
						triggerHoldoutExposure(holdouts[i], assignment.unitType);
					}
				} catch (final RuntimeException e) {
					if (failure == null) {
						failure = e;
					}
				}
			}
		}
		return failure;
	}

	private void triggerHoldoutExposure(final Experiment holdoutExperiment, final String unitType) {
		triggerHoldoutExposure(getHoldoutAssignment(holdoutExperiment, unitType));
	}

	private void triggerHoldoutExposure(final Assignment holdoutAssignment) {
		if ((holdoutAssignment != null) && holdoutAssignment.exposed.compareAndSet(false, true)) {
			enqueueExposure(holdoutAssignment);
		}
	}

	// Every enqueued exposure - ordinary or holdout, from any of getTreatment, triggerExposure's
	// holdout loop, or the variable-key path's per-candidate holdout firing - schedules its own
	// flush here rather than relying on the caller: a caller can enqueue a holdout exposure
	// without ever enqueueing its own (e.g. an already-exposed winner, or a losing variable-key
	// candidate that only fires holdouts), and setTimeout() is idempotent, so centralizing the
	// call is strictly safer than tracking every call site individually.
	private void enqueueExposure(final Assignment assignment) {
		final Exposure exposure = new Exposure();
		exposure.id = assignment.id;
		exposure.name = assignment.name;
		exposure.unit = assignment.unitType;
		exposure.variant = assignment.variant;
		exposure.exposedAt = clock_.millis();
		exposure.assigned = assignment.assigned;
		exposure.eligible = assignment.eligible;
		exposure.overridden = assignment.overridden;
		exposure.fullOn = assignment.fullOn;
		exposure.custom = assignment.custom;
		exposure.audienceMismatch = assignment.audienceMismatch;

		try {
			eventLock_.lock();
			pendingCount_.incrementAndGet();
			exposures_.add(exposure);
		} finally {
			eventLock_.unlock();
		}

		try {
			logEvent(ContextEventLogger.EventType.Exposure, exposure);
		} finally {
			// Scheduled even if the callback above throws: the exposure is already appended and
			// counted, so a skipped schedule would leave it queued with nothing left to flush it
			// (setTimeout is idempotent, so scheduling here is never a duplicate concern).
			setTimeout();
		}
	}

	public int peekTreatment(@Nonnull final String experimentName) {
		checkReady(true);

		return getAssignment(experimentName).variant;
	}

	public Map<String, List<String>> getVariableKeys() {
		checkReady(true);

		final Map<String, List<String>> variableKeys = new HashMap<String, List<String>>(indexVariables_.size());

		try {
			dataLock_.readLock().lock();
			for (Map.Entry<String, List<ContextExperiment>> entry : indexVariables_.entrySet()) {
				final String key = entry.getKey();
				final List<ContextExperiment> keyExperimentVariables = entry.getValue();
				final List<String> values = new ArrayList<String>(keyExperimentVariables.size());

				for (final ContextExperiment experimentVariables : keyExperimentVariables) {
					values.add(experimentVariables.data.name);
				}
				variableKeys.put(key, values);
			}
		} finally {
			dataLock_.readLock().unlock();
		}
		return variableKeys;
	}

	public Object getVariableValue(@Nonnull final String key, final Object defaultValue) {
		checkReady(true);

		final Assignment assignment = getVariableAssignment(key, false);
		if (assignment != null) {
			if (assignment.variables != null) {
				if (!assignment.exposed.get()) {
					triggerExposure(assignment);
				}

				if (assignment.variables.containsKey(key)) {
					return assignment.variables.get(key);
				}
			}
		}
		return defaultValue;
	}

	public Object peekVariableValue(@Nonnull final String key, final Object defaultValue) {
		checkReady(true);

		final Assignment assignment = getVariableAssignment(key, true);
		if (assignment != null) {
			if (assignment.variables != null) {
				if (assignment.variables.containsKey(key)) {
					return assignment.variables.get(key);
				}
			}
		}
		return defaultValue;
	}

	public void track(@Nonnull final String goalName, final Map<String, Object> properties) {
		checkNotClosed();

		final GoalAchievement achievement = new GoalAchievement();
		achievement.achievedAt = clock_.millis();
		achievement.name = goalName;
		achievement.properties = (properties == null) ? null : new TreeMap<String, Object>(properties);

		try {
			eventLock_.lock();
			pendingCount_.incrementAndGet();
			achievements_.add(achievement);
		} finally {
			eventLock_.unlock();
		}

		logEvent(ContextEventLogger.EventType.Goal, achievement);

		setTimeout();
	}

	public CompletableFuture<Void> publishAsync() {
		checkNotClosed();

		return flush();
	}

	public void publish() {
		publishAsync().join();
	}

	public int getPendingCount() {
		return pendingCount_.get();
	}

	public CompletableFuture<Void> refreshAsync() {
		checkNotClosed();

		if (refreshing_.compareAndSet(false, true)) {
			refreshFuture_ = new CompletableFuture<Void>();

			dataProvider_.getContextData().thenAccept(new Consumer<ContextData>() {
				@Override
				public void accept(ContextData data) {
					Context.this.setData(data);
					refreshing_.set(false);
					refreshFuture_.complete(null);

					Context.this.logEvent(ContextEventLogger.EventType.Refresh, data);
				}
			}).exceptionally(new Function<Throwable, Void>() {
				@Override
				public Void apply(Throwable exception) {
					refreshing_.set(false);
					refreshFuture_.completeExceptionally(exception);

					Context.this.logError(exception);
					return null;
				}
			});
		}

		final CompletableFuture<Void> future = refreshFuture_;
		if (future != null) {
			return future;
		}

		return CompletableFuture.completedFuture(null);
	}

	public void refresh() {
		refreshAsync().join();
	}

	public CompletableFuture<Void> closeAsync() {
		if (!closed_.get()) {
			if (closing_.compareAndSet(false, true)) {
				clearRefreshTimer();

				if (pendingCount_.get() > 0) {
					closingFuture_ = new CompletableFuture<Void>();

					flush().thenAccept(new Consumer<Void>() {
						@Override
						public void accept(Void x) {
							closed_.set(true);
							closing_.set(false);
							closingFuture_.complete(null);

							Context.this.logEvent(ContextEventLogger.EventType.Close, null);
						}
					}).exceptionally(new Function<Throwable, Void>() {
						@Override
						public Void apply(Throwable exception) {
							closed_.set(true);
							closing_.set(false);
							closingFuture_.completeExceptionally(exception);
							// event logger gets this error during publish

							return null;
						}
					});

					return closingFuture_;
				} else {
					closed_.set(true);
					closing_.set(false);

					Context.this.logEvent(ContextEventLogger.EventType.Close, null);
				}
			}

			final CompletableFuture<Void> future = closingFuture_;
			if (future != null) {
				return future;
			}
		}

		return CompletableFuture.completedFuture(null);
	}

	@Override
	public void close() {
		closeAsync().join();
	}

	private CompletableFuture<Void> flush() {
		clearTimeout();

		if (!failed_) {
			if (pendingCount_.get() > 0) {
				Exposure[] exposures = null;
				GoalAchievement[] achievements = null;
				int eventCount;

				try {
					eventLock_.lock();
					eventCount = pendingCount_.get();

					if (eventCount > 0) {
						if (!exposures_.isEmpty()) {
							exposures = exposures_.toArray(new Exposure[0]);
							exposures_.clear();
						}

						if (!achievements_.isEmpty()) {
							achievements = achievements_.toArray(new GoalAchievement[0]);
							achievements_.clear();
						}

						pendingCount_.set(0);
					}
				} finally {
					eventLock_.unlock();
				}

				if (eventCount > 0) {
					final PublishEvent event = new PublishEvent();
					event.hashed = true;
					event.publishedAt = clock_.millis();
					event.units = Algorithm.mapSetToArray(units_.entrySet(), new Unit[0],
							new Function<Map.Entry<String, String>, Unit>() {
								@Override
								public Unit apply(Map.Entry<String, String> entry) {
									return new Unit(entry.getKey(),
											new String(getUnitHash(entry.getKey(), entry.getValue()),
													StandardCharsets.US_ASCII));
								}
							});
					event.attributes = attributes_.isEmpty() ? null : attributes_.toArray(new Attribute[0]);
					event.exposures = exposures;
					event.goals = achievements;

					final CompletableFuture<Void> result = new CompletableFuture<Void>();

					eventHandler_.publish(this, event).thenRunAsync(new Runnable() {
						@Override
						public void run() {
							Context.this.logEvent(ContextEventLogger.EventType.Publish, event);
							result.complete(null);
						}
					}).exceptionally(new Function<Throwable, Void>() {
						@Override
						public Void apply(Throwable throwable) {
							Context.this.logError(throwable);

							result.completeExceptionally(throwable);
							return null;
						}
					});

					return result;
				}
			}
		} else {
			try {
				eventLock_.lock();
				exposures_.clear();
				achievements_.clear();
				pendingCount_.set(0);
			} finally {
				eventLock_.unlock();
			}
		}

		return CompletableFuture.completedFuture(null);
	}

	private void checkNotClosed() {
		if (closed_.get()) {
			throw new IllegalStateException("ABSmartly Context is closed");
		} else if (closing_.get()) {
			throw new IllegalStateException("ABSmartly Context is closing");
		}
	}

	private void checkReady(final boolean expectNotClosed) {
		if (!isReady()) {
			throw new IllegalStateException("ABSmartly Context is not yet ready");
		} else if (expectNotClosed) {
			checkNotClosed();
		}
	}

	private boolean experimentMatches(final ContextExperiment experiment, final Assignment assignment) {
		return experiment.data.id == assignment.id &&
				experiment.data.unitType.equals(assignment.unitType) &&
				experiment.data.iteration == assignment.iteration &&
				experiment.data.fullOnVariant == assignment.fullOnVariant &&
				Arrays.equals(experiment.data.trafficSplit, assignment.trafficSplit) &&
				holdoutSetMatches(experiment.holdouts, assignment.holdouts);
	}

	// Applicable-holdout identity for cache-validity purposes is (id, iteration) per entry, in
	// resolution order - the same fields experimentMatches already uses to identify an ordinary
	// experiment's own run, and nothing finer. Seed, split, name, variants, applications, audience
	// and customFieldValues can all change without altering who is covered or which arm a unit
	// lands in, so comparing whole Experiment objects (Arrays.equals delegates to
	// Experiment.equals) would invalidate on purely cosmetic edits and force a duplicate exposure.
	// A change in the resolved set - membership added/removed, or an id/iteration change on an
	// existing entry - does alter coverage and must invalidate.
	private static boolean holdoutSetMatches(final Experiment[] a, final Experiment[] b) {
		if (a == b) {
			return true;
		} else if ((a == null) || (b == null) || (a.length != b.length)) {
			return false;
		}

		for (int i = 0; i < a.length; ++i) {
			if ((a[i].id != b[i].id) || (a[i].iteration != b[i].iteration)) {
				return false;
			}
		}

		return true;
	}

	// A custom assignment can only take effect on the normal, traffic-eligible assignment path.
	// When the cached variant was forced by a higher-precedence rule — a full-on variant, traffic
	// ineligibility, a strict audience mismatch, or holdout suppression (which clears `assigned`) —
	// the custom value can never equal that variant, so comparing the two would spuriously
	// invalidate the cache and re-expose on every getTreatment call. Treat those forced
	// assignments as cache-valid regardless of the custom assignment.
	private static boolean variantForcedRegardlessOfCustom(final Assignment assignment) {
		return assignment.fullOn || !assignment.eligible || !assignment.assigned;
	}

	private static class Assignment {
		int id;
		int iteration;
		int fullOnVariant;
		String name;
		String unitType;
		double[] trafficSplit;
		int variant;
		// Arm count the holdout's own arm (variant, above) was computed against. Set only for a
		// holdout's own Assignment (see getHoldoutAssignment); unused for ordinary experiments.
		int armCount;
		boolean assigned;
		boolean overridden;
		boolean eligible;
		boolean fullOn;
		boolean custom;

		boolean audienceMismatch;
		// Held out by a union of applicable holdouts: no exposure for this experiment, control
		// values only. `holdouts` is the resolved applicable list (by id+iteration identity, see
		// holdoutSetMatches), used to invalidate this cached assignment when coverage changes.
		boolean suppressed;
		Experiment[] holdouts;
		// The holdout Assignment objects the suppression decision above was made from, in the
		// same order as `holdouts`, resolved via getHoldoutAssignment at the same instant as the
		// decision (an entry is null only for an unconfigured unit type). triggerExposure fires
		// exactly these rather than re-resolving `holdouts` against whatever data is live by the
		// time exposure runs, so a refresh landing in between can never publish an exposure for a
		// different epoch than the one the decision was made from. Null when no such snapshot was
		// taken (the override path, which never decides suppression from holdouts); the trigger
		// falls back to a live resolution of `holdouts` in that case.
		Assignment[] holdoutAssignments;
		Map<String, Object> variables = Collections.emptyMap();

		final AtomicBoolean exposed = new AtomicBoolean(false);
	}

	// Pins the (iteration, unitType) a holdout's Assignment was computed against. This is the same
	// granularity experimentMatches uses for ordinary experiments: seedHi/seedLo/split are
	// deliberately excluded so a live seed or percentage edit - which does not change who is
	// covered - never invalidates an already-exposed unit's arm. Only an iteration bump, a genuine
	// re-randomization epoch, replaces the cached verdict (and its `exposed` flag), exactly as it
	// does for ordinary experiments. armCount is deliberately excluded here too: it is pinned on
	// Assignment itself (see armCount below), bundled with the arm number it was computed
	// against, so the cached arm is always interpreted under its own arity rather than being
	// invalidated and re-exposed under a new one for a same-iteration arity change.
	private static class HoldoutAssignment {
		final Assignment assignment;
		final int iteration;
		final String unitType;

		HoldoutAssignment(Assignment assignment, Experiment holdout, String unitType) {
			this.assignment = assignment;
			this.iteration = holdout.iteration;
			this.unitType = unitType;
		}

		boolean matches(Experiment current, String currentUnitType) {
			return (iteration == current.iteration) && unitType.equals(currentUnitType);
		}
	}

	private Assignment getAssignment(final String experimentName) {
		final ReentrantReadWriteLock.ReadLock readLock = contextLock_.readLock();
		try {
			readLock.lock();

			final Assignment assignment = assignmentCache_.get(experimentName);

			if (assignment != null) {
				final Integer custom = cassignments_.get(experimentName);
				final Integer override = overrides_.get(experimentName);
				final ContextExperiment experiment = Context.this.getExperiment(experimentName);

				if (override != null) {
					// An override still evaluates the experiment for holdout purposes (see the
					// write path below), so the fast path must revalidate the applicable-holdout
					// set exactly like the ordinary branch does via experimentMatches - otherwise
					// a holdout that becomes applicable after a refresh never fires for an
					// already-overridden experiment, and stays that way forever.
					if (assignment.overridden && assignment.variant == override
							&& holdoutSetMatches((experiment != null) ? experiment.holdouts : null,
									assignment.holdouts)) {
						// override and holdout coverage both up-to-date
						return assignment;
					}
				} else if (experiment == null) {
					if (!assignment.assigned) {
						// previously not-running experiment
						return assignment;
					}
				} else if ((custom == null) || variantForcedRegardlessOfCustom(assignment)
						|| custom == assignment.variant) {
					if (experimentMatches(experiment, assignment)) {
						// assignment up-to-date
						return assignment;
					}
				}
			}
		} finally {
			readLock.unlock();
		}

		// cache miss or out-dated
		final ReentrantReadWriteLock.WriteLock writeLock = contextLock_.writeLock();
		try {
			writeLock.lock();

			final Integer custom = cassignments_.get(experimentName);
			final Integer override = overrides_.get(experimentName);
			final ContextExperiment experiment = Context.this.getExperiment(experimentName);

			final Assignment assignment = new Assignment();
			assignment.name = experimentName;
			assignment.eligible = true;

			if (override != null) {
				if (experiment != null) {
					assignment.id = experiment.data.id;
					assignment.unitType = experiment.data.unitType;

					// An override still evaluates the experiment - only its variant is replaced -
					// so the applicable holdouts must fire exactly as they would for a normal
					// assignment. Override precedence itself is untouched: the returned variant
					// stays the overridden one.
					assignment.holdouts = experiment.holdouts;
				}

				assignment.overridden = true;
				assignment.variant = override;
			} else {
				if (experiment != null) {
					final String unitType = experiment.data.unitType;

					// Share the experiment's applicable-holdouts array by reference rather than
					// copying: it is only read here (and via Arrays.equals in experimentMatches) and
					// experiment data is treated as immutable once installed by setData, so the
					// aliasing is safe.
					assignment.holdouts = experiment.holdouts;

					boolean suppressed = false;
					if (experiment.holdouts != null && experiment.holdouts.length > 0) {
						// Union across every applicable holdout: any one of them holding this
						// experiment out suppresses its own exposure and forces control values. Each
						// holdout's arm AND the arm count it was computed against are read from its
						// pinned HoldoutAssignment (see getHoldoutAssignment) rather than recomputed
						// from the live definition here, so suppression and the holdout's own
						// exposure always agree, even after a same-iteration seed/split refresh.
						// Every applicable holdout is resolved - the loop never stops at the first
						// one that suppresses - because each one still owes its own exposure, and the
						// resolved objects are pinned below (Assignment.holdoutAssignments) so
						// triggerExposure fires exactly this decision's snapshot rather than
						// re-resolving `holdouts` against data that may have moved to a different
						// iteration by the time exposure runs.
						final Assignment[] holdoutAssignments = new Assignment[experiment.holdouts.length];
						for (int i = 0; i < experiment.holdouts.length; ++i) {
							final Assignment holdoutAssignment = Context.this
									.getHoldoutAssignment(experiment.holdouts[i], unitType);
							holdoutAssignments[i] = holdoutAssignment;
							if ((holdoutAssignment != null) && isHeldOutBy(holdoutAssignment.variant,
									holdoutAssignment.armCount, experiment.data.fullOnVariant)) {
								suppressed = true;
							}
						}
						assignment.holdoutAssignments = holdoutAssignments;
					}
					assignment.suppressed = suppressed;

					if (suppressed) {
						// A held-out unit is not a participant in this experiment: assigned stays
						// false so it never wins variable-key resolution against a genuinely
						// assigned experiment and never fires this experiment's own exposure.
						assignment.variant = 0;
					} else {
						if (experiment.data.audience != null && experiment.data.audience.length() > 0) {
							final Map<String, Object> attrs = new HashMap<String, Object>(attributes_.size());
							for (final Attribute attr : attributes_) {
								attrs.put(attr.name, attr.value);
							}

							final AudienceMatcher.Result match = audienceMatcher_
									.evaluate(experiment.data.audience, attrs);
							if (match != null) {
								assignment.audienceMismatch = !match.get();
							}
						}

						if (experiment.data.audienceStrict && assignment.audienceMismatch) {
							assignment.variant = 0;
						} else if (experiment.data.fullOnVariant == 0) {
							final String uid = units_.get(unitType);
							if (uid != null) {
								final byte[] unitHash = Context.this.getUnitHash(unitType, uid);

								final VariantAssigner assigner = Context.this.getVariantAssigner(unitType,
										unitHash);
								final boolean eligible = assigner.assign(experiment.data.trafficSplit,
										experiment.data.trafficSeedHi,
										experiment.data.trafficSeedLo) == 1;
								if (eligible) {
									if (custom != null) {
										assignment.variant = custom;
										assignment.custom = true;
									} else {
										assignment.variant = assigner.assign(experiment.data.split,
												experiment.data.seedHi,
												experiment.data.seedLo);
									}
								} else {
									assignment.eligible = false;
									assignment.variant = 0;
								}
								assignment.assigned = true;
							}
						} else {
							assignment.assigned = true;
							assignment.variant = experiment.data.fullOnVariant;
							assignment.fullOn = true;
						}
					}

					assignment.unitType = unitType;
					assignment.id = experiment.data.id;
					assignment.iteration = experiment.data.iteration;
					assignment.trafficSplit = experiment.data.trafficSplit;
					assignment.fullOnVariant = experiment.data.fullOnVariant;
				}
			}

			if ((experiment != null) && (assignment.variant < experiment.data.variants.length)) {
				assignment.variables = experiment.variables.get(assignment.variant);
			}

			assignmentCache_.put(experimentName, assignment);

			return assignment;
		} finally {
			writeLock.unlock();
		}
	}

	// Arm count is the pinned count the holdout's own arm was computed against (Assignment.armCount
	// from getHoldoutAssignment), never read live from holdout.split.length here: the live split
	// can change within the same iteration (see HoldoutAssignment), and re-deriving arity from it
	// would reinterpret an already-pinned arm number under a different meaning. Per applicable
	// holdout H and covered experiment X: H.variant==0 always holds X out. In a 3-arm H,
	// variant==1 holds X out only when X.fullOnVariant==0 (X is not full-on); when
	// X.fullOnVariant!=0, X defers to its normal assignment path exactly as variant==2 would, so
	// audienceStrict is evaluated before the full-on variant is assigned and arm 1/2 never diverge
	// for audience-unrelated reasons. A 2-arm H's variant==1 and a 3-arm H's variant==2 both defer
	// to the normal path. A null or shorter-than-2 split cannot express arm 1's 3-arm meaning, so
	// it is treated as not-held-out unless variant 0.
	private static boolean isHeldOutBy(final int holdoutVariant, final int holdoutArmCount,
			final int fullOnVariant) {
		if (holdoutVariant == 0) {
			return true;
		}

		if (holdoutArmCount == 3 && holdoutVariant == 1) {
			return fullOnVariant == 0;
		}

		return false;
	}

	// A suppressed (held-out) experiment is not a participant, so it never wins resolution over
	// a genuinely assigned or overridden experiment sharing the same variable key. It is used
	// only as a fallback, so a held-out unit still reads control values and still triggers this
	// experiment's holdouts — symmetric with the non-held-out path — when nothing else claims the
	// key.
	//
	// Every candidate reached while resolving the key was genuinely evaluated, whether or not it
	// ends up the one returned, so on the non-peek path each candidate's applicable holdouts fire
	// as it is visited - the ordinary experiment exposure is still emitted only for the winner,
	// by the caller. peek must stay side-effect free, so it skips triggering entirely.
	private Assignment getVariableAssignment(final String key, final boolean peek) {
		final List<ContextExperiment> keyExperimentVariables = getVariableExperiments(key);

		if (keyExperimentVariables != null) {
			Assignment suppressedFallback = null;
			RuntimeException failure = null;
			for (final ContextExperiment experimentVariables : keyExperimentVariables) {
				final Assignment assignment = getAssignment(experimentVariables.data.name);
				if (!peek) {
					final RuntimeException holdoutFailure = triggerApplicableHoldoutExposures(assignment);
					if (failure == null) {
						failure = holdoutFailure;
					}
				}
				if (assignment.assigned || assignment.overridden) {
					if (failure != null) {
						throw failure;
					}
					return assignment;
				}
				if (assignment.suppressed && suppressedFallback == null) {
					suppressedFallback = assignment;
				}
			}
			if (failure != null) {
				throw failure;
			}
			if (suppressedFallback != null) {
				return suppressedFallback;
			}
		}
		return null;
	}

	private ContextExperiment getExperiment(final String experimentName) {
		try {
			dataLock_.readLock().lock();
			return index_.get(experimentName);
		} finally {
			dataLock_.readLock().unlock();
		}
	}

	// Id-keyed lookup against the currently installed data, used to resolve a holdout's live
	// definition regardless of which (possibly stale) Experiment reference a caller is holding.
	private Experiment getHoldoutById(final int holdoutId) {
		try {
			dataLock_.readLock().lock();
			return holdoutsById_.get(holdoutId);
		} finally {
			dataLock_.readLock().unlock();
		}
	}

	// The holdout's own assignment is cached by holdout id rather than name, since holdout
	// entries live outside the experiments index and are shared by reference across every
	// covered experiment. Only an iteration change invalidates the cache entry (see
	// HoldoutAssignment), matching experimentMatches's treatment of ordinary experiments and
	// guaranteeing an already-exposed unit's arm survives any seed, split or cosmetic edit.
	//
	// The `holdout` parameter can be a stale Experiment reference: callers reach this method via
	// a cached Assignment.holdouts array that may predate the most recent setData (e.g. the
	// override fast path, or a refresh racing between getAssignment and triggerExposure). Trusting
	// its iteration would let a dead definition overwrite a cache entry that a concurrent,
	// genuinely newer evaluation already installed, producing a duplicate same-epoch exposure with
	// a contradictory arm. Resolving by id against the currently-installed data before every
	// matches()/compute step removes that ambiguity entirely: id is stable across installs, so the
	// live lookup always reflects the newest definition, and a stale caller-supplied reference can
	// never appear "newer" than what is actually installed.
	private Assignment getHoldoutAssignment(final Experiment holdout, final String unitType) {
		final ReentrantReadWriteLock.ReadLock readLock = contextLock_.readLock();
		try {
			readLock.lock();

			final String uid = units_.get(unitType);
			if (uid == null) {
				return null;
			}

			final Experiment liveHoldout = resolveLiveHoldout(holdout);
			final HoldoutAssignment cached = holdoutAssignmentCache_.get(holdout.id);
			if ((cached != null) && cached.matches(liveHoldout, unitType)) {
				return cached.assignment;
			}
		} finally {
			readLock.unlock();
		}

		// Cache miss: recheck under the write lock before computing so two racing threads never
		// install two different Assignment objects for the same holdout id.
		final ReentrantReadWriteLock.WriteLock writeLock = contextLock_.writeLock();
		try {
			writeLock.lock();

			final String uid = units_.get(unitType);
			if (uid == null) {
				return null;
			}

			final Experiment liveHoldout = resolveLiveHoldout(holdout);
			final HoldoutAssignment cached = holdoutAssignmentCache_.get(holdout.id);
			if ((cached != null) && cached.matches(liveHoldout, unitType)) {
				return cached.assignment;
			}

			final byte[] unitHash = Context.this.getUnitHash(unitType, uid);
			final VariantAssigner assigner = Context.this.getVariantAssigner(unitType, unitHash);

			final Assignment assignment = new Assignment();
			assignment.id = liveHoldout.id;
			assignment.name = liveHoldout.name;
			assignment.iteration = liveHoldout.iteration;
			assignment.unitType = unitType;
			assignment.eligible = true;
			assignment.assigned = true;
			assignment.variant = assigner.assign(liveHoldout.split, liveHoldout.seedHi, liveHoldout.seedLo);
			assignment.armCount = (liveHoldout.split != null) ? liveHoldout.split.length : 0;

			holdoutAssignmentCache_.put(liveHoldout.id, new HoldoutAssignment(assignment, liveHoldout, unitType));

			return assignment;
		} finally {
			writeLock.unlock();
		}
	}

	// Falls back to the caller-supplied reference only when the id is absent from the currently
	// installed data (e.g. a holdout removed or invalidated by the latest refresh); there is no
	// newer definition to prefer over it in that case.
	private Experiment resolveLiveHoldout(final Experiment holdout) {
		final Experiment live = Context.this.getHoldoutById(holdout.id);
		return (live != null) ? live : holdout;
	}

	private List<ContextExperiment> getVariableExperiments(final String key) {
		return Concurrency.getRW(dataLock_, indexVariables_, key);
	}

	private byte[] getUnitHash(final String unitType, final String unitUID) {
		return Concurrency.computeIfAbsentRW(contextLock_, hashedUnits_, unitType, new Function<String, byte[]>() {
			@Override
			public byte[] apply(String key) {
				return Hashing.hashUnit(unitUID);
			}
		});
	}

	private VariantAssigner getVariantAssigner(final String unitType, final byte[] unitHash) {
		return Concurrency.computeIfAbsentRW(contextLock_, assigners_, unitType,
				new Function<String, VariantAssigner>() {
					@Override
					public VariantAssigner apply(String key) {
						return new VariantAssigner(unitHash);
					}
				});
	}

	private void setTimeout() {
		if (isReady()) {
			if (timeout_ == null) {
				try {
					timeoutLock_.lock();
					if (timeout_ == null) {
						timeout_ = scheduler_.schedule(new Runnable() {
							@Override
							public void run() {
								Context.this.flush();
							}
						}, publishDelay_, TimeUnit.MILLISECONDS);
					}
				} finally {
					timeoutLock_.unlock();
				}
			}
		}
	}

	private void clearTimeout() {
		if (timeout_ != null) {
			try {
				timeoutLock_.lock();
				if (timeout_ != null) {
					timeout_.cancel(false);
					timeout_ = null;
				}
			} finally {
				timeoutLock_.unlock();
			}
		}
	}

	private void setRefreshTimer() {
		if ((refreshInterval_ > 0) && (refreshTimer_ == null)) {
			refreshTimer_ = scheduler_.scheduleWithFixedDelay(new Runnable() {
				@Override
				public void run() {
					Context.this.refreshAsync();
				}
			}, refreshInterval_, refreshInterval_, TimeUnit.MILLISECONDS);
		}
	}

	private void clearRefreshTimer() {
		if (refreshTimer_ != null) {
			refreshTimer_.cancel(false);
			refreshTimer_ = null;
		}
	}

	private static class ContextExperiment {
		Experiment data;
		Experiment[] holdouts;
		List<Map<String, Object>> variables;
		Map<String, ContextCustomFieldValue> customFieldValues;
	}

	private static class ContextCustomFieldValue {
		String type;
		Object value;
	}

	// Missing or malformed holdout references are ignored. Sort resolved holdouts by id for
	// deterministic exposure ordering and stable cache matching.
	private static Experiment[] resolveApplicableHoldouts(final Experiment experiment,
			final Map<Integer, Experiment> holdoutsById) {
		if (experiment.holdoutIds == null || experiment.holdoutIds.length == 0) {
			return null;
		}

		final List<Experiment> applicable = new ArrayList<Experiment>(experiment.holdoutIds.length);
		for (final int holdoutId : experiment.holdoutIds) {
			final Experiment holdout = holdoutsById.get(holdoutId);
			if (holdout != null) {
				applicable.add(holdout);
			}
		}

		if (applicable.isEmpty()) {
			return null;
		}

		Collections.sort(applicable, new Comparator<Experiment>() {
			@Override
			public int compare(Experiment a, Experiment b) {
				return Integer.valueOf(a.id).compareTo(b.id);
			}
		});

		return applicable.toArray(new Experiment[0]);
	}

	private void setData(final ContextData data) {
		final Map<String, ContextExperiment> index = new HashMap<String, ContextExperiment>();
		final Map<String, List<ContextExperiment>> indexVariables = new HashMap<String, List<ContextExperiment>>();

		final Map<Integer, Experiment> holdoutsById = new HashMap<Integer, Experiment>();
		if (data.holdouts != null) {
			for (final Experiment holdout : data.holdouts) {
				if ((holdout != null) && (holdout.split != null) && (holdout.split.length > 0)) {
					holdoutsById.put(holdout.id, holdout);
				}
			}
		}

		for (final Experiment experiment : data.experiments) {
			final ContextExperiment contextExperiment = new ContextExperiment();
			contextExperiment.data = experiment;
			contextExperiment.holdouts = resolveApplicableHoldouts(experiment, holdoutsById);
			contextExperiment.variables = new ArrayList<Map<String, Object>>(experiment.variants.length);

			for (final ExperimentVariant variant : experiment.variants) {
				if ((variant.config != null) && !variant.config.isEmpty()) {
					final Map<String, Object> variables = variableParser_.parse(this, experiment.name, variant.name,
							variant.config);
					for (final String key : variables.keySet()) {
						List<ContextExperiment> keyExperimentVariables = indexVariables.get(key);
						if (keyExperimentVariables == null) {
							keyExperimentVariables = new ArrayList<ContextExperiment>();
							indexVariables.put(key, keyExperimentVariables);
						}

						int at = Collections.binarySearch(keyExperimentVariables, contextExperiment,
								new Comparator<ContextExperiment>() {
									@Override
									public int compare(ContextExperiment a, ContextExperiment b) {
										return Integer.valueOf(a.data.id).compareTo(b.data.id);
									}
								});

						if (at < 0) {
							at = -at - 1;
							keyExperimentVariables.add(at, contextExperiment);
						}
					}

					contextExperiment.variables.add(variables);
				} else {
					contextExperiment.variables.add(Collections.<String, Object> emptyMap());
				}
			}

			contextExperiment.customFieldValues = new HashMap<String, ContextCustomFieldValue>();
			if (experiment.customFieldValues != null) {
				for (final CustomFieldValue customFieldValue : experiment.customFieldValues) {
					final ContextCustomFieldValue value = new ContextCustomFieldValue();
					contextExperiment.customFieldValues.put(customFieldValue.getName(), value);

					value.type = customFieldValue.getType();
					if (customFieldValue.getValue() != null) {
						if (customFieldValue.getType().startsWith("json")) {
							value.value = variableParser_.parse(this, experiment.name, customFieldValue.getValue());
						} else if (customFieldValue.getType().equals("boolean")) {
							value.value = Boolean.parseBoolean(customFieldValue.getValue());
						} else if (customFieldValue.getType().equals("number")) {
							value.value = Double.parseDouble(customFieldValue.getValue());
						} else {
							value.value = customFieldValue.getValue();
						}
					}
				}
			}

			index.put(experiment.name, contextExperiment);
		}

		try {
			dataLock_.writeLock().lock();

			index_ = index;
			indexVariables_ = indexVariables;
			holdoutsById_ = holdoutsById;
			data_ = data;

			setRefreshTimer();
		} finally {
			dataLock_.writeLock().unlock();
		}
	}

	private void setDataFailed(final Throwable exception) {
		try {
			dataLock_.writeLock().lock();
			index_ = new HashMap<String, ContextExperiment>();
			indexVariables_ = new HashMap<String, List<ContextExperiment>>();
			holdoutsById_ = new HashMap<Integer, Experiment>();
			data_ = new ContextData();
			failed_ = true;
		} finally {
			dataLock_.writeLock().unlock();
		}
	}

	private void logEvent(ContextEventLogger.EventType event, Object data) {
		if (eventLogger_ != null) {
			eventLogger_.handleEvent(this, event, data);
		}
	}

	private void logError(Throwable error) {
		if (eventLogger_ != null) {
			while (error instanceof CompletionException) {
				error = error.getCause();
			}
			eventLogger_.handleEvent(this, ContextEventLogger.EventType.Error, error);
		}
	}

	private final Clock clock_;
	private final long publishDelay_;
	private final long refreshInterval_;
	private final ContextEventHandler eventHandler_;
	private final ContextEventLogger eventLogger_;
	private final ContextDataProvider dataProvider_;
	private final VariableParser variableParser_;
	private final AudienceMatcher audienceMatcher_;
	private final ScheduledExecutorService scheduler_;
	private final Map<String, String> units_;
	private boolean failed_;

	private final ReentrantReadWriteLock dataLock_ = new ReentrantReadWriteLock();
	private ContextData data_;
	private Map<String, ContextExperiment> index_;
	private Map<String, List<ContextExperiment>> indexVariables_;
	// Id-keyed view of the holdouts installed by the most recent setData, used to resolve a
	// holdout's live definition independent of any stale Experiment reference a caller holds.
	private Map<Integer, Experiment> holdoutsById_;
	private final ReentrantReadWriteLock contextLock_ = new ReentrantReadWriteLock();

	private final Map<String, byte[]> hashedUnits_;
	private final Map<String, VariantAssigner> assigners_;
	private final Map<String, Assignment> assignmentCache_ = new HashMap<String, Assignment>();
	// A holdout's identity, for both this cache's keying and for detecting whether a covered
	// experiment's own set of applicable holdouts is still current (holdoutSetMatches), is its
	// (id, iteration) pair - never a full Experiment comparison. Rationale: the governing
	// invariant is that a unit must never appear in both arms of the same holdout within one
	// context's lifetime, which the once-per-context `exposed` AtomicBoolean on each cached
	// Assignment enforces only as long as the entry it lives on is not needlessly replaced.
	// seedHi/seedLo/split/name/variants/applications/audience/customFieldValues can all change
	// without altering who is a member, so none of them may invalidate the entry - doing so
	// would reset `exposed` and let an already-exposed unit be re-assigned into the other arm on
	// the very next refresh. Only an iteration bump is a genuine re-randomization epoch and
	// legitimately replaces the entry (and thus resets exposure), matching how experimentMatches
	// already treats iteration for ordinary experiments.
	private final Map<Integer, HoldoutAssignment> holdoutAssignmentCache_ = new HashMap<Integer, HoldoutAssignment>();

	private final ReentrantLock eventLock_ = new ReentrantLock();
	private final ArrayList<Exposure> exposures_ = new ArrayList<Exposure>();
	private final ArrayList<GoalAchievement> achievements_ = new ArrayList<GoalAchievement>();

	private final List<Attribute> attributes_ = new ArrayList<Attribute>();
	private final Map<String, Integer> overrides_;
	private final Map<String, Integer> cassignments_;

	private final AtomicInteger pendingCount_ = new AtomicInteger(0);
	private final AtomicBoolean closing_ = new AtomicBoolean(false);
	private final AtomicBoolean closed_ = new AtomicBoolean(false);
	private final AtomicBoolean refreshing_ = new AtomicBoolean(false);

	private volatile CompletableFuture<Void> readyFuture_;
	private volatile CompletableFuture<Void> closingFuture_;
	private volatile CompletableFuture<Void> refreshFuture_;

	private final ReentrantLock timeoutLock_ = new ReentrantLock();
	private volatile ScheduledFuture<?> timeout_ = null;
	private volatile ScheduledFuture<?> refreshTimer_ = null;
}
