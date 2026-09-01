package com.absmartly.sdk;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java8.util.concurrent.CompletableFuture;
import java8.util.function.Function;

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

	Context createReadyContextWithoutUnits(ContextData data) {
		return createReadyContext(ContextConfig.create(), data);
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

	Object getAssignment(Context context, String experimentName) throws Exception {
		final Method method = Context.class.getDeclaredMethod("getAssignment", String.class);
		method.setAccessible(true);
		return method.invoke(context, experimentName);
	}

	Object exposeTreatmentAssignment(Context context, Object assignment) throws Exception {
		final Method method = Context.class.getDeclaredMethod("exposeTreatmentAssignment", assignment.getClass());
		method.setAccessible(true);
		return method.invoke(context, assignment);
	}

	int getExposedTreatmentVariant(Context context, Object assignment) throws Exception {
		final Method method = Context.class.getDeclaredMethod("getExposedTreatmentVariant", assignment.getClass());
		method.setAccessible(true);
		return (Integer) method.invoke(context, assignment);
	}

	Object exposeVariableAssignment(Context context, String key, Object assignment) throws Exception {
		final Method method = Context.class.getDeclaredMethod("exposeVariableAssignment", String.class,
				assignment.getClass());
		method.setAccessible(true);
		return method.invoke(context, key, assignment);
	}

	void retireAssignment(Object assignment) throws Exception {
		final Field field = assignment.getClass().getDeclaredField("exposureState");
		field.setAccessible(true);
		((AtomicInteger) field.get(assignment)).set(2);
	}

	int assignmentVariant(Object assignment) throws Exception {
		final Field field = assignment.getClass().getDeclaredField("variant");
		field.setAccessible(true);
		return field.getInt(assignment);
	}

	Object settleExposure(Context context, Object assignment, Function<Object, Object> resolver) throws Exception {
		final Method method = Context.class.getDeclaredMethod("settleExposure", assignment.getClass(), Function.class);
		method.setAccessible(true);
		return method.invoke(context, assignment, resolver);
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

	// Reproduces the null-snapshot regression: a ready context with no unit installed peeks a
	// covered full-on experiment (peekTreatment never requires the covered unit type - only the
	// holdout resolution does), pinning a null holdoutAssignments entry into the cached
	// Assignment because "user_id" was absent. setUnit("user_id", ...) must evict that cache
	// entry so getTreatment recomputes the decision with the now-present unit, instead of
	// reusing the pinned null and silently skipping the holdout's exposure forever.
	//
	// Fails against pre-fix code: setUnit only writes units_, never touches assignmentCache_, so
	// getTreatment's cache-hit path (experimentMatches: same iteration, same holdouts array)
	// reuses the stale Assignment. triggerApplicableHoldoutExposures then fires the pinned
	// snapshot, sees holdoutAssignments[0] == null, and triggerHoldoutExposure(Assignment) skips
	// a null argument outright - only the experiment's own exposure is queued, ever.
	@Test
	void setUnitInvalidatesNullHoldoutSnapshotSoTheHoldoutExposureIsNotLostForever() {
		final String coveredUnitType = "user_id";
		final Experiment experiment = coveredBy(
				newExperiment(1, "exp_null_snapshot_fullon", coveredUnitType, 2), 11);
		final Experiment holdout = newHoldout(11, "holdout_user_id", coveredUnitType, HOLDOUT_B_SEED_HI,
				HOLDOUT_B_SEED_LO, "full"); // not held out for UID once resolvable

		final Context context = createReadyContextWithoutUnits( // no unit installed yet
				contextDataOf(new Experiment[]{holdout}, experiment));

		// peekTreatment resolves the full-on variant without ever needing coveredUnitType; the
		// holdout resolution against the missing unit yields a null snapshot entry, pinned into
		// the cached Assignment without suppressing (a null entry never suppresses).
		assertEquals(2, context.peekTreatment("exp_null_snapshot_fullon"));

		context.setUnit(coveredUnitType, UID);

		// recomputed from a coherent decision now that the unit is present: still fullOn=2 (this
		// holdout's arm does not hold it out), but this time both exposures are owed.
		assertEquals(2, context.getTreatment("exp_null_snapshot_fullon"));
		assertEquals(2, context.getPendingCount());

		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
		context.publish();

		final PublishEvent expected = publishedEvent(coveredUnitType, UID,
				new Exposure(1, "exp_null_snapshot_fullon", coveredUnitType, 2, clock.millis(), true, true, false,
						true, false, false),
				holdoutExposure(coveredUnitType, 11, "holdout_user_id", 1));
		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(context, expected);
	}

	// Selectivity for Defect 2 (High): eviction must not discard an already-exposed assignment.
	// A full-on covered experiment is assignable without the unit, so getTreatment (not
	// peekTreatment) queues its own exposure immediately while the holdout's snapshot entry is
	// still null. setUnit must leave this exposed Assignment in place; a subsequent getTreatment
	// must not re-queue the experiment's exposure a second time.
	//
	// Fails without the `!assignment.exposed.get()` guard: eviction removes the exposed
	// Assignment, the next getTreatment builds a fresh one with exposed==false, and the
	// experiment's exposure is published twice for the same unit.
	@Test
	void setUnitDoesNotEvictAlreadyExposedAssignmentSoNoDuplicateExposure() throws Exception {
		final String coveredUnitType = "user_id";
		final Experiment experiment = coveredBy(
				newExperiment(1, "exp_exposed_before_unit", coveredUnitType, 2), 11);
		final Experiment holdout = newHoldout(11, "holdout_user_id", coveredUnitType, HOLDOUT_B_SEED_HI,
				HOLDOUT_B_SEED_LO, "full");

		final Context context = createReadyContextWithoutUnits(
				contextDataOf(new Experiment[]{holdout}, experiment));

		assertEquals(2, context.getTreatment("exp_exposed_before_unit"));
		assertEquals(1, context.getPendingCount());
		final Object exposed = getAssignment(context, "exp_exposed_before_unit");

		context.setUnit(coveredUnitType, UID);

		assertEquals(2, context.getTreatment("exp_exposed_before_unit"));
		assertEquals(1, context.getPendingCount()); // unchanged: no duplicate exposure
		assertSame(exposed, getAssignment(context, "exp_exposed_before_unit"));
	}

	// Selectivity: an unrelated unit type must never touch cache entries at all, exposed or not.
	// Fails under `assignmentCache_.clear()`, which wipes every entry regardless of unit type.
	@Test
	void setUnitForUnrelatedUnitTypeDoesNotEvictExposedAssignment() {
		final Experiment experiment = coveredBy(newExperiment(1, "exp_unrelated_unit"), 11);
		final Context context = createReadyContext(contextDataOf(
				new Experiment[]{newHoldout(11, "holdout_a", HOLDOUT_B_SEED_HI, HOLDOUT_B_SEED_LO)}, experiment));

		assertEquals(NORMAL_VARIANT, context.getTreatment("exp_unrelated_unit"));
		assertEquals(2, context.getPendingCount()); // experiment's own exposure + holdout's

		context.setUnit("other_unit_type", "some-other-uid");

		assertEquals(NORMAL_VARIANT, context.getTreatment("exp_unrelated_unit"));
		assertEquals(2, context.getPendingCount()); // unchanged: no duplicate exposure
	}

	// Selectivity: with two cached experiments, only the one whose snapshot holds a null entry
	// for the just-installed unit type is evicted; the other, already resolved and exposed
	// against its own (already-present) unit type, is left untouched.
	@Test
	void setUnitEvictsOnlyTheAffectedEntryAndLeavesTheOtherUntouched() {
		final String lateUnitType = "user_id";
		final Experiment lateExperiment = coveredBy(
				newExperiment(1, "exp_late_unit", lateUnitType, 2), 11);
		final Experiment lateHoldout = newHoldout(11, "holdout_late", lateUnitType, HOLDOUT_B_SEED_HI,
				HOLDOUT_B_SEED_LO, "full");

		final Experiment presentExperiment = coveredBy(newExperiment(2, "exp_present_unit"), 12);
		final Experiment presentHoldout = newHoldout(12, "holdout_present", HOLDOUT_B_SEED_HI, HOLDOUT_B_SEED_LO);

		final Context context = createReadyContext(UID, // installs UNIT_TYPE only
				contextDataOf(new Experiment[]{lateHoldout, presentHoldout}, lateExperiment, presentExperiment));

		assertEquals(2, context.peekTreatment("exp_late_unit")); // null snapshot, not yet exposed
		assertEquals(NORMAL_VARIANT, context.getTreatment("exp_present_unit")); // resolved and exposed
		assertEquals(2, context.getPendingCount()); // exp_present_unit + holdout_present

		context.setUnit(lateUnitType, UID);

		// the affected entry recomputes and owes both of its exposures.
		assertEquals(2, context.getTreatment("exp_late_unit"));
		assertEquals(4, context.getPendingCount());

		// the unaffected entry must not be re-triggered by the eviction pass or by this
		// unrelated getTreatment call.
		assertEquals(NORMAL_VARIANT, context.getTreatment("exp_present_unit"));
		assertEquals(4, context.getPendingCount());
	}

	// Defect 1 (Medium): the predicate must compare against assignment.unitType - the value
	// getHoldoutAssignment was actually called with (experiment.data.unitType) - not the
	// referenced holdout's own declared unitType, which nothing requires to match.
	//
	// Fails against `unitType.equals(assignment.holdouts[i].unitType)`: the covered experiment's
	// unitType is "A", the holdout declares "B", and the null snapshot was produced by "A" being
	// absent. setUnit("A", ...) must evict, but the old predicate compares "A" against the
	// holdout's declared "B" and never does.
	@Test
	void setUnitEvictsUsingTheCoveredExperimentsUnitTypeNotTheHoldoutsDeclaredUnitType() {
		final Experiment experiment = coveredBy(newExperiment(1, "exp_mismatched_unit_type", "A", 2), 11);
		final Experiment holdout = newHoldout(11, "holdout_declares_b", "B", HOLDOUT_B_SEED_HI, HOLDOUT_B_SEED_LO,
				"full");

		final Context context = createReadyContextWithoutUnits( // neither "A" nor "B" installed
				contextDataOf(new Experiment[]{holdout}, experiment));

		assertEquals(2, context.peekTreatment("exp_mismatched_unit_type")); // null snapshot: "A" is absent

		context.setUnit("A", UID);

		assertEquals(2, context.getTreatment("exp_mismatched_unit_type"));
		assertEquals(2, context.getPendingCount()); // both exposures owed: eviction happened
	}

	// Kills the "re-resolve the null live at trigger time" mutant: the late-resolved holdout's
	// arm actually suppresses this unit, so a coherent recomputation must flip the experiment
	// from its pinned fullOn=2/unsuppressed verdict to control (0) and publish only the holdout's
	// exposure. Re-resolving the null in place instead of recomputing the whole decision would
	// leave variant=2/unsuppressed as-is and publish a contradictory "held out" holdout exposure
	// alongside a "participated" experiment exposure for the same unit.
	@Test
	void setUnitRecomputesSuppressionCoherentlyWhenLateResolvedHoldoutSuppresses() {
		final String coveredUnitType = "user_id";
		final Experiment experiment = coveredBy(
				newExperiment(1, "exp_null_snapshot_suppresses", coveredUnitType, 2), 11);
		final Experiment holdout = newHoldout(11, "holdout_user_id_suppress", coveredUnitType, HOLDOUT_A_SEED_HI,
				HOLDOUT_A_SEED_LO, "full"); // holds UID out once resolvable

		final Context context = createReadyContextWithoutUnits(
				contextDataOf(new Experiment[]{holdout}, experiment));

		assertEquals(2, context.peekTreatment("exp_null_snapshot_suppresses")); // null snapshot never suppresses

		context.setUnit(coveredUnitType, UID);

		assertEquals(0, context.getTreatment("exp_null_snapshot_suppresses")); // now suppressed -> control
		assertEquals(1, context.getPendingCount()); // holdout exposure only

		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
		context.publish();

		final PublishEvent expected = publishedEvent(coveredUnitType, UID,
				holdoutExposure(coveredUnitType, 11, "holdout_user_id_suppress", 0));
		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(context, expected);
	}

	@Test
	void detachedTreatmentAssignmentCannotPublishBesideSuppressingReplacement() throws Exception {
		final String unitType = "user_id";
		final String experimentName = "exp_detached_suppressed";
		final Experiment experiment = coveredBy(newExperiment(1, experimentName, unitType, 2), 11);
		final Experiment holdout = newHoldout(11, "holdout_detached_suppressed", unitType,
				HOLDOUT_A_SEED_HI, HOLDOUT_A_SEED_LO, "full");
		final Context context = createReadyContextWithoutUnits(
				contextDataOf(new Experiment[]{holdout}, experiment));

		final Object retained = getAssignment(context, experimentName);
		context.setUnit(unitType, UID);
		assertEquals(0, context.getTreatment(experimentName));
		assertEquals(1, context.getPendingCount());

		exposeTreatmentAssignment(context, retained);
		assertEquals(1, context.getPendingCount());

		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
		context.publish();
		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(context,
				publishedEvent(unitType, UID, holdoutExposure(unitType, 11, "holdout_detached_suppressed", 0)));
	}

	@Test
	void detachedTreatmentAssignmentRetriesCurrentEntryWithoutLosingExposure() throws Exception {
		final String unitType = "user_id";
		final String experimentName = "exp_detached_retry";
		final Experiment experiment = coveredBy(newExperiment(1, experimentName, unitType, 2), 11);
		final Experiment holdout = newHoldout(11, "holdout_detached_retry", unitType,
				HOLDOUT_B_SEED_HI, HOLDOUT_B_SEED_LO, "full");
		final Context context = createReadyContextWithoutUnits(
				contextDataOf(new Experiment[]{holdout}, experiment));

		final Object retained = getAssignment(context, experimentName);
		context.setUnit(unitType, UID);
		exposeTreatmentAssignment(context, retained);

		assertEquals(2, context.getPendingCount());
		assertEquals(2, context.getTreatment(experimentName));
		assertEquals(2, context.getPendingCount());

		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
		context.publish();
		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(context,
				publishedEvent(unitType, UID,
						new Exposure(1, experimentName, unitType, 2, clock.millis(), true, true, false, true, false,
								false),
						holdoutExposure(unitType, 11, "holdout_detached_retry", 1)));
	}

	@Test
	void detachedTreatmentReturnsTheReplacementVariant() throws Exception {
		final String unitType = "user_id";
		final String experimentName = "exp_detached_variant";
		final Experiment experiment = coveredBy(newExperiment(1, experimentName, unitType, 2), 11);
		final Experiment holdout = newHoldout(11, "holdout_detached_variant", unitType,
				HOLDOUT_A_SEED_HI, HOLDOUT_A_SEED_LO, "full");
		final Context context = createReadyContextWithoutUnits(
				contextDataOf(new Experiment[]{holdout}, experiment));

		final Object retained = getAssignment(context, experimentName);
		context.setUnit(unitType, UID);

		assertEquals(0, getExposedTreatmentVariant(context, retained));
		assertEquals(1, context.getPendingCount());
	}

	@Test
	void treatmentExposureSettlesAfterTwoConsecutiveRetirements() throws Exception {
		final Experiment first = newExperiment(1, "exp_first", 2);
		final Experiment second = newExperiment(2, "exp_second", 1);
		final Experiment settled = newExperiment(3, "exp_settled", 1);
		final Context context = createReadyContext(contextDataOf(first, second, settled));
		final Object firstAssignment = getAssignment(context, first.name);
		final Object secondAssignment = getAssignment(context, second.name);
		final Object settledAssignment = getAssignment(context, settled.name);
		retireAssignment(firstAssignment);
		retireAssignment(secondAssignment);

		final Object result = settleExposure(context, firstAssignment, new Function<Object, Object>() {
			int call;

			@Override
			public Object apply(Object ignored) {
				return call++ == 0 ? secondAssignment : settledAssignment;
			}
		});

		assertSame(settledAssignment, result);
		assertEquals(1, assignmentVariant(result));
		assertEquals(1, context.getPendingCount());
	}

	@Test
	void exposureSettlementStopsAfterTheBoundedNumberOfRetirements() throws Exception {
		final Experiment experiment = newExperiment(1, "exp_bounded_retry", 1);
		final Context context = createReadyContext(contextDataOf(experiment));
		final Object retired = getAssignment(context, experiment.name);
		retireAssignment(retired);
		final AtomicInteger resolutions = new AtomicInteger();

		settleExposure(context, retired, new Function<Object, Object>() {
			@Override
			public Object apply(Object ignored) {
				if (resolutions.incrementAndGet() > 2) {
					throw new AssertionError("exposure retry exceeded its bound");
				}
				return retired;
			}
		});

		assertEquals(2, resolutions.get());
		assertEquals(0, context.getPendingCount());
	}

	@Test
	void detachedVariableAssignmentReresolvesTheKeyToANewWinningExperiment() throws Exception {
		final String unitType = "user_id";
		final Experiment original = coveredBy(newExperiment(2, "exp_detached_variable_old", unitType, 1), 11);
		original.variants[1].config = "{\"detached_var\":\"old\"}";
		final Experiment holdout = newHoldout(11, "holdout_detached_variable", unitType,
				HOLDOUT_B_SEED_HI, HOLDOUT_B_SEED_LO, "full");
		final Context context = createReadyContextWithoutUnits(
				contextDataOf(new Experiment[]{holdout}, original));

		final Object retained = getAssignment(context, original.name);
		context.setUnit(unitType, UID);

		final Experiment winner = coveredBy(newExperiment(1, "exp_detached_variable_winner", unitType, 1), 11);
		winner.variants[1].config = "{\"detached_var\":\"new winner\"}";
		final CompletableFuture<ContextData> refreshFuture = new CompletableFuture<>();
		when(dataProvider.getContextData()).thenReturn(refreshFuture);
		final CompletableFuture<Void> refreshing = context.refreshAsync();
		refreshFuture.complete(contextDataOf(new Experiment[]{holdout}, winner, original));
		refreshing.join();

		final Object resolved = exposeVariableAssignment(context, "detached_var", retained);
		assertEquals(1, assignmentVariant(resolved));
		assertEquals(2, context.getPendingCount());
		assertEquals("new winner", context.getVariableValue("detached_var", "default"));
		assertEquals(2, context.getPendingCount());

		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
		context.publish();
		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(context,
				publishedEvent(unitType, UID,
						holdoutExposure(unitType, 11, "holdout_detached_variable", 1),
						new Exposure(1, winner.name, unitType, 1, clock.millis(), true, true, false, true, false,
								false)));
	}

	// Kills a narrower variant of the "re-resolve the null live at trigger time" mutant that
	// setUnitRecomputesSuppressionCoherentlyWhenLateResolvedHoldoutSuppresses cannot reach: once
	// an Assignment is already exposed, setUnit's `!exposed` guard deliberately leaves its null
	// snapshot entry in the cache forever (Defect 2), so a null pinned entry can still reach
	// triggerApplicableHoldoutExposures on a LATER evaluation of the SAME already-exposed
	// assignment - specifically via the variable-key path, which re-triggers every candidate's
	// holdouts unconditionally on each call, independent of that candidate's own exposed state.
	// Correct code passes the pinned null straight to triggerHoldoutExposure(Assignment), which
	// no-ops on null every time, so the holdout is never fired for this unit's life once the
	// snapshot went stale exposed. The mutant instead re-resolves live once the unit is present
	// and fires a fresh, contradictory "held out" exposure next to the "participated" one already
	// published.
	@Test
	void triggerApplicableHoldoutExposuresNeverLiveResolvesAPinnedNullEntryForAnAlreadyExposedAssignment() {
		final String coveredUnitType = "user_id";
		final Experiment experiment = coveredBy(
				newExperiment(1, "exp_a_mutant_pinned_null", coveredUnitType, 1), 11);
		experiment.variants[1].config = "{\"my_var\":\"value_from_variant\"}";
		final Experiment holdout = newHoldout(11, "holdout_a_mutant_pinned_null", coveredUnitType,
				HOLDOUT_A_SEED_HI, HOLDOUT_A_SEED_LO, "full"); // holds UID out once resolvable

		final Context context = createReadyContextWithoutUnits( // no unit installed yet
				contextDataOf(new Experiment[]{holdout}, experiment));

		// fullOn resolves and exposes without ever needing coveredUnitType; the holdout's null
		// snapshot entry never suppresses and, being null both here (unit absent) and under the
		// mutant's live resolution (uid still null in units_), never fires either.
		assertEquals(1, context.getTreatment("exp_a_mutant_pinned_null"));
		assertEquals(1, context.getPendingCount()); // own exposure only

		context.setUnit(coveredUnitType, UID); // exposed guard: cache entry survives with pinned null

		// re-evaluating the same already-exposed assignment via the variable-key path, which
		// unconditionally re-triggers every candidate's applicable holdouts on every call.
		assertEquals("value_from_variant", context.getVariableValue("my_var", "default"));

		// correct code: pinned[0] is still null, triggerHoldoutExposure(null) no-ops - no second
		// exposure, ever, for this permanently-degraded assignment.
		assertEquals(1, context.getPendingCount());
	}

	// Multiple holdouts applicable to one covered experiment, each declaring a different (and
	// irrelevant, per Defect 1) unitType, guard against wrong-index matching or stopping the
	// eviction scan at the wrong entry. Every applicable holdout is resolved against the SAME
	// value - the covered experiment's own unitType - so all three snapshot entries are null
	// together, and setUnit must evict once and let all three, plus the experiment's own, fire.
	@Test
	void setUnitEvictsAssignmentWithMultipleHoldoutsDeclaringDifferentUnitTypes() {
		final String coveredUnitType = "user_id";
		final Experiment experiment = coveredBy(
				newExperiment(1, "exp_multi_holdout_unit_types", coveredUnitType, 2), 11, 12, 13);
		final Experiment holdoutA = newHoldout(11, "holdout_11", "type_x", HOLDOUT_B_SEED_HI, HOLDOUT_B_SEED_LO,
				"full");
		final Experiment holdoutB = newHoldout(12, "holdout_12", "type_y", HOLDOUT_B_SEED_HI, HOLDOUT_B_SEED_LO,
				"full");
		final Experiment holdoutC = newHoldout(13, "holdout_13", coveredUnitType, HOLDOUT_B_SEED_HI,
				HOLDOUT_B_SEED_LO, "full");

		final Context context = createReadyContextWithoutUnits(
				contextDataOf(new Experiment[]{holdoutA, holdoutB, holdoutC}, experiment));

		assertEquals(2, context.peekTreatment("exp_multi_holdout_unit_types"));

		context.setUnit(coveredUnitType, UID);

		assertEquals(2, context.getTreatment("exp_multi_holdout_unit_types"));
		assertEquals(4, context.getPendingCount()); // experiment + 3 holdout exposures
	}

	// setUnit must be a no-op with respect to the assignment cache when nothing has been cached
	// yet for this context - no exception, and the subsequent assignment behaves normally.
	@Test
	void setUnitWithNoCachedAssignmentDoesNotThrowAndSubsequentAssignmentIsNormal() {
		final Experiment experiment = coveredBy(newExperiment(1, "exp_no_prior_assignment"), 11);
		final Experiment holdout = newHoldout(11, "holdout_a", UNIT_TYPE, HOLDOUT_B_SEED_HI, HOLDOUT_B_SEED_LO,
				"full");

		final Context context = createReadyContextWithoutUnits(
				contextDataOf(new Experiment[]{holdout}, experiment));

		context.setUnit(UNIT_TYPE, UID); // nothing cached yet - must not throw

		assertEquals(NORMAL_VARIANT, context.getTreatment("exp_no_prior_assignment"));
	}

	// A redundant setUnit call for a unit type that is already installed with the same uid must
	// be a pure no-op: no exception, no re-evaluation, no duplicate exposure.
	@Test
	void setUnitCalledTwiceWithSameUidIsANoOpAndDoesNotThrow() {
		final Experiment experiment = coveredBy(newExperiment(1, "exp_set_unit_twice"), 11);
		final Experiment holdout = newHoldout(11, "holdout_a", HOLDOUT_B_SEED_HI, HOLDOUT_B_SEED_LO); // not held out

		final Context context = createReadyContext(contextDataOf(new Experiment[]{holdout}, experiment));

		assertEquals(NORMAL_VARIANT, context.getTreatment("exp_set_unit_twice"));
		assertEquals(2, context.getPendingCount()); // own exposure + holdout's

		context.setUnit(UNIT_TYPE, UID); // same unit type, same uid: must not throw

		assertEquals(NORMAL_VARIANT, context.getTreatment("exp_set_unit_twice"));
		assertEquals(2, context.getPendingCount()); // unchanged: no duplicate exposure, cache untouched
	}

	// Kills the "evict on holdoutAssignments != null alone" mutant (dropping the check that some
	// entry is actually null): a cached, unexposed assignment whose holdout snapshot is fully
	// resolved (no null entries, because the unit was already present when it was computed) must
	// survive a redundant setUnit call for that same unit type. The distinguishing signal is a
	// same-iteration split refresh, which both experimentMatches and audienceMatches ignore: a
	// retained entry stays pinned to its original variant, while a wrongly-evicted entry recomputes
	// against the new split and lands on a different variant.
	//
	// Fails against `(holdoutAssignments != null) && unitType.equals(assignment.unitType)` alone:
	// the array is non-null (one resolved holdout entry) and the unit type matches, so the entry
	// is evicted even though it holds no null entry, and the redundant setUnit call silently
	// re-decides the experiment.
	@Test
	void setUnitRedundantCallDoesNotEvictFullyResolvedUnexposedHoldoutSnapshot() {
		final Experiment experiment = coveredBy(newExperiment(1, "exp_redundant_set_unit"), 11);
		final Experiment holdout = newHoldout(11, "holdout_a", HOLDOUT_B_SEED_HI, HOLDOUT_B_SEED_LO); // not held out

		final Context context = createReadyContext(contextDataOf(new Experiment[]{holdout}, experiment));

		// resolved with the unit already present: holdoutAssignments holds no null entry.
		assertEquals(NORMAL_VARIANT, context.peekTreatment("exp_redundant_set_unit"));
		assertEquals(0, context.getPendingCount()); // peek never exposes

		final Experiment refreshedExperiment = coveredBy(newExperiment(1, "exp_redundant_set_unit"), 11);
		refreshedExperiment.split = new double[]{1.0, 0.0}; // a recomputation must land in variant 0
		final CompletableFuture<ContextData> refreshFuture = new CompletableFuture<>();
		when(dataProvider.getContextData()).thenReturn(refreshFuture);
		final CompletableFuture<Void> refreshing = context.refreshAsync();
		refreshFuture.complete(contextDataOf(new Experiment[]{holdout}, refreshedExperiment));
		refreshing.join();

		context.setUnit(UNIT_TYPE, UID); // redundant: same unit type, same uid already installed

		assertEquals(NORMAL_VARIANT, context.getTreatment("exp_redundant_set_unit"));
		assertEquals(2, context.getPendingCount()); // own exposure + holdout's

		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
		context.publish();

		final PublishEvent expected = publishedEvent(UID,
				new Exposure(1, "exp_redundant_set_unit", UNIT_TYPE, NORMAL_VARIANT, clock.millis(), true, true, false,
						false, false, false),
				holdoutExposure(11, "holdout_a", 1));
		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(context, expected);
	}

	// Task 3: peekVariableValue populates the same cache as getAssignment/getTreatment, and
	// getVariableValue has its own per-candidate holdout triggering. A late-resolved SUPPRESSING
	// holdout must flip the recomputed decision (fullOn -> held out), which is only observable if
	// the cache entry was actually recomputed rather than live-patched: a live patch-up would
	// leave the fullOn variant (and its variable value) untouched and merely append a
	// contradictory holdout exposure next to it.
	@Test
	void peekVariableValueThenSetUnitThenGetVariableValueRecomputesUnderLateSuppressingHoldout() {
		final String coveredUnitType = "user_id";
		final Experiment experiment = coveredBy(
				newExperiment(1, "exp_var_late_unit_suppress", coveredUnitType, 1), 11);
		experiment.variants[1].config = "{\"my_var\":\"value_from_variant\"}";
		final Experiment holdout = newHoldout(11, "holdout_var_late", coveredUnitType, HOLDOUT_A_SEED_HI,
				HOLDOUT_A_SEED_LO, "full"); // holds UID out once resolvable

		final Context context = createReadyContextWithoutUnits( // no unit installed yet
				contextDataOf(new Experiment[]{holdout}, experiment));

		// fullOn resolves without needing coveredUnitType; the holdout's null snapshot entry
		// never suppresses, so the fullOn variant's variable value wins.
		assertEquals("value_from_variant", context.peekVariableValue("my_var", "default"));
		assertEquals(0, context.getPendingCount());

		context.setUnit(coveredUnitType, UID);

		// recomputed from a coherent decision: the now-resolvable holdout holds UID out, so the
		// experiment is suppressed and control values (no "my_var" key) are returned.
		assertEquals("default", context.getVariableValue("my_var", "default"));
		assertEquals(1, context.getPendingCount()); // holdout's own exposure only

		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
		context.publish();

		final PublishEvent expected = publishedEvent(coveredUnitType, UID,
				holdoutExposure(coveredUnitType, 11, "holdout_var_late", 0));
		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(context, expected);
	}

	// Task 4a: overrides never take a holdoutAssignments snapshot (the override branch in
	// getAssignment never calls getHoldoutAssignment), so invalidateAssignmentsPinnedWithMissingUnit
	// leaves an overridden assignment untouched regardless of a late setUnit - by design, it
	// relies on triggerApplicableHoldoutExposures' live-resolution fallback (pinned == null) at
	// the moment of exposure instead. Pinning this: the unit is absent when the override is
	// peeked, arrives via setUnit before the assignment is ever exposed, and the holdout must
	// still be correctly resolved (live, against the now-present unit) when exposure finally
	// fires.
	@Test
	void setUnitDoesNotEvictOverrideAssignmentAndHoldoutResolvesLiveAtExposureTime() {
		final String coveredUnitType = "user_id";
		final Experiment experiment = coveredBy(
				newExperiment(1, "exp_override_late_unit", coveredUnitType, 0), 11);
		final Experiment holdout = newHoldout(11, "holdout_override_late", coveredUnitType, HOLDOUT_B_SEED_HI,
				HOLDOUT_B_SEED_LO, "full"); // not held out once resolvable

		final ContextConfig config = ContextConfig.create().setOverride("exp_override_late_unit", 3);
		final Context context = createReadyContext(config, // no unit installed yet
				contextDataOf(new Experiment[]{holdout}, experiment));

		assertEquals(3, context.peekTreatment("exp_override_late_unit")); // override wins; not exposed

		context.setUnit(coveredUnitType, UID); // no snapshot to evict; override assignment untouched

		assertEquals(3, context.getTreatment("exp_override_late_unit")); // override still wins
		assertEquals(2, context.getPendingCount()); // override's own exposure + holdout's (live-resolved)

		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
		context.publish();

		final PublishEvent expected = publishedEvent(coveredUnitType, UID,
				new Exposure(1, "exp_override_late_unit", coveredUnitType, 3, clock.millis(), false, true, true,
						false, false, false),
				holdoutExposure(coveredUnitType, 11, "holdout_override_late", 1));
		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(context, expected);
	}

	// Task 4b: a custom assignment takes the ordinary snapshot path (getAssignment's non-override
	// branch), so it is subject to the same null-snapshot eviction as any other covered
	// experiment. The custom assignment can only apply once the experiment's own unit is present
	// (it is read inside the traffic-eligibility branch, which is itself gated on the unit), so
	// initially it cannot apply at all; after the late setUnit forces a coherent recompute, the
	// custom assignment applies normally.
	@Test
	void setUnitEvictsNullSnapshotForCustomAssignmentAndCustomAppliesAfterRecompute() {
		final String coveredUnitType = "user_id";
		final Experiment experiment = coveredBy(
				newExperiment(1, "exp_custom_late_unit", coveredUnitType, 0), 11);
		final Experiment holdout = newHoldout(11, "holdout_custom_late", coveredUnitType, HOLDOUT_B_SEED_HI,
				HOLDOUT_B_SEED_LO, "full"); // not held out once resolvable

		final ContextConfig config = ContextConfig.create().setCustomAssignment("exp_custom_late_unit", 3);
		final Context context = createReadyContext(config, // no unit installed yet
				contextDataOf(new Experiment[]{holdout}, experiment));

		// the custom assignment is read only inside the uid-present branch, so with the unit
		// absent it cannot apply at all: control values.
		assertEquals(0, context.peekTreatment("exp_custom_late_unit"));

		context.setUnit(coveredUnitType, UID); // evicts the null-snapshot cache entry

		assertEquals(3, context.getTreatment("exp_custom_late_unit")); // custom applies now
		assertEquals(2, context.getPendingCount()); // own exposure + holdout's

		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
		context.publish();

		final PublishEvent expected = publishedEvent(coveredUnitType, UID,
				new Exposure(1, "exp_custom_late_unit", coveredUnitType, 3, clock.millis(), true, true, false, false,
						true, false),
				holdoutExposure(coveredUnitType, 11, "holdout_custom_late", 1));
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

	@Test
	void throwingLoggerDoesNotMakeExperimentOrHoldoutExposureRetryable() {
		doThrow(new RuntimeException("boom")).when(eventLogger).handleEvent(any(), any(),
				org.mockito.ArgumentMatchers.argThat(o -> (o instanceof Exposure) && (((Exposure) o).id == 1)));

		final Experiment experiment = coveredBy(newExperiment(1, "exp_throwing_logger_terminal"), 11);
		final Experiment holdout = newHoldout(11, "holdout_terminal", HOLDOUT_B_SEED_HI, HOLDOUT_B_SEED_LO);
		final Context context = createReadyContext(contextDataOf(new Experiment[]{holdout}, experiment));

		assertThrows(RuntimeException.class, () -> context.getTreatment(experiment.name));
		Mockito.reset(eventLogger);
		assertEquals(NORMAL_VARIANT, context.getTreatment(experiment.name));

		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
		context.publish();
		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(context,
				publishedEvent(UID,
						new Exposure(1, experiment.name, UNIT_TYPE, NORMAL_VARIANT, clock.millis(), true, true, false,
								false, false, false),
						holdoutExposure(11, "holdout_terminal", 1)));
	}

	// Regression test: a throwing logger for a suppressed experiment's ONLY holdout exposure must
	// still schedule a flush. There is no ordinary exposure to fall back on for scheduling (the
	// covered experiment is suppressed), so enqueueExposure's own setTimeout() call is the only
	// thing that can ever flush this queued exposure - if it is skipped because logEvent threw,
	// the exposure is stranded in the queue for the lifetime of the context. The exception from
	// the logger must still propagate to the caller.
	//
	// Fails against pre-change code: setTimeout() is called after logEvent() with no finally, so
	// the throw from the holdout exposure's logEvent call skips setTimeout() entirely - the
	// exposure is queued (pendingCount == 1) but scheduler.schedule is never invoked.
	@Test
	void throwingLoggerOnSuppressedExperimentsOnlyHoldoutStillSchedulesFlush() {
		doThrow(new RuntimeException("boom")).when(eventLogger).handleEvent(any(), any(), any(Exposure.class));

		final Experiment experiment = coveredBy(newExperiment(1, "exp_stranded_flush"), 11);
		final Context context = createReadyContext(contextDataOf(
				new Experiment[]{newHoldout(11, "holdout_a", HOLDOUT_A_SEED_HI, HOLDOUT_A_SEED_LO)}, experiment));

		when(scheduler.schedule((Runnable) any(), eq(100L), eq(TimeUnit.MILLISECONDS)))
				.thenReturn(mock(java.util.concurrent.ScheduledFuture.class));

		assertThrows(RuntimeException.class, () -> context.getTreatment("exp_stranded_flush"));

		// the holdout's exposure was queued (this experiment has no exposure of its own - it is
		// suppressed) and a flush was scheduled for it despite the throw.
		assertEquals(1, context.getPendingCount());
		verify(scheduler, Mockito.timeout(5000).times(1)).schedule((Runnable) any(), eq(100L),
				eq(TimeUnit.MILLISECONDS));
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
