package com.absmartly.sdk;

import java.io.Closeable;
import java.util.*;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java8.util.concurrent.CompletableFuture;
import java8.util.concurrent.CompletionException;
import java8.util.function.BiFunction;
import java8.util.function.Consumer;
import java8.util.function.Function;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.absmartly.sdk.internal.Algorithm;
import com.absmartly.sdk.internal.Concurrency;
import com.absmartly.sdk.internal.VariantAssigner;
import com.absmartly.sdk.internal.hashing.Hashing;
import com.absmartly.sdk.java.nio.charset.StandardCharsets;
import com.absmartly.sdk.java.time.Clock;
import com.absmartly.sdk.java.util.Objects;
import com.absmartly.sdk.json.*;

public class Context implements Closeable {
	private static final int ASSIGNMENT_UNEXPOSED = 0;
	private static final int ASSIGNMENT_EXPOSED = 1;
	private static final int ASSIGNMENT_RETIRED = 2;
	private static final int MAX_EXPOSURE_ATTEMPTS = 3;
	private static final Logger log = LoggerFactory.getLogger(Context.class);

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
			final CompletableFuture<Void> newReadyFuture = new CompletableFuture<Void>();
			readyFuture_.set(newReadyFuture);
			dataFuture.thenAccept(new Consumer<ContextData>() {
				@Override
				public void accept(ContextData data) {
					Context.this.setData(data);
					final CompletableFuture<Void> rf = readyFuture_.getAndSet(COMPLETED_VOID_FUTURE);
					if (rf != null) {
						rf.complete(null);
					}

					Context.this.logEvent(ContextEventLogger.EventType.Ready, data);

					if (Context.this.getPendingCount() > 0) {
						Context.this.setTimeout();
					}
				}
			}).exceptionally(new Function<Throwable, Void>() {
				@Override
				public Void apply(Throwable exception) {
					Context.this.setDataFailed(exception);
					final CompletableFuture<Void> rf = readyFuture_.getAndSet(COMPLETED_VOID_FUTURE);
					if (rf != null) {
						rf.complete(null);
					}

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

	public boolean isFinalized() {
		return isClosed();
	}

	public boolean isFinalizing() {
		return isClosing();
	}

	public CompletableFuture<Context> waitUntilReadyAsync() {
		if (data_ != null) {
			return CompletableFuture.completedFuture(this);
		} else {
			final CompletableFuture<Void> rf = readyFuture_.get();
			if (rf != null) {
				return rf.thenApply(new Function<Void, Context>() {
					@Override
					public Context apply(Void k) {
						return Context.this;
					}
				});
			}
			return CompletableFuture.completedFuture(this);
		}
	}

	public Context waitUntilReady() {
		if (data_ == null) {
			final CompletableFuture<Void> future = readyFuture_.get(); // cache here to avoid locking
			if (future != null && !future.isDone()) {
				future.join();
			}
		}
		return this;
	}

	public String[] getExperiments() {
		if (!isReady() || isClosed() || isClosing()) {
			return new String[0];
		}

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
		if (!isReady() || isClosed() || isClosing()) {
			return new String[0];
		}

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
		if (!isReady() || isClosed() || isClosing()) {
			return null;
		}

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
		if (!isReady() || isClosed() || isClosing()) {
			return null;
		}

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
				throw new IllegalArgumentException(String.format("Unit '%s' UID already set.", unitType));
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
	// Only unexposed assignments are evicted. Eviction creates a new exposure state, so evicting an
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
			final Assignment[] holdoutAssignments = assignment.holdoutAssignments;
			if ((holdoutAssignments != null) && unitType.equals(assignment.unitType)) {
				for (final Assignment holdoutAssignment : holdoutAssignments) {
					if (holdoutAssignment == null) {
						if (assignment.exposureState.compareAndSet(ASSIGNMENT_UNEXPOSED, ASSIGNMENT_RETIRED)) {
							it.remove();
						}
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
		attrsSeq_.incrementAndGet();
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
		if (!isReady()) {
			return 0;
		}

		if (isClosed() || isClosing()) {
			return 0;
		}

		return getExposedTreatmentVariant(getAssignment(experimentName));
	}

	private int getExposedTreatmentVariant(final Assignment assignment) {
		return exposeTreatmentAssignment(assignment).variant;
	}

	private Assignment exposeTreatmentAssignment(final Assignment assignment) {
		return settleExposure(assignment, new Function<Assignment, Assignment>() {
			@Override
			public Assignment apply(final Assignment retired) {
				return getAssignment(retired.name);
			}
		});
	}

	// A suppressed assignment (held out by an applicable holdout) never publishes its own
	// exposure - the holdout experiment's own exposure is the sole membership record. Firing
	// either exposure still triggers evaluation of every holdout applicable to this unit type,
	// keeping both holdout arms symmetric regardless of which covered experiment triggered it.
	// The trigger loop below must run even if the exposure above throws (e.g. a
	// ContextEventLogger implementation that throws): the exposure state is already CAS'd by the
	// time we get here, so a skipped holdout trigger would never be retried for this context's
	// life. Failures are collected and re-thrown once every holdout has had a chance to fire,
	// rather than swallowed or allowed to abort the loop early.
	private int triggerExposure(final Assignment assignment) {
		if (assignment.exposureState.compareAndSet(ASSIGNMENT_UNEXPOSED, ASSIGNMENT_EXPOSED)) {
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
			return ASSIGNMENT_EXPOSED;
		}
		return assignment.exposureState.get();
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
		if ((holdoutAssignment != null) && holdoutAssignment.exposureState.compareAndSet(ASSIGNMENT_UNEXPOSED,
				ASSIGNMENT_EXPOSED)) {
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
		if (!isReady() || isClosed() || isClosing()) {
			return 0;
		}

		return getAssignment(experimentName).variant;
	}

	public Map<String, List<String>> getVariableKeys() {
		if (!isReady() || isClosed() || isClosing()) {
			return new HashMap<String, List<String>>();
		}

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
		if (!isReady() || isClosed() || isClosing()) {
			return defaultValue;
		}

		final Assignment assignment = exposeVariableAssignment(key, getVariableAssignment(key, false));
		if (assignment != null) {
			if (assignment.variables != null) {
				if (assignment.variables.containsKey(key)) {
					return assignment.variables.get(key);
				}
			}
		}
		return defaultValue;
	}

	private Assignment exposeVariableAssignment(final String key, final Assignment assignment) {
		return settleExposure(assignment, new Function<Assignment, Assignment>() {
			@Override
			public Assignment apply(final Assignment retired) {
				return getVariableAssignment(key, false);
			}
		});
	}

	private Assignment settleExposure(final Assignment assignment, final Function<Assignment, Assignment> resolver) {
		Assignment current = assignment;
		for (int attempt = 0; current != null; ++attempt) {
			if (triggerExposure(current) != ASSIGNMENT_RETIRED) {
				return current;
			}
			if (attempt + 1 >= MAX_EXPOSURE_ATTEMPTS) {
				// Exhaustion returns the attempted assignment that lost to a concurrent retirement.
				return current;
			}
			current = resolver.apply(current);
		}
		return current;
	}

	public Object peekVariableValue(@Nonnull final String key, final Object defaultValue) {
		if (!isReady() || isClosed() || isClosing()) {
			return defaultValue;
		}

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
			final CompletableFuture<Void> newRefreshFuture = new CompletableFuture<Void>();
			refreshFuture_.set(newRefreshFuture);

			dataProvider_.getContextData().thenAccept(new Consumer<ContextData>() {
				@Override
				public void accept(ContextData data) {
					Context.this.setData(data);
					refreshing_.set(false);
					newRefreshFuture.complete(null);

					Context.this.logEvent(ContextEventLogger.EventType.Refresh, data);
				}
			}).exceptionally(new Function<Throwable, Void>() {
				@Override
				public Void apply(Throwable exception) {
					refreshing_.set(false);
					newRefreshFuture.completeExceptionally(exception);

					Context.this.logError(exception);
					return null;
				}
			});
		}

		final CompletableFuture<Void> future = refreshFuture_.get();
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
					final CompletableFuture<Void> newClosingFuture = new CompletableFuture<Void>();
					closingFuture_.set(newClosingFuture);

					flush().thenAccept(new Consumer<Void>() {
						@Override
						public void accept(Void x) {
							closed_.set(true);
							closing_.set(false);
							newClosingFuture.complete(null);

							Context.this.logEvent(ContextEventLogger.EventType.Close, null);
						}
					}).exceptionally(new Function<Throwable, Void>() {
						@Override
						public Void apply(Throwable exception) {
							// If events were restored by flush's failure handler, leave the context
							// open so a retry of closeAsync() can attempt to publish them.
							if (pendingCount_.get() == 0) {
								closed_.set(true);
							}
							closing_.set(false);
							newClosingFuture.completeExceptionally(exception);

							return null;
						}
					});

					return newClosingFuture;
				} else {
					closed_.set(true);
					closing_.set(false);

					Context.this.logEvent(ContextEventLogger.EventType.Close, null);

					// Nothing was pending here, so no closingFuture_ was published for this
					// attempt; return directly to avoid picking up a stale future left behind
					// by an earlier failed close attempt.
					return CompletableFuture.completedFuture(null);
				}
			}

			final CompletableFuture<Void> future = closingFuture_.get();
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

	@Deprecated
	public CompletableFuture<Void> finalizeAsync() {
		return closeAsync();
	}

	private CompletableFuture<Void> flush() {
		clearTimeout();

		if (!failed_) {
			if (pendingCount_.get() > 0) {
				Exposure[] exposures = null;
				GoalAchievement[] achievements = null;
				int eventCount;
				final CompletableFuture<Void> result = new CompletableFuture<Void>();
				final CompletableFuture<Void> callerResult = new CompletableFuture<Void>();
				result.handle(new BiFunction<Void, Throwable, Void>() {
					@Override
					public Void apply(Void ignoredResult, Throwable exception) {
						if (exception != null) {
							callerResult.completeExceptionally(exception);
						} else {
							callerResult.complete(null);
						}
						return null;
					}
				});

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

					try {
						contextLock_.writeLock().lock();
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
					} finally {
						contextLock_.writeLock().unlock();
					}
					event.exposures = exposures;
					event.goals = achievements;

					final Exposure[] finalExposures = exposures;
					final GoalAchievement[] finalAchievements = achievements;
					final int finalEventCount = eventCount;

					final Function<Throwable, Void> onPublishFailure = new Function<Throwable, Void>() {
						@Override
						public Void apply(Throwable throwable) {
							try {
								eventLock_.lock();
								if (finalExposures != null) {
									for (int i = finalExposures.length - 1; i >= 0; i--) {
										exposures_.add(0, finalExposures[i]);
									}
								}
								if (finalAchievements != null) {
									for (int i = finalAchievements.length - 1; i >= 0; i--) {
										achievements_.add(0, finalAchievements[i]);
									}
								}
								pendingCount_.addAndGet(finalEventCount);
							} finally {
								eventLock_.unlock();
							}

							try {
								Context.this.logError(throwable);
							} catch (final Throwable ignored) {
								// diagnostic logger failures must not affect publish accounting
							}
							result.completeExceptionally(throwable);
							return null;
						}
					};

					final CompletableFuture<Void> publishResult;
					try {
						publishResult = eventHandler_.publish(this, event);
					} catch (final Throwable throwable) {
						onPublishFailure.apply(throwable);
						return callerResult;
					}

					// The Publish log event runs in its own stage so a logger exception cannot be
					// mistaken for a publisher failure and trigger event restoration.
					publishResult.thenRunAsync(new Runnable() {
						@Override
						public void run() {
							try {
								Context.this.logEvent(ContextEventLogger.EventType.Publish, event);
							} catch (final Throwable ignored) {
								// diagnostic logger failures must not affect publish accounting
							} finally {
								result.complete(null);
							}
						}
					});

					publishResult.exceptionally(onPublishFailure);

					return callerResult;
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
			throw new IllegalStateException("ABSmartly Context is finalized.");
		} else if (closing_.get()) {
			throw new IllegalStateException("ABSmartly Context is closing.");
		}
	}

	private void checkReady(final boolean expectNotClosed) {
		if (!isReady()) {
			throw new IllegalStateException("ABSmartly Context is not yet ready.");
		} else if (expectNotClosed) {
			checkNotClosed();
		}
	}

	private boolean experimentMatches(final ContextExperiment experiment, final Assignment assignment) {
		return experiment.data.id == assignment.id &&
				(experiment.data.unitType != null && experiment.data.unitType.equals(assignment.unitType)) &&
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

	private boolean audienceMatches(final Experiment experiment, final Assignment assignment) {
		if (experiment.audience != null && experiment.audience.length() > 0) {
			if (attrsSeq_.get() > assignment.attrsSeq) {
				final Map<String, Object> attrs = buildAttributesMap();

				final AudienceMatcher.Result match = audienceMatcher_.evaluate(experiment.audience, attrs);
				final boolean newAudienceMismatch = (match != null) ? !match.get() : false;

				if (newAudienceMismatch != assignment.audienceMismatch) {
					return false;
				}
			}
		}
		return true;
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
		// The cassignments_ entry read while this Assignment was resolved (null if none was set
		// yet), regardless of whether the resolution path actually consulted it - a full-on or
		// traffic-ineligible variant never does. Comparing the live entry against this pinned
		// value, rather than against the resolved variant, is what tells the cache apart from a
		// custom assignment that legitimately changes the outcome: the live value can equal the
		// resolved variant by construction on the eligible path, but a forced variant can never
		// equal a newly-set custom value, which would otherwise invalidate on every call.
		Integer customAssignment;

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
		int attrsSeq;

		final AtomicInteger exposureState = new AtomicInteger(ASSIGNMENT_UNEXPOSED);
	}

	// A holdout's own Assignment is cached per (id, effective unit type): one holdout id can cover
	// experiments with different unit types (getHoldoutAssignment receives the covered
	// experiment's unitType, not the holdout's own declared unitType - see the comment on
	// getHoldoutAssignment), and each such unit type is a distinct suppression decision with its
	// own once-per-context exposure state. Keying by id alone let two unit types evict each
	// other's slot on every alternating lookup, discarding the loser's exposureState and
	// re-publishing that holdout's membership on the next recomputation. The unit type is part of
	// this key rather than a field compared inside matches(): duplicating it as both would leave
	// two sources of truth for the same identity.
	private static final class HoldoutCacheKey {
		final int id;
		final String unitType;

		HoldoutCacheKey(int id, String unitType) {
			this.id = id;
			this.unitType = unitType;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof HoldoutCacheKey)) {
				return false;
			}
			final HoldoutCacheKey other = (HoldoutCacheKey) o;
			return (id == other.id) && unitType.equals(other.unitType);
		}

		@Override
		public int hashCode() {
			return (31 * id) + unitType.hashCode();
		}
	}

	// Pins the iteration a holdout's Assignment was computed against, for the unit type already
	// fixed by this entry's HoldoutCacheKey. This is the same granularity experimentMatches uses
	// for ordinary experiments: seedHi/seedLo/split are deliberately excluded so a live seed or
	// percentage edit - which does not change who is covered - never invalidates an already-
	// exposed unit's arm. Only an iteration bump, a genuine re-randomization epoch, replaces the
	// cached verdict (and its exposure state), exactly as it does for ordinary experiments.
	// armCount is deliberately excluded here too: it is pinned on Assignment itself (see armCount
	// below), bundled with the arm number it was computed against, so the cached arm is always
	// interpreted under its own arity rather than being invalidated and re-exposed under a new
	// one for a same-iteration arity change.
	private static class HoldoutAssignment {
		final Assignment assignment;
		final int iteration;

		HoldoutAssignment(Assignment assignment, Experiment holdout) {
			this.assignment = assignment;
			this.iteration = holdout.iteration;
		}

		boolean matches(Experiment current) {
			return iteration == current.iteration;
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
				} else if (Objects.equals(custom, assignment.customAssignment)) {
					if (experimentMatches(experiment, assignment)
							&& audienceMatches(experiment.data, assignment)) {
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
			assignment.customAssignment = custom;

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

					if (experiment.data.audience != null && experiment.data.audience.length() > 0) {
						final Map<String, Object> attrs = buildAttributesMap();

						final AudienceMatcher.Result match = audienceMatcher_
								.evaluate(experiment.data.audience, attrs);
						if (match != null) {
							assignment.audienceMismatch = !match.get();
						}
					}

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
					assignment.attrsSeq = attrsSeq_.get();
				}
			}

			if ((experiment != null) && experiment.data.variants != null && assignment.variant >= 0
					&& (assignment.variant < experiment.data.variants.length)) {
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

	// The holdout's own assignment is cached by (id, effective unit type) rather than name, since
	// holdout entries live outside the experiments index and are shared by reference across every
	// covered experiment - see HoldoutCacheKey for why the unit type is part of the identity.
	// Only an iteration change invalidates the cache entry (see HoldoutAssignment), matching
	// experimentMatches's treatment of ordinary experiments and guaranteeing an already-exposed
	// unit's arm survives any seed, split or cosmetic edit.
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
			final HoldoutAssignment cached = holdoutAssignmentCache_.get(new HoldoutCacheKey(holdout.id, unitType));
			if ((cached != null) && cached.matches(liveHoldout)) {
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
			final HoldoutAssignment cached = holdoutAssignmentCache_.get(new HoldoutCacheKey(holdout.id, unitType));
			if ((cached != null) && cached.matches(liveHoldout)) {
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

			holdoutAssignmentCache_.put(new HoldoutCacheKey(liveHoldout.id, unitType),
					new HoldoutAssignment(assignment, liveHoldout));

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
		if (isReady() && publishDelay_ >= 0) {
			if (timeout_ == null) {
				try {
					timeoutLock_.lock();
					if (timeout_ == null) {
						timeout_ = scheduler_.schedule(new Runnable() {
							@Override
							public void run() {
								Context.this.flush().exceptionally(new Function<Throwable, Void>() {
									@Override
									public Void apply(Throwable exception) {
										Context.this.logError(exception);
										return null;
									}
								});
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
		if (data == null) {
			throw new IllegalArgumentException("Context data cannot be null");
		}

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
			contextExperiment.variables = new ArrayList<Map<String, Object>>(
					experiment.variants != null ? experiment.variants.length : 0);

			if (experiment.variants != null)
				for (final ExperimentVariant variant : experiment.variants) {
					if ((variant.config != null) && !variant.config.isEmpty()) {
						try {
							final Map<String, Object> variables = variableParser_.parse(this, experiment.name,
									variant.name,
									variant.config);
							if (variables != null) {
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
						} catch (Exception e) {
							log.error("Failed to parse variant config for experiment '{}', variant '{}': {}",
									experiment.name, variant.name, e.getMessage());
							contextExperiment.variables.add(Collections.<String, Object> emptyMap());
						}
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
						try {
							final String type = customFieldValue.getType();
							if (type != null && type.startsWith("json")) {
								value.value = variableParser_.parse(this, experiment.name, customFieldValue.getValue());
							} else if (type != null && type.equals("boolean")) {
								value.value = Boolean.parseBoolean(customFieldValue.getValue());
							} else if (type != null && type.equals("number")) {
								value.value = Double.parseDouble(customFieldValue.getValue());
							} else {
								value.value = customFieldValue.getValue();
							}
						} catch (NumberFormatException e) {
							log.warn("Failed to parse custom field number value for experiment '{}': {}",
									experiment.name, e.getMessage());
							value.value = customFieldValue.getValue();
						} catch (Exception e) {
							log.warn("Failed to parse custom field value for experiment '{}': {}", experiment.name,
									e.getMessage());
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

	public Throwable readyError() {
		return readyError_;
	}

	private void setDataFailed(final Throwable exception) {
		try {
			dataLock_.writeLock().lock();
			index_ = new HashMap<String, ContextExperiment>();
			indexVariables_ = new HashMap<String, List<ContextExperiment>>();
			holdoutsById_ = new HashMap<Integer, Experiment>();
			data_ = new ContextData();
			failed_ = true;
			readyError_ = exception;
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

	private Map<String, Object> buildAttributesMap() {
		final Map<String, Object> attrs = new HashMap<String, Object>(attributes_.size());
		for (final Attribute attr : attributes_) {
			attrs.put(attr.name, attr.value);
		}
		return attrs;
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
	private volatile boolean failed_;
	private volatile Throwable readyError_;

	private final ReentrantReadWriteLock dataLock_ = new ReentrantReadWriteLock();
	private volatile ContextData data_;
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
	// context's lifetime, which the once-per-context exposure state on each cached
	// Assignment enforces only as long as the entry it lives on is not needlessly replaced.
	// seedHi/seedLo/split/name/variants/applications/audience/customFieldValues can all change
	// without altering who is a member, so none of them may invalidate the entry - doing so
	// would reset exposure and let an already-exposed unit be re-assigned into the other arm on
	// the very next refresh. Only an iteration bump is a genuine re-randomization epoch and
	// legitimately replaces the entry (and thus resets exposure), matching how experimentMatches
	// already treats iteration for ordinary experiments.
	//
	// Keyed by (id, effective unit type) rather than id alone: see HoldoutCacheKey.
	private final Map<HoldoutCacheKey, HoldoutAssignment> holdoutAssignmentCache_ = new HashMap<HoldoutCacheKey, HoldoutAssignment>();

	private final ReentrantLock eventLock_ = new ReentrantLock();
	private final ArrayList<Exposure> exposures_ = new ArrayList<Exposure>();
	private final ArrayList<GoalAchievement> achievements_ = new ArrayList<GoalAchievement>();

	private final List<Attribute> attributes_ = new ArrayList<Attribute>();
	private final Map<String, Integer> overrides_;
	private final Map<String, Integer> cassignments_;
	private final AtomicInteger attrsSeq_ = new AtomicInteger(0);

	private final AtomicInteger pendingCount_ = new AtomicInteger(0);
	private final AtomicBoolean closing_ = new AtomicBoolean(false);
	private final AtomicBoolean closed_ = new AtomicBoolean(false);
	private final AtomicBoolean refreshing_ = new AtomicBoolean(false);

	private static final CompletableFuture<Void> COMPLETED_VOID_FUTURE = CompletableFuture.completedFuture(null);
	private final AtomicReference<CompletableFuture<Void>> readyFuture_ = new AtomicReference<CompletableFuture<Void>>();
	private final AtomicReference<CompletableFuture<Void>> closingFuture_ = new AtomicReference<CompletableFuture<Void>>();
	private final AtomicReference<CompletableFuture<Void>> refreshFuture_ = new AtomicReference<CompletableFuture<Void>>();

	private final ReentrantLock timeoutLock_ = new ReentrantLock();
	private volatile ScheduledFuture<?> timeout_ = null;
	private volatile ScheduledFuture<?> refreshTimer_ = null;
}
