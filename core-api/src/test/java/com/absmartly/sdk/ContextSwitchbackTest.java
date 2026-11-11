package com.absmartly.sdk;

import static com.absmartly.sdk.SwitchbackTestSupport.clusters;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java8.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import com.absmartly.sdk.SwitchbackTestSupport.MutableClock;
import com.absmartly.sdk.internal.SwitchbackAssigner;
import com.absmartly.sdk.internal.VariantAssigner;
import com.absmartly.sdk.internal.hashing.Hashing;
import com.absmartly.sdk.java.nio.charset.StandardCharsets;
import com.absmartly.sdk.json.ContextData;
import com.absmartly.sdk.json.Experiment;
import com.absmartly.sdk.json.ExperimentApplication;
import com.absmartly.sdk.json.ExperimentVariant;
import com.absmartly.sdk.json.Exposure;
import com.absmartly.sdk.json.PublishEvent;

class ContextSwitchbackTest {
	static final String UNIT_TYPE = "store_id";
	static final String UID = "e791e240fcd3df7d238cfc285f475e8152fcc0ec";
	// Hashing.hashUnit(UID): the MD5/base64url string the Collector's UnitHasher also produces.
	static final String UID_HASH = "pAE3a1i5Drs5mKRNq56adA";

	static final long HOUR = 3_600_000L;
	static final long DAY = 24 * HOUR;
	// 2026-01-01T00:00:00Z, hour aligned.
	static final long ANCHOR = 1_767_225_600_000L;

	static final int SEED_HI = -5;
	static final int SEED_LO = -7;

	// The store unit's cluster value: attribute CLUSTER_PATH, set on every default context.
	static final String CLUSTER_PATH = "store_key";
	static final String CLUSTER_KEY = "store-42";
	// Periods 0..11 of the raw cluster key "store-42" under seed (SEED_HI, SEED_LO), computed with
	// an independent Python reference (murmur3 over the platform formulas) that reproduces every
	// switchback/golden-vectors.json case.
	static final int[] EXPECTED_2_ARMS = {0, 1, 0, 1, 1, 0, 1, 0, 1, 0, 0, 1};
	static final int[] EXPECTED_3_ARMS = {0, 2, 1, 2, 1, 0, 1, 2, 0, 1, 0, 2};
	static final int[] EXPECTED_10_ARMS = {8, 3, 7, 4, 2, 0, 5, 1, 9, 6, 3, 7};
	// Same reference fed UID_HASH and the raw UID: what keying by the unit instead of the cluster
	// value would serve.
	static final int[] UID_HASH_3_ARMS = {1, 2, 0, 2, 1, 0, 2, 0, 1, 1, 2, 0};
	static final int[] UID_3_ARMS = {2, 0, 1, 0, 1, 2, 1, 2, 0, 0, 1, 2};

	MutableClock clock;
	ContextDataProvider dataProvider;
	ContextEventHandler eventHandler;
	ScheduledExecutorService scheduler;

	@BeforeEach
	void setUp() {
		clock = new MutableClock(ANCHOR);
		dataProvider = mock(ContextDataProvider.class);
		eventHandler = mock(ContextEventHandler.class);
		scheduler = mock(ScheduledExecutorService.class);
		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.<Void> completedFuture(null));
	}

	static Experiment switchback(int id, String name, int armCount, long periodMs) {
		final Experiment experiment = new Experiment();
		experiment.id = id;
		experiment.name = name;
		experiment.unitType = UNIT_TYPE;
		experiment.iteration = 1;
		experiment.seedHi = SEED_HI;
		experiment.seedLo = SEED_LO;
		experiment.split = new double[armCount];
		experiment.variants = new ExperimentVariant[armCount];
		for (int i = 0; i < armCount; ++i) {
			experiment.split[i] = 1.0 / armCount;
			experiment.variants[i] = new ExperimentVariant(String.valueOf((char) ('A' + i)),
					"{\"sb.arm\":" + i + "}");
		}
		experiment.trafficSeedHi = 1;
		experiment.trafficSeedLo = 2;
		experiment.trafficSplit = new double[]{0.0, 1.0};
		experiment.applications = new ExperimentApplication[]{new ExperimentApplication("website")};
		experiment.randomizationType = "switchback";
		experiment.switchbackClusters = clusters(UNIT_TYPE, CLUSTER_PATH);
		experiment.switchbackStartAt = ANCHOR;
		experiment.switchbackPeriodMs = periodMs;
		experiment.switchbackCarryoverDurationMs = 0L;
		experiment.switchbackBurnInDurationMs = 0L;
		experiment.switchbackBurnOutDurationMs = 0L;
		experiment.switchbackDesignType = "balanced_block";
		experiment.switchbackEstimand = "global_average_treatment_effect";
		return experiment;
	}

	static Experiment switchback(int armCount, long periodMs) {
		return switchback(1, "exp_sb", armCount, periodMs);
	}

	static ContextData dataOf(Experiment... experiments) {
		final ContextData data = new ContextData();
		data.experiments = experiments;
		return data;
	}

	Context createContext(Map<String, String> units, ContextData data) {
		return createContext(units, data, new AudienceMatcher(new DefaultAudienceDeserializer()));
	}

	Context createContext(Map<String, String> units, ContextData data, AudienceMatcher audienceMatcher) {
		final ContextConfig config = ContextConfig.create();
		if (units != null) {
			config.setUnits(units);
		}
		config.setAttribute(CLUSTER_PATH, CLUSTER_KEY);
		return Context.create(clock, config, scheduler, CompletableFuture.completedFuture(data), dataProvider,
				eventHandler, null, new DefaultVariableParser(), audienceMatcher);
	}

	Context createContext(ContextData data) {
		return createContext(TestUtils.mapOf(UNIT_TYPE, UID), data);
	}

	List<PublishEvent> publishAndCapture(Context context, int expectedPublishes) {
		context.publish();
		final ArgumentCaptor<PublishEvent> captor = ArgumentCaptor.forClass(PublishEvent.class);
		verify(eventHandler, Mockito.timeout(5000).times(expectedPublishes)).publish(Mockito.eq(context),
				captor.capture());
		return captor.getAllValues();
	}

	Exposure[] publishExposures(Context context) {
		final List<PublishEvent> events = publishAndCapture(context, 1);
		final Exposure[] exposures = events.get(0).exposures;
		return (exposures != null) ? exposures : new Exposure[0];
	}

	static Exposure exposure(int id, String name, int variant, long exposedAt, boolean assigned, boolean eligible) {
		return new Exposure(id, name, UNIT_TYPE, variant, exposedAt, assigned, eligible, false, false, false, false);
	}

	int[] peekSchedule(Context context, String name, long periodMs, int periods) {
		final int[] result = new int[periods];
		for (int i = 0; i < periods; ++i) {
			clock.now = ANCHOR + i * periodMs;
			result[i] = context.peekTreatment(name);
		}
		return result;
	}

	void refreshTo(Context context, Experiment... experiments) {
		when(dataProvider.getContextData()).thenReturn(CompletableFuture.completedFuture(dataOf(experiments)));
		context.refresh();
	}

	// Holdout 11 for UID under seed (13, 111); split decides whether UID is held out.
	static Experiment holdout(double[] split) {
		final Experiment holdout = new Experiment();
		holdout.id = 11;
		holdout.name = "holdout_a";
		holdout.unitType = UNIT_TYPE;
		holdout.iteration = 1;
		holdout.seedHi = 13;
		holdout.seedLo = 111;
		holdout.split = split;
		holdout.trafficSplit = new double[]{0.0, 1.0};
		return holdout;
	}

	// The cluster identity is the raw attribute value, neither hashed nor the unit uid.
	@Test
	void clusterIdentityIsTheRawClusterValue() {
		assertEquals(UID_HASH, new String(Hashing.hashUnit(UID), StandardCharsets.US_ASCII));

		final Context context = createContext(dataOf(switchback(3, HOUR)));
		final int[] served = peekSchedule(context, "exp_sb", HOUR, 12);

		assertArrayEquals(EXPECTED_3_ARMS, served);
		assertFalse(Arrays.equals(UID_HASH_3_ARMS, served));
		assertFalse(Arrays.equals(UID_3_ARMS, served));
		final String hashedKey = new String(Hashing.hashUnit(CLUSTER_KEY), StandardCharsets.US_ASCII);
		boolean hashedDiffers = false;
		for (int i = 0; i < 12; ++i) {
			assertEquals(SwitchbackAssigner.assign(CLUSTER_KEY, i, ((long) SEED_HI << 32) | (SEED_LO & 0xffffffffL), 3),
					served[i]);
			hashedDiffers |= SwitchbackAssigner.assign(hashedKey, i,
					((long) SEED_HI << 32) | (SEED_LO & 0xffffffffL), 3) != served[i];
		}
		assertTrue(hashedDiffers);
	}

	@Test
	void servesTwoThreeAndTenArmPermutations() {
		final Context context = createContext(dataOf(switchback(1, "exp_2", 2, HOUR),
				switchback(2, "exp_3", 3, HOUR), switchback(3, "exp_10", 10, HOUR)));

		assertArrayEquals(EXPECTED_2_ARMS, peekSchedule(context, "exp_2", HOUR, 12));
		assertArrayEquals(EXPECTED_3_ARMS, peekSchedule(context, "exp_3", HOUR, 12));
		assertArrayEquals(EXPECTED_10_ARMS, peekSchedule(context, "exp_10", HOUR, 12));
	}

	@Test
	void oneHourBoundaryAtExactEdgeMilliseconds() {
		final Context context = createContext(dataOf(switchback(2, HOUR)));

		clock.now = ANCHOR - 1;
		assertEquals(0, context.peekTreatment("exp_sb"));
		clock.now = ANCHOR;
		assertEquals(EXPECTED_2_ARMS[0], context.peekTreatment("exp_sb"));
		clock.now = ANCHOR + HOUR - 1;
		assertEquals(EXPECTED_2_ARMS[0], context.peekTreatment("exp_sb"));
		clock.now = ANCHOR + HOUR;
		assertEquals(EXPECTED_2_ARMS[1], context.peekTreatment("exp_sb"));
		clock.now = ANCHOR + 3 * HOUR - 1;
		assertEquals(EXPECTED_2_ARMS[2], context.peekTreatment("exp_sb"));
		clock.now = ANCHOR + 3 * HOUR;
		assertEquals(EXPECTED_2_ARMS[3], context.peekTreatment("exp_sb"));
	}

	@Test
	void twentyFourHourBoundaryAtExactEdgeMilliseconds() {
		final Context context = createContext(dataOf(switchback(2, DAY)));

		clock.now = ANCHOR - 1;
		assertEquals(0, context.peekTreatment("exp_sb"));
		clock.now = ANCHOR;
		assertEquals(EXPECTED_2_ARMS[0], context.peekTreatment("exp_sb"));
		clock.now = ANCHOR + HOUR;
		assertEquals(EXPECTED_2_ARMS[0], context.peekTreatment("exp_sb"));
		clock.now = ANCHOR + DAY - 1;
		assertEquals(EXPECTED_2_ARMS[0], context.peekTreatment("exp_sb"));
		clock.now = ANCHOR + DAY;
		assertEquals(EXPECTED_2_ARMS[1], context.peekTreatment("exp_sb"));
		clock.now = ANCHOR + 4 * DAY - 1;
		assertEquals(EXPECTED_2_ARMS[3], context.peekTreatment("exp_sb"));
		clock.now = ANCHOR + 4 * DAY;
		assertEquals(EXPECTED_2_ARMS[4], context.peekTreatment("exp_sb"));
	}

	// The Collector serves switchbackStartAt as the anchor without rounding it (the backend freezes
	// hour-aligned anchors); before it the experiment is an ineligible control that records no
	// exposure. An anchor that is not hour aligned is unservable (Registry), never served as-is
	// and never rounded: ineligible control in every period, no exposure.
	@Test
	void unalignedStartIsUnservableAndPreAnchorIsIneligible() {
		final Experiment aligned = switchback(1, "exp_aligned", 2, HOUR);
		aligned.switchbackStartAt = ANCHOR + HOUR;
		final Experiment unaligned = switchback(2, "exp_unaligned", 2, HOUR);
		final long start = ANCHOR + 1_234_567L;
		unaligned.switchbackStartAt = start;
		final Context context = createContext(dataOf(aligned, unaligned));

		for (final long now : new long[]{ANCHOR, start - 1, start, ANCHOR + HOUR - 1}) {
			clock.now = now;
			assertEquals(0, context.getTreatment("exp_aligned"));
			assertEquals(0, context.getTreatment("exp_unaligned"));
		}
		assertEquals(0, context.getPendingCount());
		for (int i = 1; i < 4; ++i) {
			clock.now = ANCHOR + i * HOUR;
			assertEquals(EXPECTED_2_ARMS[i - 1], context.getTreatment("exp_aligned"));
			assertEquals(0, context.getTreatment("exp_unaligned"));
			clock.now = start + i * HOUR;
			assertEquals(0, context.getTreatment("exp_unaligned"));
		}

		assertArrayEquals(new Exposure[]{
				exposure(1, "exp_aligned", EXPECTED_2_ARMS[0], ANCHOR + HOUR, true, true),
				exposure(1, "exp_aligned", EXPECTED_2_ARMS[1], ANCHOR + 2 * HOUR, true, true),
				exposure(1, "exp_aligned", EXPECTED_2_ARMS[2], ANCHOR + 3 * HOUR, true, true),
		}, publishExposures(context));
	}

	// Collector ContextAPISwitchbackTest.anchorNotOnAnHourBoundaryIsUnservableOnGetAndPost, plus the
	// Registry's other anchor bounds: startAt < 0 or >= 10000-01-01T00:00:00Z. Unservable means an
	// ineligible control on resolution ("GET") and no exposure on publish ("POST").
	@Test
	void anchorNotOnAnHourBoundaryIsUnservableOnGetAndPost() {
		final long maxAnchor = 253_402_300_800_000L;
		final long[] unservable = {ANCHOR + 1, ANCHOR - 1, ANCHOR + 30 * 60_000L, ANCHOR + HOUR - 1, -HOUR, -1,
				maxAnchor, maxAnchor + HOUR, Long.MAX_VALUE - (Long.MAX_VALUE % HOUR)};
		for (final long startAt : unservable) {
			reset(eventHandler);
			when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.<Void> completedFuture(null));
			final Experiment experiment = switchback(2, HOUR);
			experiment.switchbackStartAt = startAt;
			// The cluster attribute is set at context creation, before every resolution below.
			clock.now = 0;
			final Context context = createContext(dataOf(experiment));
			for (final long now : new long[]{0, ANCHOR, ANCHOR + 2 * HOUR + 1, startAt, startAt + HOUR,
					maxAnchor + DAY}) {
				clock.now = now;
				assertEquals(0, context.getTreatment("exp_sb"), "startAt " + startAt + " now " + now);
				assertEquals(0, context.getVariableValue("sb.arm", -1));
			}
			assertEquals(0, context.getPendingCount(), "startAt " + startAt);
			context.publish();
			verify(eventHandler, times(0)).publish(any(), any());
		}

		// In-range hour-aligned anchors, including 0 and the last hour before the bound, serve.
		for (final long startAt : new long[]{0, ANCHOR, maxAnchor - HOUR}) {
			final Experiment experiment = switchback(2, HOUR);
			experiment.switchbackStartAt = startAt;
			clock.now = startAt + HOUR;
			final Context context = createContext(dataOf(experiment));
			assertEquals(EXPECTED_2_ARMS[1], context.getTreatment("exp_sb"), "startAt " + startAt);
			assertEquals(1, context.getPendingCount());
		}
	}

	@Test
	void nullStartAtIsIneligibleAndNeverFallsBackToUnitAssignment() {
		final Experiment experiment = switchback(2, HOUR);
		experiment.switchbackStartAt = null;
		final Context context = createContext(dataOf(experiment));

		for (int i = 0; i < 4; ++i) {
			clock.now = ANCHOR + i * HOUR;
			assertEquals(0, context.getTreatment("exp_sb"));
			// Variant 0's own config, like any ineligible assignment.
			assertEquals(0, context.getVariableValue("sb.arm", -1));
		}

		assertEquals(0, context.getPendingCount());
	}

	@Test
	void samePeriodIsCachedAndNextPeriodRollsOverWithOneExposureEach() {
		final Context context = createContext(dataOf(switchback(2, HOUR)));

		clock.now = ANCHOR + 10;
		assertEquals(EXPECTED_2_ARMS[0], context.getTreatment("exp_sb"));
		assertEquals(1, context.getPendingCount());
		clock.now = ANCHOR + HOUR - 1;
		assertEquals(EXPECTED_2_ARMS[0], context.getTreatment("exp_sb"));
		assertEquals(EXPECTED_2_ARMS[0], context.peekTreatment("exp_sb"));
		assertEquals(1, context.getPendingCount());

		clock.now = ANCHOR + HOUR;
		assertEquals(EXPECTED_2_ARMS[1], context.peekTreatment("exp_sb"));
		assertEquals(1, context.getPendingCount());
		assertEquals(EXPECTED_2_ARMS[1], context.getTreatment("exp_sb"));
		assertEquals(2, context.getPendingCount());
		clock.now = ANCHOR + HOUR + 30 * 60_000L;
		assertEquals(EXPECTED_2_ARMS[1], context.getTreatment("exp_sb"));
		assertEquals(2, context.getPendingCount());

		final Exposure[] exposures = publishExposures(context);
		assertArrayEquals(new Exposure[]{
				exposure(1, "exp_sb", EXPECTED_2_ARMS[0], ANCHOR + 10, true, true),
				exposure(1, "exp_sb", EXPECTED_2_ARMS[1], ANCHOR + HOUR, true, true),
		}, exposures);
	}

	@Test
	void variableLookupsFollowThePeriodAndExposeOncePerPeriod() {
		final Context context = createContext(dataOf(switchback(3, HOUR)));

		assertEquals(TestUtils.mapOf("sb.arm", TestUtils.listOf("exp_sb")), context.getVariableKeys());
		assertArrayEquals(new String[]{"exp_sb"}, context.getExperiments());

		for (int i = 0; i < 3; ++i) {
			clock.now = ANCHOR + i * HOUR;
			assertEquals(EXPECTED_3_ARMS[i], context.peekVariableValue("sb.arm", -1));
			assertEquals(EXPECTED_3_ARMS[i], context.getVariableValue("sb.arm", -1));
			assertEquals(EXPECTED_3_ARMS[i], context.getVariableValue("sb.arm", -1));
			assertEquals(EXPECTED_3_ARMS[i], context.getTreatment("exp_sb"));
		}
		assertEquals(3, context.getPendingCount());
	}

	// No unit of a linked type: the experiment does not apply and records nothing. unitType is
	// never a fallback.
	@Test
	void missingClusterUnitIsUnassignedWithoutFallback() {
		final Experiment experiment = switchback(2, HOUR);
		experiment.unitType = "user_id";
		final Context context = createContext(TestUtils.mapOf("user_id", UID), dataOf(experiment));

		for (int i = 0; i < 3; ++i) {
			clock.now = ANCHOR + i * HOUR;
			assertEquals(0, context.peekTreatment("exp_sb"));
			assertEquals(0, context.getTreatment("exp_sb"));
			assertEquals(-1, context.getVariableValue("sb.arm", -1));
		}
		assertEquals(0, context.getPendingCount());

		// Setting a linked unit later makes the experiment apply.
		context.setUnit(UNIT_TYPE, UID);
		assertEquals(EXPECTED_2_ARMS[2], context.getTreatment("exp_sb"));
		assertArrayEquals(new Exposure[]{exposure(1, "exp_sb", EXPECTED_2_ARMS[2], ANCHOR + 2 * HOUR, true, true)},
				publishExposures(context));
	}

	@Test
	void fullOnServesTheFullOnVariantInEveryPeriod() {
		final Experiment experiment = switchback(3, HOUR);
		experiment.fullOnVariant = 2;
		final Context context = createContext(dataOf(experiment));

		for (int i = 0; i < 4; ++i) {
			clock.now = ANCHOR + i * HOUR;
			assertEquals(2, context.getTreatment("exp_sb"));
		}
		for (final Exposure exposure : publishExposures(context)) {
			assertEquals(2, exposure.variant);
			assertTrue(exposure.fullOn);
		}
	}

	@Test
	void overrideAndCustomAssignmentWinInEveryPeriod() {
		final Context context = createContext(dataOf(switchback(1, "exp_override", 3, HOUR),
				switchback(2, "exp_custom", 3, HOUR)));
		context.setOverride("exp_override", 7);
		context.setCustomAssignment("exp_custom", 2);

		for (int i = 0; i < 3; ++i) {
			clock.now = ANCHOR + i * HOUR;
			assertEquals(7, context.getTreatment("exp_override"));
			assertEquals(2, context.getTreatment("exp_custom"));
		}
		for (final Exposure exposure : publishExposures(context)) {
			if (exposure.name.equals("exp_override")) {
				assertTrue(exposure.overridden);
				assertEquals(7, exposure.variant);
			} else {
				assertTrue(exposure.custom);
				assertEquals(2, exposure.variant);
			}
		}
	}

	// Traffic eligibility uses the existing per-unit traffic hash, so it matches a unit-randomized
	// twin with the same traffic seed and stays fixed across periods.
	@Test
	void trafficEligibilityIsClusterSticky() {
		int eligibleCount = 0;
		int ineligibleCount = 0;
		for (int u = 0; u < 24; ++u) {
			reset(eventHandler);
			when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.<Void> completedFuture(null));

			final Experiment sb = switchback(1, "exp_sb", 2, HOUR);
			sb.trafficSplit = new double[]{0.5, 0.5};
			final Experiment twin = switchback(2, "exp_unit", 2, HOUR);
			twin.trafficSplit = new double[]{0.5, 0.5};
			twin.randomizationType = null;
			twin.switchbackStartAt = null;

			clock.now = ANCHOR;
			final Context context = createContext(TestUtils.mapOf(UNIT_TYPE, "store_" + u), dataOf(sb, twin));
			context.getTreatment("exp_unit");
			for (int i = 0; i < 4; ++i) {
				clock.now = ANCHOR + i * HOUR;
				context.getTreatment("exp_sb");
			}

			final Exposure[] exposures = publishExposures(context);
			assertEquals(5, exposures.length);
			final boolean twinEligible = exposures[0].eligible;
			for (int i = 1; i < exposures.length; ++i) {
				assertEquals("exp_sb", exposures[i].name);
				assertEquals(twinEligible, exposures[i].eligible);
				if (!twinEligible) {
					assertEquals(0, exposures[i].variant);
				}
			}
			if (twinEligible) {
				++eligibleCount;
			} else {
				++ineligibleCount;
			}
		}
		assertTrue(eligibleCount > 0);
		assertTrue(ineligibleCount > 0);
	}

	@Test
	void unitExperimentsAreUnaffected() {
		final Experiment unit = switchback(2, HOUR);
		unit.randomizationType = "unit";
		final Experiment absent = switchback(2, "exp_absent", 2, HOUR);
		absent.randomizationType = null;

		final Context context = createContext(dataOf(unit, absent));
		final int variant = context.getTreatment("exp_sb");
		final int absentVariant = context.getTreatment("exp_absent");
		for (int i = 1; i < 6; ++i) {
			clock.now = ANCHOR + i * HOUR;
			assertEquals(variant, context.getTreatment("exp_sb"));
			assertEquals(absentVariant, context.getTreatment("exp_absent"));
		}
		assertEquals(2, context.getPendingCount());
	}

	@Test
	void refreshInvalidatesWhenGridFieldsChangeButNotForAnalysisOnlyFields() {
		final Context context = createContext(dataOf(switchback(2, HOUR)));
		clock.now = ANCHOR + 10;
		assertEquals(EXPECTED_2_ARMS[0], context.getTreatment("exp_sb"));
		assertEquals(1, context.getPendingCount());

		final Experiment burnsChanged = switchback(2, HOUR);
		burnsChanged.switchbackBurnInDurationMs = 600_000L;
		burnsChanged.switchbackBurnOutDurationMs = 300_000L;
		burnsChanged.switchbackCarryoverDurationMs = 900_000L;
		burnsChanged.switchbackEstimand = "other";
		refreshTo(context, burnsChanged);
		assertEquals(EXPECTED_2_ARMS[0], context.getTreatment("exp_sb"));
		assertEquals(1, context.getPendingCount());

		// Same period index 0 under both grids, so only the config comparison can invalidate.
		final Experiment periodChanged = switchback(2, 2 * HOUR);
		refreshTo(context, periodChanged);
		assertEquals(EXPECTED_2_ARMS[0], context.getTreatment("exp_sb"));
		assertEquals(2, context.getPendingCount());

		final Experiment designChanged = switchback(2, 2 * HOUR);
		designChanged.switchbackDesignType = "other_design";
		refreshTo(context, designChanged);
		context.getTreatment("exp_sb");
		assertEquals(3, context.getPendingCount());

		final Experiment toUnit = switchback(2, 2 * HOUR);
		toUnit.randomizationType = "unit";
		refreshTo(context, toUnit);
		context.getTreatment("exp_sb");
		assertEquals(4, context.getPendingCount());

		final Experiment backToSwitchback = switchback(2, 2 * HOUR);
		refreshTo(context, backToSwitchback);
		context.getTreatment("exp_sb");
		assertEquals(5, context.getPendingCount());

		// The cluster map (types, paths and their order) and the allow-list are identity.
		final Experiment pathChanged = switchback(2, 2 * HOUR);
		pathChanged.switchbackClusters = clusters(UNIT_TYPE, "'store_key'");
		refreshTo(context, pathChanged);
		assertEquals(EXPECTED_2_ARMS[0], context.getTreatment("exp_sb"));
		assertEquals(6, context.getPendingCount());

		final Experiment linkAdded = switchback(2, 2 * HOUR);
		linkAdded.switchbackClusters = clusters(UNIT_TYPE, "'store_key'", "device_id", "city");
		refreshTo(context, linkAdded);
		assertEquals(EXPECTED_2_ARMS[0], context.getTreatment("exp_sb"));
		assertEquals(7, context.getPendingCount());

		final Experiment linksReordered = switchback(2, 2 * HOUR);
		linksReordered.switchbackClusters = clusters("device_id", "city", UNIT_TYPE, "'store_key'");
		refreshTo(context, linksReordered);
		assertEquals(EXPECTED_2_ARMS[0], context.getTreatment("exp_sb"));
		assertEquals(8, context.getPendingCount());

		final Experiment valuesSet = switchback(2, 2 * HOUR);
		valuesSet.switchbackClusters = linksReordered.switchbackClusters;
		valuesSet.switchbackClusterValues = new String[]{CLUSTER_KEY};
		refreshTo(context, valuesSet);
		assertEquals(EXPECTED_2_ARMS[0], context.getTreatment("exp_sb"));
		assertEquals(9, context.getPendingCount());

		// An unrelated unitType edit is not identity: switchbacks never read it.
		final Experiment unitTypeChanged = switchback(2, 2 * HOUR);
		unitTypeChanged.switchbackClusters = valuesSet.switchbackClusters;
		unitTypeChanged.switchbackClusterValues = valuesSet.switchbackClusterValues;
		unitTypeChanged.unitType = "anything";
		refreshTo(context, unitTypeChanged);
		assertEquals(EXPECTED_2_ARMS[0], context.getTreatment("exp_sb"));
		assertEquals(9, context.getPendingCount());
	}

	@Test
	void refreshSettingStartAtStartsTheGrid() {
		final Experiment pending = switchback(2, HOUR);
		pending.switchbackStartAt = null;
		final Context context = createContext(dataOf(pending));
		clock.now = ANCHOR + 10;
		assertEquals(0, context.getTreatment("exp_sb"));

		refreshTo(context, switchback(2, HOUR));
		assertEquals(EXPECTED_2_ARMS[0], context.getTreatment("exp_sb"));
		assertEquals(1, context.getPendingCount());
	}

	// Holdouts never apply to a switchback: a holdout that would hold the unit out neither
	// suppresses the switchback nor records its own exposure through it.
	@Test
	void holdoutSuppressionAndExposureSpanPeriods() {
		// split[0.1,0.9] -> variant 0 (held out) for UID.
		final Experiment holdout = holdout(new double[]{0.1, 0.9});

		final Experiment covered = switchback(2, HOUR);
		covered.holdoutIds = new int[]{11};
		final ContextData data = dataOf(covered);
		data.holdouts = new Experiment[]{holdout};

		final Context context = createContext(data);
		for (int i = 0; i < 3; ++i) {
			clock.now = ANCHOR + i * HOUR;
			assertEquals(EXPECTED_2_ARMS[i], context.getTreatment("exp_sb"));
			assertEquals(EXPECTED_2_ARMS[i], context.getVariableValue("sb.arm", -1));
		}

		assertArrayEquals(new Exposure[]{
				exposure(1, "exp_sb", EXPECTED_2_ARMS[0], ANCHOR, true, true),
				exposure(1, "exp_sb", EXPECTED_2_ARMS[1], ANCHOR + HOUR, true, true),
				exposure(1, "exp_sb", EXPECTED_2_ARMS[2], ANCHOR + 2 * HOUR, true, true),
		}, publishExposures(context));
	}

	static final String ASSIGN_VARIANT_1 = "{\"rules\":[{\"type\":\"assign\",\"environments\":[],\"variant\":1}]}";

	// A rule win is published exactly like a unit experiment's: ruleOverride set, assigned false.
	static Exposure ruleExposure(String name, int variant, long exposedAt) {
		return new Exposure(1, name, UNIT_TYPE, variant, exposedAt, false, true, false, false, false, false, true);
	}

	// Collector ContextAPISwitchbackTest.assignmentRuleOverridesTheSchedule: the Collector evaluates
	// assignment rules first for every experiment, switchbacks included, so a matching rule assigns
	// its variant even before the anchor, with ruleOverride set and the exposure recorded.
	@Test
	void assignmentRuleOverridesTheSchedule() {
		final Experiment experiment = switchback(2, HOUR);
		experiment.assignmentRules = ASSIGN_VARIANT_1;
		for (int u = 0; u < 10; ++u) {
			reset(eventHandler);
			when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.<Void> completedFuture(null));
			clock.now = ANCHOR - 1;
			final Context context = createContext(TestUtils.mapOf(UNIT_TYPE, "user-" + u), dataOf(experiment));
			assertEquals(1, context.getTreatment("exp_sb"));
			assertEquals(1, context.getVariableValue("sb.arm", -1));
			// The rule also beats every period's schedule draw.
			for (int i = 0; i < 4; ++i) {
				clock.now = ANCHOR + i * HOUR;
				assertEquals(1, context.getTreatment("exp_sb"));
			}
			final Exposure[] exposures = publishExposures(context);
			assertEquals(5, exposures.length);
			assertEquals(ruleExposure("exp_sb", 1, ANCHOR - 1), exposures[0]);
			for (int i = 0; i < 4; ++i) {
				assertEquals(ruleExposure("exp_sb", 1, ANCHOR + i * HOUR), exposures[i + 1]);
			}
		}
	}

	// Collector ContextAPISwitchbackTest.unservableOnlyReplacesTheVariantDrawNotFullOnOrRules: an
	// unservable schedule (no anchor; a bad period; here also an unaligned anchor) only replaces the
	// schedule draw, so full-on and a matching assignment rule still apply and are recorded.
	@Test
	void unservableOnlyReplacesTheVariantDrawNotFullOnOrRules() {
		final Experiment fullOn = switchback(1, "sb_full_on", 2, HOUR);
		fullOn.switchbackStartAt = null;
		fullOn.fullOnVariant = 1;
		final Experiment rules = switchback(2, "sb_rules", 2, HOUR);
		rules.switchbackPeriodMs = 0L;
		rules.assignmentRules = ASSIGN_VARIANT_1;
		final Experiment unalignedRules = switchback(3, "sb_unaligned_rules", 2, HOUR);
		unalignedRules.switchbackStartAt = ANCHOR + 1;
		unalignedRules.assignmentRules = ASSIGN_VARIANT_1;
		final Context context = createContext(TestUtils.mapOf(UNIT_TYPE, "15"),
				dataOf(fullOn, rules, unalignedRules));

		assertEquals(1, context.getTreatment("sb_full_on"));
		assertEquals(1, context.getTreatment("sb_rules"));
		assertEquals(1, context.getTreatment("sb_unaligned_rules"));
		final Exposure[] exposures = publishExposures(context);
		assertEquals(3, exposures.length);
		assertEquals(new Exposure(1, "sb_full_on", UNIT_TYPE, 1, ANCHOR, true, true, false, true, false, false,
				false), exposures[0]);
		assertEquals(new Exposure(2, "sb_rules", UNIT_TYPE, 1, ANCHOR, false, true, false, false, false, false,
				true), exposures[1]);
		assertEquals(new Exposure(3, "sb_unaligned_rules", UNIT_TYPE, 1, ANCHOR, false, true, false, false, false,
				false, true), exposures[2]);
	}

	// Rules are evaluated only for an eligible cluster: an ineligible cluster stays a silent control
	// even when a rule would match (the Collector returns before assignExperiment).
	@Test
	void assignmentRulesDoNotApplyToAnIneligibleCluster() {
		final Experiment experiment = switchback(2, HOUR);
		experiment.assignmentRules = ASSIGN_VARIANT_1;
		experiment.switchbackClusterValues = new String[]{"other-store"};
		final Context context = createContext(dataOf(experiment));
		assertEquals(0, context.getTreatment("exp_sb"));
		assertEquals(0, context.getPendingCount());
	}

	// A split rule randomizes over the serving unit (the Collector resets its assigner to the serving
	// unit's hash), not over the payload's unitType.
	@Test
	void splitRuleRandomizesOverTheServingUnit() {
		final Experiment experiment = switchback(2, HOUR);
		experiment.unitType = "user_id";
		experiment.assignmentRules = "{\"rules\":[{\"type\":\"split\",\"environments\":[],"
				+ "\"percentages\":\"50/50\"}]}";
		int ones = 0;
		for (int u = 0; u < 32; ++u) {
			reset(eventHandler);
			when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.<Void> completedFuture(null));
			final String uid = "store-" + u;
			final int expected = new VariantAssigner(Hashing.hashUnit(uid)).assign(new double[]{0.5, 0.5}, SEED_HI,
					SEED_LO);
			final Context context = createContext(TestUtils.mapOf(UNIT_TYPE, uid, "user_id", "fixed-user"),
					dataOf(experiment));
			assertEquals(expected, context.getTreatment("exp_sb"), uid);
			assertArrayEquals(new Exposure[]{ruleExposure("exp_sb", expected, ANCHOR)}, publishExposures(context),
					uid);
			ones += expected;
		}
		assertTrue((ones > 0) && (ones < 32));
	}

	// Audience-strict mismatch: control, recorded with audienceMismatch and assigned=false, as for a unit
	// experiment (the Collector's assignExperiment; publish only suppresses a null cluster key).
	@Test
	void audienceStrictMismatchIsRecordedAsControl() {
		final Experiment experiment = switchback(2, HOUR);
		experiment.audience = STRICT_GOLD_AUDIENCE;
		experiment.audienceStrict = true;
		final Context context = createContext(dataOf(experiment));
		clock.now = ANCHOR + HOUR;
		assertEquals(0, context.getTreatment("exp_sb"));
		assertArrayEquals(new Exposure[]{new Exposure(1, "exp_sb", UNIT_TYPE, 0, ANCHOR + HOUR, false, true, false,
				false, false, true, false)}, publishExposures(context));
	}

	// Exact wire form of a switchback strict audience mismatch: assigned=false, audienceMismatch=true,
	// the same flags a unit experiment publishes for it. Runs in period 1, whose draw is 1, so the
	// variant 0 below is the strict mismatch's control and not a draw of arm 0.
	@Test
	void audienceStrictMismatchExposureSerializesExactly() {
		final Experiment experiment = switchback(2, HOUR);
		experiment.audience = STRICT_GOLD_AUDIENCE;
		experiment.audienceStrict = true;
		final Context context = createContext(dataOf(experiment));
		assertEquals(1, EXPECTED_2_ARMS[1]);
		clock.now = ANCHOR + HOUR + 5;
		context.getTreatment("exp_sb");
		clock.now = ANCHOR + HOUR + 9;

		final PublishEvent event = publishAndCapture(context, 1).get(0);
		final String json = new String(new DefaultContextEventSerializer().serialize(event),
				StandardCharsets.UTF_8);
		assertEquals("{\"hashed\":true,\"units\":[{\"type\":\"store_id\",\"uid\":\"" + UID_HASH + "\"}],"
				+ "\"publishedAt\":" + (ANCHOR + HOUR + 9) + ","
				+ "\"exposures\":["
				+ "{\"id\":1,\"name\":\"exp_sb\",\"unit\":\"store_id\",\"variant\":0,\"exposedAt\":"
				+ (ANCHOR + HOUR + 5)
				+ ",\"assigned\":false,\"eligible\":true,\"overridden\":false,\"fullOn\":false,\"custom\":false,"
				+ "\"audienceMismatch\":true,\"ruleOverride\":false}"
				+ "],"
				+ "\"attributes\":[{\"name\":\"" + CLUSTER_PATH + "\",\"value\":\"" + CLUSTER_KEY
				+ "\",\"setAt\":" + ANCHOR + "}]}", json);
	}

	// An audience the default context never matches (no "tier" attribute).
	static final String STRICT_GOLD_AUDIENCE = "{\"filter\":[{\"eq\":[{\"var\":\"tier\"},{\"value\":\"gold\"}]}]}";

	static Exposure strictMismatchExposure(long exposedAt) {
		return new Exposure(1, "exp_sb", UNIT_TYPE, 0, exposedAt, false, true, false, false, false, true, false);
	}

	// The audience check comes before the period check (as in the Collector, whose strict branch returns
	// before the switchback variant is consulted), so a strict mismatch before the anchor is recorded.
	@Test
	void audienceStrictMismatchBeforeTheAnchorIsRecorded() {
		final Experiment experiment = switchback(2, HOUR);
		experiment.audience = STRICT_GOLD_AUDIENCE;
		experiment.audienceStrict = true;
		clock.now = ANCHOR - 1;
		final Context context = createContext(dataOf(experiment));
		assertEquals(0, context.getTreatment("exp_sb"));
		assertArrayEquals(new Exposure[]{strictMismatchExposure(ANCHOR - 1)}, publishExposures(context));
	}

	// Likewise with an unservable schedule (no anchor): the strict mismatch is recorded.
	@Test
	void audienceStrictMismatchWithAnUnservableScheduleIsRecorded() {
		final Experiment experiment = switchback(2, HOUR);
		experiment.audience = STRICT_GOLD_AUDIENCE;
		experiment.audienceStrict = true;
		experiment.switchbackStartAt = null;
		clock.now = ANCHOR + HOUR + 3;
		final Context context = createContext(dataOf(experiment));
		assertEquals(0, context.getTreatment("exp_sb"));
		assertArrayEquals(new Exposure[]{strictMismatchExposure(ANCHOR + HOUR + 3)}, publishExposures(context));
	}

	// A strict-mismatched switchback is not assigned, so on a shared variable key it gives way to the
	// next experiment that declares the key, as a unit experiment does. Only that experiment is exposed.
	@Test
	void strictMismatchedSwitchbackGivesWayOnASharedVariableKey() {
		final Experiment sb = switchback(2, HOUR);
		sb.audience = STRICT_GOLD_AUDIENCE;
		sb.audienceStrict = true;
		final Experiment unit = switchback(2, "exp_unit", 2, HOUR);
		unit.randomizationType = null;
		unit.switchbackClusters = null;
		unit.switchbackStartAt = null;
		unit.switchbackPeriodMs = null;
		unit.variants[0] = new ExperimentVariant("A", "{\"sb.arm\":10}");
		unit.variants[1] = new ExperimentVariant("B", "{\"sb.arm\":11}");
		unit.fullOnVariant = 1;
		clock.now = ANCHOR + HOUR + 3;
		final Context context = createContext(dataOf(sb, unit));
		assertEquals(TestUtils.mapOf("sb.arm", TestUtils.listOf("exp_sb", "exp_unit")), context.getVariableKeys());

		assertEquals(11, context.peekVariableValue("sb.arm", -1));
		assertEquals(0, context.getPendingCount());
		assertEquals(11, context.getVariableValue("sb.arm", -1));
		assertArrayEquals(new Exposure[]{new Exposure(2, "exp_unit", UNIT_TYPE, 1, ANCHOR + HOUR + 3, true, true,
				false, true, false, false, false)}, publishExposures(context));
	}

	// Alone on the key, a strict-mismatched switchback yields the default value and records nothing.
	@Test
	void strictMismatchedSwitchbackAloneOnAVariableKeyReturnsTheDefault() {
		final Experiment sb = switchback(2, HOUR);
		sb.audience = STRICT_GOLD_AUDIENCE;
		sb.audienceStrict = true;
		clock.now = ANCHOR + HOUR + 3;
		final Context context = createContext(dataOf(sb));
		assertEquals(-1, context.peekVariableValue("sb.arm", -1));
		assertEquals(-1, context.getVariableValue("sb.arm", -1));
		assertEquals(0, context.getPendingCount());
	}

	// The branches of the SWITCHBACK.md "Exposure flags" table: every branch an eligible-cluster
	// switchback shares with a unit experiment.
	enum ParityBranch {
		OVERRIDE, OVERRIDE_OVER_STRICT_MISMATCH, RULE, RULE_OVER_STRICT_MISMATCH, STRICT_MISMATCH, STRICT_MISMATCH_OVER_FULL_ON, STRICT_MISMATCH_OVER_TRAFFIC_MISS, NON_STRICT_MISMATCH, FULL_ON, TRAFFIC_MISS, CUSTOM, DRAW;

		void configure(Experiment experiment) {
			switch (this) {
			case OVERRIDE_OVER_STRICT_MISMATCH:
			case RULE_OVER_STRICT_MISMATCH:
			case STRICT_MISMATCH:
				experiment.audience = STRICT_GOLD_AUDIENCE;
				experiment.audienceStrict = true;
				break;
			case STRICT_MISMATCH_OVER_FULL_ON:
				experiment.audience = STRICT_GOLD_AUDIENCE;
				experiment.audienceStrict = true;
				experiment.fullOnVariant = 1;
				break;
			case STRICT_MISMATCH_OVER_TRAFFIC_MISS:
				experiment.audience = STRICT_GOLD_AUDIENCE;
				experiment.audienceStrict = true;
				experiment.trafficSplit = new double[]{1.0, 0.0};
				break;
			case NON_STRICT_MISMATCH:
				experiment.audience = STRICT_GOLD_AUDIENCE;
				break;
			case FULL_ON:
				experiment.fullOnVariant = 1;
				break;
			case TRAFFIC_MISS:
				experiment.trafficSplit = new double[]{1.0, 0.0};
				break;
			default:
				break;
			}
			if ((this == RULE) || (this == RULE_OVER_STRICT_MISMATCH)) {
				experiment.assignmentRules = ASSIGN_VARIANT_1;
			}
		}

		void configure(Context context) {
			if ((this == OVERRIDE) || (this == OVERRIDE_OVER_STRICT_MISMATCH)) {
				context.setOverride("exp_parity", 1);
			} else if (this == CUSTOM) {
				context.setCustomAssignment("exp_parity", 1);
			}
		}
	}

	// The SWITCHBACK.md "Exposure flags" table, one row per branch. Columns: assigned, eligible,
	// overridden, fullOn, custom, audienceMismatch, ruleOverride.
	static final Map<ParityBranch, boolean[]> EXPOSURE_FLAGS = new EnumMap<>(ParityBranch.class);
	static {
		EXPOSURE_FLAGS.put(ParityBranch.OVERRIDE, flags(false, true, true, false, false, false, false));
		EXPOSURE_FLAGS.put(ParityBranch.OVERRIDE_OVER_STRICT_MISMATCH,
				flags(false, true, true, false, false, false, false));
		EXPOSURE_FLAGS.put(ParityBranch.RULE, flags(false, true, false, false, false, false, true));
		EXPOSURE_FLAGS.put(ParityBranch.RULE_OVER_STRICT_MISMATCH,
				flags(false, true, false, false, false, false, true));
		EXPOSURE_FLAGS.put(ParityBranch.STRICT_MISMATCH, flags(false, true, false, false, false, true, false));
		EXPOSURE_FLAGS.put(ParityBranch.STRICT_MISMATCH_OVER_FULL_ON,
				flags(false, true, false, false, false, true, false));
		EXPOSURE_FLAGS.put(ParityBranch.STRICT_MISMATCH_OVER_TRAFFIC_MISS,
				flags(false, true, false, false, false, true, false));
		EXPOSURE_FLAGS.put(ParityBranch.NON_STRICT_MISMATCH, flags(true, true, false, false, false, true, false));
		EXPOSURE_FLAGS.put(ParityBranch.FULL_ON, flags(true, true, false, true, false, false, false));
		EXPOSURE_FLAGS.put(ParityBranch.TRAFFIC_MISS, flags(true, false, false, false, false, false, false));
		EXPOSURE_FLAGS.put(ParityBranch.CUSTOM, flags(true, true, false, false, true, false, false));
		EXPOSURE_FLAGS.put(ParityBranch.DRAW, flags(true, true, false, false, false, false, false));
	}

	// The variant each branch serves at ANCHOR + HOUR + 3 (period 1). Override, rule, full-on and custom
	// all set 1; strict mismatch and traffic miss serve control; the draw (also behind a non-strict
	// mismatch) serves the switchback schedule's period-1 arm. The unit experiment serves the same
	// variant on every branch except the two that draw, where it randomizes over the unit instead.
	static final Map<ParityBranch, Integer> EXPECTED_VARIANT = new EnumMap<>(ParityBranch.class);
	static {
		EXPECTED_VARIANT.put(ParityBranch.OVERRIDE, 1);
		EXPECTED_VARIANT.put(ParityBranch.OVERRIDE_OVER_STRICT_MISMATCH, 1);
		EXPECTED_VARIANT.put(ParityBranch.RULE, 1);
		EXPECTED_VARIANT.put(ParityBranch.RULE_OVER_STRICT_MISMATCH, 1);
		EXPECTED_VARIANT.put(ParityBranch.STRICT_MISMATCH, 0);
		EXPECTED_VARIANT.put(ParityBranch.STRICT_MISMATCH_OVER_FULL_ON, 0);
		EXPECTED_VARIANT.put(ParityBranch.STRICT_MISMATCH_OVER_TRAFFIC_MISS, 0);
		EXPECTED_VARIANT.put(ParityBranch.NON_STRICT_MISMATCH, EXPECTED_2_ARMS[1]);
		EXPECTED_VARIANT.put(ParityBranch.FULL_ON, 1);
		EXPECTED_VARIANT.put(ParityBranch.TRAFFIC_MISS, 0);
		EXPECTED_VARIANT.put(ParityBranch.CUSTOM, 1);
		EXPECTED_VARIANT.put(ParityBranch.DRAW, EXPECTED_2_ARMS[1]);
	}

	static boolean[] flags(boolean... flags) {
		return flags;
	}

	static boolean[] exposureFlags(Exposure exposure) {
		return new boolean[]{exposure.assigned, exposure.eligible, exposure.overridden, exposure.fullOn,
				exposure.custom, exposure.audienceMismatch, exposure.ruleOverride};
	}

	Exposure exposeOnce(Experiment experiment, ParityBranch branch) {
		reset(eventHandler);
		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.<Void> completedFuture(null));
		branch.configure(experiment);
		final Context context = createContext(dataOf(experiment));
		branch.configure(context);
		context.getTreatment("exp_parity");
		final Exposure[] exposures = publishExposures(context);
		assertEquals(1, exposures.length, branch.name());
		return exposures[0];
	}

	// The same branch run on a unit experiment and on an eligible-cluster switchback publishes the same
	// exposure, apart from the unit and the variant, and both match the SWITCHBACK.md table. The
	// variant is checked too: on the switchback for every branch, on the unit wherever it does not draw.
	@ParameterizedTest(name = "{0}")
	@EnumSource(ParityBranch.class)
	void exposureFlagsMatchAUnitExperimentForEveryBranch(ParityBranch branch) {
		clock.now = ANCHOR + HOUR + 3;
		assertEquals(1, EXPECTED_2_ARMS[1]);

		final Experiment unit = switchback(1, "exp_parity", 2, HOUR);
		unit.randomizationType = null;
		unit.switchbackClusters = null;
		unit.switchbackStartAt = null;
		unit.switchbackPeriodMs = null;
		final Exposure unitExposure = exposeOnce(unit, branch);

		final Exposure switchbackExposure = exposeOnce(switchback(1, "exp_parity", 2, HOUR), branch);

		final boolean[] expected = EXPOSURE_FLAGS.get(branch);
		assertArrayEquals(expected, exposureFlags(unitExposure), "unit " + branch);
		assertArrayEquals(expected, exposureFlags(switchbackExposure), "switchback " + branch);
		final int expectedVariant = EXPECTED_VARIANT.get(branch);
		assertEquals(expectedVariant, switchbackExposure.variant, "switchback variant " + branch);
		if ((branch != ParityBranch.DRAW) && (branch != ParityBranch.NON_STRICT_MISMATCH)) {
			assertEquals(expectedVariant, unitExposure.variant, "unit variant " + branch);
		}
		assertEquals(unitExposure.id, switchbackExposure.id);
		assertEquals(unitExposure.name, switchbackExposure.name);
		assertEquals(unitExposure.exposedAt, switchbackExposure.exposedAt);
	}

	// A traffic miss on an eligible cluster is recorded: eligible=false, variant 0, under the serving
	// unit (the Collector's assignExperiment marks it ineligible and publish still emits it; publish
	// only suppresses a null cluster key). An ineligible cluster, by contrast, records nothing.
	@Test
	void trafficMissOnAnEligibleClusterPublishesAnIneligibleExposure() {
		final Experiment miss = switchback(1, "exp_miss", 2, HOUR);
		miss.trafficSplit = new double[]{1.0, 0.0};
		final Experiment ineligibleCluster = switchback(2, "exp_cluster_ineligible", 2, HOUR);
		ineligibleCluster.trafficSplit = new double[]{1.0, 0.0};
		ineligibleCluster.switchbackClusterValues = new String[]{"other-store"};
		final Context context = createContext(dataOf(miss, ineligibleCluster));
		clock.now = ANCHOR + 2 * HOUR + 3;
		assertEquals(0, context.getTreatment("exp_miss"));
		assertEquals(0, context.getTreatment("exp_cluster_ineligible"));
		assertArrayEquals(new Exposure[]{exposure(1, "exp_miss", 0, ANCHOR + 2 * HOUR + 3, true, false)},
				publishExposures(context));
	}

	@Test
	void customFieldValuesAndUnitLookupsWork() {
		final Context context = createContext(dataOf(switchback(2, HOUR)));
		assertEquals(UID, context.getUnit(UNIT_TYPE));
		assertEquals(0, context.getCustomFieldKeys().length);
		assertEquals(null, context.getCustomFieldValue("exp_sb", "missing"));
	}

	// Each experiment keeps entries for at most its two most recent periods.
	@Test
	void perPeriodCacheIsBounded() {
		final Context context = createContext(dataOf(switchback(2, HOUR)));
		for (int i = 0; i < 50; ++i) {
			clock.now = ANCHOR + i * HOUR;
			context.getTreatment("exp_sb");
		}
		assertEquals(2, context.assignmentCacheSizeForTest());
		assertEquals(50, context.getPendingCount());
	}

	// unitType on a switchback payload is never used for assignment: the cluster map decides.
	@Test
	void unitTypeIsNotUsedForSwitchbackAssignment() {
		final Experiment experiment = switchback(2, HOUR);
		experiment.unitType = "user_id";
		final Context context = createContext(
				TestUtils.mapOf(UNIT_TYPE, UID, "user_id", "u1"), dataOf(experiment));
		for (int i = 0; i < 3; ++i) {
			clock.now = ANCHOR + i * HOUR;
			assertEquals(EXPECTED_2_ARMS[i], context.getTreatment("exp_sb"));
		}
		final Exposure[] exposures = publishExposures(context);
		assertEquals(3, exposures.length);
		for (final Exposure exposure : exposures) {
			assertEquals(UNIT_TYPE, exposure.unit);
		}
	}

	// The publish payload carries no switchback-specific exposure or publish fields.
	@Test
	void publishPayloadHasNoNewFields() {
		final Context context = createContext(dataOf(switchback(2, HOUR)));
		clock.now = ANCHOR + 5;
		context.getTreatment("exp_sb");
		clock.now = ANCHOR + HOUR + 7;
		context.getTreatment("exp_sb");
		clock.now = ANCHOR + HOUR + 9;

		final PublishEvent event = publishAndCapture(context, 1).get(0);
		final String json = new String(new DefaultContextEventSerializer().serialize(event),
				StandardCharsets.UTF_8);

		assertEquals("{\"hashed\":true,\"units\":[{\"type\":\"store_id\",\"uid\":\"" + UID_HASH + "\"}],"
				+ "\"publishedAt\":" + (ANCHOR + HOUR + 9) + ","
				+ "\"exposures\":["
				+ "{\"id\":1,\"name\":\"exp_sb\",\"unit\":\"store_id\",\"variant\":" + EXPECTED_2_ARMS[0]
				+ ",\"exposedAt\":" + (ANCHOR + 5)
				+ ",\"assigned\":true,\"eligible\":true,\"overridden\":false,\"fullOn\":false,\"custom\":false,"
				+ "\"audienceMismatch\":false,\"ruleOverride\":false},"
				+ "{\"id\":1,\"name\":\"exp_sb\",\"unit\":\"store_id\",\"variant\":" + EXPECTED_2_ARMS[1]
				+ ",\"exposedAt\":" + (ANCHOR + HOUR + 7)
				+ ",\"assigned\":true,\"eligible\":true,\"overridden\":false,\"fullOn\":false,\"custom\":false,"
				+ "\"audienceMismatch\":false,\"ruleOverride\":false}"
				+ "],"
				// The cluster attribute is published timestamped, as before: the Collector re-resolves
				// the cluster at exposedAt from it.
				+ "\"attributes\":[{\"name\":\"" + CLUSTER_PATH + "\",\"value\":\"" + CLUSTER_KEY
				+ "\",\"setAt\":" + ANCHOR + "}]}", json);
		assertNotEquals(EXPECTED_2_ARMS[0], EXPECTED_2_ARMS[1]);
	}

	@Test
	void periodsPublishedAcrossFlushesStayOnePerPeriod() {
		final Context context = createContext(dataOf(switchback(2, HOUR)));
		final List<Integer> variants = new ArrayList<Integer>();
		clock.now = ANCHOR;
		context.getTreatment("exp_sb");
		context.publish();
		clock.now = ANCHOR + 1;
		context.getTreatment("exp_sb");
		assertEquals(0, context.getPendingCount());
		clock.now = ANCHOR + HOUR;
		context.getTreatment("exp_sb");
		context.publish();

		for (final PublishEvent event : publishAndCapture(context, 2)) {
			for (final Exposure exposure : event.exposures) {
				variants.add(exposure.variant);
			}
		}
		assertEquals(TestUtils.listOf(EXPECTED_2_ARMS[0], EXPECTED_2_ARMS[1]), variants);
		verify(eventHandler, times(2)).publish(any(), any());
	}

	// The clock crosses into period 1 between resolution (first read) and any later read; the
	// exposure must still carry the instant that selected period 0.
	@Test
	void treatmentExposureIsStampedWithTheResolutionInstant() {
		final Context context = createContext(dataOf(switchback(2, HOUR)));
		clock.now = ANCHOR + HOUR;
		clock.nextRead = ANCHOR + HOUR - 1;

		assertEquals(EXPECTED_2_ARMS[0], context.getTreatment("exp_sb"));
		assertArrayEquals(new Exposure[]{exposure(1, "exp_sb", EXPECTED_2_ARMS[0], ANCHOR + HOUR - 1, true, true)},
				publishExposures(context));
	}

	@Test
	void variableExposureIsStampedWithTheResolutionInstant() {
		// Two arms, all traffic in arm 1; holdouts never fire through a switchback.
		final Experiment holdout = holdout(new double[]{0.0, 1.0});

		final Experiment covered = switchback(2, HOUR);
		covered.holdoutIds = new int[]{11};
		final ContextData data = dataOf(covered);
		data.holdouts = new Experiment[]{holdout};

		final Context context = createContext(data);
		clock.now = ANCHOR + HOUR;
		clock.nextRead = ANCHOR + HOUR - 1;

		assertEquals(EXPECTED_2_ARMS[0], context.getVariableValue("sb.arm", -1));
		assertArrayEquals(new Exposure[]{exposure(1, "exp_sb", EXPECTED_2_ARMS[0], ANCHOR + HOUR - 1, true, true)},
				publishExposures(context));
	}

	// A peek caches the period's assignment without exposing; the later getTreatment exposes at
	// its own resolution instant, not the peek's.
	@Test
	void exposureAfterPeekUsesTheExposingCallsInstant() {
		final Context context = createContext(dataOf(switchback(2, HOUR)));
		clock.now = ANCHOR + 10;
		assertEquals(EXPECTED_2_ARMS[0], context.peekTreatment("exp_sb"));
		clock.now = ANCHOR + 20 * 60_000L;
		assertEquals(EXPECTED_2_ARMS[0], context.getTreatment("exp_sb"));
		assertArrayEquals(
				new Exposure[]{exposure(1, "exp_sb", EXPECTED_2_ARMS[0], ANCHOR + 20 * 60_000L, true, true)},
				publishExposures(context));
	}

	// Unit experiments keep stamping at enqueue time.
	@Test
	void unitExposureTimingIsUnchanged() {
		final Experiment unit = switchback(2, HOUR);
		unit.randomizationType = "unit";
		final Context context = createContext(dataOf(unit));
		clock.now = ANCHOR + HOUR;
		clock.nextRead = ANCHOR + HOUR - 1;

		final int variant = context.getTreatment("exp_sb");
		assertArrayEquals(new Exposure[]{exposure(1, "exp_sb", variant, ANCHOR + HOUR, true, true)},
				publishExposures(context));
	}

	// A grid change that lowers the current index below the retained ones must not evict the
	// entry it just installed.
	@Test
	void gridRefreshToALowerIndexExposesOnce() {
		final Context context = createContext(dataOf(switchback(2, HOUR)));
		clock.now = ANCHOR + 99 * HOUR;
		context.getTreatment("exp_sb");
		clock.now = ANCHOR + 100 * HOUR;
		context.getTreatment("exp_sb");
		assertEquals(2, context.getPendingCount());

		final Experiment restarted = switchback(2, HOUR);
		restarted.switchbackStartAt = ANCHOR + 100 * HOUR;
		refreshTo(context, restarted);
		for (int i = 0; i < 3; ++i) {
			assertEquals(EXPECTED_2_ARMS[0], context.getTreatment("exp_sb"));
		}
		assertEquals(3, context.getPendingCount());
	}

	@Test
	void periodLengthRefreshToALowerIndexExposesOnce() {
		final Context context = createContext(dataOf(switchback(2, HOUR)));
		clock.now = ANCHOR + 100 * HOUR;
		context.getTreatment("exp_sb");
		clock.now = ANCHOR + 101 * HOUR;
		context.getTreatment("exp_sb");

		refreshTo(context, switchback(2, 200 * HOUR));
		context.getTreatment("exp_sb");
		context.getTreatment("exp_sb");
		assertEquals(3, context.getPendingCount());
	}

	// Rewinding the clock across retained periods re-exposes each re-entered evicted period at most
	// once, never once per call.
	@Test
	void clockRewindReexposesAnEvictedPeriodAtMostOnce() {
		final Context context = createContext(dataOf(switchback(2, HOUR)));
		for (int i = 0; i < 3; ++i) {
			clock.now = ANCHOR + i * HOUR;
			context.getTreatment("exp_sb");
		}
		assertEquals(3, context.getPendingCount());

		clock.now = ANCHOR;
		for (int i = 0; i < 3; ++i) {
			assertEquals(EXPECTED_2_ARMS[0], context.getTreatment("exp_sb"));
		}
		assertEquals(4, context.getPendingCount());

		// Retained {0, 2}: period 2 is still deduplicated, period 1 was evicted.
		clock.now = ANCHOR + 2 * HOUR;
		context.getTreatment("exp_sb");
		assertEquals(4, context.getPendingCount());
		clock.now = ANCHOR + HOUR;
		for (int i = 0; i < 3; ++i) {
			assertEquals(EXPECTED_2_ARMS[1], context.getTreatment("exp_sb"));
		}
		assertEquals(5, context.getPendingCount());

		final Exposure[] exposures = publishExposures(context);
		assertEquals(exposure(1, "exp_sb", EXPECTED_2_ARMS[0], ANCHOR, true, true), exposures[3]);
		assertEquals(exposure(1, "exp_sb", EXPECTED_2_ARMS[1], ANCHOR + HOUR, true, true), exposures[4]);
	}

	// An overridden switchback slot is re-resolved on a grid change like a normal one; the
	// override still wins and the holdout never fires.
	@Test
	void overrideIsReresolvedWhenTheGridChanges() {
		final Experiment holdout = holdout(new double[]{0.0, 1.0});

		final Experiment original = switchback(3, HOUR);
		original.holdoutIds = new int[]{11};
		final ContextData data = dataOf(original);
		data.holdouts = new Experiment[]{holdout};
		final Context context = createContext(data);
		context.setOverride("exp_sb", 2);

		clock.now = ANCHOR + 10;
		assertEquals(2, context.getTreatment("exp_sb"));
		assertEquals(2, context.getVariableValue("sb.arm", -1));
		assertEquals(1, context.getPendingCount());

		// Same index 0 under the new period length; variant 2 now carries a different value.
		final Experiment regridded = switchback(3, 2 * HOUR);
		regridded.holdoutIds = new int[]{11};
		regridded.variants[2] = new ExperimentVariant("C", "{\"sb.arm\":22}");
		final ContextData refreshed = dataOf(regridded);
		refreshed.holdouts = new Experiment[]{holdout};
		when(dataProvider.getContextData()).thenReturn(CompletableFuture.completedFuture(refreshed));
		context.refresh();

		assertEquals(2, context.getTreatment("exp_sb"));
		assertEquals(22, context.getVariableValue("sb.arm", -1));
		assertEquals(2, context.getPendingCount());

		final Exposure[] exposures = publishExposures(context);
		assertEquals(2, exposures.length);
		for (final int i : new int[]{0, 1}) {
			assertEquals(new Exposure(1, "exp_sb", UNIT_TYPE, 2, ANCHOR + 10, false, true, true, false, false,
					false), exposures[i]);
		}
	}

	// Each identity transition is a new epoch: returning to an identical grid re-resolves instead of
	// resurrecting the slot from before the transition.
	@Test
	void switchbackToUnitToIdenticalSwitchbackReresolves() {
		final Context context = createContext(dataOf(switchback(2, HOUR)));
		clock.now = ANCHOR + 10;
		assertEquals(EXPECTED_2_ARMS[0], context.getTreatment("exp_sb"));
		assertEquals(1, context.getPendingCount());

		final Experiment toUnit = switchback(2, HOUR);
		toUnit.randomizationType = "unit";
		refreshTo(context, toUnit);
		context.getTreatment("exp_sb");
		assertEquals(2, context.getPendingCount());

		refreshTo(context, switchback(2, HOUR));
		assertEquals(EXPECTED_2_ARMS[0], context.getTreatment("exp_sb"));
		assertEquals(EXPECTED_2_ARMS[0], context.getTreatment("exp_sb"));
		assertEquals(3, context.getPendingCount());
	}

	@Test
	void removedAndRestoredSwitchbackReresolves() {
		final Context context = createContext(dataOf(switchback(2, HOUR)));
		clock.now = ANCHOR + 10;
		context.getTreatment("exp_sb");

		refreshTo(context);
		assertEquals(0, context.getTreatment("exp_sb"));
		assertEquals(2, context.getPendingCount());

		refreshTo(context, switchback(2, HOUR));
		assertEquals(EXPECTED_2_ARMS[0], context.getTreatment("exp_sb"));
		assertEquals(3, context.getPendingCount());
	}

	// The pre-anchor bare-name slot shares the lifecycle of the period slots.
	@Test
	void preAnchorSlotIsDroppedWhenTheGridChanges() {
		final Experiment gridA = switchback(2, HOUR);
		gridA.switchbackStartAt = ANCHOR + 10 * HOUR;
		final Context context = createContext(dataOf(gridA));
		clock.now = ANCHOR + 10;
		assertEquals(0, context.getTreatment("exp_sb"));
		assertEquals(0, context.getPendingCount());

		refreshTo(context, switchback(2, HOUR));
		assertEquals(EXPECTED_2_ARMS[0], context.getTreatment("exp_sb"));
		assertEquals(1, context.getPendingCount());

		refreshTo(context, gridA);
		assertEquals(0, context.getTreatment("exp_sb"));
		assertEquals(1, context.getPendingCount());
		assertEquals(0, context.getVariableValue("sb.arm", -1));
		assertEquals(1, context.getPendingCount());
	}

	// Epochs are reconciled lazily; an intermediate state that is never looked up must still end the
	// epoch.
	@Test
	void transitionsWithoutAnInterveningLookupStillReresolve() {
		final Context context = createContext(dataOf(switchback(2, HOUR)));
		clock.now = ANCHOR + 10;
		context.getTreatment("exp_sb");
		assertEquals(1, context.getPendingCount());

		final Experiment toUnit = switchback(2, HOUR);
		toUnit.randomizationType = "unit";
		refreshTo(context, toUnit);
		refreshTo(context, switchback(2, HOUR));
		assertEquals(EXPECTED_2_ARMS[0], context.getTreatment("exp_sb"));
		assertEquals(2, context.getPendingCount());

		refreshTo(context);
		refreshTo(context, switchback(2, HOUR));
		assertEquals(EXPECTED_2_ARMS[0], context.getTreatment("exp_sb"));
		assertEquals(3, context.getPendingCount());

		final Experiment regridded = switchback(2, HOUR);
		regridded.switchbackDesignType = "other";
		refreshTo(context, regridded);
		refreshTo(context, switchback(2, HOUR));
		assertEquals(EXPECTED_2_ARMS[0], context.getTreatment("exp_sb"));
		assertEquals(EXPECTED_2_ARMS[0], context.getTreatment("exp_sb"));
		assertEquals(4, context.getPendingCount());

		// An unchanged identity keeps the epoch.
		refreshTo(context, switchback(2, HOUR));
		context.getTreatment("exp_sb");
		assertEquals(4, context.getPendingCount());
	}

	// Counts assignment-rule evaluations.
	static final class CountingAudienceMatcher extends AudienceMatcher {
		final AtomicInteger ruleEvaluations = new AtomicInteger();

		CountingAudienceMatcher() {
			super(new DefaultAudienceDeserializer());
		}

		@Override
		RuleAction evaluateRules(ParsedRules parsed, String environmentName, Map<String, Object> attributes,
				String experimentName) {
			ruleEvaluations.incrementAndGet();
			return super.evaluateRules(parsed, environmentName, attributes, experimentName);
		}
	}

	static final String ASSIGN_VARIANT_1_TO_US = "{\"rules\":[{\"type\":\"assign\",\"environments\":[],"
			+ "\"conditions\":{\"and\":[{\"eq\":[{\"var\":\"country\"},{\"value\":\"US\"}]}]},"
			+ "\"variant\":1}]}";

	// A unit experiment whose rule outcome changes after setAttribute: the read path evaluates the
	// rules and rejects the slot, the write path does not re-evaluate that same slot, and the rebuild
	// evaluates them once more. Two evaluations, not three.
	@Test
	void staleRuleTransitionEvaluatesTheRulesTwice() {
		final Experiment unit = switchback(2, HOUR);
		unit.randomizationType = "unit";
		unit.assignmentRules = ASSIGN_VARIANT_1_TO_US;
		final CountingAudienceMatcher matcher = new CountingAudienceMatcher();
		final Context context = createContext(TestUtils.mapOf(UNIT_TYPE, UID), dataOf(unit), matcher);
		context.peekTreatment("exp_sb");
		assertEquals(1, matcher.ruleEvaluations.get());

		context.setAttribute("country", "US");
		matcher.ruleEvaluations.set(0);
		assertEquals(1, context.peekTreatment("exp_sb"));
		assertEquals(2, matcher.ruleEvaluations.get());
		// Current again: no further evaluation.
		assertEquals(1, context.peekTreatment("exp_sb"));
		assertEquals(2, matcher.ruleEvaluations.get());
	}

	// A legacy (missing or unreadable cluster map) switchback serves under its declared unitType, so
	// a unitType change on refresh is identity for it, unlike for a readable map.
	@Test
	void legacySwitchbackUnitTypeChangeIsIdentity() {
		final Experiment legacy = switchback(2, HOUR);
		legacy.switchbackClusters = null;
		final Context context = createContext(TestUtils.mapOf(UNIT_TYPE, UID, "user_id", "u1"), dataOf(legacy));
		clock.now = ANCHOR + 10;
		assertEquals(0, context.getTreatment("exp_sb"));
		assertEquals(0, context.getVariableValue("sb.arm", -1));
		assertEquals(UNIT_TYPE, context.switchbackResolutionForTest("exp_sb").unitType);

		final Experiment other = switchback(2, HOUR);
		other.switchbackClusters = null;
		other.unitType = "other";
		refreshTo(context, other);
		assertEquals(0, context.getTreatment("exp_sb"));
		assertEquals(-1, context.getVariableValue("sb.arm", -1));

		final Experiment user = switchback(2, HOUR);
		user.switchbackClusters = null;
		user.unitType = "user_id";
		refreshTo(context, user);
		assertEquals(0, context.getTreatment("exp_sb"));
		assertEquals(0, context.getVariableValue("sb.arm", -1));
		final Context.SwitchbackResolution resolution = context.switchbackResolutionForTest("exp_sb");
		assertEquals("user_id", resolution.unitType);
		assertFalse(resolution.eligible);
		assertTrue(resolution.silent);
		assertEquals(0, context.getPendingCount());
	}

	// An ineligible or absent switchback slot never consulted its rules, so neither a set attribute
	// nor a rules refresh re-evaluates them or changes its result.
	@Test
	void rulesAreNotReevaluatedForAnIneligibleOrAbsentSlot() {
		final Experiment ineligible = switchback(1, "exp_ineligible", 2, HOUR);
		ineligible.assignmentRules = ASSIGN_VARIANT_1;
		ineligible.switchbackClusterValues = new String[]{"other-store"};
		final Experiment absent = switchback(2, "exp_absent", 2, HOUR);
		absent.assignmentRules = ASSIGN_VARIANT_1;
		absent.switchbackClusters = clusters("device_id", CLUSTER_PATH);
		final CountingAudienceMatcher matcher = new CountingAudienceMatcher();
		final Context context = createContext(TestUtils.mapOf(UNIT_TYPE, UID), dataOf(ineligible, absent), matcher);
		clock.now = ANCHOR + 10;
		assertEquals(0, context.getTreatment("exp_ineligible"));
		assertEquals(0, context.getTreatment("exp_absent"));
		assertEquals(0, matcher.ruleEvaluations.get());

		context.setAttribute("x", 1);
		final Experiment ineligibleRefreshed = switchback(1, "exp_ineligible", 2, HOUR);
		ineligibleRefreshed.assignmentRules = "{\"rules\":[{\"type\":\"assign\",\"environments\":[],"
				+ "\"variant\":1,\"name\":\"changed\"}]}";
		ineligibleRefreshed.switchbackClusterValues = ineligible.switchbackClusterValues;
		final Experiment absentRefreshed = switchback(2, "exp_absent", 2, HOUR);
		absentRefreshed.assignmentRules = ineligibleRefreshed.assignmentRules;
		absentRefreshed.switchbackClusters = absent.switchbackClusters;
		refreshTo(context, ineligibleRefreshed, absentRefreshed);

		assertEquals(0, context.getTreatment("exp_ineligible"));
		assertEquals(0, context.getTreatment("exp_absent"));
		assertEquals(0, matcher.ruleEvaluations.get());
		assertEquals(0, context.getPendingCount());
	}

	// A rule-won switchback re-resolved in the same period (an attribute set, a different rule with
	// the same variant) keeps its variant and is exposed once. This pins that rules are not
	// switchback identity; no second exposure in-period. It is not reuse coverage.
	@Test
	void inPeriodRuleRefreshKeepsTheVariantAndExposesOnce() {
		final Experiment experiment = switchback(2, HOUR);
		experiment.assignmentRules = ASSIGN_VARIANT_1;
		final Context context = createContext(dataOf(experiment));
		clock.now = ANCHOR + 5;
		assertEquals(1, context.getTreatment("exp_sb"));

		context.setAttribute("tier", "x");
		final Experiment refreshed = switchback(2, HOUR);
		refreshed.assignmentRules = "{\"rules\":[{\"type\":\"assign\",\"environments\":[],\"variant\":1,"
				+ "\"conditions\":{\"and\":[{\"eq\":[{\"var\":\"tier\"},{\"value\":\"x\"}]}]}}]}";
		refreshTo(context, refreshed);
		clock.now = ANCHOR + 6;
		assertEquals(1, context.getTreatment("exp_sb"));
		assertArrayEquals(new Exposure[]{ruleExposure("exp_sb", 1, ANCHOR + 5)}, publishExposures(context));
	}

	// SDK choice (see SWITCHBACK.md): before the anchor nothing is recorded, a traffic miss included,
	// because the pre-period check precedes traffic. From the anchor the same miss is recorded.
	@Test
	void preAnchorTrafficMissIsSilent() {
		final Experiment experiment = switchback(2, HOUR);
		experiment.trafficSplit = new double[]{1.0, 0.0};
		// The cluster attribute is set before the call, so the cluster itself is eligible.
		clock.now = ANCHOR - 1;
		final Context context = createContext(dataOf(experiment));
		assertEquals(0, context.getTreatment("exp_sb"));
		assertEquals(0, context.getPendingCount());

		// Contrast: at the anchor the cluster is eligible and the draw can run, so the traffic miss
		// is recorded, as eligible=false (period 0 draws 0, so the variant alone cannot show the miss).
		clock.now = ANCHOR;
		assertEquals(0, context.getTreatment("exp_sb"));
		assertArrayEquals(new Exposure[]{exposure(1, "exp_sb", 0, ANCHOR, true, false)}, publishExposures(context));
	}

	// An override on a switchback with no serving unit is exposed under the declared unitType when the
	// cluster map is missing or unreadable (the legacy path's unit), never under null or a cluster
	// map type.
	@Test
	void overrideWithoutAServingUnitOnALegacySwitchbackUsesTheDeclaredUnitType() {
		final Experiment missing = switchback(1, "exp_missing_map", 2, HOUR);
		missing.unitType = "user_id";
		missing.switchbackClusters = null;
		final Experiment unreadable = switchback(2, "exp_unreadable_map", 2, HOUR);
		unreadable.unitType = "user_id";
		unreadable.switchbackClusters = clusters(UNIT_TYPE, "'unterminated");
		final Context context = createContext(dataOf(missing, unreadable));
		context.setOverride("exp_missing_map", 1);
		context.setOverride("exp_unreadable_map", 1);
		clock.now = ANCHOR + 5;
		assertEquals(1, context.getTreatment("exp_missing_map"));
		assertEquals(1, context.getTreatment("exp_unreadable_map"));

		assertArrayEquals(new Exposure[]{
				new Exposure(1, "exp_missing_map", "user_id", 1, ANCHOR + 5, false, true, true, false, false, false),
				new Exposure(2, "exp_unreadable_map", "user_id", 1, ANCHOR + 5, false, true, true, false, false,
						false),
		}, publishExposures(context));
	}

	// Exact wire form of a rule-won and a traffic-miss switchback exposure.
	@Test
	void ruleWonAndTrafficMissExposuresSerializeExactly() {
		final Experiment rule = switchback(1, "exp_rule", 2, HOUR);
		rule.assignmentRules = ASSIGN_VARIANT_1;
		final Experiment miss = switchback(2, "exp_miss", 2, HOUR);
		miss.trafficSplit = new double[]{1.0, 0.0};
		final Context context = createContext(dataOf(rule, miss));
		clock.now = ANCHOR + 5;
		context.getTreatment("exp_rule");
		context.getTreatment("exp_miss");
		clock.now = ANCHOR + 9;

		final PublishEvent event = publishAndCapture(context, 1).get(0);
		final String json = new String(new DefaultContextEventSerializer().serialize(event),
				StandardCharsets.UTF_8);
		assertEquals("{\"hashed\":true,\"units\":[{\"type\":\"store_id\",\"uid\":\"" + UID_HASH + "\"}],"
				+ "\"publishedAt\":" + (ANCHOR + 9) + ","
				+ "\"exposures\":["
				+ "{\"id\":1,\"name\":\"exp_rule\",\"unit\":\"store_id\",\"variant\":1,\"exposedAt\":"
				+ (ANCHOR + 5)
				+ ",\"assigned\":false,\"eligible\":true,\"overridden\":false,\"fullOn\":false,\"custom\":false,"
				+ "\"audienceMismatch\":false,\"ruleOverride\":true},"
				+ "{\"id\":2,\"name\":\"exp_miss\",\"unit\":\"store_id\",\"variant\":0,\"exposedAt\":"
				+ (ANCHOR + 5)
				+ ",\"assigned\":true,\"eligible\":false,\"overridden\":false,\"fullOn\":false,\"custom\":false,"
				+ "\"audienceMismatch\":false,\"ruleOverride\":false}"
				+ "],"
				+ "\"attributes\":[{\"name\":\"" + CLUSTER_PATH + "\",\"value\":\"" + CLUSTER_KEY
				+ "\",\"setAt\":" + ANCHOR + "}]}", json);
	}

	// Audience evaluation runs under contextLock_; this matcher refreshes the context from inside it.
	static final class RefreshingAudienceMatcher extends AudienceMatcher {
		volatile Context context;
		volatile boolean refreshOnNextEvaluation;

		RefreshingAudienceMatcher() {
			super(new DefaultAudienceDeserializer());
		}

		@Override
		public Result evaluate(String audience, Map<String, Object> attributes) {
			if (refreshOnNextEvaluation) {
				refreshOnNextEvaluation = false;
				context.refresh();
			}
			return new Result(true);
		}
	}

	void stubRefreshData(boolean completeOnAnotherThread, ContextData data) {
		if (!completeOnAnotherThread) {
			when(dataProvider.getContextData()).thenReturn(CompletableFuture.completedFuture(data));
			return;
		}
		when(dataProvider.getContextData()).thenAnswer(invocation -> {
			final CompletableFuture<ContextData> future = new CompletableFuture<ContextData>();
			// Completes only once refreshAsync has attached its continuation, so setData runs on
			// this thread rather than inline on the caller.
			final Thread completer = new Thread(() -> {
				while (future.getNumberOfDependents() == 0) {
					Thread.yield();
				}
				future.complete(data);
			});
			completer.setDaemon(true);
			completer.start();
			return future;
		});
	}

	// Runs getTreatment on a daemon worker and fails, rather than hanging the build, if it does not
	// return within the bound.
	int getTreatmentWithin(Context context, String name, long timeoutMs) throws InterruptedException {
		final int[] result = new int[1];
		final Throwable[] failure = new Throwable[1];
		final Thread worker = new Thread(() -> {
			try {
				result[0] = context.getTreatment(name);
			} catch (Throwable t) {
				failure[0] = t;
			}
		});
		worker.setDaemon(true);
		worker.start();
		worker.join(timeoutMs);
		assertFalse(worker.isAlive(), "getTreatment did not return: refresh from audience evaluation deadlocked");
		if (failure[0] != null) {
			throw new AssertionError(failure[0]);
		}
		return result[0];
	}

	void refreshFromAudienceRevalidationCompletes(boolean completeOnAnotherThread) throws InterruptedException {
		final Experiment unit = switchback(2, HOUR);
		unit.randomizationType = "unit";
		unit.audience = "{\"filter\":[{\"value\":true}]}";
		final RefreshingAudienceMatcher matcher = new RefreshingAudienceMatcher();
		final Context context = createContext(TestUtils.mapOf(UNIT_TYPE, UID), dataOf(unit), matcher);
		matcher.context = context;
		stubRefreshData(completeOnAnotherThread, dataOf(unit));

		final int primed = context.getTreatment("exp_sb");
		context.setAttribute("country", "PT");
		matcher.refreshOnNextEvaluation = true;

		assertEquals(primed, getTreatmentWithin(context, "exp_sb", 10_000));
		verify(dataProvider, times(1)).getContextData();
		assertEquals(1, context.getPendingCount());
	}

	@Test
	void refreshFromAudienceRevalidationWithACompletedProviderFutureDoesNotDeadlock()
			throws InterruptedException {
		refreshFromAudienceRevalidationCompletes(false);
	}

	@Test
	void refreshFromAudienceRevalidationWithAnAsyncProviderDoesNotDeadlock() throws InterruptedException {
		refreshFromAudienceRevalidationCompletes(true);
	}

	// First resolution evaluates the audience under the contextLock_ write lock.
	@Test
	void refreshFromAudienceEvaluationOnFirstResolutionWithAnAsyncProviderDoesNotDeadlock()
			throws InterruptedException {
		final Experiment unit = switchback(2, HOUR);
		unit.randomizationType = "unit";
		unit.audience = "{\"filter\":[{\"value\":true}]}";
		final RefreshingAudienceMatcher matcher = new RefreshingAudienceMatcher();
		final Context context = createContext(TestUtils.mapOf(UNIT_TYPE, UID), dataOf(unit), matcher);
		matcher.context = context;
		stubRefreshData(true, dataOf(unit));
		matcher.refreshOnNextEvaluation = true;

		getTreatmentWithin(context, "exp_sb", 10_000);
		verify(dataProvider, times(1)).getContextData();
		assertEquals(1, context.getPendingCount());
	}

	// Holds both revalidating readers at a barrier, so both have rejected the cached slot under
	// contextLock_ read before either can take the write lock.
	static final class BarrierAudienceMatcher extends AudienceMatcher {
		final AtomicInteger evaluations = new AtomicInteger();
		final CyclicBarrier readers = new CyclicBarrier(2);

		BarrierAudienceMatcher() {
			super(new DefaultAudienceDeserializer());
		}

		@Override
		public Result evaluate(String audience, Map<String, Object> attributes) {
			final int call = evaluations.incrementAndGet();
			if (call == 1) {
				return new Result(true);
			}
			if (call <= 3) {
				try {
					readers.await(10, TimeUnit.SECONDS);
				} catch (Exception e) {
					throw new IllegalStateException(e);
				}
			}
			return new Result(false);
		}
	}

	@Test
	void concurrentResolutionOfTheSamePeriodExposesOnce() throws InterruptedException {
		final Experiment experiment = switchback(2, HOUR);
		experiment.audience = "{\"filter\":[{\"value\":true}]}";
		resolvePeriodOneConcurrently(experiment, null, EXPECTED_2_ARMS[1]);
	}

	// The cluster attribute changes before both readers re-resolve the slot, and a third worker
	// keeps writing it meanwhile. The slot keeps the key read at first resolution (Lisboa, variant 1
	// in period 1); Faro is variant 0 there, so a re-read key is visible.
	@Test
	void concurrentResolutionWithConcurrentSetAttributeExposesOnce() throws InterruptedException {
		final Experiment experiment = switchback(2, HOUR);
		experiment.audience = "{\"filter\":[{\"value\":true}]}";
		experiment.switchbackClusters = clusters(UNIT_TYPE, "city");
		resolvePeriodOneConcurrently(experiment, "Faro", 1);
	}

	void resolvePeriodOneConcurrently(Experiment experiment, String concurrentCity, int expectedVariant)
			throws InterruptedException {
		final BarrierAudienceMatcher matcher = new BarrierAudienceMatcher();
		final Context context = createContext(TestUtils.mapOf(UNIT_TYPE, UID), dataOf(experiment), matcher);
		clock.now = ANCHOR + HOUR + 10;
		context.setAttribute("city", "Lisboa");

		// Caches period 1 without exposing it; the attribute change forces both callers to revalidate.
		assertEquals(expectedVariant, context.peekTreatment("exp_sb"));
		context.setAttribute("country", "PT");
		if (concurrentCity != null) {
			context.setAttribute("city", concurrentCity);
		}

		final int[] variants = new int[2];
		final Throwable[] failures = new Throwable[3];
		final Thread[] workers = new Thread[(concurrentCity != null) ? 3 : 2];
		for (int i = 0; i < 2; ++i) {
			final int slot = i;
			workers[i] = new Thread(() -> {
				try {
					variants[slot] = context.getTreatment("exp_sb");
				} catch (Throwable t) {
					failures[slot] = t;
				}
			});
		}
		if (concurrentCity != null) {
			final AtomicInteger writes = new AtomicInteger();
			workers[2] = new Thread(() -> {
				try {
					// Starts once both readers are in the audience barrier, so no reader blocks
					// on a queued writer before the barrier trips.
					while ((matcher.evaluations.get() < 3) && (workers[0].isAlive() || workers[1].isAlive())) {
						Thread.yield();
					}
					while ((workers[0].isAlive() || workers[1].isAlive() || writes.get() < 1000)
							&& writes.get() < 100_000) {
						context.setAttribute("city", concurrentCity);
						writes.incrementAndGet();
					}
				} catch (Throwable t) {
					failures[2] = t;
				}
			});
		}
		for (final Thread worker : workers) {
			worker.setDaemon(true);
			worker.start();
		}
		for (final Thread worker : workers) {
			worker.join(10_000);
			assertFalse(worker.isAlive());
		}
		assertArrayEquals(new Throwable[3], failures);

		assertEquals(expectedVariant, variants[0]);
		assertEquals(expectedVariant, variants[1]);
		assertEquals(1, context.getPendingCount());
		assertArrayEquals(new Exposure[]{new Exposure(1, "exp_sb", UNIT_TYPE, expectedVariant, ANCHOR + HOUR + 10,
				true, true, false, false, false, true)}, publishExposures(context));
	}
}
