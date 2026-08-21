package com.absmartly.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
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
import com.absmartly.sdk.json.ExperimentVariant;
import com.absmartly.sdk.json.Exposure;
import com.absmartly.sdk.json.PublishEvent;
import com.absmartly.sdk.json.Unit;

// A holdout arrives as an ordinary experiment entry (in ContextData.holdouts, not .experiments) so
// its variant semantics are: variant 0 = held out (no experimentation at all), variant 1 = exposed.
// Applicability to a covered experiment is derived client-side from unit type plus the full/full_on
// rule, minus that holdout's own excludedExperimentIds - there is no per-experiment holdoutIds field.
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
				fullOnVariant == 2 ? new ExperimentVariant("B", null) : new ExperimentVariant("B", null)
		};
		experiment.audienceStrict = false;
		experiment.audience = null;
		return experiment;
	}

	static Experiment newHoldout(int id, String name, int seedHi, int seedLo) {
		return newHoldout(id, name, UNIT_TYPE, seedHi, seedLo, "full", null);
	}

	static Experiment newHoldout(int id, String name, String unitType, int seedHi, int seedLo, String holdoutType,
			int[] excludedExperimentIds) {
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
		holdout.excludedExperimentIds = excludedExperimentIds;
		return holdout;
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

	// A held-out unit gets control values and emits zero exposures for the experiment it is held
	// out of.
	@Test
	void heldOutUnitGetsControlValuesAndEmitsNoExposureForCoveredExperiment() {
		final Experiment experiment = newExperiment(1, "exp_holdout_in");
		final Context context = createReadyContext(contextDataOf(
				new Experiment[]{newHoldout(11, "holdout_a", HOLDOUT_A_SEED_HI, HOLDOUT_A_SEED_LO)}, experiment));

		assertEquals(0, context.peekTreatment("exp_holdout_in"));

		context.getTreatment("exp_holdout_in");
		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
		context.publish();

		final PublishEvent expected = publishedEvent(UID, holdoutExposure(11, "holdout_a", 0));
		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(context, expected);
	}

	// The holdout's own exposure is exactly one ordinary exposure with the unit's holdout
	// variant and no holdout-specific fields.
	@Test
	void heldOutUnitEmitsExactlyOneOrdinaryHoldoutExposure() {
		final Experiment experiment = newExperiment(1, "exp_holdout_in");
		final Context context = createReadyContext(contextDataOf(
				new Experiment[]{newHoldout(11, "holdout_a", HOLDOUT_A_SEED_HI, HOLDOUT_A_SEED_LO)}, experiment));

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
		final Experiment experiment = newExperiment(1, "exp_holdout_out");
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

	// An experiment excluded from a holdout is never covered by it and emits normally for
	// both a held-out and a non-held-out unit, even while the same unit is suppressed elsewhere.
	@Test
	void excludedExperimentEmitsNormallyEvenForHeldOutUnit() {
		final Experiment covered = newExperiment(1, "exp_holdout_in");
		final Experiment excluded = newExperiment(2, "exp_excluded");
		final Experiment holdout = newHoldout(11, "holdout_a", UNIT_TYPE, HOLDOUT_A_SEED_HI, HOLDOUT_A_SEED_LO, "full",
				new int[]{2});

		final Context context = createReadyContext(contextDataOf(new Experiment[]{holdout}, covered, excluded));

		assertEquals(0, context.getTreatment("exp_holdout_in")); // suppressed -> control
		assertEquals(NORMAL_VARIANT, context.getTreatment("exp_excluded")); // exclusion -> unaffected

		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
		context.publish();

		final PublishEvent expected = publishedEvent(UID,
				holdoutExposure(11, "holdout_a", 0),
				new Exposure(2, "exp_excluded", UNIT_TYPE, NORMAL_VARIANT, clock.millis(), true, true, false, false,
						false, false));
		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(context, expected);
	}

	// A normal experiment covered by two holdouts is suppressed if the unit is held out by
	// EITHER one (union), and each applicable holdout still emits its own independent exposure.
	@Test
	void unionOfApplicableHoldoutsSuppressesExperimentAndBothEmitOwnExposure() {
		final Experiment experiment = newExperiment(1, "exp_multi_holdout");
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
	// holdouts must still emit their own exposure with the correct variant.
	@Test
	void unionRuleAppliesWhenTheHigherIdHoldoutIsTheOnlyOneHoldingUnitOut() {
		final Experiment experiment = newExperiment(1, "exp_union_high_id_decides");
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
		final Experiment experiment = newExperiment(1, "exp_multi_holdout_miss");
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

	// A full_on holdout is skipped entirely for a non-full-on experiment (no suppression, no
	// exposure triggered), and applies normally to a full-on one; a `full` holdout applies to a
	// full-on experiment regardless of its fullOnVariant.
	@Test
	void fullOnHoldoutSkippedForNonFullOnExperiment() {
		final Experiment experiment = newExperiment(1, "exp_regular");
		final Context context = createReadyContext(contextDataOf(
				new Experiment[]{newHoldout(11, "holdout_fullon", UNIT_TYPE, HOLDOUT_A_SEED_HI, HOLDOUT_A_SEED_LO,
						"full_on", null)},
				experiment));

		// the unit would be held out (matches the holdout's split), but full_on holdouts only
		// cover full-on experiments, so evaluation must proceed normally.
		assertEquals(NORMAL_VARIANT, context.getTreatment("exp_regular"));

		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
		context.publish();

		// the full_on holdout must not fire either - it never became applicable to any evaluated
		// experiment in this context.
		final PublishEvent expected = publishedEvent(UID,
				new Exposure(1, "exp_regular", UNIT_TYPE, NORMAL_VARIANT, clock.millis(), true, true, false, false,
						false, false));
		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(context, expected);
	}

	@Test
	void fullOnHoldoutAppliesToFullOnExperiment() {
		final Experiment experiment = newExperiment(1, "exp_fullon", 2);
		final Context context = createReadyContext(contextDataOf(
				new Experiment[]{newHoldout(11, "holdout_fullon", UNIT_TYPE, HOLDOUT_A_SEED_HI, HOLDOUT_A_SEED_LO,
						"full_on", null)},
				experiment));

		assertEquals(0, context.getTreatment("exp_fullon")); // suppressed -> control
		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
		context.publish();

		final PublishEvent expected = publishedEvent(UID, holdoutExposure(11, "holdout_fullon", 0));
		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(context, expected);
	}

	@Test
	void fullHoldoutAppliesToFullOnExperimentRegardlessOfFullOnVariant() {
		final Experiment experiment = newExperiment(1, "exp_fullon_full_holdout", 2);
		final Context context = createReadyContext(contextDataOf(
				new Experiment[]{newHoldout(11, "holdout_a", HOLDOUT_A_SEED_HI, HOLDOUT_A_SEED_LO)}, experiment));

		assertEquals(0, context.getTreatment("exp_fullon_full_holdout")); // held out despite fullOnVariant=2
	}

	// Holdout applicability is derived from unit type alone; the `applications` field (which
	// the wire contract leaves empty on holdout entries) plays no role in matching.
	@Test
	void applicabilityIgnoresApplicationsFieldOnBothSides() {
		final Experiment experiment = newExperiment(1, "exp_scoped");
		experiment.applications = new ExperimentApplication[]{new ExperimentApplication("mobile")};

		final Experiment holdout = newHoldout(11, "holdout_a", HOLDOUT_A_SEED_HI, HOLDOUT_A_SEED_LO);
		holdout.applications = null; // matches the wire contract's empty applications array

		final Context context = createReadyContext(contextDataOf(new Experiment[]{holdout}, experiment));

		assertEquals(0, context.peekTreatment("exp_scoped")); // still held out despite mismatched applications
	}

	// A holdout only covers experiments sharing its unit type; a matching split/seed on a
	// different unit type must never suppress.
	@Test
	void holdoutDoesNotApplyToDifferentUnitType() {
		final Experiment experiment = newExperiment(1, "exp_session", UNIT_TYPE, 0);
		final Experiment holdout = newHoldout(11, "holdout_user", "user_id", HOLDOUT_A_SEED_HI, HOLDOUT_A_SEED_LO,
				"full", null);

		final Context context = createReadyContext(contextDataOf(new Experiment[]{holdout}, experiment));

		assertEquals(NORMAL_VARIANT, context.peekTreatment("exp_session"));
	}

	// The unit type an experiment/holdout uses need not have any unit configured on this context
	// at all (ContextConfig only sets session_id here). getHoldoutAssignment must treat a missing
	// unit as "not evaluable" - control values, no exception, no holdout exposure - rather than
	// NPE on the missing units_ entry.
	@Test
	void unconfiguredUnitTypeGetsControlValuesAndEmitsNoExposureAtAll() {
		final Experiment experiment = newExperiment(1, "exp_user_holdout", "user_id", 0);
		final Experiment holdout = newHoldout(11, "holdout_user", "user_id", HOLDOUT_A_SEED_HI, HOLDOUT_A_SEED_LO,
				"full", null);

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

	@Test
	void assignsNormallyWhenExperimentUnitTypeHasNoHoldouts() {
		final Experiment experiment = newExperiment(1, "exp_no_matching_holdouts");
		final Experiment holdout = newHoldout(11, "holdout_other", "user_id", HOLDOUT_A_SEED_HI, HOLDOUT_A_SEED_LO,
				"full", null);

		final Context context = createReadyContext(contextDataOf(new Experiment[]{holdout}, experiment));

		assertEquals(NORMAL_VARIANT, context.peekTreatment("exp_no_matching_holdouts"));
	}

	// A custom assignment can never override a held-out unit's variant - holdout precedence
	// beats custom assignments, matching the existing audience/full-on/traffic precedence rules.
	@Test
	void customAssignmentCannotOverrideHeldOutVariant() {
		final Experiment experiment = newExperiment(1, "exp_holdout_custom");

		final ContextConfig config = ContextConfig.create().setUnit(UNIT_TYPE, UID).setCustomAssignment(
				"exp_holdout_custom", 3);
		final Context context = createReadyContext(config, contextDataOf(
				new Experiment[]{newHoldout(11, "holdout_a", HOLDOUT_A_SEED_HI, HOLDOUT_A_SEED_LO)}, experiment));

		assertEquals(0, context.peekTreatment("exp_holdout_custom"));
	}

	@Test
	void reusesCachedHeldOutAssignmentWithCustomAssignment() {
		final Experiment experiment = newExperiment(1, "exp_holdout_custom_cache");

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
		final Experiment experiment = newExperiment(1, "exp_holdout_override");

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
		final Experiment heldOutExperiment = newExperiment(1, "exp_holdout_override_held_out");
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

		final Experiment notHeldOutExperiment = newExperiment(1, "exp_holdout_override_not_held_out");
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
		final Experiment experiment = newExperiment(1, "exp_holdout_audience");
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
		final Experiment experimentA = newExperiment(1, "exp_shared_a");
		final Experiment experimentB = newExperiment(2, "exp_shared_b");
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
		final Experiment experimentA = newExperiment(1, "exp_shared_a");
		final Experiment experimentB = newExperiment(2, "exp_shared_b");
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
		final Experiment experiment = newExperiment(1, "exp_holdout_cache");
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
		final Experiment experiment = newExperiment(1, "exp_holdout_seed_thrash");

		// unit is held out initially (holdout A's seed, iteration 1)
		final Context context = createReadyContext(contextDataOf(
				new Experiment[]{newHoldout(11, "holdout_a", HOLDOUT_A_SEED_HI, HOLDOUT_A_SEED_LO)}, experiment));

		assertEquals(0, context.getTreatment("exp_holdout_seed_thrash")); // held out -> control
		assertEquals(1, context.getPendingCount()); // only the holdout's own exposure (variant 0)

		// same holdout id and iteration, but a seed edit that would flip this unit to variant 1
		// if it were re-assigned.
		final Experiment reseededHoldout = newHoldout(11, "holdout_a", HOLDOUT_B_SEED_HI, HOLDOUT_B_SEED_LO);
		final Experiment refreshedExperiment = newExperiment(1, "exp_holdout_seed_thrash");

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
		final Experiment experiment = newExperiment(1, "exp_holdout_cosmetic");
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
		final Experiment refreshedExperiment = newExperiment(1, "exp_holdout_cosmetic");

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
		final Experiment experiment = newExperiment(1, "exp_holdout_cosmetic_covered");
		final Context context = createReadyContext(UID_NOT_HELD_OUT, contextDataOf(
				new Experiment[]{newHoldout(11, "holdout_a", HOLDOUT_A_SEED_HI, HOLDOUT_A_SEED_LO)}, experiment));

		assertEquals(0, context.getTreatment("exp_holdout_cosmetic_covered")); // not held out
		assertEquals(2, context.getPendingCount()); // own exposure + holdout exposure (variant 1)

		final Experiment cosmeticHoldout = newHoldout(11, "holdout_a_renamed", HOLDOUT_A_SEED_HI,
				HOLDOUT_A_SEED_LO);
		final Experiment refreshedExperiment = newExperiment(1, "exp_holdout_cosmetic_covered");

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
		final Experiment experiment = newExperiment(1, "exp_holdout_iteration");

		// unit is NOT held out initially (holdout B's seed, iteration 1)
		final Context context = createReadyContext(contextDataOf(
				new Experiment[]{newHoldout(11, "holdout_a", HOLDOUT_B_SEED_HI, HOLDOUT_B_SEED_LO)}, experiment));

		assertEquals(NORMAL_VARIANT, context.getTreatment("exp_holdout_iteration"));
		assertEquals(2, context.getPendingCount()); // normal exposure + holdout exposure (variant 1)

		// same holdout id, new iteration with holdout A's seed: a genuine new epoch that now holds
		// the unit out.
		final Experiment newEpochHoldout = newHoldout(11, "holdout_a", HOLDOUT_A_SEED_HI, HOLDOUT_A_SEED_LO);
		newEpochHoldout.iteration = 2;
		final Experiment refreshedExperiment = newExperiment(1, "exp_holdout_iteration");

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
		final Experiment experimentA = newExperiment(1, "exp_holdout_desync_a");
		final Context context = createReadyContext(contextDataOf(
				new Experiment[]{newHoldout(11, "holdout_a", HOLDOUT_A_SEED_HI, HOLDOUT_A_SEED_LO)}, experimentA));

		assertEquals(0, context.getTreatment("exp_holdout_desync_a")); // held out -> control
		assertEquals(1, context.getPendingCount()); // holdout's own exposure (variant 0)

		// same holdout id and iteration, but a seed edit that would flip this unit to variant 1
		// if suppression were recomputed directly - plus a brand-new covered experiment whose
		// Assignment cache has never been populated, forcing the write-lock computation path.
		final Experiment reseededHoldout = newHoldout(11, "holdout_a", HOLDOUT_B_SEED_HI, HOLDOUT_B_SEED_LO);
		final Experiment refreshedExperimentA = newExperiment(1, "exp_holdout_desync_a");
		final Experiment experimentB = newExperiment(2, "exp_holdout_desync_b");

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

	// --- Cross-SDK parity vectors -----------------------------------------------------------
	// Verdicts below were computed offline against the SDK's own MD5 -> base64url-unpadded ->
	// murmur3_32 pipeline (VariantAssigner/UnitHasher, unmodified) and independently against the
	// collector's server-side verdict for the same fixtures (test_holdouts.py ::
	// TestCollectorHoldoutVerdictVectors), pinning unicode unit ids, a seed whose high AND low
	// 32-bit halves both have the sign bit set, a 1% percentage boundary, an assignment-probability
	// boundary immediately either side of 10%, and a unit held out by two holdouts simultaneously.

	static final String VERDICT_UNIT_TYPE = "verdict_unit_type";

	Context verdictContext(String uid, Experiment... holdouts) {
		final Experiment experiment = newExperiment(9, "verdict_vectors_experiment", VERDICT_UNIT_TYPE, 0);
		final ContextConfig config = ContextConfig.create().setUnit(VERDICT_UNIT_TYPE, uid);
		return createReadyContext(config, contextDataOf(holdouts, experiment));
	}

	static Experiment verdictHoldout(int id, int seedHi, int seedLo, double[] split) {
		final Experiment holdout = newHoldout(id, "holdout_" + id, VERDICT_UNIT_TYPE, seedHi, seedLo, "full", null);
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
		final Experiment experimentHeldOut = newExperiment(1, "exp_var_held_out");
		experimentHeldOut.variants[1].config = "{\"var_a\":\"value_a\"}";
		final Context heldOutContext = createReadyContext(contextDataOf(
				new Experiment[]{newHoldout(11, "holdout_a", HOLDOUT_A_SEED_HI, HOLDOUT_A_SEED_LO)},
				experimentHeldOut));

		// suppressed: control values only, but evaluating the variable must still trigger the
		// holdout's own exposure.
		assertEquals("default", heldOutContext.getVariableValue("var_a", "default"));
		assertEquals(1, heldOutContext.getPendingCount()); // holdout exposure only

		final Experiment experimentNotHeldOut = newExperiment(1, "exp_var_not_held_out");
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
		final Experiment suppressedExperiment = newExperiment(1, "exp_var_key_suppressed");
		suppressedExperiment.variants[1].config = "{\"shared\":\"from_suppressed\"}";

		final Experiment assignedExperiment = newExperiment(2, "exp_var_key_assigned");
		assignedExperiment.variants[1].config = "{\"shared\":\"from_assigned\"}";

		final Experiment holdout = newHoldout(11, "holdout_a", UNIT_TYPE, HOLDOUT_A_SEED_HI, HOLDOUT_A_SEED_LO,
				"full", new int[]{2}); // excludes exp_var_key_assigned from coverage

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
		final Experiment suppressedExperiment = newExperiment(1, "exp_var_key_peek_suppressed");
		suppressedExperiment.variants[1].config = "{\"shared_peek\":\"from_suppressed\"}";

		final Experiment assignedExperiment = newExperiment(2, "exp_var_key_peek_assigned");
		assignedExperiment.variants[1].config = "{\"shared_peek\":\"from_assigned\"}";

		final Experiment holdout = newHoldout(11, "holdout_a", UNIT_TYPE, HOLDOUT_A_SEED_HI, HOLDOUT_A_SEED_LO,
				"full", new int[]{2});

		final Context context = createReadyContext(
				contextDataOf(new Experiment[]{holdout}, suppressedExperiment, assignedExperiment));

		assertEquals("from_assigned", context.peekVariableValue("shared_peek", "default"));
		assertEquals(0, context.getPendingCount()); // no exposure of any kind
	}

	// Regression test for the unsorted-exclusion-array fix: the wire does not guarantee
	// excludedExperimentIds is sorted, and isExcluded binary-searches it. setData must normalize
	// the array so exclusions are applied correctly regardless of wire ordering.
	@Test
	void unsortedExcludedExperimentIdsStillExcludeCorrectly() {
		final Experiment covered = newExperiment(1, "exp_holdout_in_unsorted");
		final Experiment excluded = newExperiment(2, "exp_excluded_unsorted");
		final Experiment holdout = newHoldout(11, "holdout_a", UNIT_TYPE, HOLDOUT_A_SEED_HI, HOLDOUT_A_SEED_LO,
				"full", new int[]{9, 5, 2, 7}); // deliberately unsorted; excludes id 2

		final Context context = createReadyContext(contextDataOf(new Experiment[]{holdout}, covered, excluded));

		assertEquals(0, context.getTreatment("exp_holdout_in_unsorted")); // suppressed -> control
		assertEquals(NORMAL_VARIANT, context.getTreatment("exp_excluded_unsorted")); // exclusion unaffected
	}

	// Regression test for the logger-robustness fix: a throwing ContextEventLogger must not stop
	// sibling holdout exposures from being queued. Holdout A (checked first, lower id) has a
	// logger that throws; holdout B (checked second) must still fire.
	@Test
	void throwingLoggerDoesNotPermanentlyLoseSiblingHoldoutExposure() {
		doThrow(new RuntimeException("boom")).when(eventLogger).handleEvent(any(), any(),
				org.mockito.ArgumentMatchers.argThat(o -> (o instanceof Exposure) && (((Exposure) o).id == 11)));

		final Experiment experiment = newExperiment(1, "exp_multi_holdout_throwing_logger");
		final Context context = createReadyContext(contextDataOf(
				new Experiment[]{
						newHoldout(11, "holdout_a", HOLDOUT_A_SEED_HI, HOLDOUT_A_SEED_LO), // holds UID out, throws
						newHoldout(12, "holdout_b", HOLDOUT_B_SEED_HI, HOLDOUT_B_SEED_LO), // does not hold UID out
				}, experiment));

		assertThrows(RuntimeException.class, () -> context.getTreatment("exp_multi_holdout_throwing_logger"));

		// both exposures were queued for publish despite holdout A's logger call throwing.
		assertEquals(2, context.getPendingCount());
	}

	// setData silently drops malformed holdout entries (null, split==null, split empty) rather
	// than indexing them: each case below places the malformed entry at the covered experiment's
	// own unit type, so if the filter regressed to `holdout != null` alone, the malformed entry
	// would become "applicable" and either NPE or crash inside VariantAssigner.assign. Instead
	// the experiment must assign normally, as if no holdout existed at all.
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
		final Experiment experiment = newExperiment(1, "exp_null_split_holdout");
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
		final Experiment experiment = newExperiment(1, "exp_empty_split_holdout");
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
}
