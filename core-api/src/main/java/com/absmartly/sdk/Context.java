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
		} finally {
			writeLock.unlock();
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
			queueExposure(assignment);
		}

		return assignment.variant;
	}

	private void queueExposure(final Assignment assignment) {
		if (assignment.exposed.compareAndSet(false, true)) {
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

			logEvent(ContextEventLogger.EventType.Exposure, exposure);

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

		final Assignment assignment = getVariableAssignment(key);
		if (assignment != null) {
			if (assignment.variables != null) {
				if (!assignment.exposed.get()) {
					queueExposure(assignment);
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

		final Assignment assignment = getVariableAssignment(key);
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

	private boolean experimentMatches(final Experiment experiment, final Assignment assignment) {
		return experiment.id == assignment.id &&
				experiment.unitType.equals(assignment.unitType) &&
				experiment.iteration == assignment.iteration &&
				experiment.fullOnVariant == assignment.fullOnVariant &&
				Arrays.equals(experiment.trafficSplit, assignment.trafficSplit);
	}

	private static class Assignment {
		int id;
		int iteration;
		int fullOnVariant;
		String name;
		String unitType;
		double[] trafficSplit;
		int variant;
		boolean assigned;
		boolean overridden;
		boolean eligible;
		boolean fullOn;
		boolean custom;

		boolean audienceMismatch;
		Map<String, Object> variables = Collections.emptyMap();

		final AtomicBoolean exposed = new AtomicBoolean(false);
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
					if (assignment.overridden && assignment.variant == override) {
						// override up-to-date
						return assignment;
					}
				} else if (experiment == null) {
					if (!assignment.assigned) {
						// previously not-running experiment
						return assignment;
					}
				} else if ((custom == null) || custom == assignment.variant) {
					if (experimentMatches(experiment.data, assignment)) {
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
				}

				assignment.overridden = true;
				assignment.variant = override;
			} else {
				if (experiment != null) {
					final String unitType = experiment.data.unitType;

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
						final String uid = units_.get(experiment.data.unitType);
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

	private Assignment getVariableAssignment(final String key) {
		final List<ContextExperiment> keyExperimentVariables = getVariableExperiments(key);

		if (keyExperimentVariables != null) {
			for (final ContextExperiment experimentVariables : keyExperimentVariables) {
				final Assignment assignment = getAssignment(experimentVariables.data.name);
				if (assignment.assigned || assignment.overridden) {
					return assignment;
				}
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
		List<Map<String, Object>> variables;
		Map<String, ContextCustomFieldValue> customFieldValues;
	}

	private static class ContextCustomFieldValue {
		String type;
		Object value;
	}

	private void setData(final ContextData data) {
		final Map<String, ContextExperiment> index = new HashMap<String, ContextExperiment>();
		final Map<String, List<ContextExperiment>> indexVariables = new HashMap<String, List<ContextExperiment>>();

		for (final Experiment experiment : data.experiments) {
			final ContextExperiment contextExperiment = new ContextExperiment();
			contextExperiment.data = experiment;
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
	private final ReentrantReadWriteLock contextLock_ = new ReentrantReadWriteLock();

	private final Map<String, byte[]> hashedUnits_;
	private final Map<String, VariantAssigner> assigners_;
	private final Map<String, Assignment> assignmentCache_ = new HashMap<String, Assignment>();

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
