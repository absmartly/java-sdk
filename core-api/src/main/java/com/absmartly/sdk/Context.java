package com.absmartly.sdk;

import java.io.Closeable;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.absmartly.sdk.internal.VariantAssigner;
import com.absmartly.sdk.internal.hashing.Hashing;
import com.absmartly.sdk.json.*;

public class Context implements Closeable {
	public static Context create(@Nonnull Clock clock, @Nonnull ContextConfig config,
			@Nonnull ScheduledExecutorService scheduler,
			@Nonnull CompletableFuture<ContextData> dataFuture, @Nonnull ContextDataProvider dataProvider,
			@Nonnull ContextEventHandler eventHandler, @Nonnull VariableParser variableParser) {
		return new Context(clock, config, scheduler, dataFuture, dataProvider, eventHandler, variableParser);
	}

	private Context(Clock clock, ContextConfig config, ScheduledExecutorService scheduler,
			CompletableFuture<ContextData> dataFuture, ContextDataProvider dataProvider,
			ContextEventHandler eventHandler, VariableParser variableParser) {
		final Map<String, String> units = config.getUnits();
		clock_ = clock;
		publishDelay_ = config.getPublishDelay();
		units_ = new HashMap<>(config.getUnits());

		eventHandler_ = eventHandler;
		dataProvider_ = dataProvider;
		variableParser_ = variableParser;
		scheduler_ = scheduler;
		assigners_ = new ConcurrentHashMap<>(units.size());
		hashedUnits_ = new ConcurrentHashMap<>(units.size());

		final Map<String, Object> attributes = config.getAttributes();
		if (attributes != null) {
			setAttributes(attributes);
		}

		final Map<String, Integer> overrides = config.getOverrides();
		overrides_ = (overrides != null) ? new ConcurrentHashMap<>(overrides) : new ConcurrentHashMap<>();

		if (dataFuture.isDone()) {
			dataFuture.thenAccept(this::setData)
					.exceptionally(exception -> {
						setDataFailed(exception);
						return null;
					});
		} else {
			readyFuture_ = new CompletableFuture<>();
			dataFuture.thenAccept(data -> {
				setData(data);
				readyFuture_.complete(null);
				readyFuture_ = null;

				if (getPendingCount() > 0) {
					setTimeout();
				}
			}).exceptionally(exception -> {
				setDataFailed(exception);
				readyFuture_.complete(null);
				readyFuture_ = null;
				return null;
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
			return readyFuture_.thenApply((k) -> this);
		}
	}

	public Context waitUntilReady() {
		if (data_ == null) {
			readyFuture_.join();
		}
		return this;
	}

	public String[] getExperiments() {
		checkReady(true);

		try {
			dataLock_.readLock().lock();
			return Arrays.stream(data_.experiments).map(x -> x.name).toArray(String[]::new);
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

	public void setOverride(@Nonnull String experimentName, int variant) {
		checkNotClosed();

		final Integer previous = overrides_.put(experimentName, variant);
		if ((previous == null) || (previous != variant)) {
			final Assignment assignment = assignmentCache_.get(experimentName);
			if (assignment != null) {
				if (!assignment.overridden || (assignment.variant != variant)) {
					assignmentCache_.remove(experimentName, assignment);
				}
			}
		}
	}

	public Integer getOverride(@Nonnull String experimentName) {
		return overrides_.get(experimentName);
	}

	public void setOverrides(@Nonnull Map<String, Integer> overrides) {
		overrides.forEach(this::setOverride);
	}

	public void setAttribute(@Nonnull String name, @Nullable Object value) {
		checkNotClosed();

		attributes_.put(name, new Attribute(name, value, clock_.millis()));
	}

	public void setAttributes(@Nonnull Map<String, Object> attributes) {
		attributes.forEach(this::setAttribute);
	}

	public int getTreatment(@Nonnull String experimentName) {
		checkReady(true);

		final Assignment assignment = getAssignment(experimentName);
		if (!assignment.exposed.get()) {
			queueExposure(assignment);
		}

		return assignment.variant;
	}

	private void queueExposure(Assignment assignment) {
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

			try {
				eventLock_.lock();
				pendingCount_.incrementAndGet();
				exposures_.add(exposure);
			} finally {
				eventLock_.unlock();
			}

			setTimeout();
		}
	}

	public int peekTreatment(@Nonnull String experimentName) {
		checkReady(true);

		return getAssignment(experimentName).variant;
	}

	public Map<String, String> getVariableKeys() {
		checkReady(true);

		final Map<String, String> variableKeys = new HashMap<>(indexVariables_.size());
		indexVariables_.forEach((k, v) -> variableKeys.put(k, v.data.name));
		return variableKeys;
	}

	public Object getVariableValue(@Nonnull String key, Object defaultValue) {
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

	public Object peekVariableValue(@Nonnull String key, Object defaultValue) {
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

	public void track(@Nonnull String goalName, Map<String, Object> properties) {
		checkNotClosed();

		final GoalAchievement achievement = new GoalAchievement();
		achievement.achievedAt = clock_.millis();
		achievement.name = goalName;
		achievement.properties = (properties == null) ? null : new TreeMap<>(properties);

		try {
			eventLock_.lock();
			pendingCount_.incrementAndGet();
			achievements_.add(achievement);
		} finally {
			eventLock_.unlock();
		}

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
			refreshFuture_ = new CompletableFuture<>();

			dataProvider_.getContextData().thenAccept(data -> {
				setData(data);
				refreshing_.set(false);
				refreshFuture_.complete(null);
			}).exceptionally(exception -> {
				refreshing_.set(false);
				refreshFuture_.completeExceptionally(exception);
				return null;
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
				if (pendingCount_.get() > 0) {
					closingFuture_ = new CompletableFuture<>();

					flush().thenAccept(x -> {
						closed_.set(true);
						closing_.set(false);
						closingFuture_.complete(null);
					}).exceptionally(exception -> {
						closed_.set(true);
						closing_.set(false);
						closingFuture_.completeExceptionally(exception);
						return null;
					});

					return closingFuture_;
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
					event.units = units_.entrySet().stream()
							.map(entry -> new Unit(entry.getKey(),
									new String(
											hashedUnits_.computeIfAbsent(entry.getKey(),
													k -> Hashing.hashUnit(entry.getValue())),
											StandardCharsets.US_ASCII)))
							.toArray(Unit[]::new);
					event.attributes = attributes_.isEmpty() ? null : attributes_.values().toArray(new Attribute[0]);
					event.exposures = exposures;
					event.goals = achievements;

					return eventHandler_.publish(event);
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
			throw new IllegalStateException("ABSmartly Context is closed.");
		} else if (closing_.get()) {
			throw new IllegalStateException("ABSmartly Context is closing.");
		}
	}

	private void checkReady(boolean expectNotClosed) {
		if (!isReady()) {
			throw new IllegalStateException("ABSmartly Context is not yet ready.");
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
		Map<String, Object> variables = Collections.emptyMap();

		final AtomicBoolean exposed = new AtomicBoolean(false);
	}

	private Assignment getAssignment(String experimentName) {
		return assignmentCache_.computeIfAbsent(experimentName, k -> {
			final Integer override = overrides_.get(experimentName);
			final ExperimentVariables experiment = getExperiment(experimentName);

			final Assignment assignment = new Assignment();
			assignment.name = experimentName;
			assignment.eligible = true;

			if (override != null) {
				if (experiment != null) {
					assignment.unitType = experiment.data.unitType;
					assignment.assigned = units_.containsKey(experiment.data.unitType);
				}

				assignment.overridden = true;
				assignment.variant = override;
			} else {
				if (experiment != null) {
					final String unitType = experiment.data.unitType;
					if (experiment.data.fullOnVariant == 0) {
						final String uid = units_.get(experiment.data.unitType);
						if (uid != null) {
							final byte[] unitHash = getUnitHash(unitType, uid);

							final VariantAssigner assigner = getVariantAssigner(unitType, unitHash);
							final boolean eligible = assigner.assign(experiment.data.trafficSplit,
									experiment.data.trafficSeedHi,
									experiment.data.trafficSeedLo) == 1;
							if (eligible) {
								assignment.variant = assigner.assign(experiment.data.split, experiment.data.seedHi,
										experiment.data.seedLo);
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

			return assignment;
		});
	}

	private Assignment getVariableAssignment(String key) {
		final ExperimentVariables experiment = getVariableExperiment(key);

		if (experiment != null) {
			return getAssignment(experiment.data.name);
		}
		return null;
	}

	private ExperimentVariables getExperiment(String experimentName) {
		try {
			dataLock_.readLock().lock();
			return index_.get(experimentName);
		} finally {
			dataLock_.readLock().unlock();
		}
	}

	private ExperimentVariables getVariableExperiment(String key) {
		try {
			dataLock_.readLock().lock();
			return indexVariables_.get(key);
		} finally {
			dataLock_.readLock().unlock();
		}
	}

	private byte[] getUnitHash(String unitType, String unitUID) {
		return hashedUnits_.computeIfAbsent(unitType, k -> Hashing.hashUnit(unitUID));
	}

	private VariantAssigner getVariantAssigner(String unitType, byte[] unitHash) {
		return assigners_.computeIfAbsent(unitType, k -> new VariantAssigner(unitHash));
	}

	private void setTimeout() {
		if (isReady()) {
			if (timeout_ == null) {
				try {
					timeoutLock_.lock();
					if (timeout_ == null) {
						timeout_ = scheduler_.schedule((Runnable) this::flush, publishDelay_, TimeUnit.MILLISECONDS);
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

	private static class ExperimentVariables {
		Experiment data;
		ArrayList<Map<String, Object>> variables;
	}

	private void setData(ContextData data) {
		try {
			final Map<String, ExperimentVariables> index = new HashMap<>();
			final Map<String, ExperimentVariables> indexVariables = new HashMap<>();

			for (final Experiment experiment : data.experiments) {
				final ExperimentVariables experimentVariables = new ExperimentVariables();
				experimentVariables.data = experiment;
				experimentVariables.variables = new ArrayList<>(experiment.variants.length);

				for (final ExperimentVariant variant : experiment.variants) {
					if ((variant.config != null) && !variant.config.isEmpty()) {
						final Map<String, Object> variables = variableParser_.parse(experiment.name, variant.name,
								variant.config);
						for (final String key : variables.keySet()) {
							indexVariables.put(key, experimentVariables);
						}
						experimentVariables.variables.add(variables);
					} else {
						experimentVariables.variables.add(Collections.emptyMap());
					}
				}

				index.put(experiment.name, experimentVariables);
			}

			dataLock_.writeLock().lock();

			for (Iterator<Map.Entry<String, Assignment>> it = assignmentCache_.entrySet().iterator(); it.hasNext();) {
				final Map.Entry<String, Assignment> entry = it.next();
				final String experimentName = entry.getKey();
				final Assignment assignment = entry.getValue();

				final ExperimentVariables experiment = index.get(experimentName);
				if (experiment == null) {
					if (assignment.assigned) {
						// previously running experiment was stopped
						it.remove();
					}
				} else {
					if (!assignment.assigned) {
						// previously not running experiment was started
						it.remove();
					} else if (!experimentMatches(experiment.data, assignment)) {
						// other relevant experiment data changed
						it.remove();
					}
				}
			}

			index_ = index;
			indexVariables_ = indexVariables;
			data_ = data;
		} finally {
			dataLock_.writeLock().unlock();
		}
	}

	private void setDataFailed(Throwable exception) {
		try {
			dataLock_.writeLock().lock();
			index_ = new HashMap<>();
			indexVariables_ = new HashMap<>();
			data_ = new ContextData();
			failed_ = true;
		} finally {
			dataLock_.writeLock().unlock();
		}
	}

	private final Clock clock_;
	private final long publishDelay_;
	private final ContextEventHandler eventHandler_;
	private final ContextDataProvider dataProvider_;
	private final VariableParser variableParser_;
	private final ScheduledExecutorService scheduler_;
	private final Map<String, String> units_;
	private boolean failed_;

	private final ReentrantReadWriteLock dataLock_ = new ReentrantReadWriteLock();
	private ContextData data_;
	private Map<String, ExperimentVariables> index_;
	private Map<String, ExperimentVariables> indexVariables_;

	private final ConcurrentMap<String, byte[]> hashedUnits_;
	private final ConcurrentMap<String, VariantAssigner> assigners_;
	private final ConcurrentMap<String, Assignment> assignmentCache_ = new ConcurrentHashMap<>();

	private final ReentrantLock eventLock_ = new ReentrantLock();
	private final ArrayList<Exposure> exposures_ = new ArrayList<>();
	private final ArrayList<GoalAchievement> achievements_ = new ArrayList<>();

	private final ConcurrentMap<String, Attribute> attributes_ = new ConcurrentHashMap<>();
	private final ConcurrentMap<String, Integer> overrides_;

	private final AtomicInteger pendingCount_ = new AtomicInteger(0);
	private final AtomicBoolean closing_ = new AtomicBoolean(false);
	private final AtomicBoolean closed_ = new AtomicBoolean(false);
	private final AtomicBoolean refreshing_ = new AtomicBoolean(false);

	private volatile CompletableFuture<Void> readyFuture_;
	private volatile CompletableFuture<Void> closingFuture_;
	private volatile CompletableFuture<Void> refreshFuture_;

	private final ReentrantLock timeoutLock_ = new ReentrantLock();
	private volatile ScheduledFuture<?> timeout_ = null;
}
