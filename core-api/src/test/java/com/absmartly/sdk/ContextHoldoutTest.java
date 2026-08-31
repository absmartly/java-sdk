package com.absmartly.sdk;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java8.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.absmartly.sdk.internal.hashing.Hashing;
import com.absmartly.sdk.java.nio.charset.StandardCharsets;
import com.absmartly.sdk.java.time.Clock;
import com.absmartly.sdk.json.Attribute;
import com.absmartly.sdk.json.ContextData;
import com.absmartly.sdk.json.Experiment;
import com.absmartly.sdk.json.ExperimentApplication;
import com.absmartly.sdk.json.ExperimentVariant;
import com.absmartly.sdk.json.Exposure;
import com.absmartly.sdk.json.PublishEvent;
import com.absmartly.sdk.json.Unit;

// A holdout arrives as an ordinary experiment entry (in ContextData.holdouts, not .experiments) so
// its variant semantics are: variant 0 = held out (no experimentation at all), variant 1 = exposed.
// A covered experiment declares its coverage explicitly via holdoutIds, an array of holdout ids
// resolved server-side; the SDK's only job is to look each id up in the installed holdouts[] and
// evaluate every one it finds (an id absent from holdouts[] is simply not covered by it).
// A held-out unit gets control values and emits NO exposure for the experiments it covers; the
// holdout experiment itself always emits one ordinary exposure, cached once per context.
class ContextHoldoutTest extends TestUtils {
	static final String UNIT_TYPE = "session_id";
	static final String UID = "e791e240fcd3df7d238cfc285f475e8152fcc0ec";
	static final String UID_NOT_HELD_OUT = "b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3";

	// split[0.5,0.5], seedHi=100, seedLo=200 -> variant 1 for UID, variant 0 for UID_NOT_HELD_OUT.
	static final int NORMAL_SEED_HI = 100;
	static final int NORMAL_SEED_LO = 200;
	static final int NORMAL_VARIANT = 1;

	// split[0.1,0.9], seedHi=13, seedLo=111 -> variant 0 (held out) for UID, variant 1 for
	// UID_NOT_HELD_OUT.
	static final int HOLDOUT_A_SEED_HI = 13;
	static final int HOLDOUT_A_SEED_LO = 111;

	// split[0.1,0.9], seedHi=1, seedLo=222 -> variant 1 (not held out) for both UIDs.
	static final int HOLDOUT_B_SEED_HI = 1;
	static final int HOLDOUT_B_SEED_LO = 222;

	// A 3-arm (all_full_on) holdout's split has length 3, which is what selects the arm-1 rule -
	// holdoutType is never read by Context.java. Seeds below were found by exhaustive search
	// against this split for UID: seedLo=1 -> arm 0, seedLo=3 -> arm 1, seedLo=0 -> arm 2.
	static final double[] THREE_ARM_SPLIT = {0.3, 0.3, 0.4};
	static final int THREE_ARM_0_SEED_HI = 0;
	static final int THREE_ARM_0_SEED_LO = 1;
	static final int THREE_ARM_1_SEED_HI = 0;
	static final int THREE_ARM_1_SEED_LO = 3;
	static final int THREE_ARM_2_SEED_HI = 0;
	static final int THREE_ARM_2_SEED_LO = 0;

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

	Context createReadyContext(String uid, ContextData data) {
		final ContextConfig config = ContextConfig.create().setUnit(UNIT_TYPE, uid);
		return Context.create(clock, config, scheduler, CompletableFuture.completedFuture(data), dataProvider,
				eventHandler, eventLogger, variableParser, audienceMatcher);
	}

	Context createReadyContext(ContextData data) {
		return createReadyContext(UID, data);
	}

	Context createReadyContext(ContextConfig config, ContextData data) {
		return Context.create(clock, config, scheduler, CompletableFuture.completedFuture(data), dataProvider,
				eventHandler, eventLogger, variableParser, audienceMatcher);
	}

	static Experiment newExperiment(int id, String name) {
		return newExperiment(id, name, UNIT_TYPE, 0);
	}

	static Experiment newExperiment(int id, String name, int fullOnVariant) {
		return newExperiment(id, name, UNIT_TYPE, fullOnVariant);
	}

	static Experiment newExperiment(int id, String name, String unitType, int fullOnVariant) {
		final Experiment experiment = new Experiment();
		experiment.id = id;
		experiment.name = name;
		experiment.unitType = unitType;
		experiment.iteration = 1;
		experiment.seedHi = NORMAL_SEED_HI;
		experiment.seedLo = NORMAL_SEED_LO;
		experiment.split = new double[]{0.5, 0.5};
		experiment.trafficSeedHi = 1;
		experiment.trafficSeedLo = 2;
		experiment.trafficSplit = new double[]{0.0, 1.0};
		experiment.fullOnVariant = fullOnVariant;
		experiment.applications = new ExperimentApplication[]{new ExperimentApplication("website")};
		experiment.variants = new ExperimentVariant[]{
				new ExperimentVariant("A", null),
				new ExperimentVariant("B", null)
		};
		experiment.audienceStrict = false;
		experiment.audience = null;
		return experiment;
	}

	static Experiment newHoldout(int id, String name, int seedHi, int seedLo) {
		return newHoldout(id, name, UNIT_TYPE, seedHi, seedLo, "full");
	}

	static Experiment newHoldout(int id, String name, String unitType, int seedHi, int seedLo, String holdoutType) {
		final Experiment holdout = new Experiment();
		holdout.id = id;
		holdout.name = name;
		holdout.unitType = unitType;
		holdout.iteration = 1;
		holdout.seedHi = seedHi;
		holdout.seedLo = seedLo;
		holdout.split = new double[]{0.1, 0.9};
		holdout.trafficSplit = new double[]{0.0, 1.0};
		holdout.fullOnVariant = 0;
		holdout.applications = new ExperimentApplication[0];
		holdout.variants = new ExperimentVariant[]{
				new ExperimentVariant("A", null),
				new ExperimentVariant("B", null)
		};
		holdout.audienceStrict = false;
		holdout.audience = null;
		holdout.holdoutType = holdoutType;
		return holdout;
	}

	// A 3-arm (all_full_on) holdout. holdoutType is set for wire-fidelity only; Context.java
	// derives arity solely from split.length.
	static Experiment newThreeArmHoldout(int id, String name, int seedHi, int seedLo) {
		final Experiment holdout = newHoldout(id, name, UNIT_TYPE, seedHi, seedLo, "all_full_on");
		holdout.split = THREE_ARM_SPLIT;
		holdout.variants = new ExperimentVariant[]{
				new ExperimentVariant("A", null),
				new ExperimentVariant("B", null),
				new ExperimentVariant("C", null)
		};
		return holdout;
	}

	static Experiment coveredBy(Experiment experiment, int... holdoutIds) {
		experiment.holdoutIds = holdoutIds;
		return experiment;
	}

	static ContextData contextDataOf(Experiment... experiments) {
		return contextDataOf(null, experiments);
	}

	static ContextData contextDataOf(Experiment[] holdouts, Experiment... experiments) {
		final ContextData data = new ContextData();
		data.experiments = experiments;
		data.holdouts = holdouts;
		return data;
	}

	PublishEvent publishedEvent(String uid, Exposure... exposures) {
		return publishedEvent(UNIT_TYPE, uid, exposures);
	}

	PublishEvent publishedEvent(String unitType, String uid, Exposure... exposures) {
		final PublishEvent expected = new PublishEvent();
		expected.hashed = true;
		expected.publishedAt = clock.millis();
		expected.units = new Unit[]{
				new Unit(unitType, new String(Hashing.hashUnit(uid), StandardCharsets.US_ASCII))
		};
		expected.exposures = exposures;
		return expected;
	}

	Exposure holdoutExposure(int id, String name, int variant) {
		return holdoutExposure(UNIT_TYPE, id, name, variant);
	}

	Exposure holdoutExposure(String unitType, int id, String name, int variant) {
		return new Exposure(id, name, unitType, variant, clock.millis(), true, true, false, false, false, false);
	}

	// A held-out unit gets control values and emits only the holdout exposure.
	@Test
	void heldOutUnitGetsControlValuesAndEmitsNoExposureForCoveredExperiment() {
		final Experiment experiment = coveredBy(newExperiment(1, "exp_holdout_in"), 11);
		final Context context = createReadyContext(contextDataOf(
				new Experiment[]{newHoldout(11, "holdout_a", HOLDOUT_A_SEED_HI, HOLDOUT_A_SEED_LO)}, experiment));

		assertEquals(0, context.peekTreatment("exp_holdout_in"));

		context.getTreatment("exp_holdout_in");
		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
		context.publish();

		final PublishEvent expected = publishedEvent(UID, holdoutExposure(11, "holdout_a", 0));
		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(context, expected);
	}

	// A non-held-out unit emits the holdout exposure with variant=1 plus its own normal
	// exposure, unchanged.
	@Test
	void notHeldOutUnitEmitsHoldoutExposureVariantOneAndNormalExposure() {
		final Experiment experiment = coveredBy(newExperiment(1, "exp_holdout_out"), 11);
		final Context context = createReadyContext(UID_NOT_HELD_OUT, contextDataOf(
				new Experiment[]{newHoldout(11, "holdout_a", HOLDOUT_A_SEED_HI, HOLDOUT_A_SEED_LO)}, experiment));

		assertEquals(0, context.getTreatment("exp_holdout_out")); // UID_NOT_HELD_OUT's normal variant is 0
		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
		context.publish();

		final PublishEvent expected = publishedEvent(UID_NOT_HELD_OUT,
				new Exposure(1, "exp_holdout_out", UNIT_TYPE, 0, clock.millis(), true, true, false, false, false,
						false),
				holdoutExposure(11, "holdout_a", 1));
		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(context, expected);
	}

	// An experiment without holdoutIds is not covered by any holdout, even one that suppresses a
	// sibling experiment for the same unit under the same context.
	@Test
	void experimentWithoutHoldoutIdsEmitsNormallyEvenForHeldOutUnit() {
		final Experiment covered = coveredBy(newExperiment(1, "exp_holdout_in"), 11);
		final Experiment notCovered = newExperiment(2, "exp_not_covered"); // holdoutIds left null

		final Experiment holdout = newHoldout(11, "holdout_a", HOLDOUT_A_SEED_HI, HOLDOUT_A_SEED_LO);

		final Context context = createReadyContext(contextDataOf(new Experiment[]{holdout}, covered, notCovered));

		assertEquals(0, context.getTreatment("exp_holdout_in")); // suppressed -> control
		assertEquals(NORMAL_VARIANT, context.getTreatment("exp_not_covered")); // no coverage -> unaffected

		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
		context.publish();

		final PublishEvent expected = publishedEvent(UID,
				holdoutExposure(11, "holdout_a", 0),
				new Exposure(2, "exp_not_covered", UNIT_TYPE, NORMAL_VARIANT, clock.millis(), true, true, false,
						false, false, false));
		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(context, expected);
	}

	// A normal experiment covered by two holdouts is suppressed if the unit is held out by
	// EITHER one (union), and each applicable holdout still emits its own independent exposure.
	@Test
	void unionOfApplicableHoldoutsSuppressesExperimentAndBothEmitOwnExposure() {
		final Experiment experiment = coveredBy(newExperiment(1, "exp_multi_holdout"), 11, 12);
		final Context context = createReadyContext(contextDataOf(
				new Experiment[]{
						newHoldout(11, "holdout_a", HOLDOUT_A_SEED_HI, HOLDOUT_A_SEED_LO), // holds UID out
						newHoldout(12, "holdout_b", HOLDOUT_B_SEED_HI, HOLDOUT_B_SEED_LO), // does not hold UID out
				}, experiment));

		assertEquals(0, context.getTreatment("exp_multi_holdout")); // union -> suppressed
		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
		context.publish();

		final PublishEvent expected = publishedEvent(UID,
				holdoutExposure(11, "holdout_a", 0),
				holdoutExposure(12, "holdout_b", 1));
		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(context, expected);
	}

	// The union rule is never proven if the deciding (held-out) holdout always sits at index 0 -
	// a bug that stops at the first applicable holdout, or only ever consults holdouts[0], would
	// still pass every other multi-holdout test above. Here the LOW-id holdout (11) does NOT hold
	// the unit out and the HIGHER-id one (12) DOES; suppression must still trigger and both
	// holdouts must still emit their own exposure with the correct variant. This also pins the
	// id-lookup itself: swapping which Experiment object id 11 vs id 12 resolves to would flip
	// both the suppression verdict and which exposure carries which variant.
	@Test
	void unionRuleAppliesWhenTheHigherIdHoldoutIsTheOnlyOneHoldingUnitOut() {
		final Experiment experiment = coveredBy(newExperiment(1, "exp_union_high_id_decides"), 11, 12);
		final Context context = createReadyContext(contextDataOf(
				new Experiment[]{
						newHoldout(11, "holdout_low_id", HOLDOUT_B_SEED_HI, HOLDOUT_B_SEED_LO), // does not hold UID out
						newHoldout(12, "holdout_high_id", HOLDOUT_A_SEED_HI, HOLDOUT_A_SEED_LO), // holds UID out
				}, experiment));

		assertEquals(0, context.getTreatment("exp_union_high_id_decides")); // union -> suppressed
		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
		context.publish();

		final PublishEvent expected = publishedEvent(UID,
				holdoutExposure(11, "holdout_low_id", 1),
				holdoutExposure(12, "holdout_high_id", 0));
		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(context, expected);
	}

	@Test
	void unitNotHeldOutByEitherHoldoutIsNotSuppressed() {
		final Experiment experiment = coveredBy(newExperiment(1, "exp_multi_holdout_miss"), 11, 12);
		final Context context = createReadyContext(UID_NOT_HELD_OUT, contextDataOf(
				new Experiment[]{
						newHoldout(11, "holdout_a", HOLDOUT_A_SEED_HI, HOLDOUT_A_SEED_LO),
						newHoldout(12, "holdout_b", HOLDOUT_B_SEED_HI, HOLDOUT_B_SEED_LO),
				}, experiment));

		assertEquals(0, context.getTreatment("exp_multi_holdout_miss")); // UID_NOT_HELD_OUT's normal variant
		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
		context.publish();

		final PublishEvent expected = publishedEvent(UID_NOT_HELD_OUT,
				new Exposure(1, "exp_multi_holdout_miss", UNIT_TYPE, 0, clock.millis(), true, true, false, false,
						false, false),
				holdoutExposure(11, "holdout_a", 1),
				holdoutExposure(12, "holdout_b", 1));
		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(context, expected);
	}

	// A holdout suppresses a full-on experiment exactly as it does a traffic-eligible one -
	// coverage is declared explicitly via holdoutIds, so a full-on variant never bypasses it.
	@Test
	void holdoutSuppressesFullOnExperimentRegardlessOfFullOnVariant() {
		final Experiment experiment = coveredBy(newExperiment(1, "exp_fullon_holdout", 2), 11);
		final Context context = createReadyContext(contextDataOf(
				new Experiment[]{newHoldout(11, "holdout_a", HOLDOUT_A_SEED_HI, HOLDOUT_A_SEED_LO)}, experiment));

		assertEquals(0, context.getTreatment("exp_fullon_holdout")); // held out despite fullOnVariant=2
	}

	// --- Three-arm (all_full_on) holdouts ----------------------------------------------------
	// Arm 0 holds out every in-scope experiment, full-on or not, exactly like a two-arm holdout's
	// variant 0.
	@Test
	void threeArmVariantZeroHoldsOutBothFullOnAndNonFullOnExperiments() {
		final Experiment nonFullOn = coveredBy(newExperiment(1, "exp_three_arm_0_non_fullon"), 21);
		final Experiment fullOn = coveredBy(newExperiment(2, "exp_three_arm_0_fullon", 2), 21);
		final Experiment holdout = newThreeArmHoldout(21, "holdout_three_arm", THREE_ARM_0_SEED_HI,
				THREE_ARM_0_SEED_LO);

		final Context context = createReadyContext(
				contextDataOf(new Experiment[]{holdout}, nonFullOn, fullOn));

		assertEquals(0, context.getTreatment("exp_three_arm_0_non_fullon")); // held out -> control
		assertEquals(0, context.getTreatment("exp_three_arm_0_fullon")); // held out despite fullOnVariant=2

		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
		context.publish();

		final PublishEvent expected = publishedEvent(UID, holdoutExposure(21, "holdout_three_arm", 0));
		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(context, expected);
	}

	// Arm 1 (full-on only) is the heart of the feature: a non-full-on experiment is forced to
	// control, exactly as if held out, while a full-on experiment in the same holdout's scope is
	// assigned its own fullOnVariant with fullOn=true and is NOT suppressed.
	@Test
	void threeArmVariantOneForcesNonFullOnExperimentToControlButAssignsFullOnExperimentNormally() {
		final Experiment nonFullOn = coveredBy(newExperiment(1, "exp_three_arm_1_non_fullon"), 21);
		final Experiment fullOn = coveredBy(newExperiment(2, "exp_three_arm_1_fullon", 2), 21);
		final Experiment holdout = newThreeArmHoldout(21, "holdout_three_arm", THREE_ARM_1_SEED_HI,
				THREE_ARM_1_SEED_LO);

		final Context context = createReadyContext(
				contextDataOf(new Experiment[]{holdout}, nonFullOn, fullOn));

		assertEquals(0, context.getTreatment("exp_three_arm_1_non_fullon")); // forced to control
		assertEquals(2, context.getTreatment("exp_three_arm_1_fullon")); // its own fullOnVariant, not suppressed

		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
		context.publish();

		// the non-full-on experiment emits no exposure of its own; the full-on one does, with
		// fullOn=true; the holdout's own exposure fires once at variant 1.
		final PublishEvent expected = publishedEvent(UID,
				holdoutExposure(21, "holdout_three_arm", 1),
				new Exposure(2, "exp_three_arm_1_fullon", UNIT_TYPE, 2, clock.millis(), true, true, false, true,
						false, false));
		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(context, expected);
	}

	// Arm 2 (normal traffic) evaluates every in-scope experiment completely normally, full-on or
	// not, exactly as if uncovered.
	@Test
	void threeArmVariantTwoEvaluatesBothExperimentKindsNormally() {
		final Experiment nonFullOn = coveredBy(newExperiment(1, "exp_three_arm_2_non_fullon"), 21);
		final Experiment fullOn = coveredBy(newExperiment(2, "exp_three_arm_2_fullon", 2), 21);
		final Experiment holdout = newThreeArmHoldout(21, "holdout_three_arm", THREE_ARM_2_SEED_HI,
				THREE_ARM_2_SEED_LO);

		final Context context = createReadyContext(
				contextDataOf(new Experiment[]{holdout}, nonFullOn, fullOn));

		assertEquals(NORMAL_VARIANT, context.getTreatment("exp_three_arm_2_non_fullon")); // normal assignment
		assertEquals(2, context.getTreatment("exp_three_arm_2_fullon")); // its own fullOnVariant

		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
		context.publish();

		final PublishEvent expected = publishedEvent(UID,
				new Exposure(1, "exp_three_arm_2_non_fullon", UNIT_TYPE, NORMAL_VARIANT, clock.millis(), true, true,
						false, false, false, false),
				holdoutExposure(21, "holdout_three_arm", 2),
				new Exposure(2, "exp_three_arm_2_fullon", UNIT_TYPE, 2, clock.millis(), true, true, false, true,
						false, false));
		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(context, expected);
	}

	// Union: a 2-arm holdout that holds the unit out wins even when an applicable 3-arm holdout's
	// arm (2, normal traffic) would not have held it out on its own.
	@Test
	void twoArmHoldoutWinsUnionEvenWhenThreeArmWouldNotHoldOut() {
		final Experiment experiment = coveredBy(newExperiment(1, "exp_union_two_arm_wins"), 11, 22);
		final Experiment twoArmHoldout = newHoldout(11, "holdout_two_arm", HOLDOUT_A_SEED_HI, HOLDOUT_A_SEED_LO); // holds UID out
		final Experiment threeArmHoldout = newThreeArmHoldout(22, "holdout_three_arm", THREE_ARM_2_SEED_HI,
				THREE_ARM_2_SEED_LO); // does not hold UID out

		final Context context = createReadyContext(
				contextDataOf(new Experiment[]{twoArmHoldout, threeArmHoldout}, experiment));

		assertEquals(0, context.getTreatment("exp_union_two_arm_wins")); // union -> suppressed

		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
		context.publish();

		final PublishEvent expected = publishedEvent(UID,
				holdoutExposure(11, "holdout_two_arm", 0),
				holdoutExposure(22, "holdout_three_arm", 2));
		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(context, expected);
	}

	// Union, the other direction: a 3-arm holdout's arm 1 holds a non-full-on experiment out even
	// when an applicable 2-arm holdout would not have.
	@Test
	void threeArmHoldoutWinsUnionEvenWhenTwoArmWouldNotHoldOut() {
		final Experiment experiment = coveredBy(newExperiment(1, "exp_union_three_arm_wins"), 11, 22);
		final Experiment twoArmHoldout = newHoldout(11, "holdout_two_arm", HOLDOUT_B_SEED_HI, HOLDOUT_B_SEED_LO); // does not hold UID out
		final Experiment threeArmHoldout = newThreeArmHoldout(22, "holdout_three_arm", THREE_ARM_1_SEED_HI,
				THREE_ARM_1_SEED_LO); // holds non-full-on UID out (arm 1)

		final Context context = createReadyContext(
				contextDataOf(new Experiment[]{twoArmHoldout, threeArmHoldout}, experiment));

		assertEquals(0, context.getTreatment("exp_union_three_arm_wins")); // union -> suppressed

		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
		context.publish();

		final PublishEvent expected = publishedEvent(UID,
				holdoutExposure(11, "holdout_two_arm", 1),
				holdoutExposure(22, "holdout_three_arm", 1));
		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(context, expected);
	}

	// Arm 1 must defer to the normal assignment path for a full-on experiment rather than
	// short-circuiting to fullOnVariant: audienceStrict is still evaluated first, so an audience
	// mismatch yields control (not the full-on variant) exactly as it would under arm 2 (normal
	// traffic). The own exposure still fires - the experiment was evaluated and rejected by
	// audience, not held out by the holdout - with audienceMismatch=true and assigned=false,
	// identically for both arms.
	@Test
	void threeArmArmOneDefersToNormalPathSoAudienceMismatchStillWinsForFullOnExperiment() {
		final String audience = "{\"filter\":[{\"gte\":[{\"var\":\"age\"},{\"value\":20}]}]}";

		final Experiment fullOnArm1 = coveredBy(newExperiment(1, "exp_three_arm_1_audience_fullon", 2), 21);
		fullOnArm1.audienceStrict = true;
		fullOnArm1.audience = audience;
		final Experiment holdoutArm1 = newThreeArmHoldout(21, "holdout_three_arm", THREE_ARM_1_SEED_HI,
				THREE_ARM_1_SEED_LO);
		final Context contextArm1 = createReadyContext(contextDataOf(new Experiment[]{holdoutArm1}, fullOnArm1));
		contextArm1.setAttribute("age", 5); // mismatches the audience filter

		final Experiment fullOnArm2 = coveredBy(newExperiment(1, "exp_three_arm_2_audience_fullon", 2), 21);
		fullOnArm2.audienceStrict = true;
		fullOnArm2.audience = audience;
		final Experiment holdoutArm2 = newThreeArmHoldout(21, "holdout_three_arm", THREE_ARM_2_SEED_HI,
				THREE_ARM_2_SEED_LO);
		final Context contextArm2 = createReadyContext(contextDataOf(new Experiment[]{holdoutArm2}, fullOnArm2));
		contextArm2.setAttribute("age", 5); // mismatches the audience filter

		// arm 1 (full-on only) and arm 2 (normal traffic) reach the identical verdict: audience
		// mismatch forces control, not the full-on variant.
		assertEquals(0, contextArm1.getTreatment("exp_three_arm_1_audience_fullon"));
		assertEquals(0, contextArm2.getTreatment("exp_three_arm_2_audience_fullon"));

		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
		contextArm1.publish();
		contextArm2.publish();

		final PublishEvent expectedArm1 = publishedEvent(UID,
				new Exposure(1, "exp_three_arm_1_audience_fullon", UNIT_TYPE, 0, clock.millis(), false, true, false,
						false, false, true),
				holdoutExposure(21, "holdout_three_arm", 1));
		expectedArm1.attributes = new Attribute[]{new Attribute("age", 5, clock.millis())};
		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(contextArm1, expectedArm1);

		final PublishEvent expectedArm2 = publishedEvent(UID,
				new Exposure(1, "exp_three_arm_2_audience_fullon", UNIT_TYPE, 0, clock.millis(), false, true, false,
						false, false, true),
				holdoutExposure(21, "holdout_three_arm", 2));
		expectedArm2.attributes = new Attribute[]{new Attribute("age", 5, clock.millis())};
		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(contextArm2, expectedArm2);
	}

	// The unit type an experiment/holdout uses need not have any unit configured on this context
	// at all (ContextConfig only sets session_id here). getHoldoutAssignment must treat a missing
	// unit as "not evaluable" - control values, no exception, no holdout exposure - rather than
	// NPE on the missing units_ entry.
	@Test
	void unconfiguredUnitTypeGetsControlValuesAndEmitsNoExposureAtAll() {
		final Experiment experiment = coveredBy(newExperiment(1, "exp_user_holdout", "user_id", 0), 11);
		final Experiment holdout = newHoldout(11, "holdout_user", "user_id", HOLDOUT_A_SEED_HI, HOLDOUT_A_SEED_LO,
				"full");

		final ContextConfig config = ContextConfig.create().setUnit(UNIT_TYPE, UID); // only session_id, no user_id
		final Context context = createReadyContext(config, contextDataOf(new Experiment[]{holdout}, experiment));

		assertEquals(0, context.peekTreatment("exp_user_holdout"));

		context.getTreatment("exp_user_holdout");
		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
		context.publish();

		assertEquals(0, context.getPendingCount()); // neither the experiment nor the holdout fires
	}

	// An absent holdouts key (null, the ContextData default) leaves behaviour identical to
	// pre-holdout: no suppression, no NPE.
	@Test
	void assignsNormallyWhenHoldoutsKeyIsAbsent() {
		final Experiment experiment = newExperiment(1, "exp_no_holdouts_key");
		final Context context = createReadyContext(contextDataOf(experiment));

		assertEquals(NORMAL_VARIANT, context.peekTreatment("exp_no_holdouts_key"));
	}

	// A holdoutId that names no entry in holdouts[] is ignored - wire inconsistency tolerance,
	// not an error - and the experiment assigns exactly as if it had no coverage at all.
	@Test
	void holdoutIdAbsentFromHoldoutsArrayIsIgnoredAndExperimentAssignsNormally() {
		final Experiment experiment = coveredBy(newExperiment(1, "exp_dangling_holdout_id"), 999);
		final Context context = createReadyContext(contextDataOf(experiment)); // no holdouts[] entry with id 999

		assertEquals(NORMAL_VARIANT, context.peekTreatment("exp_dangling_holdout_id"));
	}

	// A custom assignment can never override a held-out unit's variant - holdout precedence
	// beats custom assignments, matching the existing audience/full-on/traffic precedence rules.
	@Test
	void customAssignmentCannotOverrideHeldOutVariant() {
		final Experiment experiment = coveredBy(newExperiment(1, "exp_holdout_custom"), 11);

		final ContextConfig config = ContextConfig.create().setUnit(UNIT_TYPE, UID).setCustomAssignment(
				"exp_holdout_custom", 3);
		final Context context = createReadyContext(config, contextDataOf(
				new Experiment[]{newHoldout(11, "holdout_a", HOLDOUT_A_SEED_HI, HOLDOUT_A_SEED_LO)}, experiment));

		assertEquals(0, context.peekTreatment("exp_holdout_custom"));
	}

	@Test
	void reusesCachedHeldOutAssignmentWithCustomAssignment() {
		final Experiment experiment = coveredBy(newExperiment(1, "exp_holdout_custom_cache"), 11);

		final ContextConfig config = ContextConfig.create().setUnit(UNIT_TYPE, UID).setCustomAssignment(
				"exp_holdout_custom_cache", 3);
		final Context context = createReadyContext(config, contextDataOf(
				new Experiment[]{newHoldout(11, "holdout_a", HOLDOUT_A_SEED_HI, HOLDOUT_A_SEED_LO)}, experiment));

		// the custom assignment can never apply while held out; repeated calls must hit the
		// cache and not queue duplicate exposures.
		assertEquals(0, context.getTreatment("exp_holdout_custom_cache"));
		assertEquals(0, context.getTreatment("exp_holdout_custom_cache"));
		assertEquals(1, context.getPendingCount()); // one exposure: the holdout's own
	}

	@Test
	void overrideTakesPrecedenceOverHoldout() {
		final Experiment experiment = coveredBy(newExperiment(1, "exp_holdout_override"), 11);

		final ContextConfig config = ContextConfig.create().setUnit(UNIT_TYPE, UID).setOverride(
				"exp_holdout_override", 3);
		final Context context = createReadyContext(config, contextDataOf(
				new Experiment[]{newHoldout(11, "holdout_a", HOLDOUT_A_SEED_HI, HOLDOUT_A_SEED_LO)}, experiment));

		assertEquals(3, context.peekTreatment("exp_holdout_override"));
	}

	// peekTreatment alone cannot prove the override branch attaches applicable holdouts, since
	// peek never triggers exposures for anything. getTreatment on an overridden experiment must
	// still trigger its applicable holdout's own exposure - for both arms - because the
	// experiment was evaluated, exactly as it would be without the override.
	@Test
	void getTreatmentOnOverriddenExperimentTriggersApplicableHoldoutExposureBothArms() {
		final Experiment heldOutExperiment = coveredBy(newExperiment(1, "exp_holdout_override_held_out"), 11);
		final ContextConfig heldOutConfig = ContextConfig.create().setUnit(UNIT_TYPE, UID)
				.setOverride("exp_holdout_override_held_out", 3);
		final Context heldOutContext = createReadyContext(heldOutConfig, contextDataOf(
				new Experiment[]{newHoldout(11, "holdout_a", HOLDOUT_A_SEED_HI, HOLDOUT_A_SEED_LO)},
				heldOutExperiment));

		assertEquals(3, heldOutContext.getTreatment("exp_holdout_override_held_out")); // override wins
		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
		heldOutContext.publish();

		final PublishEvent expectedHeldOut = publishedEvent(UID,
				new Exposure(1, "exp_holdout_override_held_out", UNIT_TYPE, 3, clock.millis(), false, true, true,
						false, false, false),
				holdoutExposure(11, "holdout_a", 0));
		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(heldOutContext, expectedHeldOut);

		final Experiment notHeldOutExperiment = coveredBy(newExperiment(1, "exp_holdout_override_not_held_out"), 11);
		final ContextConfig notHeldOutConfig = ContextConfig.create().setUnit(UNIT_TYPE, UID_NOT_HELD_OUT)
				.setOverride("exp_holdout_override_not_held_out", 3);
		final Context notHeldOutContext = createReadyContext(notHeldOutConfig, contextDataOf(
				new Experiment[]{newHoldout(11, "holdout_a", HOLDOUT_A_SEED_HI, HOLDOUT_A_SEED_LO)},
				notHeldOutExperiment));

		assertEquals(3, notHeldOutContext.getTreatment("exp_holdout_override_not_held_out")); // override wins
		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
		notHeldOutContext.publish();

		final PublishEvent expectedNotHeldOut = publishedEvent(UID_NOT_HELD_OUT,
				new Exposure(1, "exp_holdout_override_not_held_out", UNIT_TYPE, 3, clock.millis(), false, true, true,
						false, false, false),
				holdoutExposure(11, "holdout_a", 1));
		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(notHeldOutContext, expectedNotHeldOut);
	}

	@Test
	void holdoutTakesPrecedenceOverAudienceMismatch() {
		final Experiment experiment = coveredBy(newExperiment(1, "exp_holdout_audience"), 11);
		experiment.audienceStrict = true;
		experiment.audience = "{\"filter\":[{\"gte\":[{\"var\":\"age\"},{\"value\":20}]}]}";

		final Context context = createReadyContext(contextDataOf(
				new Experiment[]{newHoldout(11, "holdout_a", HOLDOUT_A_SEED_HI, HOLDOUT_A_SEED_LO)}, experiment));
		context.setAttribute("age", 5); // would mismatch the audience filter if evaluated

		assertEquals(0, context.peekTreatment("exp_holdout_audience"));
	}

	// The holdout's own exposure is emitted once per context, not once per suppressed
	// experiment - two experiments covered by the same holdout still yield a single holdout
	// exposure.
	@Test
	void holdoutExposureEmittedOncePerContextNotPerSuppressedExperiment() {
		final Experiment experimentA = coveredBy(newExperiment(1, "exp_shared_a"), 11);
		final Experiment experimentB = coveredBy(newExperiment(2, "exp_shared_b"), 11);
		final Experiment holdout = newHoldout(11, "holdout_a", HOLDOUT_A_SEED_HI, HOLDOUT_A_SEED_LO);

		final Context context = createReadyContext(contextDataOf(new Experiment[]{holdout}, experimentA,
				experimentB));

		assertEquals(0, context.getTreatment("exp_shared_a"));
		assertEquals(0, context.getTreatment("exp_shared_b"));

		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
		context.publish();

		final PublishEvent expected = publishedEvent(UID, holdoutExposure(11, "holdout_a", 0));
		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(context, expected);
	}

	@Test
	void holdoutExposureFiresOnceEvenWhenTriggeringExperimentIsNotSuppressed() {
		final Experiment experimentA = coveredBy(newExperiment(1, "exp_shared_a"), 11);
		final Experiment experimentB = coveredBy(newExperiment(2, "exp_shared_b"), 11);
		final Experiment holdout = newHoldout(11, "holdout_a", HOLDOUT_A_SEED_HI, HOLDOUT_A_SEED_LO);

		final Context context = createReadyContext(UID_NOT_HELD_OUT,
				contextDataOf(new Experiment[]{holdout}, experimentA, experimentB));

		context.getTreatment("exp_shared_a");
		context.getTreatment("exp_shared_b");

		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
		context.publish();

		final PublishEvent expected = publishedEvent(UID_NOT_HELD_OUT,
				new Exposure(1, "exp_shared_a", UNIT_TYPE, 0, clock.millis(), true, true, false, false, false, false),
				holdoutExposure(11, "holdout_a", 1),
				new Exposure(2, "exp_shared_b", UNIT_TYPE, 0, clock.millis(), true, true, false, false, false, false));
		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(context, expected);
	}

	// Refresh with an unchanged holdout definition must not re-trigger the holdout's exposure or
	// change the covered experiment's cached suppression.
	@Test
	void reusesCachedSuppressedAssignmentAcrossRepeatedCalls() {
		final Experiment experiment = coveredBy(newExperiment(1, "exp_holdout_cache"), 11);
		final Context context = createReadyContext(contextDataOf(
				new Experiment[]{newHoldout(11, "holdout_a", HOLDOUT_A_SEED_HI, HOLDOUT_A_SEED_LO)}, experiment));

		assertEquals(0, context.getTreatment("exp_holdout_cache"));
		assertEquals(0, context.getTreatment("exp_holdout_cache"));
		assertEquals(1, context.getPendingCount()); // only the holdout's own exposure
	}

	// A live seed edit within the same iteration must not change who is a member: naively
	// comparing seedHi/seedLo (or the whole Experiment) would invalidate the cached holdout
	// Assignment, reset its `exposed` flag, and let an already-exposed unit be re-assigned into
	// the OTHER arm - variant 0 unit ends up exposed as variant 1 too, or vice versa. Same
	// iteration must keep both the verdict and the exposure state pinned to the original arm.
	@Test
	void refreshWithSeedChangeSameIterationKeepsUnitInOriginalArmAndDoesNotReExpose() {
		final Experiment experiment = coveredBy(newExperiment(1, "exp_holdout_seed_thrash"), 11);

		// unit is held out initially (holdout A's seed, iteration 1)
		final Context context = createReadyContext(contextDataOf(
				new Experiment[]{newHoldout(11, "holdout_a", HOLDOUT_A_SEED_HI, HOLDOUT_A_SEED_LO)}, experiment));

		assertEquals(0, context.getTreatment("exp_holdout_seed_thrash")); // held out -> control
		assertEquals(1, context.getPendingCount()); // only the holdout's own exposure (variant 0)

		// same holdout id and iteration, but a seed edit that would flip this unit to variant 1
		// if it were re-assigned.
		final Experiment reseededHoldout = newHoldout(11, "holdout_a", HOLDOUT_B_SEED_HI, HOLDOUT_B_SEED_LO);
		final Experiment refreshedExperiment = coveredBy(newExperiment(1, "exp_holdout_seed_thrash"), 11);

		final CompletableFuture<ContextData> refreshFuture = new CompletableFuture<>();
		when(dataProvider.getContextData()).thenReturn(refreshFuture);
		final CompletableFuture<Void> refreshing = context.refreshAsync();
		refreshFuture.complete(contextDataOf(new Experiment[]{reseededHoldout}, refreshedExperiment));
		refreshing.join();

		// still variant 0 / held out: the pinned assignment from iteration 1 is reused, not
		// recomputed against the new seed.
		assertEquals(0, context.getTreatment("exp_holdout_seed_thrash"));
		assertEquals(1, context.getPendingCount()); // no second, contradictory exposure in variant 1
	}

	// A cosmetic holdout edit - name, variants, applications - cannot change who is a member.
	// Neither the holdout nor the covered experiment it suppresses may re-expose the unit: doing
	// so would put the unit in variant 0 (already recorded) and, on the very same underlying
	// assignment, effectively re-litigate variant 1 as well.
	@Test
	void refreshWithCosmeticHoldoutEditDoesNotReExposeHeldOutUnit() {
		final Experiment experiment = coveredBy(newExperiment(1, "exp_holdout_cosmetic"), 11);
		final Context context = createReadyContext(contextDataOf(
				new Experiment[]{newHoldout(11, "holdout_a", HOLDOUT_A_SEED_HI, HOLDOUT_A_SEED_LO)}, experiment));

		assertEquals(0, context.getTreatment("exp_holdout_cosmetic")); // held out
		assertEquals(1, context.getPendingCount()); // only the holdout's own exposure

		final Experiment cosmeticHoldout = newHoldout(11, "holdout_a_renamed", HOLDOUT_A_SEED_HI,
				HOLDOUT_A_SEED_LO);
		cosmeticHoldout.applications = new ExperimentApplication[]{new ExperimentApplication("website")};
		cosmeticHoldout.variants = new ExperimentVariant[]{
				new ExperimentVariant("Control", null), new ExperimentVariant("HeldOut", null)
		};
		final Experiment refreshedExperiment = coveredBy(newExperiment(1, "exp_holdout_cosmetic"), 11);

		final CompletableFuture<ContextData> refreshFuture = new CompletableFuture<>();
		when(dataProvider.getContextData()).thenReturn(refreshFuture);
		final CompletableFuture<Void> refreshing = context.refreshAsync();
		refreshFuture.complete(contextDataOf(new Experiment[]{cosmeticHoldout}, refreshedExperiment));
		refreshing.join();

		assertEquals(0, context.getTreatment("exp_holdout_cosmetic")); // still held out
		assertEquals(1, context.getPendingCount()); // no new exposure for either the holdout or the experiment
	}

	// The covered experiment side of the same guarantee: a non-held-out unit's own ordinary
	// exposure must not be duplicated by a cosmetic holdout edit either.
	@Test
	void refreshWithCosmeticHoldoutEditDoesNotDuplicateCoveredExperimentExposure() {
		final Experiment experiment = coveredBy(newExperiment(1, "exp_holdout_cosmetic_covered"), 11);
		final Context context = createReadyContext(UID_NOT_HELD_OUT, contextDataOf(
				new Experiment[]{newHoldout(11, "holdout_a", HOLDOUT_A_SEED_HI, HOLDOUT_A_SEED_LO)}, experiment));

		assertEquals(0, context.getTreatment("exp_holdout_cosmetic_covered")); // not held out
		assertEquals(2, context.getPendingCount()); // own exposure + holdout exposure (variant 1)

		final Experiment cosmeticHoldout = newHoldout(11, "holdout_a_renamed", HOLDOUT_A_SEED_HI,
				HOLDOUT_A_SEED_LO);
		final Experiment refreshedExperiment = coveredBy(newExperiment(1, "exp_holdout_cosmetic_covered"), 11);

		final CompletableFuture<ContextData> refreshFuture = new CompletableFuture<>();
		when(dataProvider.getContextData()).thenReturn(refreshFuture);
		final CompletableFuture<Void> refreshing = context.refreshAsync();
		refreshFuture.complete(contextDataOf(new Experiment[]{cosmeticHoldout}, refreshedExperiment));
		refreshing.join();

		assertEquals(0, context.getTreatment("exp_holdout_cosmetic_covered"));
		assertEquals(2, context.getPendingCount()); // no duplicate exposure of either kind
	}

	// An iteration bump is the one holdout edit that legitimately changes membership: it is a new
	// randomization epoch, exactly as it is for ordinary experiments (see
	// refreshClearAssignmentCacheForIterationChange in ContextTest). The unit is re-assigned once
	// against the new epoch and lands in exactly one arm of it; the prior epoch's exposure - a
	// distinct, already-published fact about a now-superseded assignment - is not retroactively
	// invalidated. Note that a seed/split change WITHOUT an iteration bump (same epoch) must NOT
	// re-assign, which is exactly what distinguishes this test from the cosmetic-edit tests above.
	@Test
	void refreshWithIterationBumpReassignsToExactlyOneNewArm() {
		final Experiment experiment = coveredBy(newExperiment(1, "exp_holdout_iteration"), 11);

		// unit is NOT held out initially (holdout B's seed, iteration 1)
		final Context context = createReadyContext(contextDataOf(
				new Experiment[]{newHoldout(11, "holdout_a", HOLDOUT_B_SEED_HI, HOLDOUT_B_SEED_LO)}, experiment));

		assertEquals(NORMAL_VARIANT, context.getTreatment("exp_holdout_iteration"));
		assertEquals(2, context.getPendingCount()); // normal exposure + holdout exposure (variant 1)

		// same holdout id, new iteration with holdout A's seed: a genuine new epoch that now holds
		// the unit out.
		final Experiment newEpochHoldout = newHoldout(11, "holdout_a", HOLDOUT_A_SEED_HI, HOLDOUT_A_SEED_LO);
		newEpochHoldout.iteration = 2;
		final Experiment refreshedExperiment = coveredBy(newExperiment(1, "exp_holdout_iteration"), 11);

		final CompletableFuture<ContextData> refreshFuture = new CompletableFuture<>();
		when(dataProvider.getContextData()).thenReturn(refreshFuture);
		final CompletableFuture<Void> refreshing = context.refreshAsync();
		refreshFuture.complete(contextDataOf(new Experiment[]{newEpochHoldout}, refreshedExperiment));
		refreshing.join();

		assertEquals(0, context.getTreatment("exp_holdout_iteration")); // new epoch: held out
		// the new epoch's own exposure (variant 0); the now-suppressed experiment emits no
		// exposure of its own for this epoch, so exactly one exposure is added.
		assertEquals(3, context.getPendingCount());

		// re-querying does not add a third exposure for the new epoch: exactly one arm was
		// recorded for it, never both.
		assertEquals(0, context.getTreatment("exp_holdout_iteration"));
		assertEquals(3, context.getPendingCount());
	}

	// A same-iteration seed/split edit must not change who is suppressed, even for a covered
	// experiment whose ordinary Assignment cache is a fresh miss (newly added, or invalidated).
	// Suppression must come from the same pinned HoldoutAssignment as the holdout's own exposure,
	// never recomputed directly from the live definition - otherwise a unit already exposed as
	// holdout variant 0 could receive a covered experiment's treatment and exposure after the
	// direct recomputation flips to variant 1.
	@Test
	void refreshedSeedDoesNotDesyncSuppressionFromPinnedHoldoutArmForNewlyEvaluatedExperiment() {
		final Experiment experimentA = coveredBy(newExperiment(1, "exp_holdout_desync_a"), 11);
		final Context context = createReadyContext(contextDataOf(
				new Experiment[]{newHoldout(11, "holdout_a", HOLDOUT_A_SEED_HI, HOLDOUT_A_SEED_LO)}, experimentA));

		assertEquals(0, context.getTreatment("exp_holdout_desync_a")); // held out -> control
		assertEquals(1, context.getPendingCount()); // holdout's own exposure (variant 0)

		// same holdout id and iteration, but a seed edit that would flip this unit to variant 1
		// if suppression were recomputed directly - plus a brand-new covered experiment whose
		// Assignment cache has never been populated, forcing the write-lock computation path.
		final Experiment reseededHoldout = newHoldout(11, "holdout_a", HOLDOUT_B_SEED_HI, HOLDOUT_B_SEED_LO);
		final Experiment refreshedExperimentA = coveredBy(newExperiment(1, "exp_holdout_desync_a"), 11);
		final Experiment experimentB = coveredBy(newExperiment(2, "exp_holdout_desync_b"), 11);

		final CompletableFuture<ContextData> refreshFuture = new CompletableFuture<>();
		when(dataProvider.getContextData()).thenReturn(refreshFuture);
		final CompletableFuture<Void> refreshing = context.refreshAsync();
		refreshFuture.complete(
				contextDataOf(new Experiment[]{reseededHoldout}, refreshedExperimentA, experimentB));
		refreshing.join();

		// the pinned holdout arm (variant 0) must still govern suppression for the newly
		// evaluated experiment, not the live seed's recomputed arm (which would be variant 1).
		assertEquals(0, context.getTreatment("exp_holdout_desync_b"));
		assertEquals(0, context.getTreatment("exp_holdout_desync_a"));

		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
		context.publish();

		// no new exposure at all: both experiments remain suppressed under the pinned arm, and
		// the holdout's own exposure was already recorded before the refresh.
		final PublishEvent expected = publishedEvent(UID, holdoutExposure(11, "holdout_a", 0));
		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(context, expected);
	}

	// --- Arm-count pinning across a same-iteration arity change -----------------------------
	// The arm number cached in HoldoutAssignment is meaningless without the arm count it was
	// computed against: arm 1 means "defer to normal assignment" under a 2-arm holdout but
	// "full-on only" under a 3-arm one. A same-iteration refresh that only changes split.length
	// must not silently re-mean an already-pinned arm.

	// A unit pinned at arm 1 under a 2-arm holdout defers to normal assignment. If the holdout is
	// refreshed to 3 arms within the same iteration, a newly evaluated non-full-on experiment must
	// still defer to normal assignment (arm 1's pinned 2-arm meaning), not suddenly be suppressed
	// as though arm 1 meant "full-on only" under the new, live arity.
	@Test
	void refreshFromTwoArmToThreeArmSameIterationDoesNotReinterpretDeferringArmAsFullOnOnly() {
		final Experiment experimentA = coveredBy(newExperiment(1, "exp_arity_2to3_a"), 31);
		final Context context = createReadyContext(contextDataOf(
				new Experiment[]{newHoldout(31, "holdout_arity", HOLDOUT_B_SEED_HI, HOLDOUT_B_SEED_LO)},
				experimentA));

		// arm 1 under 2 arms: not held out, deferred to normal assignment.
		assertEquals(NORMAL_VARIANT, context.getTreatment("exp_arity_2to3_a"));

		// same holdout id and iteration, refreshed to 3 arms; a brand-new covered experiment is
		// evaluated only after the refresh, forcing a fresh isHeldOutBy call against the pinned
		// arm.
		final Experiment threeArmHoldout = newThreeArmHoldout(31, "holdout_arity", THREE_ARM_1_SEED_HI,
				THREE_ARM_1_SEED_LO);
		final Experiment refreshedExperimentA = coveredBy(newExperiment(1, "exp_arity_2to3_a"), 31);
		final Experiment experimentB = coveredBy(newExperiment(2, "exp_arity_2to3_b"), 31);

		final CompletableFuture<ContextData> refreshFuture = new CompletableFuture<>();
		when(dataProvider.getContextData()).thenReturn(refreshFuture);
		final CompletableFuture<Void> refreshing = context.refreshAsync();
		refreshFuture.complete(
				contextDataOf(new Experiment[]{threeArmHoldout}, refreshedExperimentA, experimentB));
		refreshing.join();

		// still deferred to normal assignment: the pinned arm is 1 under 2 arms, not under 3.
		assertEquals(NORMAL_VARIANT, context.getTreatment("exp_arity_2to3_b"));

		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
		context.publish();

		final PublishEvent expected = publishedEvent(UID,
				new Exposure(1, "exp_arity_2to3_a", UNIT_TYPE, NORMAL_VARIANT, clock.millis(), true, true, false,
						false, false, false),
				holdoutExposure(31, "holdout_arity", 1),
				new Exposure(2, "exp_arity_2to3_b", UNIT_TYPE, NORMAL_VARIANT, clock.millis(), true, true, false,
						false, false, false));
		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(context, expected);
	}

	// The reverse direction: a unit pinned at arm 1 under a 3-arm holdout holds a non-full-on
	// experiment out. If the holdout is refreshed to 2 arms within the same iteration, a newly
	// evaluated non-full-on experiment must remain held out under arm 1's pinned 3-arm meaning,
	// not be silently un-suppressed as though arm 1 meant "defer to normal" under the new, live
	// arity.
	@Test
	void refreshFromThreeArmToTwoArmSameIterationKeepsFullOnOnlyArmSuppressingNonFullOnExperiment() {
		final Experiment experimentA = coveredBy(newExperiment(1, "exp_arity_3to2_a"), 41);
		final Context context = createReadyContext(contextDataOf(
				new Experiment[]{newThreeArmHoldout(41, "holdout_arity_rev", THREE_ARM_1_SEED_HI,
						THREE_ARM_1_SEED_LO)},
				experimentA));

		// arm 1 under 3 arms: full-on only, so a non-full-on experiment is held out.
		assertEquals(0, context.getTreatment("exp_arity_3to2_a"));
		assertEquals(1, context.getPendingCount()); // only the holdout's own exposure (variant 1)

		// same holdout id and iteration, refreshed to 2 arms; a brand-new covered experiment is
		// evaluated only after the refresh.
		final Experiment twoArmHoldout = newHoldout(41, "holdout_arity_rev", HOLDOUT_B_SEED_HI, HOLDOUT_B_SEED_LO);
		final Experiment refreshedExperimentA = coveredBy(newExperiment(1, "exp_arity_3to2_a"), 41);
		final Experiment experimentB = coveredBy(newExperiment(2, "exp_arity_3to2_b"), 41);

		final CompletableFuture<ContextData> refreshFuture = new CompletableFuture<>();
		when(dataProvider.getContextData()).thenReturn(refreshFuture);
		final CompletableFuture<Void> refreshing = context.refreshAsync();
		refreshFuture.complete(
				contextDataOf(new Experiment[]{twoArmHoldout}, refreshedExperimentA, experimentB));
		refreshing.join();

		// still held out: the pinned arm is 1 under 3 arms, not under 2.
		assertEquals(0, context.getTreatment("exp_arity_3to2_b"));

		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
		context.publish();

		// neither covered experiment ever emits its own exposure; the holdout's own exposure
		// fired once, before the refresh.
		final PublishEvent expected = publishedEvent(UID, holdoutExposure(41, "holdout_arity_rev", 1));
		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(context, expected);
	}

	// Within one context, an experiment evaluated before a same-iteration arity change and one
	// evaluated only after it must not disagree about whether the pinned holdout arm holds them
	// out: both are covered by the exact same HoldoutAssignment, so both must read the exact same
	// pinned arm count.
	@Test
	void experimentsEvaluatedBeforeAndAfterSameIterationArityChangeAgreeOnSuppression() {
		final Experiment experimentPre = coveredBy(newExperiment(1, "exp_arity_consistency_pre"), 71);
		final Context context = createReadyContext(contextDataOf(
				new Experiment[]{newHoldout(71, "holdout_arity_consistency", HOLDOUT_B_SEED_HI, HOLDOUT_B_SEED_LO)},
				experimentPre));

		// evaluated BEFORE the refresh: arm 1 under 2 arms, not held out.
		assertEquals(NORMAL_VARIANT, context.getTreatment("exp_arity_consistency_pre"));

		final Experiment threeArmHoldout = newThreeArmHoldout(71, "holdout_arity_consistency",
				THREE_ARM_1_SEED_HI, THREE_ARM_1_SEED_LO);
		final Experiment refreshedExperimentPre = coveredBy(newExperiment(1, "exp_arity_consistency_pre"), 71);
		final Experiment experimentPost = coveredBy(newExperiment(2, "exp_arity_consistency_post"), 71);

		final CompletableFuture<ContextData> refreshFuture = new CompletableFuture<>();
		when(dataProvider.getContextData()).thenReturn(refreshFuture);
		final CompletableFuture<Void> refreshing = context.refreshAsync();
		refreshFuture.complete(
				contextDataOf(new Experiment[]{threeArmHoldout}, refreshedExperimentPre, experimentPost));
		refreshing.join();

		// evaluated ONLY AFTER the refresh, against the very same HoldoutAssignment: must agree
		// with exp_arity_consistency_pre's own verdict rather than being re-meant under the new,
		// live 3-arm split.
		assertEquals(NORMAL_VARIANT, context.getTreatment("exp_arity_consistency_post"));
		// the pre-refresh verdict is unchanged by the refresh.
		assertEquals(NORMAL_VARIANT, context.getTreatment("exp_arity_consistency_pre"));
	}

	// A refresh landing between the suppression decision and the exposure trigger must not let
	// the published event mix two different holdout epochs. H@iteration1's arm does not suppress
	// E; getTreatment(E) decides E's normal variant and queues E's own exposure. The event logger
	// runs synchronously inside that enqueue (after E is queued, before the holdout trigger loop
	// executes) and installs a refresh that bumps H to iteration2 with an arm that WOULD suppress
	// E. The published event must still carry E's ordinary exposure together with H@iteration1's
	// arm - the pair the decision was actually made from - never H@iteration2's arm.
	//
	// Fails against pre-change code: triggerApplicableHoldoutExposures re-resolves
	// `assignment.holdouts` via getHoldoutAssignment at trigger time rather than firing a pinned
	// snapshot, so by the time the trigger loop runs, getHoldoutAssignment sees the refreshed
	// (iteration2) holdout definition, finds the iteration1 cache entry stale, recomputes against
	// iteration2's arm (suppressing), and publishes E's ordinary exposure alongside a
	// contradictory iteration2 variant-0 holdout exposure instead of iteration1's variant-1 one.
	@Test
	void triggerFiresHoldoutExposureFromDecisionEpochDespiteRefreshRacingBetweenDecisionAndTrigger() {
		final Experiment holdoutIteration1 = newHoldout(11, "holdout_h", HOLDOUT_B_SEED_HI, HOLDOUT_B_SEED_LO);
		final Experiment experiment = coveredBy(newExperiment(1, "exp_epoch_race"), 11);

		final Experiment holdoutIteration2 = newHoldout(11, "holdout_h", HOLDOUT_A_SEED_HI, HOLDOUT_A_SEED_LO);
		holdoutIteration2.iteration = 2;
		final Experiment refreshedExperiment = coveredBy(newExperiment(1, "exp_epoch_race"), 11);
		when(dataProvider.getContextData()).thenReturn(CompletableFuture
				.completedFuture(contextDataOf(new Experiment[]{holdoutIteration2}, refreshedExperiment)));

		final Context context = createReadyContext(
				contextDataOf(new Experiment[]{holdoutIteration1}, experiment));

		doAnswer(invocation -> {
			final Object data = invocation.getArgument(2);
			if ((data instanceof Exposure) && (((Exposure) data).id == 1)) {
				context.refreshAsync().join();
			}
			return null;
		}).when(eventLogger).handleEvent(any(), any(), any());

		assertEquals(NORMAL_VARIANT, context.getTreatment("exp_epoch_race")); // not suppressed under iteration1

		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
		context.publish();

		final PublishEvent expected = publishedEvent(UID,
				new Exposure(1, "exp_epoch_race", UNIT_TYPE, NORMAL_VARIANT, clock.millis(), true, true, false,
						false, false, false),
				holdoutExposure(11, "holdout_h", 1)); // iteration1's arm, not iteration2's
		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(context, expected);
	}

	// --- Cross-SDK parity vectors -----------------------------------------------------------
	// Verdicts below were computed offline against the SDK's own MD5 -> base64url-unpadded ->
	// murmur3_32 pipeline (VariantAssigner/UnitHasher, unmodified) and independently against the
	// collector's server-side verdict for the same fixtures (test_holdouts.py ::
	// TestCollectorHoldoutVerdictVectors), pinning unicode unit ids, a seed whose high AND low
	// 32-bit halves both have the sign bit set, a 1% percentage boundary, an assignment-probability
	// boundary immediately either side of 10%, and a unit held out by two holdouts simultaneously.

	static final String VERDICT_UNIT_TYPE = "verdict_unit_type";

	Context verdictContext(String uid, Experiment... holdouts) {
		final int[] holdoutIds = new int[holdouts.length];
		for (int i = 0; i < holdouts.length; ++i) {
			holdoutIds[i] = holdouts[i].id;
		}
		final Experiment experiment = coveredBy(
				newExperiment(9, "verdict_vectors_experiment", VERDICT_UNIT_TYPE, 0), holdoutIds);
		final ContextConfig config = ContextConfig.create().setUnit(VERDICT_UNIT_TYPE, uid);
		return createReadyContext(config, contextDataOf(holdouts, experiment));
	}

	static Experiment verdictHoldout(int id, int seedHi, int seedLo, double[] split) {
		final Experiment holdout = newHoldout(id, "holdout_" + id, VERDICT_UNIT_TYPE, seedHi, seedLo, "full");
		holdout.split = split;
		return holdout;
	}

	// Verdict is read off the holdout's own exposure variant (0 = held out), the exact quantity
	// the collector's TestCollectorHoldoutVerdictVectors checks. Suppression is exercised too: a
	// held-out unit's covered-experiment variant must be forced to control (0) and emit no
	// exposure of its own, regardless of that experiment's independent seed.
	void assertHeldOutBy(Context context, int holdoutId, String holdoutName) {
		assertEquals(0, context.getTreatment("verdict_vectors_experiment"));
		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
		context.publish();

		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(org.mockito.ArgumentMatchers.eq(context),
				org.mockito.ArgumentMatchers.argThat(event -> (event.exposures.length == 1)
						&& (event.exposures[0].id == holdoutId) && event.exposures[0].name.equals(holdoutName)
						&& (event.exposures[0].variant == 0)));
	}

	void assertNotHeldOut(Context context, int holdoutId, String holdoutName) {
		context.getTreatment("verdict_vectors_experiment");
		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
		context.publish();

		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(org.mockito.ArgumentMatchers.eq(context),
				org.mockito.ArgumentMatchers.argThat(event -> java.util.Arrays.stream(event.exposures)
						.anyMatch(e -> (e.id == holdoutId) && e.name.equals(holdoutName) && (e.variant == 1))));
	}

	@Test
	void unicodeUnitIds() {
		// BMP diacritics, held out by a holdout with seed=42, 10%.
		assertHeldOutBy(verdictContext("façade", verdictHoldout(107, 0, 42, new double[]{0.1, 0.9})), 107,
				"holdout_107");

		// CJK + emoji combination, not held out by the same holdout.
		assertNotHeldOut(verdictContext("unicode_emoji_\uD83D\uDE80_0",
				verdictHoldout(107, 0, 42, new double[]{0.1, 0.9})), 107, "holdout_107");
	}

	@Test
	void signedSeedHalves() {
		// seed = -9223372034707292160 (0x8000000080000000 as signed int64) has the sign bit set
		// in BOTH seedHi and seedLo once split via (seed >> 32) / (int) seed.
		final int seedHi = (int) (-9223372034707292160L >> 32);
		final int seedLo = (int) -9223372034707292160L;

		assertHeldOutBy(verdictContext("signed_unit_4", verdictHoldout(106, seedHi, seedLo, new double[]{0.1, 0.9})),
				106, "holdout_106");
		assertNotHeldOut(verdictContext("signed_unit_b_1",
				verdictHoldout(106, seedHi, seedLo, new double[]{0.1, 0.9})), 106, "holdout_106");
	}

	@Test
	void percentageBoundaryAtOnePercent() {
		assertHeldOutBy(verdictContext("pct1_unit_188", verdictHoldout(105, 0, 999999, new double[]{0.01, 0.99})),
				105, "holdout_105");
		assertNotHeldOut(verdictContext("pct1_unit_0", verdictHoldout(105, 0, 999999, new double[]{0.01, 0.99})),
				105, "holdout_105");
	}

	@Test
	void assignmentProbabilityBoundaryAroundTenPercent() {
		// closest computed assignment probabilities immediately below/above the 10% threshold for
		// seed=42, pinning the exact split boundary behaviour (prob < cumSum).
		assertHeldOutBy(
				verdictContext("boundary_unit_175653", verdictHoldout(107, 0, 42, new double[]{0.1, 0.9})), 107,
				"holdout_107");
		assertNotHeldOut(verdictContext("boundary_unit_75792", verdictHoldout(107, 0, 42, new double[]{0.1, 0.9})),
				107, "holdout_107");
	}

	@Test
	void unitHeldOutByTwoHoldoutsSimultaneously() {
		final Context context = verdictContext("dual_holdout_unit_5",
				verdictHoldout(107, 0, 42, new double[]{0.1, 0.9}),
				verdictHoldout(108, 0, 55, new double[]{0.1, 0.9}));

		assertEquals(0, context.getTreatment("verdict_vectors_experiment"));
		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
		context.publish();

		final PublishEvent expected = publishedEvent(VERDICT_UNIT_TYPE, "dual_holdout_unit_5",
				holdoutExposure(VERDICT_UNIT_TYPE, 107, "holdout_107", 0),
				holdoutExposure(VERDICT_UNIT_TYPE, 108, "holdout_108", 0));
		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(context, expected);
	}

	// --- `assigned` and holdout-exposure symmetry ---------------------------------------------
	// A suppressed assignment is not a participant in its experiment: `assigned` stays false.
	// Reading a variable must still trigger the holdout's own exposure, for both arms, purely
	// because the experiment was evaluated - regardless of whether that evaluation reached
	// `getTreatment` or `getVariableValue`, and regardless of whether the experiment's own
	// exposure was itself suppressed.
	@Test
	void variableLookupFiresHoldoutExposureForBothHeldOutAndNonHeldOutUnits() {
		final Experiment experimentHeldOut = coveredBy(newExperiment(1, "exp_var_held_out"), 11);
		experimentHeldOut.variants[1].config = "{\"var_a\":\"value_a\"}";
		final Context heldOutContext = createReadyContext(contextDataOf(
				new Experiment[]{newHoldout(11, "holdout_a", HOLDOUT_A_SEED_HI, HOLDOUT_A_SEED_LO)},
				experimentHeldOut));

		// suppressed: control values only, but evaluating the variable must still trigger the
		// holdout's own exposure.
		assertEquals("default", heldOutContext.getVariableValue("var_a", "default"));
		assertEquals(1, heldOutContext.getPendingCount()); // holdout exposure only

		final Experiment experimentNotHeldOut = coveredBy(newExperiment(1, "exp_var_not_held_out"), 11);
		experimentNotHeldOut.variants[1].config = "{\"var_a\":\"value_a\"}";
		final Context notHeldOutContext = createReadyContext(UID_NOT_HELD_OUT, contextDataOf(
				new Experiment[]{newHoldout(11, "holdout_a", HOLDOUT_A_SEED_HI, HOLDOUT_A_SEED_LO)},
				experimentNotHeldOut));

		assertEquals("default", notHeldOutContext.getVariableValue("var_a", "default")); // normal variant is 0
		assertEquals(2, notHeldOutContext.getPendingCount()); // own exposure + holdout exposure
	}

	// A held-out experiment must never win variable-key resolution over one the unit is
	// genuinely assigned to, even when the held-out experiment has the lower id and is checked
	// first.
	@Test
	void variableKeyResolutionSkipsSuppressedAssignmentInFavorOfAssignedOne() {
		final Experiment suppressedExperiment = coveredBy(newExperiment(1, "exp_var_key_suppressed"), 11);
		suppressedExperiment.variants[1].config = "{\"shared\":\"from_suppressed\"}";

		final Experiment assignedExperiment = newExperiment(2, "exp_var_key_assigned");
		assignedExperiment.variants[1].config = "{\"shared\":\"from_assigned\"}";

		final Experiment holdout = newHoldout(11, "holdout_a", HOLDOUT_A_SEED_HI, HOLDOUT_A_SEED_LO);

		final Context context = createReadyContext(
				contextDataOf(new Experiment[]{holdout}, suppressedExperiment, assignedExperiment));

		assertEquals("from_assigned", context.getVariableValue("shared", "default"));

		// the lower-id suppressed experiment was evaluated to make this resolution decision, so
		// its applicable holdout must fire even though its own value lost the key - the assigned
		// experiment's own ordinary exposure fires too, but the suppressed one's does not.
		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
		context.publish();

		final PublishEvent expected = publishedEvent(UID,
				holdoutExposure(11, "holdout_a", 0),
				new Exposure(2, "exp_var_key_assigned", UNIT_TYPE, NORMAL_VARIANT, clock.millis(), true, true, false,
						false, false, false));
		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(context, expected);
	}

	// The peek path must stay side-effect free: resolving a variable key via peekVariableValue
	// must not trigger any holdout exposure for a candidate it evaluates along the way.
	@Test
	void peekVariableValueNeverTriggersHoldoutExposureForEvaluatedCandidates() {
		final Experiment suppressedExperiment = coveredBy(newExperiment(1, "exp_var_key_peek_suppressed"), 11);
		suppressedExperiment.variants[1].config = "{\"shared_peek\":\"from_suppressed\"}";

		final Experiment assignedExperiment = newExperiment(2, "exp_var_key_peek_assigned");
		assignedExperiment.variants[1].config = "{\"shared_peek\":\"from_assigned\"}";

		final Experiment holdout = newHoldout(11, "holdout_a", HOLDOUT_A_SEED_HI, HOLDOUT_A_SEED_LO);

		final Context context = createReadyContext(
				contextDataOf(new Experiment[]{holdout}, suppressedExperiment, assignedExperiment));

		assertEquals("from_assigned", context.peekVariableValue("shared_peek", "default"));
		assertEquals(0, context.getPendingCount()); // no exposure of any kind
	}

	// Regression test for the logger-robustness fix: a throwing ContextEventLogger must not stop
	// sibling holdout exposures from being queued. Holdout A (checked first, lower id) has a
	// logger that throws; holdout B (checked second) must still fire.
	@Test
	void throwingLoggerDoesNotPermanentlyLoseSiblingHoldoutExposure() {
		doThrow(new RuntimeException("boom")).when(eventLogger).handleEvent(any(), any(),
				org.mockito.ArgumentMatchers.argThat(o -> (o instanceof Exposure) && (((Exposure) o).id == 11)));

		final Experiment experiment = coveredBy(newExperiment(1, "exp_multi_holdout_throwing_logger"), 11, 12);
		final Context context = createReadyContext(contextDataOf(
				new Experiment[]{
						newHoldout(11, "holdout_a", HOLDOUT_A_SEED_HI, HOLDOUT_A_SEED_LO), // holds UID out, throws
						newHoldout(12, "holdout_b", HOLDOUT_B_SEED_HI, HOLDOUT_B_SEED_LO), // does not hold UID out
				}, experiment));

		assertThrows(RuntimeException.class, () -> context.getTreatment("exp_multi_holdout_throwing_logger"));

		// both exposures were queued for publish despite holdout A's logger call throwing.
		assertEquals(2, context.getPendingCount());
	}

	// Malformed holdouts are omitted from the id index; references to omitted entries must not
	// affect normal experiment assignment.
	@Test
	void nullHoldoutArrayElementIsIgnoredAndExperimentAssignsNormally() {
		final Experiment experiment = newExperiment(1, "exp_null_holdout_entry");
		final Context context = createReadyContext(contextDataOf(new Experiment[]{null}, experiment));

		assertEquals(NORMAL_VARIANT, context.getTreatment("exp_null_holdout_entry"));
		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
		context.publish();

		final PublishEvent expected = publishedEvent(UID,
				new Exposure(1, "exp_null_holdout_entry", UNIT_TYPE, NORMAL_VARIANT, clock.millis(), true, true,
						false, false, false, false));
		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(context, expected);
	}

	@Test
	void holdoutWithNullSplitIsIgnoredAndExperimentAssignsNormally() {
		final Experiment experiment = coveredBy(newExperiment(1, "exp_null_split_holdout"), 11);
		final Experiment malformedHoldout = newHoldout(11, "holdout_null_split", HOLDOUT_A_SEED_HI,
				HOLDOUT_A_SEED_LO);
		malformedHoldout.split = null;

		final Context context = createReadyContext(
				contextDataOf(new Experiment[]{malformedHoldout}, experiment));

		assertEquals(NORMAL_VARIANT, context.getTreatment("exp_null_split_holdout"));
		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
		context.publish();

		final PublishEvent expected = publishedEvent(UID,
				new Exposure(1, "exp_null_split_holdout", UNIT_TYPE, NORMAL_VARIANT, clock.millis(), true, true,
						false, false, false, false));
		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(context, expected);
	}

	@Test
	void holdoutWithEmptySplitIsIgnoredAndExperimentAssignsNormally() {
		final Experiment experiment = coveredBy(newExperiment(1, "exp_empty_split_holdout"), 11);
		final Experiment malformedHoldout = newHoldout(11, "holdout_empty_split", HOLDOUT_A_SEED_HI,
				HOLDOUT_A_SEED_LO);
		malformedHoldout.split = new double[0];

		final Context context = createReadyContext(
				contextDataOf(new Experiment[]{malformedHoldout}, experiment));

		assertEquals(NORMAL_VARIANT, context.getTreatment("exp_empty_split_holdout"));
		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
		context.publish();

		final PublishEvent expected = publishedEvent(UID,
				new Exposure(1, "exp_empty_split_holdout", UNIT_TYPE, NORMAL_VARIANT, clock.millis(), true, true,
						false, false, false, false));
		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(context, expected);
	}

	// --- Holdout cache/publish invariants under concurrent refresh and overrides ------------

	// A stale Experiment reference reaching getHoldoutAssignment must never overwrite a cache
	// entry a concurrent, genuinely newer evaluation already installed and exposed. This
	// reproduces the exact mechanism directly (bypassing the surrounding call-site plumbing via
	// reflection into the private trigger path, since the public API closes every call site that
	// could reach this window): the holdout
	// H is bumped to a new iteration with a flipped verdict via the normal refresh + evaluation
	// path, genuinely installing and exposing an it2 HoldoutAssignment (variant 1). A directly
	// reconstructed, deliberately stale it1 Experiment object - same id, old iteration, the OLD
	// seed that computes the OPPOSITE arm (variant 0) - is then fed straight into the private
	// trigger path exactly as a lagging caller's cached holdouts array would. The fix must resolve
	// H's live (it2) definition by id and return the already-exposed cache entry unchanged; a
	// regression would recompute from the dead it1 seed, overwrite the it2 entry with a fresh,
	// unexposed Assignment, and let it be exposed a second time with the opposite (contradictory)
	// arm.
	@Test
	void staleHoldoutReferenceNeverOverwritesNewerPinnedCacheEntry() throws Exception {
		final Experiment holdoutIteration1 = newHoldout(11, "holdout_h", HOLDOUT_A_SEED_HI, HOLDOUT_A_SEED_LO);
		final Experiment f = coveredBy(newExperiment(1, "exp_f"), 11);

		final Context context = createReadyContext(contextDataOf(new Experiment[]{holdoutIteration1}, f));

		assertEquals(0, context.getTreatment("exp_f")); // held out by H@it1 (variant 0)
		assertEquals(1, context.getPendingCount()); // H@it1's own exposure (variant 0)

		// Refresh: H bumped to a genuine new epoch (iteration 2) with a seed that flips the
		// verdict to variant 1, covering a new experiment G. Evaluating G installs and exposes the
		// it2 HoldoutAssignment for real, through the normal path.
		final Experiment holdoutIteration2 = newHoldout(11, "holdout_h", HOLDOUT_B_SEED_HI, HOLDOUT_B_SEED_LO);
		holdoutIteration2.iteration = 2;
		final Experiment refreshedF = coveredBy(newExperiment(1, "exp_f"), 11);
		final Experiment g = coveredBy(newExperiment(2, "exp_g"), 11);

		final CompletableFuture<ContextData> refreshFuture = new CompletableFuture<>();
		when(dataProvider.getContextData()).thenReturn(refreshFuture);
		final CompletableFuture<Void> refreshing = context.refreshAsync();
		refreshFuture.complete(contextDataOf(new Experiment[]{holdoutIteration2}, refreshedF, g));
		refreshing.join();

		assertEquals(NORMAL_VARIANT, context.getTreatment("exp_g")); // not held out by H@it2
		assertEquals(3, context.getPendingCount()); // G's own exposure + H@it2's own exposure (variant 1)

		// A deliberately stale reference to H: same id, OLD iteration (1), and the OLD seed that
		// computes the OPPOSITE arm from the live it2 definition. This is exactly what a lagging
		// caller's cached holdouts array would still hold.
		final Experiment staleHoldoutReference = newHoldout(11, "holdout_h", HOLDOUT_A_SEED_HI, HOLDOUT_A_SEED_LO);

		final Method triggerHoldoutExposure = Context.class.getDeclaredMethod("triggerHoldoutExposure",
				Experiment.class, String.class);
		triggerHoldoutExposure.setAccessible(true);
		triggerHoldoutExposure.invoke(context, staleHoldoutReference, UNIT_TYPE);

		// no second, contradictory exposure: the live it2 entry (already exposed, variant 1) must
		// have been resolved and reused rather than overwritten by a fresh it1-seeded recompute.
		assertEquals(3, context.getPendingCount());

		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
		context.publish();

		// f was suppressed under it1 and never emits an exposure of its own; H legitimately emits
		// once per distinct epoch (it1 variant 0, it2 variant 1) - the invariant broken by the bug
		// is a THIRD, contradictory exposure for the SAME it2 epoch, which is what is absent here.
		final PublishEvent expected = publishedEvent(UID,
				holdoutExposure(11, "holdout_h", 0),
				new Exposure(2, "exp_g", UNIT_TYPE, NORMAL_VARIANT, clock.millis(), true, true, false, false, false,
						false),
				holdoutExposure(11, "holdout_h", 1));
		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(context, expected);
	}

	// The override fast path must revalidate applicable-holdout coverage exactly like the
	// ordinary experimentMatches branch does. A holdout that becomes applicable to an
	// already-overridden, already-exposed experiment only after a refresh must still fire when
	// that experiment is evaluated again - the override never suppresses holdout evaluation.
	// Invalidating on the coverage change re-fires E's own exposure too, symmetric with how an
	// ordinary experiment's experimentMatches-driven invalidation already behaves.
	@Test
	void holdoutBecomingApplicableAfterRefreshStillFiresForOverriddenExperiment() {
		final Experiment experiment = newExperiment(1, "exp_override_late_holdout");

		final ContextConfig config = ContextConfig.create().setUnit(UNIT_TYPE, UID)
				.setOverride("exp_override_late_holdout", 3);
		// no holdouts at all initially.
		final Context context = createReadyContext(config, contextDataOf(experiment));

		assertEquals(3, context.getTreatment("exp_override_late_holdout")); // override wins, no holdout yet
		assertEquals(1, context.getPendingCount()); // only E's own exposure

		// refresh installs a holdout covering the same unit type/experiment for the first time.
		final Experiment refreshedExperiment = coveredBy(newExperiment(1, "exp_override_late_holdout"), 11);
		final Experiment holdout = newHoldout(11, "holdout_late", HOLDOUT_A_SEED_HI, HOLDOUT_A_SEED_LO);

		final CompletableFuture<ContextData> refreshFuture = new CompletableFuture<>();
		when(dataProvider.getContextData()).thenReturn(refreshFuture);
		final CompletableFuture<Void> refreshing = context.refreshAsync();
		refreshFuture.complete(contextDataOf(new Experiment[]{holdout}, refreshedExperiment));
		refreshing.join();

		// re-evaluating the overridden experiment must still trigger the newly applicable
		// holdout's own exposure.
		assertEquals(3, context.getTreatment("exp_override_late_holdout")); // override still wins
		assertEquals(3, context.getPendingCount()); // + E's re-fired exposure + the holdout's own

		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
		context.publish();

		final Exposure ownExposure = new Exposure(1, "exp_override_late_holdout", UNIT_TYPE, 3, clock.millis(),
				false, true, true, false, false, false);
		final PublishEvent expected = publishedEvent(UID, ownExposure, ownExposure,
				holdoutExposure(11, "holdout_late", 0));
		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(context, expected);
	}

	// getVariableValue resolves via the variable-key path, which fires candidate holdout
	// exposures as each candidate is visited. When the winning assignment was already exposed (so getVariableValue itself never calls triggerExposure -> setTimeout), a holdout
	// exposure fired for a losing/suppressed candidate along the way must still schedule a flush
	// on its own - it must not sit unflushed until an unrelated event.
	@Test
	void variablePathHoldoutExposureSchedulesFlushWithoutAnyOtherEvent() {
		final Experiment suppressed = coveredBy(newExperiment(1, "exp_var_flush_suppressed"), 11);
		suppressed.variants[1].config = "{\"flush_key\":\"from_suppressed\"}";

		final Experiment assigned = newExperiment(2, "exp_var_flush_assigned");
		assigned.variants[1].config = "{\"flush_key\":\"from_assigned\"}";

		final Experiment holdout = newHoldout(11, "holdout_flush", HOLDOUT_A_SEED_HI, HOLDOUT_A_SEED_LO);

		final Context context = createReadyContext(
				contextDataOf(new Experiment[]{holdout}, suppressed, assigned));

		when(scheduler.schedule((Runnable) any(), eq(100L), eq(TimeUnit.MILLISECONDS)))
				.thenReturn(mock(java.util.concurrent.ScheduledFuture.class));

		assertEquals("from_assigned", context.getVariableValue("flush_key", "default"));

		// the suppressed candidate's applicable holdout fired an exposure that no other call path
		// enqueued/exposed; a flush must have been scheduled for it regardless.
		verify(scheduler, Mockito.timeout(5000).times(1)).schedule((Runnable) any(), eq(100L),
				eq(TimeUnit.MILLISECONDS));
	}

	// setData must not mutate a caller-supplied experiment's holdoutIds array in place -
	// resolution only ever reads it. The caller's array is asserted unchanged after setData
	// runs, and coverage (which the resolution reads directly off it) still works.
	@Test
	void setDataDoesNotMutateCallerSuppliedHoldoutIdsArray() {
		final int[] callerArray = new int[]{12, 11}; // deliberately unsorted, and includes a dangling id (12)
		final Experiment covered = newExperiment(1, "exp_holdout_no_mutate_in");
		covered.holdoutIds = callerArray;
		final Experiment notCovered = newExperiment(2, "exp_holdout_no_mutate_not_covered");
		final Experiment holdout = newHoldout(11, "holdout_no_mutate", HOLDOUT_A_SEED_HI, HOLDOUT_A_SEED_LO);

		final Context context = createReadyContext(contextDataOf(new Experiment[]{holdout}, covered, notCovered));

		// coverage still resolves correctly via id 11, and the dangling id 12 is simply ignored.
		assertEquals(0, context.getTreatment("exp_holdout_no_mutate_in")); // suppressed -> control
		assertEquals(NORMAL_VARIANT,
				context.getTreatment("exp_holdout_no_mutate_not_covered")); // no coverage -> unaffected

		// the caller's own array, handed in via ContextData, must remain exactly as constructed.
		assertArrayEquals(new int[]{12, 11}, callerArray);
	}
}
