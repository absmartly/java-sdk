package com.absmartly.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.ScheduledExecutorService;
import java8.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.absmartly.sdk.internal.hashing.Hashing;
import com.absmartly.sdk.java.nio.charset.StandardCharsets;
import com.absmartly.sdk.java.time.Clock;
import com.absmartly.sdk.json.ContextData;
import com.absmartly.sdk.json.Experiment;
import com.absmartly.sdk.json.ExperimentApplication;
import com.absmartly.sdk.json.ExperimentHoldout;
import com.absmartly.sdk.json.ExperimentVariant;
import com.absmartly.sdk.json.Exposure;
import com.absmartly.sdk.json.PublishEvent;
import com.absmartly.sdk.json.Unit;

class ContextHoldoutTest extends TestUtils {
	static final String UNIT_TYPE = "session_id";
	static final String UID = "e791e240fcd3df7d238cfc285f475e8152fcc0ec";

	// split[0.5,0.5], seedHi=100, seedLo=200 -> variant 1 for UID above.
	static final int NORMAL_SEED_HI = 100;
	static final int NORMAL_SEED_LO = 200;
	static final int NORMAL_VARIANT = 1;

	// split[0.1,0.9], seedHi=13, seedLo=111 -> variant 0 (i.e. user IS in this holdout) for UID above.
	static final int HOLDOUT_IN_SEED_HI = 13;
	static final int HOLDOUT_IN_SEED_LO = 111;

	// split[0.1,0.9], seedHi=1, seedLo=222 -> variant 1 (i.e. user is NOT in this holdout) for UID above.
	static final int HOLDOUT_OUT_SEED_HI = 1;
	static final int HOLDOUT_OUT_SEED_LO = 222;

	ContextDataProvider dataProvider;
	ContextEventLogger eventLogger;
	ContextEventHandler eventHandler;
	VariableParser variableParser;
	AudienceMatcher audienceMatcher;
	ScheduledExecutorService scheduler;
	Clock clock = Clock.fixed(1_620_000_000_000L);

	@BeforeEach
	void setUp() {
		dataProvider = mock(ContextDataProvider.class);
		eventHandler = mock(ContextEventHandler.class);
		eventLogger = mock(ContextEventLogger.class);
		variableParser = new DefaultVariableParser();
		audienceMatcher = new AudienceMatcher(new DefaultAudienceDeserializer());
		scheduler = mock(ScheduledExecutorService.class);
	}

	Context createReadyContext(ContextData data) {
		final ContextConfig config = ContextConfig.create().setUnit(UNIT_TYPE, UID);
		return Context.create(clock, config, scheduler, CompletableFuture.completedFuture(data), dataProvider,
				eventHandler, eventLogger, variableParser, audienceMatcher);
	}

	Context createReadyContext(ContextConfig config, ContextData data) {
		return Context.create(clock, config, scheduler, CompletableFuture.completedFuture(data), dataProvider,
				eventHandler, eventLogger, variableParser, audienceMatcher);
	}

	static Experiment newExperiment(int id, String name) {
		final Experiment experiment = new Experiment();
		experiment.id = id;
		experiment.name = name;
		experiment.unitType = UNIT_TYPE;
		experiment.iteration = 1;
		experiment.seedHi = NORMAL_SEED_HI;
		experiment.seedLo = NORMAL_SEED_LO;
		experiment.split = new double[]{0.5, 0.5};
		experiment.trafficSeedHi = 1;
		experiment.trafficSeedLo = 2;
		experiment.trafficSplit = new double[]{0.0, 1.0};
		experiment.fullOnVariant = 0;
		experiment.applications = new ExperimentApplication[]{new ExperimentApplication("website")};
		experiment.variants = new ExperimentVariant[]{
				new ExperimentVariant("A", null),
				new ExperimentVariant("B", null)
		};
		experiment.audienceStrict = false;
		experiment.audience = null;
		return experiment;
	}

	static ExperimentHoldout newHoldout(int id, int seedHi, int seedLo) {
		return new ExperimentHoldout(id, seedHi, seedLo, new double[]{0.1, 0.9});
	}

	static ContextData contextDataOf(Experiment... experiments) {
		return contextDataOf(new ExperimentHoldout[0], experiments);
	}

	static ContextData contextDataOf(ExperimentHoldout[] holdouts, Experiment... experiments) {
		final ContextData data = new ContextData();
		data.experiments = experiments;
		data.holdouts = holdouts;
		return data;
	}

	@Test
	void assignsNormallyWhenExperimentHasNoHoldouts() {
		final Experiment experiment = newExperiment(1, "exp_no_holdout");

		final Context context = createReadyContext(contextDataOf(experiment));

		assertEquals(NORMAL_VARIANT, context.peekTreatment("exp_no_holdout"));
	}

	@Test
	void assignsControlVariantWhenUnitIsInHoldout() {
		final Experiment experiment = newExperiment(1, "exp_holdout_in");
		experiment.holdoutIds = new int[]{11};

		final Context context = createReadyContext(contextDataOf(
				new ExperimentHoldout[]{newHoldout(11, HOLDOUT_IN_SEED_HI, HOLDOUT_IN_SEED_LO)}, experiment));

		assertEquals(0, context.peekTreatment("exp_holdout_in"));
	}

	@Test
	void assignsNormallyWhenUnitIsNotInHoldout() {
		final Experiment experiment = newExperiment(1, "exp_holdout_out");
		experiment.holdoutIds = new int[]{11};

		final Context context = createReadyContext(contextDataOf(
				new ExperimentHoldout[]{newHoldout(11, HOLDOUT_OUT_SEED_HI, HOLDOUT_OUT_SEED_LO)}, experiment));

		assertEquals(NORMAL_VARIANT, context.peekTreatment("exp_holdout_out"));
	}

	@Test
	void checksAllHoldoutsUntilAMatchIsFound() {
		final Experiment experiment = newExperiment(1, "exp_multi_holdout");
		experiment.holdoutIds = new int[]{11, 12};

		final Context context = createReadyContext(contextDataOf(
				new ExperimentHoldout[]{
						newHoldout(11, HOLDOUT_OUT_SEED_HI, HOLDOUT_OUT_SEED_LO),
						newHoldout(12, HOLDOUT_IN_SEED_HI, HOLDOUT_IN_SEED_LO),
				}, experiment));

		assertEquals(0, context.peekTreatment("exp_multi_holdout"));
	}

	@Test
	void evaluatesMultipleHoldoutsInCanonicalIdOrder() {
		final Experiment experiment = newExperiment(1, "exp_ordered_holdouts");
		experiment.holdoutIds = new int[]{42, 11};

		final Context context = createReadyContext(contextDataOf(
				new ExperimentHoldout[]{
						newHoldout(42, HOLDOUT_IN_SEED_HI, HOLDOUT_IN_SEED_LO),
						newHoldout(11, HOLDOUT_IN_SEED_HI, HOLDOUT_IN_SEED_LO),
				}, experiment));

		assertEquals(0, context.getTreatment("exp_ordered_holdouts"));

		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
		context.publish();

		final PublishEvent expected = new PublishEvent();
		expected.hashed = true;
		expected.publishedAt = clock.millis();
		expected.units = new Unit[]{
				new Unit(UNIT_TYPE, new String(Hashing.hashUnit(UID), StandardCharsets.US_ASCII))
		};
		expected.exposures = new Exposure[]{
				new Exposure(1, "exp_ordered_holdouts", UNIT_TYPE, 0, clock.millis(), true, true, false, false,
						false, false, true, 11),
		};

		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(context, expected);
	}

	@Test
	void assignsNormallyWhenNotInAnyHoldout() {
		final Experiment experiment = newExperiment(1, "exp_multi_holdout_miss");
		experiment.holdoutIds = new int[]{11, 12};

		final Context context = createReadyContext(contextDataOf(
				new ExperimentHoldout[]{
						newHoldout(11, HOLDOUT_OUT_SEED_HI, HOLDOUT_OUT_SEED_LO),
						newHoldout(12, HOLDOUT_OUT_SEED_HI, HOLDOUT_OUT_SEED_LO),
				}, experiment));

		assertEquals(NORMAL_VARIANT, context.peekTreatment("exp_multi_holdout_miss"));
	}

	@Test
	void sharesHoldoutDefinitionsAcrossExperiments() {
		final Experiment experimentA = newExperiment(1, "exp_shared_a");
		experimentA.holdoutIds = new int[]{11};
		final Experiment experimentB = newExperiment(2, "exp_shared_b");
		experimentB.holdoutIds = new int[]{11};

		final Context context = createReadyContext(contextDataOf(
				new ExperimentHoldout[]{newHoldout(11, HOLDOUT_IN_SEED_HI, HOLDOUT_IN_SEED_LO)},
				experimentA, experimentB));

		// same seed -> same membership across both experiments referencing the shared holdout
		assertEquals(0, context.peekTreatment("exp_shared_a"));
		assertEquals(0, context.peekTreatment("exp_shared_b"));
	}

	@Test
	void assignsNormallyWhenHoldoutIdIsUnknown() {
		final Experiment experiment = newExperiment(1, "exp_unknown_holdout");
		experiment.holdoutIds = new int[]{99}; // no matching top-level definition

		final Context context = createReadyContext(contextDataOf(
				new ExperimentHoldout[]{newHoldout(11, HOLDOUT_IN_SEED_HI, HOLDOUT_IN_SEED_LO)}, experiment));

		assertEquals(NORMAL_VARIANT, context.peekTreatment("exp_unknown_holdout"));
	}

	@Test
	void resolvesKnownHoldoutAndSkipsUnknownId() {
		final Experiment experiment = newExperiment(1, "exp_mixed_holdout");
		experiment.holdoutIds = new int[]{99, 11}; // 99 is unknown, 11 resolves and holds the unit out

		final Context context = createReadyContext(contextDataOf(
				new ExperimentHoldout[]{newHoldout(11, HOLDOUT_IN_SEED_HI, HOLDOUT_IN_SEED_LO)}, experiment));

		assertEquals(0, context.peekTreatment("exp_mixed_holdout"));
	}

	@Test
	void skipsMalformedHoldoutWithNullSplit() {
		final Experiment experiment = newExperiment(1, "exp_null_split_holdout");
		experiment.holdoutIds = new int[]{11};

		// a holdout whose split is missing from the payload must be dropped, not crash assignment
		final Context context = createReadyContext(contextDataOf(
				new ExperimentHoldout[]{new ExperimentHoldout(11, HOLDOUT_IN_SEED_HI, HOLDOUT_IN_SEED_LO, null)},
				experiment));

		assertEquals(NORMAL_VARIANT, context.peekTreatment("exp_null_split_holdout"));
	}

	@Test
	void skipsHoldoutWhenUnitMissingForUnitType() {
		final Experiment experiment = newExperiment(1, "exp_no_unit_holdout");
		experiment.unitType = "user_id"; // no unit registered for this type
		experiment.holdoutIds = new int[]{11};

		final Context context = createReadyContext(contextDataOf(
				new ExperimentHoldout[]{newHoldout(11, HOLDOUT_IN_SEED_HI, HOLDOUT_IN_SEED_LO)}, experiment));

		// holdout evaluation is skipped (no uid); assignment falls through unassigned -> control 0, not held out
		assertEquals(0, context.getTreatment("exp_no_unit_holdout"));

		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
		context.publish();

		final PublishEvent expected = new PublishEvent();
		expected.hashed = true;
		expected.publishedAt = clock.millis();
		expected.units = new Unit[]{
				new Unit(UNIT_TYPE, new String(Hashing.hashUnit(UID), StandardCharsets.US_ASCII))
		};
		expected.exposures = new Exposure[]{
				new Exposure(1, "exp_no_unit_holdout", "user_id", 0, clock.millis(), false, true, false, false, false,
						false, false, 0),
		};

		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(context, expected);
	}

	@Test
	void reusesCachedAssignmentForHeldOutExperiment() {
		final Experiment experiment = newExperiment(1, "exp_holdout_cache");
		experiment.holdoutIds = new int[]{11};

		final Context context = createReadyContext(contextDataOf(
				new ExperimentHoldout[]{newHoldout(11, HOLDOUT_IN_SEED_HI, HOLDOUT_IN_SEED_LO)}, experiment));

		assertEquals(0, context.getTreatment("exp_holdout_cache"));
		// second call hits the cache (holdouts present and unchanged) -> no new exposure
		assertEquals(0, context.getTreatment("exp_holdout_cache"));
		assertEquals(1, context.getPendingCount());
	}

	@Test
	void holdoutTakesPrecedenceOverAudienceMismatch() {
		final Experiment experiment = newExperiment(1, "exp_holdout_audience");
		experiment.audienceStrict = true;
		experiment.audience = "{\"filter\":[{\"gte\":[{\"var\":\"age\"},{\"value\":20}]}]}";
		experiment.holdoutIds = new int[]{11};

		final Context context = createReadyContext(contextDataOf(
				new ExperimentHoldout[]{newHoldout(11, HOLDOUT_IN_SEED_HI, HOLDOUT_IN_SEED_LO)}, experiment));
		context.setAttribute("age", 5); // would mismatch the audience filter if evaluated

		assertEquals(0, context.peekTreatment("exp_holdout_audience"));
	}

	@Test
	void overrideTakesPrecedenceOverHoldout() {
		final Experiment experiment = newExperiment(1, "exp_holdout_override");
		experiment.holdoutIds = new int[]{11};

		final ContextConfig config = ContextConfig.create().setUnit(UNIT_TYPE, UID).setOverride(
				"exp_holdout_override", 3);
		final Context context = createReadyContext(config, contextDataOf(
				new ExperimentHoldout[]{newHoldout(11, HOLDOUT_IN_SEED_HI, HOLDOUT_IN_SEED_LO)}, experiment));

		assertEquals(3, context.peekTreatment("exp_holdout_override"));
	}

	@Test
	void holdoutTakesPrecedenceOverCustomAssignment() {
		final Experiment experiment = newExperiment(1, "exp_holdout_custom");
		experiment.holdoutIds = new int[]{11};

		final ContextConfig config = ContextConfig.create().setUnit(UNIT_TYPE, UID).setCustomAssignment(
				"exp_holdout_custom", 3);
		final Context context = createReadyContext(config, contextDataOf(
				new ExperimentHoldout[]{newHoldout(11, HOLDOUT_IN_SEED_HI, HOLDOUT_IN_SEED_LO)}, experiment));

		assertEquals(0, context.peekTreatment("exp_holdout_custom"));
	}

	@Test
	void reusesCachedHeldOutAssignmentWithCustomAssignment() {
		final Experiment experiment = newExperiment(1, "exp_holdout_custom_cache");
		experiment.holdoutIds = new int[]{11};

		final ContextConfig config = ContextConfig.create().setUnit(UNIT_TYPE, UID).setCustomAssignment(
				"exp_holdout_custom_cache", 3);
		final Context context = createReadyContext(config, contextDataOf(
				new ExperimentHoldout[]{newHoldout(11, HOLDOUT_IN_SEED_HI, HOLDOUT_IN_SEED_LO)}, experiment));

		// the custom assignment can never apply while held out; repeated calls must hit the
		// cache and not queue duplicate exposures
		assertEquals(0, context.getTreatment("exp_holdout_custom_cache"));
		assertEquals(0, context.getTreatment("exp_holdout_custom_cache"));
		assertEquals(1, context.getPendingCount());
	}

	@Test
	void reusesCachedAudienceMismatchAssignmentWithCustomAssignment() {
		final Experiment experiment = newExperiment(1, "exp_audience_custom_cache");
		experiment.audienceStrict = true;
		experiment.audience = "{\"filter\":[{\"gte\":[{\"var\":\"age\"},{\"value\":20}]}]}";

		final ContextConfig config = ContextConfig.create().setUnit(UNIT_TYPE, UID).setCustomAssignment(
				"exp_audience_custom_cache", 3);
		final Context context = createReadyContext(config, contextDataOf(experiment));
		context.setAttribute("age", 5); // mismatches the strict audience -> variant forced to 0

		// strict audience mismatch forces variant 0; the custom assignment can never apply, so
		// repeated calls must hit the cache and not queue duplicate exposures
		assertEquals(0, context.getTreatment("exp_audience_custom_cache"));
		assertEquals(0, context.getTreatment("exp_audience_custom_cache"));
		assertEquals(1, context.getPendingCount());
	}

	@Test
	void reusesCachedTrafficIneligibleAssignmentWithCustomAssignment() {
		final Experiment experiment = newExperiment(1, "exp_traffic_custom_cache");
		experiment.trafficSplit = new double[]{1.0, 0.0}; // unit is NOT in the experiment traffic

		final ContextConfig config = ContextConfig.create().setUnit(UNIT_TYPE, UID).setCustomAssignment(
				"exp_traffic_custom_cache", 3);
		final Context context = createReadyContext(config, contextDataOf(experiment));

		// traffic ineligibility forces variant 0; the custom assignment can never apply, so repeated
		// calls must hit the cache and not queue duplicate exposures
		assertEquals(0, context.getTreatment("exp_traffic_custom_cache"));
		assertEquals(0, context.getTreatment("exp_traffic_custom_cache"));
		assertEquals(1, context.getPendingCount());
	}

	@Test
	void holdoutTakesPrecedenceOverFullOn() {
		final Experiment experiment = newExperiment(1, "exp_holdout_fullon");
		experiment.fullOnVariant = 2;
		experiment.holdoutIds = new int[]{11};

		final Context context = createReadyContext(contextDataOf(
				new ExperimentHoldout[]{newHoldout(11, HOLDOUT_IN_SEED_HI, HOLDOUT_IN_SEED_LO)}, experiment));

		assertEquals(0, context.peekTreatment("exp_holdout_fullon"));
	}

	@Test
	void refreshReassignsWhenHoldoutsChange() {
		final Experiment experiment = newExperiment(1, "exp_holdout_refresh");

		final Context context = createReadyContext(contextDataOf(experiment));

		assertEquals(NORMAL_VARIANT, context.getTreatment("exp_holdout_refresh"));
		assertEquals(1, context.getPendingCount());

		final Experiment refreshedExperiment = newExperiment(1, "exp_holdout_refresh");
		refreshedExperiment.holdoutIds = new int[]{11};

		final CompletableFuture<ContextData> refreshFuture = new CompletableFuture<>();
		when(dataProvider.getContextData()).thenReturn(refreshFuture);

		final CompletableFuture<Void> refreshing = context.refreshAsync();
		refreshFuture.complete(contextDataOf(
				new ExperimentHoldout[]{newHoldout(11, HOLDOUT_IN_SEED_HI, HOLDOUT_IN_SEED_LO)}, refreshedExperiment));
		refreshing.join();

		assertEquals(0, context.getTreatment("exp_holdout_refresh"));
		assertEquals(2, context.getPendingCount()); // holdout change triggered a new exposure
	}

	@Test
	void refreshReassignsWhenReferencedHoldoutDefinitionChanges() {
		final Experiment experiment = newExperiment(1, "exp_holdout_def_change");
		experiment.holdoutIds = new int[]{11};

		// unit is NOT in the holdout initially
		final Context context = createReadyContext(contextDataOf(
				new ExperimentHoldout[]{newHoldout(11, HOLDOUT_OUT_SEED_HI, HOLDOUT_OUT_SEED_LO)}, experiment));

		assertEquals(NORMAL_VARIANT, context.getTreatment("exp_holdout_def_change"));
		assertEquals(1, context.getPendingCount());

		// same holdoutIds, but the referenced definition's seed changes so the unit is now IN the holdout
		final Experiment refreshedExperiment = newExperiment(1, "exp_holdout_def_change");
		refreshedExperiment.holdoutIds = new int[]{11};

		final CompletableFuture<ContextData> refreshFuture = new CompletableFuture<>();
		when(dataProvider.getContextData()).thenReturn(refreshFuture);

		final CompletableFuture<Void> refreshing = context.refreshAsync();
		refreshFuture.complete(contextDataOf(
				new ExperimentHoldout[]{newHoldout(11, HOLDOUT_IN_SEED_HI, HOLDOUT_IN_SEED_LO)}, refreshedExperiment));
		refreshing.join();

		assertEquals(0, context.getTreatment("exp_holdout_def_change"));
		assertEquals(2, context.getPendingCount()); // changed definition triggered a new exposure
	}

	@Test
	void exposureCarriesHeldOutAndHoldoutId() {
		final Experiment experiment = newExperiment(1, "exp_holdout_exposure");
		experiment.holdoutIds = new int[]{42};

		final Context context = createReadyContext(contextDataOf(
				new ExperimentHoldout[]{newHoldout(42, HOLDOUT_IN_SEED_HI, HOLDOUT_IN_SEED_LO)}, experiment));

		assertEquals(0, context.getTreatment("exp_holdout_exposure"));

		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));

		context.publish();

		final PublishEvent expected = new PublishEvent();
		expected.hashed = true;
		expected.publishedAt = clock.millis();
		expected.units = new Unit[]{
				new Unit(UNIT_TYPE, new String(Hashing.hashUnit(UID), StandardCharsets.US_ASCII))
		};
		expected.exposures = new Exposure[]{
				new Exposure(1, "exp_holdout_exposure", UNIT_TYPE, 0, clock.millis(), true, true, false, false, false,
						false, true, 42),
		};

		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(context, expected);
	}

	@Test
	void exposureCarriesNotHeldOutFieldsWhenUnitNotInHoldout() {
		final Experiment experiment = newExperiment(1, "exp_holdout_out_exposure");
		experiment.holdoutIds = new int[]{11};

		// holdout evaluated but unit is NOT in it -> normal assignment, heldOut=false, holdoutId=0
		final Context context = createReadyContext(contextDataOf(
				new ExperimentHoldout[]{newHoldout(11, HOLDOUT_OUT_SEED_HI, HOLDOUT_OUT_SEED_LO)}, experiment));

		assertEquals(NORMAL_VARIANT, context.getTreatment("exp_holdout_out_exposure"));

		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));

		context.publish();

		final PublishEvent expected = new PublishEvent();
		expected.hashed = true;
		expected.publishedAt = clock.millis();
		expected.units = new Unit[]{
				new Unit(UNIT_TYPE, new String(Hashing.hashUnit(UID), StandardCharsets.US_ASCII))
		};
		expected.exposures = new Exposure[]{
				new Exposure(1, "exp_holdout_out_exposure", UNIT_TYPE, NORMAL_VARIANT, clock.millis(), true, true,
						false,
						false, false, false, false, 0),
		};

		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(context, expected);
	}

	@Test
	void assignsNormallyWhenHoldoutIdsIsEmpty() {
		final Experiment experiment = newExperiment(1, "exp_empty_holdout_ids");
		experiment.holdoutIds = new int[0];

		final Context context = createReadyContext(contextDataOf(
				new ExperimentHoldout[]{newHoldout(11, HOLDOUT_IN_SEED_HI, HOLDOUT_IN_SEED_LO)}, experiment));

		assertEquals(NORMAL_VARIANT, context.peekTreatment("exp_empty_holdout_ids"));
	}

	@Test
	void skipsHoldoutWithEmptySplit() {
		final Experiment experiment = newExperiment(1, "exp_empty_split_holdout");
		experiment.holdoutIds = new int[]{11};

		// a holdout with an empty split must be dropped, not hold the unit out
		final Context context = createReadyContext(contextDataOf(
				new ExperimentHoldout[]{new ExperimentHoldout(11, HOLDOUT_IN_SEED_HI, HOLDOUT_IN_SEED_LO,
						new double[0])},
				experiment));

		assertEquals(NORMAL_VARIANT, context.peekTreatment("exp_empty_split_holdout"));
	}
}
