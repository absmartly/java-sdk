package com.absmartly.sdk;

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

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java8.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import com.absmartly.sdk.internal.SwitchbackAssigner;
import com.absmartly.sdk.internal.hashing.Hashing;
import com.absmartly.sdk.java.nio.charset.StandardCharsets;
import com.absmartly.sdk.java.time.Clock;
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

	// Periods 0..11 for UID_HASH under seed (SEED_HI, SEED_LO), computed with the platform's
	// independent Python reference (abs tests/scripts/switchback_ref.py, assign_switchback).
	static final int[] EXPECTED_2_ARMS = {1, 0, 0, 1, 1, 0, 1, 0, 1, 0, 1, 0};
	static final int[] EXPECTED_3_ARMS = {1, 2, 0, 2, 1, 0, 2, 0, 1, 1, 2, 0};
	static final int[] EXPECTED_10_ARMS = {1, 2, 5, 3, 7, 4, 8, 0, 6, 9, 4, 6};
	// Same reference fed the raw UID instead of its hash: what a wrong identity would serve.
	static final int[] RAW_UID_3_ARMS = {2, 0, 1, 0, 1, 2, 1, 2, 0, 0, 1, 2};

	static final class MutableClock extends Clock {
		long now;
		// When set, the next read returns this value once, then reads fall back to `now`.
		Long nextRead;

		MutableClock(long now) {
			this.now = now;
		}

		@Override
		public long millis() {
			if (nextRead != null) {
				final long value = nextRead;
				nextRead = null;
				return value;
			}
			return now;
		}
	}

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
		experiment.switchbackClusterUnitType = UNIT_TYPE;
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
		final ContextConfig config = ContextConfig.create();
		if (units != null) {
			config.setUnits(units);
		}
		return Context.create(clock, config, scheduler, CompletableFuture.completedFuture(data), dataProvider,
				eventHandler, null, new DefaultVariableParser(),
				new AudienceMatcher(new DefaultAudienceDeserializer()));
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

	@Test
	void clusterIdentityIsTheSdkUnitHash() {
		assertEquals(UID_HASH, new String(Hashing.hashUnit(UID), StandardCharsets.US_ASCII));

		final Context context = createContext(dataOf(switchback(3, HOUR)));
		final int[] served = peekSchedule(context, "exp_sb", HOUR, 12);

		assertArrayEquals(EXPECTED_3_ARMS, served);
		assertFalse(java.util.Arrays.equals(RAW_UID_3_ARMS, served));
		for (int i = 0; i < 12; ++i) {
			assertEquals(SwitchbackAssigner.assign(UID_HASH, i, ((long) SEED_HI << 32) | (SEED_LO & 0xffffffffL), 3),
					served[i]);
		}
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

	// The anchor is the start instant rounded up to the hour; start..anchor is pre-period.
	@Test
	void unalignedStartIsRoundedUpAndPreAnchorIsIneligible() {
		final Experiment experiment = switchback(2, HOUR);
		final long start = ANCHOR - 1_234_567L;
		experiment.switchbackStartAt = start;
		final Context context = createContext(dataOf(experiment));

		clock.now = start;
		assertEquals(0, context.getTreatment("exp_sb"));
		clock.now = ANCHOR - 1;
		assertEquals(0, context.getTreatment("exp_sb"));
		clock.now = ANCHOR;
		assertEquals(EXPECTED_2_ARMS[0], context.getTreatment("exp_sb"));
		clock.now = ANCHOR + HOUR;
		assertEquals(EXPECTED_2_ARMS[1], context.getTreatment("exp_sb"));

		final Exposure[] exposures = publishExposures(context);
		assertArrayEquals(new Exposure[]{
				exposure(1, "exp_sb", 0, start, true, false),
				exposure(1, "exp_sb", EXPECTED_2_ARMS[0], ANCHOR, true, true),
				exposure(1, "exp_sb", EXPECTED_2_ARMS[1], ANCHOR + HOUR, true, true),
		}, exposures);
	}

	@Test
	void nullStartAtIsIneligibleAndNeverFallsBackToUnitAssignment() {
		final Experiment experiment = switchback(2, HOUR);
		experiment.switchbackStartAt = null;
		final Context context = createContext(dataOf(experiment));

		for (int i = 0; i < 4; ++i) {
			clock.now = ANCHOR + i * HOUR;
			assertEquals(0, context.getTreatment("exp_sb"));
		}

		assertArrayEquals(new Exposure[]{exposure(1, "exp_sb", 0, ANCHOR, true, false)}, publishExposures(context));
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

	@Test
	void missingClusterUnitIsUnassignedWithoutFallback() {
		final Context context = createContext(TestUtils.mapOf("user_id", UID), dataOf(switchback(2, HOUR)));

		for (int i = 0; i < 3; ++i) {
			clock.now = ANCHOR + i * HOUR;
			assertEquals(0, context.peekTreatment("exp_sb"));
			assertEquals(0, context.getTreatment("exp_sb"));
			assertEquals(-1, context.getVariableValue("sb.arm", -1));
		}

		for (final Exposure exposure : publishExposures(context)) {
			assertEquals(0, exposure.variant);
			assertFalse(exposure.assigned);
		}
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

			final Context context = createContext(TestUtils.mapOf(UNIT_TYPE, "store_" + u), dataOf(sb, twin));
			clock.now = ANCHOR;
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
		when(dataProvider.getContextData()).thenReturn(CompletableFuture.completedFuture(dataOf(burnsChanged)));
		context.refresh();
		assertEquals(EXPECTED_2_ARMS[0], context.getTreatment("exp_sb"));
		assertEquals(1, context.getPendingCount());

		// Same period index 0 under both grids, so only the config comparison can invalidate.
		final Experiment periodChanged = switchback(2, 2 * HOUR);
		when(dataProvider.getContextData()).thenReturn(CompletableFuture.completedFuture(dataOf(periodChanged)));
		context.refresh();
		assertEquals(EXPECTED_2_ARMS[0], context.getTreatment("exp_sb"));
		assertEquals(2, context.getPendingCount());

		final Experiment designChanged = switchback(2, 2 * HOUR);
		designChanged.switchbackDesignType = "other_design";
		when(dataProvider.getContextData()).thenReturn(CompletableFuture.completedFuture(dataOf(designChanged)));
		context.refresh();
		context.getTreatment("exp_sb");
		assertEquals(3, context.getPendingCount());

		final Experiment toUnit = switchback(2, 2 * HOUR);
		toUnit.randomizationType = "unit";
		when(dataProvider.getContextData()).thenReturn(CompletableFuture.completedFuture(dataOf(toUnit)));
		context.refresh();
		context.getTreatment("exp_sb");
		assertEquals(4, context.getPendingCount());

		final Experiment backToSwitchback = switchback(2, 2 * HOUR);
		when(dataProvider.getContextData())
				.thenReturn(CompletableFuture.completedFuture(dataOf(backToSwitchback)));
		context.refresh();
		context.getTreatment("exp_sb");
		assertEquals(5, context.getPendingCount());
	}

	@Test
	void refreshSettingStartAtStartsTheGrid() {
		final Experiment pending = switchback(2, HOUR);
		pending.switchbackStartAt = null;
		final Context context = createContext(dataOf(pending));
		clock.now = ANCHOR + 10;
		assertEquals(0, context.getTreatment("exp_sb"));

		when(dataProvider.getContextData()).thenReturn(CompletableFuture.completedFuture(dataOf(switchback(2, HOUR))));
		context.refresh();
		assertEquals(EXPECTED_2_ARMS[0], context.getTreatment("exp_sb"));
		assertEquals(2, context.getPendingCount());
	}

	@Test
	void holdoutSuppressionAndExposureSpanPeriods() {
		final Experiment holdout = new Experiment();
		holdout.id = 11;
		holdout.name = "holdout_a";
		holdout.unitType = UNIT_TYPE;
		holdout.iteration = 1;
		// split[0.1,0.9], seedHi=13, seedLo=111 -> variant 0 (held out) for UID.
		holdout.seedHi = 13;
		holdout.seedLo = 111;
		holdout.split = new double[]{0.1, 0.9};
		holdout.trafficSplit = new double[]{0.0, 1.0};

		final Experiment covered = switchback(2, HOUR);
		covered.holdoutIds = new int[]{11};
		final ContextData data = dataOf(covered);
		data.holdouts = new Experiment[]{holdout};

		final Context context = createContext(data);
		for (int i = 0; i < 3; ++i) {
			clock.now = ANCHOR + i * HOUR;
			assertEquals(0, context.getTreatment("exp_sb"));
		}

		assertArrayEquals(new Exposure[]{
				new Exposure(11, "holdout_a", UNIT_TYPE, 0, ANCHOR, true, true, false, false, false, false)
		}, publishExposures(context));
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
	void perPeriodCacheIsBounded() throws Exception {
		final Context context = createContext(dataOf(switchback(2, HOUR)));
		for (int i = 0; i < 50; ++i) {
			clock.now = ANCHOR + i * HOUR;
			context.getTreatment("exp_sb");
		}
		final Field field = Context.class.getDeclaredField("assignmentCache_");
		field.setAccessible(true);
		assertEquals(2, ((Map<?, ?>) field.get(context)).size());
		assertEquals(50, context.getPendingCount());
	}

	@Test
	void clusterUnitTypeDifferentFromUnitTypeIsIneligible() {
		final Experiment experiment = switchback(2, HOUR);
		experiment.switchbackClusterUnitType = "user_id";
		final Context context = createContext(
				TestUtils.mapOf(UNIT_TYPE, UID, "user_id", "u1"), dataOf(experiment));
		for (int i = 0; i < 3; ++i) {
			clock.now = ANCHOR + i * HOUR;
			assertEquals(0, context.getTreatment("exp_sb"));
		}
		final Exposure[] exposures = publishExposures(context);
		assertEquals(1, exposures.length);
		assertFalse(exposures[0].eligible);
	}

	// The publish payload carries no switchback-specific exposure fields.
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
				+ "\"audienceMismatch\":false},"
				+ "{\"id\":1,\"name\":\"exp_sb\",\"unit\":\"store_id\",\"variant\":" + EXPECTED_2_ARMS[1]
				+ ",\"exposedAt\":" + (ANCHOR + HOUR + 7)
				+ ",\"assigned\":true,\"eligible\":true,\"overridden\":false,\"fullOn\":false,\"custom\":false,"
				+ "\"audienceMismatch\":false}"
				+ "]}", json);
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
	void variableExposureIsStampedWithTheResolutionInstantAcrossHoldoutTriggering() {
		final Experiment holdout = new Experiment();
		holdout.id = 11;
		holdout.name = "holdout_a";
		holdout.unitType = UNIT_TYPE;
		holdout.iteration = 1;
		holdout.seedHi = 13;
		holdout.seedLo = 111;
		// Two arms, all traffic in arm 1: never holds the covered experiment out.
		holdout.split = new double[]{0.0, 1.0};
		holdout.trafficSplit = new double[]{0.0, 1.0};

		final Experiment covered = switchback(2, HOUR);
		covered.holdoutIds = new int[]{11};
		final ContextData data = dataOf(covered);
		data.holdouts = new Experiment[]{holdout};

		final Context context = createContext(data);
		clock.now = ANCHOR + HOUR;
		clock.nextRead = ANCHOR + HOUR - 1;

		assertEquals(EXPECTED_2_ARMS[0], context.getVariableValue("sb.arm", -1));
		final Exposure[] exposures = publishExposures(context);
		assertEquals(2, exposures.length);
		// Holdout membership is not period-scoped and keeps its own enqueue-time reading.
		assertEquals(new Exposure(11, "holdout_a", UNIT_TYPE, 1, ANCHOR + HOUR, true, true, false, false, false,
				false), exposures[0]);
		assertEquals(exposure(1, "exp_sb", EXPECTED_2_ARMS[0], ANCHOR + HOUR - 1, true, true), exposures[1]);
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

	void refreshTo(Context context, Experiment... experiments) {
		when(dataProvider.getContextData()).thenReturn(CompletableFuture.completedFuture(dataOf(experiments)));
		context.refresh();
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
	// override still wins and the holdout still fires exactly once.
	@Test
	void overrideIsReresolvedWhenTheGridChanges() {
		final Experiment holdout = new Experiment();
		holdout.id = 11;
		holdout.name = "holdout_a";
		holdout.unitType = UNIT_TYPE;
		holdout.iteration = 1;
		holdout.seedHi = 13;
		holdout.seedLo = 111;
		holdout.split = new double[]{0.0, 1.0};
		holdout.trafficSplit = new double[]{0.0, 1.0};

		final Experiment original = switchback(3, HOUR);
		original.holdoutIds = new int[]{11};
		final ContextData data = dataOf(original);
		data.holdouts = new Experiment[]{holdout};
		final Context context = createContext(data);
		context.setOverride("exp_sb", 2);

		clock.now = ANCHOR + 10;
		assertEquals(2, context.getTreatment("exp_sb"));
		assertEquals(2, context.getVariableValue("sb.arm", -1));
		assertEquals(2, context.getPendingCount());

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
		assertEquals(3, context.getPendingCount());

		final Exposure[] exposures = publishExposures(context);
		assertEquals(new Exposure(11, "holdout_a", UNIT_TYPE, 1, ANCHOR + 10, true, true, false, false, false,
				false), exposures[1]);
		for (final int i : new int[]{0, 2}) {
			assertEquals(new Exposure(1, "exp_sb", UNIT_TYPE, 2, ANCHOR + 10, false, true, true, false, false,
					false), exposures[i]);
		}
	}
}
