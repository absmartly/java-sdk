package com.absmartly.sdk;

import java.io.Closeable;
import java.math.BigDecimal;
import java.math.BigInteger;
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
import com.absmartly.sdk.internal.SdkVersion;
import com.absmartly.sdk.internal.SwitchbackAssigner;
import com.absmartly.sdk.internal.SwitchbackAttributePath;
import com.absmartly.sdk.internal.SwitchbackServing;
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
	private static final String RANDOMIZATION_SWITCHBACK = "switchback";
	private static final char SWITCHBACK_KEY_SEPARATOR = '\u001f';
	private static final long NO_SWITCHBACK_PERIOD = Long.MIN_VALUE;
	private static final int SWITCHBACK_RETAINED_PERIODS = 2;
	// The Collector's switchback anchor bounds (Registry): an anchor must be hour aligned and in
	// [0, 10000-01-01T00:00:00Z).
	private static final long SWITCHBACK_ANCHOR_ALIGNMENT_MS = 3600000L;
	private static final long SWITCHBACK_MAX_ANCHOR_MS = 253402300800000L;
	private static final Logger log = LoggerFactory.getLogger(Context.class);
	// Logged once per JVM: server-side code typically creates a context per request.
	private static final AtomicBoolean noClientWarningLogged_ = new AtomicBoolean(false);

	static boolean noClientWarningLogged() {
		return noClientWarningLogged_.get();
	}

	/**
	 * Creates a context with no client. Equivalent to the client-aware overload with a null client: the environment
	 * is unknown, so assignment rules scoped to environments never match, and system attributes (when enabled) carry
	 * only sdk_name and sdk_version.
	 */
	public static Context create(@Nonnull final Clock clock, @Nonnull final ContextConfig config,
			@Nonnull final ScheduledExecutorService scheduler,
			@Nonnull final CompletableFuture<ContextData> dataFuture, @Nonnull final ContextDataProvider dataProvider,
			@Nonnull final ContextEventHandler eventHandler, @Nullable final ContextEventLogger eventLogger,
			@Nonnull final VariableParser variableParser, @Nonnull AudienceMatcher audienceMatcher) {
		return new Context(clock, config, scheduler, dataFuture, dataProvider, eventHandler, eventLogger,
				variableParser, audienceMatcher, null);
	}

	/**
	 * @param client
	 *            the client whose configuration supplies the environment that assignment rules are scoped to, and the
	 *            application and application version used as system attributes. It need not be the source of the
	 *            context's data. May be null; see the overload without a client.
	 */
	static Context create(@Nonnull final Clock clock, @Nonnull final ContextConfig config,
			@Nonnull final ScheduledExecutorService scheduler,
			@Nonnull final CompletableFuture<ContextData> dataFuture, @Nonnull final ContextDataProvider dataProvider,
			@Nonnull final ContextEventHandler eventHandler, @Nullable final ContextEventLogger eventLogger,
			@Nonnull final VariableParser variableParser, @Nonnull AudienceMatcher audienceMatcher,
			@Nullable final Client client) {
		return new Context(clock, config, scheduler, dataFuture, dataProvider, eventHandler, eventLogger,
				variableParser, audienceMatcher, client);
	}

	private Context(Clock clock, ContextConfig config, ScheduledExecutorService scheduler,
			CompletableFuture<ContextData> dataFuture, ContextDataProvider dataProvider,
			ContextEventHandler eventHandler, ContextEventLogger eventLogger, VariableParser variableParser,
			AudienceMatcher audienceMatcher, Client client) {
		clock_ = clock;
		includeSystemAttributes_ = config.getIncludeSystemAttributes();
		environmentName_ = client != null ? client.getEnvironment() : null;
		applicationName_ = client != null ? client.getApplication() : null;
		applicationVersion_ = client != null ? client.getApplicationVersion() : null;
		if (includeSystemAttributes_ && client == null && noClientWarningLogged_.compareAndSet(false, true)) {
			log.warn("System attributes are enabled but the context has no client: only sdk_name and sdk_version "
					+ "are included, and environment-scoped assignment rules never match");
		}
		publishDelay_ = config.getPublishDelay();
		refreshInterval_ = config.getRefreshInterval();
		eventHandler_ = eventHandler;
		eventLogger_ = config.getEventLogger() != null ? config.getEventLogger() : eventLogger;
		dataProvider_ = dataProvider;
		variableParser_ = variableParser;
		audienceMatcher_ = audienceMatcher;
		scheduler_ = scheduler;

		units_ = new HashMap<String, Object>();

		final Map<String, Object> units = config.getUnitValues();
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

	public Context waitUntilReady() {
		final CompletableFuture<Void> future = readyFuture_.get(); // cache here to avoid locking
		if (future != null && !future.isDone()) {
			future.join();
		}
		return this;
	}

	public String[] getExperiments() {
		checkReady(false);

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
		checkReady(false);

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
		checkReady(false);

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
		checkReady(false);

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
		return ContextConfig.unitString(getUnitValue(unitType));
	}

	public Object getUnitValue(@Nonnull final String unitType) {
		final ReentrantReadWriteLock.ReadLock readLock = contextLock_.readLock();
		try {
			readLock.lock();
			return units_.get(unitType);
		} finally {
			readLock.unlock();
		}
	}

	public void setUnit(@Nonnull final String unitType, @Nonnull final String uid) {
		putUnit(unitType, uid);
	}

	public void setUnit(@Nonnull final String unitType, @Nonnull final Number uid) {
		putUnit(unitType, uid);
	}

	private void putUnit(final String unitType, final Object value) {
		checkNotClosed();

		final Object uid;
		if (value instanceof String) {
			final String trimmed = ((String) value).trim();
			if (trimmed.isEmpty()) {
				throw new IllegalArgumentException(String.format("Unit '%s' UID must not be blank.", unitType));
			}
			uid = trimmed;
		} else if (isImmutableNumber(value)) {
			uid = value;
		} else {
			throw new IllegalArgumentException(String.format("Unit '%s' must be a string or a number.", unitType));
		}

		final ReentrantReadWriteLock.WriteLock writeLock = contextLock_.writeLock();
		try {
			writeLock.lock();

			final Object previous = units_.get(unitType);
			if ((previous != null) && !sameUid(previous, uid)) {
				throw new IllegalArgumentException(String.format("Unit '%s' UID already set.", unitType));
			}

			units_.put(unitType, uid);

			invalidateAssignmentsPinnedWithMissingUnit(unitType);
		} finally {
			writeLock.unlock();
		}
	}

	// Mutable Number implementations (AtomicInteger, ...) are rejected: the context caches the unit's
	// hash, so a UID that changes after it is set would be published under its old identity.
	private static boolean isImmutableNumber(final Object uid) {
		return (uid instanceof Integer) || (uid instanceof Long) || (uid instanceof Short) || (uid instanceof Byte)
				|| (uid instanceof Double) || (uid instanceof Float) || (uid instanceof BigInteger)
				|| (uid instanceof BigDecimal);
	}

	private static boolean sameUid(final Object a, final Object b) {
		if ((a instanceof Number) && (b instanceof Number)) {
			final BigDecimal x = toBigDecimal((Number) a);
			final BigDecimal y = toBigDecimal((Number) b);
			if ((x == null) || (y == null)) {
				return Double.valueOf(((Number) a).doubleValue()).equals(((Number) b).doubleValue());
			}
			return x.compareTo(y) == 0;
		}
		return a.equals(b);
	}

	private static BigDecimal toBigDecimal(final Number value) {
		if (value instanceof BigDecimal) {
			return (BigDecimal) value;
		}
		if (value instanceof BigInteger) {
			return new BigDecimal((BigInteger) value);
		}
		if ((value instanceof Double) || (value instanceof Float)) {
			final double d = value.doubleValue();
			return (Double.isNaN(d) || Double.isInfinite(d)) ? null : new BigDecimal(d);
		}
		return BigDecimal.valueOf(value.longValue());
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
			final SwitchbackServing serving = assignment.switchbackServing;
			if ((serving != null) && (serving.status == SwitchbackServing.ABSENT)) {
				// A switchback resolved before any linked unit was set is re-resolved now that one may
				// be. A non-overridden one never published anything, so it is dropped even if exposed.
				if (!assignment.overridden
						|| assignment.exposureState.compareAndSet(ASSIGNMENT_UNEXPOSED, ASSIGNMENT_RETIRED)) {
					it.remove();
				}
				continue;
			}
			if (assignment.ruleOverride && assignment.ruleMissingUnit && unitType.equals(assignment.unitType)) {
				// A split rule resolved without its unit gave the full-on variant; with the unit now
				// available it must be re-randomized, but only while that variant is still unseen.
				if (assignment.exposureState.compareAndSet(ASSIGNMENT_UNEXPOSED, ASSIGNMENT_RETIRED)) {
					it.remove();
				}
				continue;
			}
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
		return ContextConfig.unitStrings(getUnitValues());
	}

	public Map<String, Object> getUnitValues() {
		final ReentrantReadWriteLock.ReadLock readLock = contextLock_.readLock();
		try {
			readLock.lock();
			return new HashMap<String, Object>(units_);
		} finally {
			readLock.unlock();
		}
	}

	public void setUnits(@Nonnull final Map<String, ?> units) {
		for (Map.Entry<String, ?> entry : units.entrySet()) {
			putUnit(entry.getKey(), entry.getValue());
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
		checkReady(true);

		final long now = clock_.millis();
		return exposeTreatmentAssignment(experimentName, getAssignment(experimentName, now), now).variant;
	}

	private Assignment exposeTreatmentAssignment(final String experimentName, final Assignment assignment,
			final long resolvedAt) {
		return settleExposure(assignment, resolvedAt, new Function<Long, Assignment>() {
			@Override
			public Assignment apply(final Long now) {
				return getAssignment(experimentName, now);
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
	//
	// A switchback exposure is stamped with resolvedAt, the clock reading that selected its period:
	// analysis derives the period from exposedAt, so a later reading could attribute this variant
	// to the next period. Unit experiments read the clock at enqueue time.
	private int triggerExposure(final Assignment assignment, final long resolvedAt) {
		if (assignment.exposureState.compareAndSet(ASSIGNMENT_UNEXPOSED, ASSIGNMENT_EXPOSED)) {
			RuntimeException failure = null;
			try {
				if (!assignment.suppressed && !assignment.switchbackSilent) {
					enqueueExposure(assignment, assignment.switchback ? resolvedAt : clock_.millis());
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
			enqueueExposure(holdoutAssignment, clock_.millis());
		}
	}

	// Every enqueued exposure - ordinary or holdout, from any of getTreatment, triggerExposure's
	// holdout loop, or the variable-key path's per-candidate holdout firing - schedules its own
	// flush here rather than relying on the caller: a caller can enqueue a holdout exposure
	// without ever enqueueing its own (e.g. an already-exposed winner, or a losing variable-key
	// candidate that only fires holdouts), and setTimeout() is idempotent, so centralizing the
	// call is strictly safer than tracking every call site individually.
	private void enqueueExposure(final Assignment assignment, final long exposedAt) {
		final Exposure exposure = new Exposure();
		exposure.id = assignment.id;
		exposure.name = assignment.name;
		exposure.unit = assignment.unitType;
		exposure.variant = assignment.variant;
		exposure.exposedAt = exposedAt;
		exposure.assigned = assignment.assigned;
		exposure.eligible = assignment.eligible;
		exposure.overridden = assignment.overridden;
		exposure.fullOn = assignment.fullOn;
		exposure.custom = assignment.custom;
		exposure.audienceMismatch = assignment.audienceMismatch;
		exposure.ruleOverride = assignment.ruleOverride;

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

		return getAssignment(experimentName, clock_.millis()).variant;
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

		final long now = clock_.millis();
		final Assignment assignment = exposeVariableAssignment(key, getVariableAssignment(key, false, now), now);
		if (assignment != null) {
			if (assignment.variables != null) {
				if (assignment.variables.containsKey(key)) {
					return assignment.variables.get(key);
				}
			}
		}
		return defaultValue;
	}

	private Assignment exposeVariableAssignment(final String key, final Assignment assignment, final long resolvedAt) {
		return settleExposure(assignment, resolvedAt, new Function<Long, Assignment>() {
			@Override
			public Assignment apply(final Long now) {
				return getVariableAssignment(key, false, now);
			}
		});
	}

	// Each retry after a concurrent retirement resolves at a fresh clock reading and exposes at it.
	private Assignment settleExposure(final Assignment assignment, final long resolvedAt,
			final Function<Long, Assignment> resolver) {
		Assignment current = assignment;
		long now = resolvedAt;
		for (int attempt = 0; current != null; ++attempt) {
			if (triggerExposure(current, now) != ASSIGNMENT_RETIRED) {
				return current;
			}
			if (attempt + 1 >= MAX_EXPOSURE_ATTEMPTS) {
				// Exhaustion returns the attempted assignment that lost to a concurrent retirement.
				return current;
			}
			now = clock_.millis();
			current = resolver.apply(now);
		}
		return current;
	}

	public Object peekVariableValue(@Nonnull final String key, final Object defaultValue) {
		checkReady(true);

		final Assignment assignment = getVariableAssignment(key, true, clock_.millis());
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

		if (failed_) {
			return CompletableFuture.completedFuture(null);
		}

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
		final CompletableFuture<Void> attempt;
		// The decision to start a close and the publication of its future happen under one lock, so
		// every other caller gets that attempt's future.
		synchronized (closeLock_) {
			// Read closing_ before closed_: a finishing attempt sets closed_ and then clears closing_
			// outside this lock, so the reverse order can see both false and start a second close.
			if (closing_.get() || closed_.get()) {
				final CompletableFuture<Void> current = closingFuture_;
				if ((current == null) || (closed_.get() && current.isCompletedExceptionally())) {
					return CompletableFuture.completedFuture(null);
				}
				return closeCallerFuture(current);
			}

			closing_.set(true);
			attempt = new CompletableFuture<Void>();
			closingFuture_ = attempt;
		}

		clearRefreshTimer();

		if (pendingCount_.get() > 0) {
			flush().thenAccept(new Consumer<Void>() {
				@Override
				public void accept(Void x) {
					Context.this.completeClose(attempt);
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
					attempt.completeExceptionally(exception);

					return null;
				}
			});
		} else {
			completeClose(attempt);
		}

		return closeCallerFuture(attempt);
	}

	private void completeClose(final CompletableFuture<Void> attempt) {
		closed_.set(true);
		closing_.set(false);
		attempt.complete(null);

		logEvent(ContextEventLogger.EventType.Close, null);
	}

	// Do not expose the internal completion gate: completing or cancelling a caller's dependent future
	// must not release any other close caller.
	private static CompletableFuture<Void> closeCallerFuture(final CompletableFuture<Void> attempt) {
		return attempt.thenApply(new Function<Void, Void>() {
			@Override
			public Void apply(Void value) {
				return null;
			}
		});
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
								new Function<Map.Entry<String, Object>, Unit>() {
									@Override
									public Unit apply(Map.Entry<String, Object> entry) {
										return new Unit(entry.getKey(),
												new String(getUnitHash(entry.getKey(), entry.getValue()),
														StandardCharsets.US_ASCII));
									}
								});
						final long systemSetAt = earliestEventAt(exposures, achievements, event.publishedAt);
						event.attributes = buildPublishAttributes(systemSetAt);
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
		// A switchback serves under the unit its clusters select, so its unit identity is the cluster
		// map compared by switchbackConfigMatches. On the legacy path a unitType change is part of
		// sameSwitchbackIdentity, so it starts a new generation and the stale epoch's slots are
		// dropped before they get here.
		return experiment.data.id == assignment.id &&
				(isSwitchback(experiment.data)
						|| (experiment.data.unitType != null
								&& experiment.data.unitType.equals(assignment.unitType)))
				&&
				experiment.data.iteration == assignment.iteration &&
				experiment.data.fullOnVariant == assignment.fullOnVariant &&
				Arrays.equals(experiment.data.trafficSplit, assignment.trafficSplit) &&
				holdoutSetMatches(experiment.holdouts, assignment.holdouts) &&
				switchbackConfigMatches(experiment.data, assignment);
	}

	// Grid identity: a change to any of these re-maps periods or clusters to variants. Carryover and
	// burn windows are analysis-only and deliberately excluded, as are seed and split, which do not
	// invalidate unit assignments either.
	private static boolean switchbackConfigMatches(final Experiment experiment, final Assignment assignment) {
		if (isSwitchback(experiment) != assignment.switchback) {
			return false;
		}
		return !assignment.switchback
				|| (Experiment.orderedEquals(experiment.switchbackClusters, assignment.switchbackClusters)
						&& Arrays.equals(experiment.switchbackClusterValues, assignment.switchbackClusterValues)
						&& Objects.equals(experiment.switchbackStartAt, assignment.switchbackStartAt)
						&& Objects.equals(experiment.switchbackPeriodMs, assignment.switchbackPeriodMs)
						&& Objects.equals(experiment.switchbackDesignType, assignment.switchbackDesignType));
	}

	private static void pinSwitchbackConfig(final Assignment assignment, final Experiment experiment) {
		assignment.switchback = isSwitchback(experiment);
		if (assignment.switchback) {
			assignment.switchbackClusters = experiment.switchbackClusters;
			assignment.switchbackClusterValues = experiment.switchbackClusterValues;
			assignment.switchbackStartAt = experiment.switchbackStartAt;
			assignment.switchbackPeriodMs = experiment.switchbackPeriodMs;
			assignment.switchbackDesignType = experiment.switchbackDesignType;
		}
	}

	private static boolean isSwitchback(final Experiment experiment) {
		return RANDOMIZATION_SWITCHBACK.equals(experiment.randomizationType);
	}

	private static long switchbackSeed(final Experiment experiment) {
		return ((long) experiment.seedHi << 32) | (experiment.seedLo & 0xffffffffL);
	}

	// Whether the Collector's Registry can serve this switchback's schedule: a start that is set,
	// hour aligned and in [0, 10000-01-01) (it is used as served, never rounded), a period > 0 and
	// at least two arms. An unservable schedule only replaces the draw: assignment rules and full-on
	// still apply.
	static boolean switchbackScheduleServable(final Experiment experiment) {
		final Long startAt = experiment.switchbackStartAt;
		final Long periodMs = experiment.switchbackPeriodMs;
		if ((startAt == null) || (startAt < 0) || (startAt >= SWITCHBACK_MAX_ANCHOR_MS)
				|| ((startAt % SWITCHBACK_ANCHOR_ALIGNMENT_MS) != 0)) {
			return false;
		}
		if ((periodMs == null) || (periodMs <= 0)) {
			return false;
		}
		return (experiment.split != null) && (experiment.split.length >= 2);
	}

	// periodIndex = floorDiv(now - switchbackStartAt, switchbackPeriodMs), as the Collector computes
	// it. NO_SWITCHBACK_PERIOD for absent and unit experiments, before the start, for an unservable
	// schedule (see switchbackScheduleServable) and for a cluster map taking the legacy path.
	private static long currentSwitchbackPeriod(final ContextExperiment experiment, final long nowMs) {
		if ((experiment == null) || !isSwitchback(experiment.data) || !switchbackScheduleServable(experiment.data)
				|| (experiment.switchbackSourcePaths == null)) {
			return NO_SWITCHBACK_PERIOD;
		}
		final long startAt = experiment.data.switchbackStartAt;
		if (nowMs < startAt) {
			return NO_SWITCHBACK_PERIOD;
		}
		return SwitchbackAssigner.floorDiv(nowMs - startAt, experiment.data.switchbackPeriodMs);
	}

	private static final class ContextUnit implements SwitchbackServing.Unit {
		final String type;
		final Object uid;

		ContextUnit(final String type, final Object uid) {
			this.type = type;
			this.uid = uid;
		}

		@Override
		public String type() {
			return type;
		}

		@Override
		public Object uid() {
			return uid;
		}
	}

	// Port of the Collector's ContextAPI.switchbackServing at `now` (see SwitchbackServing). The
	// cluster is resolved whether or not the schedule can be drawn now (pre-start or an unservable
	// schedule), as the Collector does, so assignment rules and full-on still see the serving unit.
	// A missing, empty or unreadable cluster map takes the Collector's legacy path: an ineligible
	// control under a unit of the declared unitType, if set. Caller holds the contextLock_ write lock.
	private SwitchbackServing resolveSwitchbackServing(final ContextExperiment experiment, final long now) {
		final List<ContextUnit> units = new ArrayList<ContextUnit>(units_.size());
		for (final Map.Entry<String, Object> unit : units_.entrySet()) {
			units.add(new ContextUnit(unit.getKey(), unit.getValue()));
		}
		if (experiment.switchbackSourcePaths == null) {
			return SwitchbackServing.legacy(experiment.data.unitType, units);
		}
		return SwitchbackServing.resolve(experiment.switchbackSourceTypes, experiment.switchbackSourcePaths,
				experiment.data.switchbackClusterValues, units, new SwitchbackServing.AttributeReader() {
					@Override
					public Object read(final SwitchbackAttributePath path) {
						final Attribute attribute = latestAttribute(path.rootKey(), now);
						return (attribute != null) ? path.resolveAttribute(attribute.value) : null;
					}
				});
	}

	// The unit type a switchback with no serving unit is recorded under (an override is still
	// exposed): the first linked type of a readable cluster map, otherwise the declared unitType the
	// legacy path reads.
	private static String switchbackFallbackUnitType(final ContextExperiment experiment) {
		return (experiment.switchbackSourcePaths != null) ? experiment.switchbackSourceTypes[0]
				: experiment.data.unitType;
	}

	// The attribute the Collector records with an exposure at `now` (ContextAPI.extractAttributes):
	// among attributes set at or before now whose trimmed name matches, the greatest setAt wins and
	// a tie goes to the one set last. Caller holds contextLock_.
	private Attribute latestAttribute(final String name, final long now) {
		Attribute selected = null;
		for (final Attribute attribute : attributes_) {
			if ((attribute.setAt <= now) && (attribute.name != null) && name.equals(attribute.name.trim())
					&& ((selected == null) || (attribute.setAt >= selected.setAt))) {
				selected = attribute;
			}
		}
		return selected;
	}

	// Unit experiments, and switchback experiments with no current period, keep the bare name.
	private static String assignmentCacheKey(final String experimentName, final long periodIndex) {
		if (periodIndex == NO_SWITCHBACK_PERIOD) {
			return experimentName;
		}
		return switchbackCacheKey(experimentName, periodIndex);
	}

	private static String switchbackCacheKey(final String experimentName, final long periodIndex) {
		return experimentName + SWITCHBACK_KEY_SEPARATOR + periodIndex;
	}

	// Switchback identity: id, iteration, the ordered cluster map, the cluster value allow-list,
	// startAt, periodMs and design, i.e. every input to the period grid and the cluster key, plus
	// unitType when either side takes the legacy path. Seed, split and analysis-only fields are
	// excluded. Absent and unit experiments have no identity.
	private static boolean sameSwitchbackIdentity(final ContextExperiment a, final ContextExperiment b) {
		final boolean aSwitchback = (a != null) && isSwitchback(a.data);
		final boolean bSwitchback = (b != null) && isSwitchback(b.data);
		if (!aSwitchback || !bSwitchback) {
			return aSwitchback == bSwitchback;
		}
		return (a.data.id == b.data.id) && (a.data.iteration == b.data.iteration)
				&& Experiment.orderedEquals(a.data.switchbackClusters, b.data.switchbackClusters)
				&& Arrays.equals(a.data.switchbackClusterValues, b.data.switchbackClusterValues)
				&& Objects.equals(a.data.switchbackStartAt, b.data.switchbackStartAt)
				&& Objects.equals(a.data.switchbackPeriodMs, b.data.switchbackPeriodMs)
				&& Objects.equals(a.data.switchbackDesignType, b.data.switchbackDesignType)
				&& (((a.switchbackSourcePaths != null) && (b.switchbackSourcePaths != null))
						|| Objects.equals(a.data.unitType, b.data.unitType));
	}

	// Lifecycle of one switchback experiment's cache slots: the bare-name slot (pre-anchor or an
	// unevaluable grid) and its period slots, all created under one switchback generation (see
	// ContextExperiment.switchbackGeneration). Indices from different generations are never
	// compared: a changed start or period length re-maps them.
	private static final class SwitchbackEpoch {
		final long generation;
		final TreeSet<Long> periods = new TreeSet<Long>();

		SwitchbackEpoch(final long generation) {
			this.generation = generation;
		}
	}

	// An epoch is stale once the installed definition is absent, not switchback, or carries a
	// different generation. Every identity transition, including an intermediate unit or removed
	// state that was never looked up, assigns a fresh generation, so a stale epoch never matches a
	// later identical definition. Caller holds contextLock_ (read or write).
	private boolean isSwitchbackEpochStale(final String experimentName, final ContextExperiment experiment) {
		if (switchbackEpochs_.isEmpty()) {
			return false;
		}
		final SwitchbackEpoch epoch = switchbackEpochs_.get(experimentName);
		return (epoch != null) && ((experiment == null) || !isSwitchback(experiment.data)
				|| (epoch.generation != experiment.switchbackGeneration));
	}

	// Drops every slot of the epoch, bare-name slot included. Caller holds the contextLock_ write lock.
	private void dropSwitchbackEpoch(final String experimentName) {
		final SwitchbackEpoch epoch = switchbackEpochs_.remove(experimentName);
		if (epoch != null) {
			for (final Long period : epoch.periods) {
				assignmentCache_.remove(switchbackCacheKey(experimentName, period));
			}
			assignmentCache_.remove(experimentName);
		}
	}

	// Records a switchback resolution under the current epoch. At most SWITCHBACK_RETAINED_PERIODS
	// period indices are kept; the lowest index other than periodIndex is evicted, so the entry
	// just installed always survives. Exposure dedup therefore holds only within the retained
	// window: re-entering an evicted period (clock rewind, or a caller delayed across two
	// boundaries) creates one new assignment for it, which then stays cached like any other.
	private void retainSwitchbackPeriod(final String experimentName, final ContextExperiment experiment,
			final long periodIndex) {
		SwitchbackEpoch epoch = switchbackEpochs_.get(experimentName);
		if (epoch == null) {
			epoch = new SwitchbackEpoch(experiment.switchbackGeneration);
			switchbackEpochs_.put(experimentName, epoch);
		}
		if (periodIndex == NO_SWITCHBACK_PERIOD) {
			return;
		}
		epoch.periods.add(periodIndex);
		while (epoch.periods.size() > SWITCHBACK_RETAINED_PERIODS) {
			Long evicted = epoch.periods.first();
			if (evicted == periodIndex) {
				evicted = epoch.periods.higher(periodIndex);
			}
			epoch.periods.remove(evicted);
			assignmentCache_.remove(switchbackCacheKey(experimentName, evicted));
		}
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
		// A rule-won assignment never consulted the audience, so an audience change cannot make it stale.
		if (assignment.ruleOverride) {
			return true;
		}
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

	// Called with the read lock held, so it only reads: the cached Assignment is never updated here,
	// and a changed rule outcome is reported as stale for the write path to rebuild. Re-resolves the
	// rules only when they changed (a refresh) or an attribute was set since the assignment was built.
	private boolean ruleMatches(final ContextExperiment experiment, final Assignment assignment) {
		// A switchback that did not resolve an eligible cluster never consulted its rules (the
		// Collector evaluates them only for a cluster key), so a rule change cannot make it stale.
		if (assignment.switchback && ((assignment.switchbackServing == null)
				|| (assignment.switchbackServing.status != SwitchbackServing.ELIGIBLE))) {
			return true;
		}
		final boolean rulesChanged = !Objects.equals(experiment.data.assignmentRules, assignment.ruleKey);
		if (!rulesChanged && attrsSeq_.get() <= assignment.attrsSeq) {
			return true;
		}

		// An exposed split resolved without its unit keeps resolving as if the unit were still
		// missing, so a unit set afterwards cannot switch the variant the user already saw.
		final boolean exposed = assignment.exposureState.get() != ASSIGNMENT_UNEXPOSED;
		final boolean unitPinnedMissing = assignment.ruleMissingUnit && exposed;
		final RuleResolution rule = resolveRule(experiment, ruleUnitType(experiment, assignment), unitPinnedMissing,
				false);
		final Integer ruleVariant = rule != null ? rule.variant : null;
		final Integer cachedRuleVariant = assignment.ruleOverride ? (Integer) assignment.variant : null;
		if (!Objects.equals(ruleVariant, cachedRuleVariant)) {
			return false;
		}

		// Same variant, but now resolved by a split without its unit (or no longer so). The read path
		// must not mutate, so this is reported as stale: the write path rebuilds an unexposed
		// assignment (letting setUnit evict and re-randomize it later) and updates an exposed one in
		// place (see reuseExposedRuleAssignment), so it is pinned without being exposed again.
		final boolean missingUnit = rule != null && rule.missingUnit;
		return missingUnit == assignment.ruleMissingUnit;
	}

	// Called with the write lock held. When the freshly resolved assignment is a rule win with the
	// same variant as an already-exposed cached one for the same experiment run, the cached object
	// is kept and only its rule bookkeeping is refreshed: creating a new Assignment would reset its
	// exposure state and publish the same decision a second time, and keeping the old flags would
	// leave a split resolved without its unit unpinned.
	private Assignment reuseExposedRuleAssignment(final ContextExperiment experiment, final Assignment previous,
			final Assignment fresh) {
		if (previous == null || experiment == null || !fresh.ruleOverride || !previous.ruleOverride
				|| previous.overridden || previous.variant != fresh.variant
				|| previous.exposureState.get() != ASSIGNMENT_EXPOSED || !experimentMatches(experiment, previous)) {
			return null;
		}
		previous.ruleKey = fresh.ruleKey;
		previous.ruleMissingUnit = fresh.ruleMissingUnit;
		previous.attrsSeq = fresh.attrsSeq;
		return previous;
	}

	private static final class RuleResolution {
		RuleResolution(int variant, boolean missingUnit) {
			this.variant = variant;
			this.missingUnit = missingUnit;
		}

		final int variant;
		final boolean missingUnit;
	}

	// The unit a split rule randomizes over: the experiment's unitType, or for a switchback the
	// serving unit this assignment resolved (the Collector resets its assigner to the serving unit).
	private static String ruleUnitType(final ContextExperiment experiment, final Assignment assignment) {
		return isSwitchback(experiment.data) ? assignment.unitType : experiment.data.unitType;
	}

	// Resolves the experiment's assignment rules to a variant, or null when no rule applies. A fixed
	// variant out of range or a split whose length differs from the variant count is ignored. A split
	// with no unit to randomize over gives the full-on variant and is flagged missingUnit.
	// Called with contextLock_ held. cacheAssigner is false on the read path, where the hashed-unit and
	// assigner caches must not be written (computeIfAbsentRW would try to upgrade to the write lock).
	private RuleResolution resolveRule(final ContextExperiment contextExperiment, final String unitType,
			final boolean unitPinnedMissing, final boolean cacheAssigner) {
		if (contextExperiment.assignmentRules == null) {
			return null;
		}

		final Experiment experiment = contextExperiment.data;
		final AudienceMatcher.RuleAction action = audienceMatcher_.evaluateRules(contextExperiment.assignmentRules,
				environmentName_, buildAttributesMap(), experiment.name);
		if (action == null) {
			return null;
		}

		final int variantCount = experiment.variants != null ? experiment.variants.length : 0;
		if (!action.isSplit()) {
			final int variant = action.getVariant();
			return (variant >= 0 && variant < variantCount) ? new RuleResolution(variant, false) : null;
		}

		final double[] split = action.getSplit();
		if (split.length != variantCount) {
			return null;
		}

		final Object uid = (unitType != null && !unitPinnedMissing) ? units_.get(unitType) : null;
		if (uid == null) {
			return new RuleResolution(experiment.fullOnVariant, true);
		}

		final VariantAssigner assigner;
		if (cacheAssigner) {
			assigner = getVariantAssigner(unitType, getUnitHash(unitType, uid));
		} else {
			final VariantAssigner cached = assigners_.get(unitType);
			if (cached != null) {
				assigner = cached;
			} else {
				final byte[] cachedHash = hashedUnits_.get(unitType);
				assigner = new VariantAssigner(cachedHash != null ? cachedHash : Hashing.hashUnit(uid));
			}
		}
		return new RuleResolution(assigner.assign(split, experiment.seedHi, experiment.seedLo), false);
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
		// The variant was set by a matching custom assignment rule. Takes precedence over holdout
		// suppression, the audience, traffic and custom assignments; the exposure is flagged so the
		// backend can exclude it from analysis.
		boolean ruleOverride;
		// The assignment rules JSON string this Assignment was resolved against (the environment is
		// fixed for the context's lifetime, so it is not part of it), used by ruleMatches to detect
		// staleness.
		String ruleKey;
		boolean ruleMissingUnit;
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

		// Switchback grid this assignment was resolved under; compared by switchbackConfigMatches.
		boolean switchback;
		Map<String, String> switchbackClusters;
		String[] switchbackClusterValues;
		Long switchbackStartAt;
		Long switchbackPeriodMs;
		String switchbackDesignType;
		// The serving resolution read for this slot (cluster key and serving unit); read once per
		// slot. Null until read, and never kept when no linked unit was set.
		SwitchbackServing switchbackServing;
		// Set for a switchback resolution the Collector records nothing for: no linked unit, or an
		// ineligible cluster (no value, disagreement, filtered value, unservable or pre-start).
		boolean switchbackSilent;

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

	private boolean isAssignmentCurrent(final Assignment assignment, final Integer custom, final Integer override,
			final ContextExperiment experiment) {
		if (override != null) {
			// An override still evaluates the experiment for holdout purposes (see the
			// write path in getAssignment), so the fast path must revalidate the applicable-holdout
			// set exactly like the ordinary branch does via experimentMatches - otherwise
			// a holdout that becomes applicable after a refresh never fires for an
			// already-overridden experiment, and stays that way forever. The switchback identity is
			// revalidated too, so a grid change re-resolves an overridden slot like any other.
			return assignment.overridden && assignment.variant == override
					&& holdoutSetMatches((experiment != null) ? experiment.holdouts : null, assignment.holdouts)
					&& ((experiment != null) ? switchbackConfigMatches(experiment.data, assignment)
							: !assignment.switchback);
		} else if (experiment == null) {
			// previously not-running experiment. A rule-won assignment is not "assigned" either, but its
			// variant came from the experiment's rules, so it must be rebuilt once the experiment is gone.
			return !assignment.assigned && !assignment.ruleOverride;
		} else if (assignment.ruleOverride || Objects.equals(custom, assignment.customAssignment)) {
			// A matching rule wins over a custom assignment, so a custom change is not staleness.
			return experimentMatches(experiment, assignment) && ruleMatches(experiment, assignment)
					&& audienceMatches(experiment.data, assignment);
		}
		return false;
	}

	// The slot's serving resolution: reused when this slot already read it (unless no linked unit was
	// set then), otherwise read now. Caller holds the contextLock_ write lock.
	private SwitchbackServing switchbackServing(final ContextExperiment experiment, final SwitchbackServing cached,
			final long now) {
		if ((cached != null) && (cached.status != SwitchbackServing.ABSENT)) {
			return cached;
		}
		return resolveSwitchbackServing(experiment, now);
	}

	// Port of the Collector's switchback branch of ContextAPI (R33): the serving resolution decides
	// whether the experiment applies and under which unit. For an eligible cluster the Collector's
	// assignExperiment order follows: assignment rules (a split rule randomizes over the serving
	// unit), audience, full-on, traffic (sticky on the serving unit's hash), then custom assignment
	// and the switchback draw over the raw cluster key. Holdouts never apply to a switchback. Only
	// the draw needs a current period: pre-start or with an unservable schedule the result is an
	// ineligible control that records nothing, while rules, audience and full-on are unaffected.
	// Caller holds the contextLock_ write lock.
	private void resolveSwitchbackAssignment(final Assignment assignment, final ContextExperiment experiment,
			final SwitchbackServing cachedServing, final Integer custom, final long periodIndex, final long now) {
		final Experiment data = experiment.data;
		pinSwitchbackConfig(assignment, data);
		assignment.ruleKey = data.assignmentRules;
		assignment.id = data.id;
		assignment.iteration = data.iteration;
		assignment.trafficSplit = data.trafficSplit;
		assignment.fullOnVariant = data.fullOnVariant;
		assignment.attrsSeq = attrsSeq_.get();

		final SwitchbackServing serving = switchbackServing(experiment, cachedServing, now);
		assignment.switchbackServing = serving;
		assignment.variant = 0;
		if (serving.status == SwitchbackServing.ABSENT) {
			// No linked unit: the experiment does not apply, like an unset unit type.
			assignment.unitType = switchbackFallbackUnitType(experiment);
			assignment.eligible = false;
			assignment.switchbackSilent = true;
			return;
		}

		final String unitType = serving.unit.type();
		assignment.unitType = unitType;
		assignment.assigned = true;
		if (serving.status == SwitchbackServing.INELIGIBLE) {
			assignment.eligible = false;
			assignment.switchbackSilent = true;
			return;
		}

		final RuleResolution rule = resolveRule(experiment, unitType, false, true);
		if (rule != null) {
			// Recorded like a unit experiment's rule win: ruleOverride set, not "assigned".
			assignment.assigned = false;
			assignment.ruleOverride = true;
			assignment.ruleMissingUnit = rule.missingUnit;
			assignment.variant = rule.variant;
			return;
		}

		if ((data.audience != null) && (data.audience.length() > 0)) {
			final AudienceMatcher.Result match = audienceMatcher_.evaluate(data.audience, buildAttributesMap());
			if (match != null) {
				assignment.audienceMismatch = !match.get();
			}
		}
		if (data.audienceStrict && assignment.audienceMismatch) {
			// Recorded like a unit experiment's strict mismatch: control, audienceMismatch, not "assigned".
			assignment.assigned = false;
			return;
		}
		if (data.fullOnVariant != 0) {
			assignment.variant = data.fullOnVariant;
			assignment.fullOn = true;
			return;
		}
		if (periodIndex == NO_SWITCHBACK_PERIOD) {
			// Pre-start or an unservable schedule: no draw, so control and no exposure.
			assignment.eligible = false;
			assignment.switchbackSilent = true;
			return;
		}

		final byte[] unitHash = getUnitHash(unitType, serving.unit.uid());
		final VariantAssigner assigner = getVariantAssigner(unitType, unitHash);
		if (assigner.assign(data.trafficSplit, data.trafficSeedHi, data.trafficSeedLo) != 1) {
			// A traffic miss on an eligible cluster is recorded, as for a unit experiment.
			assignment.eligible = false;
			return;
		}
		if (custom != null) {
			assignment.variant = custom;
			assignment.custom = true;
			return;
		}
		assignment.variant = SwitchbackAssigner.assign(serving.clusterKey, periodIndex, switchbackSeed(data),
				data.split.length);
	}

	// Test seam: the cached resolution of a switchback (or any experiment) at the clock's current
	// reading, as the Collector would report it. Null when nothing is cached for that slot.
	static final class SwitchbackResolution {
		final String unitType;
		final boolean assigned;
		final boolean eligible;
		final boolean fullOn;
		final boolean ruleOverride;
		// Whether the resolution records an exposure when exposed.
		final boolean silent;
		// Whether the switchback applies (its serving status is not ABSENT), i.e. the Collector lists an
		// assignment. Unlike !assigned, a strict audience mismatch or a rule win does not clear it.
		final boolean applies;

		SwitchbackResolution(final Assignment assignment) {
			this.unitType = assignment.unitType;
			this.assigned = assignment.assigned;
			this.eligible = assignment.eligible;
			this.fullOn = assignment.fullOn;
			this.ruleOverride = assignment.ruleOverride;
			this.silent = assignment.switchbackSilent;
			this.applies = (assignment.switchbackServing != null)
					&& (assignment.switchbackServing.status != SwitchbackServing.ABSENT);
		}
	}

	SwitchbackResolution switchbackResolutionForTest(final String experimentName) {
		final ReentrantReadWriteLock.ReadLock readLock = contextLock_.readLock();
		try {
			readLock.lock();
			final long period = currentSwitchbackPeriod(getExperiment(experimentName), clock_.millis());
			final Assignment assignment = assignmentCache_.get(assignmentCacheKey(experimentName, period));
			return (assignment != null) ? new SwitchbackResolution(assignment) : null;
		} finally {
			readLock.unlock();
		}
	}

	int assignmentCacheSizeForTest() {
		final ReentrantReadWriteLock.ReadLock readLock = contextLock_.readLock();
		try {
			readLock.lock();
			return assignmentCache_.size();
		} finally {
			readLock.unlock();
		}
	}

	// now is the caller's single clock reading for this resolution, so the cache key, the drawn
	// period and a switchback exposure's exposedAt all agree.
	private Assignment getAssignment(final String experimentName, final long now) {
		final ReentrantReadWriteLock.ReadLock readLock = contextLock_.readLock();
		// The cached slot the read path found stale (null on a miss). The write path does not
		// recheck that same object: a concurrent install is always a different object, while a
		// state change between the locks can leave the rejected object staler or, in an A-B-A
		// race, current again; rebuilding it then matches main's pre-recheck behaviour.
		final Assignment rejected;
		try {
			readLock.lock();

			final ContextExperiment experiment = Context.this.getExperiment(experimentName);
			final Assignment assignment = assignmentCache_
					.get(assignmentCacheKey(experimentName, currentSwitchbackPeriod(experiment, now)));

			// A stale epoch's slots are dropped by the write path below.
			if ((assignment != null) && !isSwitchbackEpochStale(experimentName, experiment)
					&& isAssignmentCurrent(assignment, cassignments_.get(experimentName),
							overrides_.get(experimentName), experiment)) {
				return assignment;
			}
			rejected = assignment;
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
			final long periodIndex = currentSwitchbackPeriod(experiment, now);
			final String cacheKey = assignmentCacheKey(experimentName, periodIndex);

			if (isSwitchbackEpochStale(experimentName, experiment)) {
				dropSwitchbackEpoch(experimentName);
			}

			// Recheck: a concurrent caller may have installed this key (e.g. the first call in a
			// new switchback period) between the two locks; replacing it would reset its exposure.
			final Assignment cached = assignmentCache_.get(cacheKey);
			if ((cached != null) && (cached != rejected) && isAssignmentCurrent(cached, custom, override, experiment)) {
				return cached;
			}

			final Assignment assignment = new Assignment();
			assignment.name = experimentName;
			assignment.eligible = true;
			assignment.customAssignment = custom;
			// Within one period slot the serving unit and cluster key are read once: a slot replaced
			// in the same epoch, e.g. after setAttribute changed the audience, keeps its cluster.
			final SwitchbackServing cachedServing = (cached != null) ? cached.switchbackServing : null;

			if (override != null) {
				if ((experiment != null) && isSwitchback(experiment.data)) {
					assignment.id = experiment.data.id;
					pinSwitchbackConfig(assignment, experiment.data);
					final SwitchbackServing serving = switchbackServing(experiment, cachedServing, now);
					assignment.switchbackServing = serving;
					assignment.unitType = (serving.unit != null) ? serving.unit.type()
							: switchbackFallbackUnitType(experiment);
				} else if (experiment != null) {
					assignment.id = experiment.data.id;
					assignment.unitType = experiment.data.unitType;

					// An override still evaluates the experiment - only its variant is replaced -
					// so the applicable holdouts must fire exactly as they would for a normal
					// assignment. Override precedence itself is untouched: the returned variant
					// stays the overridden one.
					assignment.holdouts = experiment.holdouts;
					pinSwitchbackConfig(assignment, experiment.data);
				}

				assignment.overridden = true;
				assignment.variant = override;
			} else {
				if ((experiment != null) && isSwitchback(experiment.data)) {
					resolveSwitchbackAssignment(assignment, experiment, cachedServing, custom, periodIndex, now);
				} else if (experiment != null) {
					final String unitType = experiment.data.unitType;
					pinSwitchbackConfig(assignment, experiment.data);

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
					// Rules are resolved after the holdouts so the holdout decisions above stay pinned and
					// still fire their own exposures. ruleKey is recorded for every assignment, including
					// those with no matching rule, so ruleMatches only re-resolves after a refresh changes
					// the rules or an attribute is set.
					assignment.ruleKey = experiment.data.assignmentRules;
					final RuleResolution rule = resolveRule(experiment, unitType, false, true);
					if (rule != null) {
						assignment.ruleOverride = true;
						assignment.ruleMissingUnit = rule.missingUnit;
					}

					if (rule == null && experiment.data.audience != null && experiment.data.audience.length() > 0) {
						final AudienceMatcher.Result match = audienceMatcher_
								.evaluate(experiment.data.audience, buildAttributesMap());
						if (match != null) {
							assignment.audienceMismatch = !match.get();
						}
					}

					// A matching rule is an explicit, author-specified assignment, like an override: it
					// wins over holdout suppression (the unit is not treated as held out, though the
					// holdouts above still fire), the audience, traffic and custom assignments.
					assignment.suppressed = suppressed && rule == null;

					if (rule != null) {
						assignment.variant = rule.variant;
					} else if (suppressed) {
						// A held-out unit is not a participant in this experiment: assigned stays
						// false so it never wins variable-key resolution against a genuinely
						// assigned experiment and never fires this experiment's own exposure.
						assignment.variant = 0;
					} else {
						if (experiment.data.audienceStrict && assignment.audienceMismatch) {
							assignment.variant = 0;
						} else if (experiment.data.fullOnVariant == 0) {
							final Object uid = units_.get(unitType);
							if (uid != null) {
								final byte[] unitHash = Context.this.getUnitHash(unitType, uid);

								final VariantAssigner assigner = Context.this.getVariantAssigner(unitType, unitHash);
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

			final Assignment reused = reuseExposedRuleAssignment(experiment, cached, assignment);
			if (reused != null) {
				return reused;
			}

			assignmentCache_.put(cacheKey, assignment);
			if ((experiment != null) && isSwitchback(experiment.data)) {
				retainSwitchbackPeriod(experimentName, experiment, periodIndex);
			}

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
	private Assignment getVariableAssignment(final String key, final boolean peek, final long now) {
		final List<ContextExperiment> keyExperimentVariables = getVariableExperiments(key);

		if (keyExperimentVariables != null) {
			Assignment suppressedFallback = null;
			RuntimeException failure = null;
			for (final ContextExperiment experimentVariables : keyExperimentVariables) {
				final Assignment assignment = getAssignment(experimentVariables.data.name, now);
				if (!peek) {
					final RuntimeException holdoutFailure = triggerApplicableHoldoutExposures(assignment);
					if (failure == null) {
						failure = holdoutFailure;
					}
				}
				if (assignment.assigned || assignment.overridden || assignment.ruleOverride) {
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

			final Object uid = units_.get(unitType);
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

			final Object uid = units_.get(unitType);
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

	private byte[] getUnitHash(final String unitType, final Object unitUID) {
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
		// data.assignmentRules parsed once when the data is installed (null when there are none), so
		// lookups never re-parse the JSON and an invalid string is logged once per refresh.
		AudienceMatcher.ParsedRules assignmentRules;
		Experiment[] holdouts;
		List<Map<String, Object>> variables;
		Map<String, ContextCustomFieldValue> customFieldValues;
		// Assigned in setData under dataLock_ write: carried over from the previous index while the
		// switchback identity is unchanged, otherwise a fresh value. Unused for unit experiments.
		long switchbackGeneration;
		// switchbackClusters split in declared order: the linked unit types, and their parsed paths.
		// Paths are null when the map is missing, empty or unreadable: the legacy path.
		String[] switchbackSourceTypes;
		SwitchbackAttributePath[] switchbackSourcePaths;
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
			if (isSwitchback(experiment)) {
				// Holdouts never apply to a switchback (see resolveSwitchbackAssignment).
				contextExperiment.holdouts = null;
				contextExperiment.switchbackSourceTypes = SwitchbackServing.sourceTypes(experiment.switchbackClusters);
				contextExperiment.switchbackSourcePaths = SwitchbackServing.sourcePaths(experiment.switchbackClusters);
			}
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

			try {
				contextExperiment.assignmentRules = audienceMatcher_.parseRules(experiment.assignmentRules);
			} catch (Exception e) {
				log.warn("Failed to parse assignmentRules for experiment '{}'; its rules are ignored: {}",
						experiment.name, e.getMessage());
				contextExperiment.assignmentRules = null;
			}
			if (contextExperiment.assignmentRules != null && contextExperiment.assignmentRules.isInvalid()) {
				log.warn("Invalid assignmentRules JSON for experiment '{}'; its rules are ignored", experiment.name);
			}

			index.put(experiment.name, contextExperiment);
		}

		try {
			dataLock_.writeLock().lock();

			for (final Map.Entry<String, ContextExperiment> entry : index.entrySet()) {
				final ContextExperiment installed = entry.getValue();
				if (isSwitchback(installed.data)) {
					final ContextExperiment previous = (index_ != null) ? index_.get(entry.getKey()) : null;
					installed.switchbackGeneration = sameSwitchbackIdentity(previous, installed)
							? previous.switchbackGeneration
							: ++switchbackGenerationCounter_;
				}
			}

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

	// Variables available to audience filters and assignment-rule conditions. System attributes come first so
	// that a user attribute of the same name overrides them. Called with contextLock_ held.
	private Map<String, Object> buildAttributesMap() {
		final Map<String, Object> attrs = new HashMap<String, Object>(attributes_.size() + 3);
		if (includeSystemAttributes_) {
			putIfNotNull(attrs, "application", applicationName_);
			putIfNotNull(attrs, "environment", environmentName_);
			if (ClientConfig.isApplicationVersionSet(applicationVersion_)) {
				attrs.put("app_version", applicationVersion_);
			}
		}
		for (final Attribute attr : attributes_) {
			attrs.put(attr.name, attr.value);
		}
		return attrs;
	}

	// The collector only applies attributes with setAt <= an event's time, so system attributes are stamped with the
	// earliest event in the batch to describe every event in it.
	private static long earliestEventAt(final Exposure[] exposures, final GoalAchievement[] achievements,
			final long publishedAt) {
		long earliest = publishedAt;
		if (exposures != null) {
			for (final Exposure exposure : exposures) {
				earliest = Math.min(earliest, exposure.exposedAt);
			}
		}
		if (achievements != null) {
			for (final GoalAchievement achievement : achievements) {
				earliest = Math.min(earliest, achievement.achievedAt);
			}
		}
		return earliest;
	}

	// System attributes the user has set themselves are left out: the collector picks the latest setAt among
	// duplicates, which would otherwise let a synthetic value supersede the user's. Called with contextLock_ held.
	private Attribute[] buildPublishAttributes(final long systemSetAt) {
		final List<Attribute> attrs = new ArrayList<Attribute>(attributes_.size() + 5);
		if (includeSystemAttributes_) {
			final Set<String> userNames = new HashSet<String>();
			for (final Attribute attr : attributes_) {
				userNames.add(attr.name);
			}
			addSystemAttribute(attrs, userNames, "sdk_name", SdkVersion.NAME, systemSetAt);
			addSystemAttribute(attrs, userNames, "sdk_version", SdkVersion.VERSION, systemSetAt);
			addSystemAttribute(attrs, userNames, "application", applicationName_, systemSetAt);
			addSystemAttribute(attrs, userNames, "environment", environmentName_, systemSetAt);
			if (ClientConfig.isApplicationVersionSet(applicationVersion_)) {
				addSystemAttribute(attrs, userNames, "app_version", applicationVersion_, systemSetAt);
			}
		}
		attrs.addAll(attributes_);
		return attrs.isEmpty() ? null : attrs.toArray(new Attribute[0]);
	}

	private static void addSystemAttribute(final List<Attribute> attrs, final Set<String> userNames,
			final String name, final Object value, final long setAt) {
		if (value != null && !userNames.contains(name)) {
			attrs.add(new Attribute(name, value, setAt));
		}
	}

	private static void putIfNotNull(final Map<String, Object> map, final String key, final Object value) {
		if (value != null) {
			map.put(key, value);
		}
	}

	private final Clock clock_;
	private final boolean includeSystemAttributes_;
	private final String environmentName_;
	private final String applicationName_;
	private final String applicationVersion_;
	private final long publishDelay_;
	private final long refreshInterval_;
	private final ContextEventHandler eventHandler_;
	private final ContextEventLogger eventLogger_;
	private final ContextDataProvider dataProvider_;
	private final VariableParser variableParser_;
	private final AudienceMatcher audienceMatcher_;
	private final ScheduledExecutorService scheduler_;
	private final Map<String, Object> units_;
	private volatile boolean failed_;
	private volatile Throwable readyError_;

	private final ReentrantReadWriteLock dataLock_ = new ReentrantReadWriteLock();
	private volatile ContextData data_;
	private Map<String, ContextExperiment> index_;
	private Map<String, List<ContextExperiment>> indexVariables_;
	// Id-keyed view of the holdouts installed by the most recent setData, used to resolve a
	// holdout's live definition independent of any stale Experiment reference a caller holds.
	private Map<Integer, Experiment> holdoutsById_;
	// Source of ContextExperiment.switchbackGeneration; guarded by dataLock_ write.
	private long switchbackGenerationCounter_;
	private final ReentrantReadWriteLock contextLock_ = new ReentrantReadWriteLock();

	private final Map<String, byte[]> hashedUnits_;
	private final Map<String, VariantAssigner> assigners_;
	// Keyed by experiment name, or by name + U+001F + period index for a switchback experiment
	// inside its grid (see assignmentCacheKey), so each period gets its own exposure state.
	private final Map<String, Assignment> assignmentCache_ = new HashMap<String, Assignment>();
	private final Map<String, SwitchbackEpoch> switchbackEpochs_ = new HashMap<String, SwitchbackEpoch>();
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
	private final Object closeLock_ = new Object();
	// Guarded by closeLock_.
	private CompletableFuture<Void> closingFuture_;
	private final AtomicReference<CompletableFuture<Void>> refreshFuture_ = new AtomicReference<CompletableFuture<Void>>();

	private final ReentrantLock timeoutLock_ = new ReentrantLock();
	private volatile ScheduledFuture<?> timeout_ = null;
	private volatile ScheduledFuture<?> refreshTimer_ = null;
}
