package com.absmartly.sdk;

import static com.absmartly.sdk.ContextSwitchbackTest.ANCHOR;
import static com.absmartly.sdk.ContextSwitchbackTest.HOUR;
import static com.absmartly.sdk.ContextSwitchbackTest.SEED_HI;
import static com.absmartly.sdk.ContextSwitchbackTest.SEED_LO;
import static com.absmartly.sdk.ContextSwitchbackTest.UID;
import static com.absmartly.sdk.ContextSwitchbackTest.UID_HASH;
import static com.absmartly.sdk.ContextSwitchbackTest.UNIT_TYPE;
import static com.absmartly.sdk.ContextSwitchbackTest.dataOf;
import static com.absmartly.sdk.ContextSwitchbackTest.switchback;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java8.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import com.absmartly.sdk.ContextSwitchbackTest.MutableClock;
import com.absmartly.sdk.internal.SwitchbackAssigner;
import com.absmartly.sdk.internal.SwitchbackBuckets;
import com.absmartly.sdk.internal.hashing.Hashing;
import com.absmartly.sdk.java.nio.charset.StandardCharsets;
import com.absmartly.sdk.json.ContextData;
import com.absmartly.sdk.json.Experiment;
import com.absmartly.sdk.json.Exposure;
import com.absmartly.sdk.json.PublishEvent;

// Cluster bucket selection inside Context. Variants are derived from SwitchbackBuckets and
// SwitchbackAssigner, both pinned by the platform golden vectors.
class ContextSwitchbackClusterTest {
	static final long SEED = ((long) SEED_HI << 32) | (SEED_LO & 0xffffffffL);
	// 3 arms over 12 periods: two keys in different buckets are told apart by their schedules.
	static final int ARMS = 3;

	MutableClock clock;
	ContextDataProvider dataProvider;
	ContextEventHandler eventHandler;

	@BeforeEach
	void setUp() {
		clock = new MutableClock(ANCHOR);
		dataProvider = mock(ContextDataProvider.class);
		eventHandler = mock(ContextEventHandler.class);
		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.<Void> completedFuture(null));
	}

	Context createContext(ContextData data) {
		return createContext(TestUtils.mapOf(UNIT_TYPE, UID), data);
	}

	Context createContext(Map<String, String> units, ContextData data) {
		final ContextConfig config = ContextConfig.create();
		config.setUnits(units);
		return Context.create(clock, config, mock(ScheduledExecutorService.class),
				CompletableFuture.completedFuture(data), dataProvider, eventHandler, null,
				new DefaultVariableParser(), new AudienceMatcher(new DefaultAudienceDeserializer()));
	}

	void refreshTo(Context context, Experiment experiment) {
		when(dataProvider.getContextData()).thenReturn(CompletableFuture.completedFuture(dataOf(experiment)));
		context.refresh();
	}

	Exposure[] publishExposures(Context context) {
		context.publish();
		final ArgumentCaptor<PublishEvent> captor = ArgumentCaptor.forClass(PublishEvent.class);
		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(Mockito.eq(context), captor.capture());
		return captor.getValue().exposures;
	}

	static Experiment attributeSwitchback(String path, int count) {
		final Experiment experiment = switchback(ARMS, HOUR);
		experiment.switchbackClusterAttribute = path;
		experiment.switchbackClusterCount = count;
		return experiment;
	}

	static int expected(String key, int count, long periodIndex) {
		final int bucket = SwitchbackBuckets.bucketOf(SEED_HI, SEED_LO, key, count);
		return SwitchbackAssigner.assign(String.valueOf(bucket), periodIndex, SEED, ARMS);
	}

	static int[] expectedSchedule(String key, int count) {
		final int[] result = new int[12];
		for (int i = 0; i < result.length; ++i) {
			result[i] = expected(key, count, i);
		}
		return result;
	}

	int[] peekSchedule(Context context) {
		final int[] result = new int[12];
		for (int i = 0; i < result.length; ++i) {
			clock.now = ANCHOR + i * HOUR;
			result[i] = context.peekTreatment("exp_sb");
		}
		return result;
	}

	static Exposure exposure(int variant, long exposedAt, boolean eligible, boolean audienceMismatch) {
		return new Exposure(1, "exp_sb", UNIT_TYPE, variant, exposedAt, true, eligible, false, false, false,
				audienceMismatch);
	}

	@Test
	void unitSourceHashesThePublishedUnitHashForEveryCount() {
		for (final int count : new int[]{10, 50, 100}) {
			final Experiment experiment = switchback(ARMS, HOUR);
			experiment.switchbackClusterCount = count;
			assertArrayEquals(expectedSchedule(UID_HASH, count), peekSchedule(createContext(dataOf(experiment))));
		}
	}

	@Test
	void attributeSourceHashesTheResolvedAttributeForEveryCount() {
		for (final int count : new int[]{10, 50, 100}) {
			clock.now = ANCHOR;
			final Context context = createContext(dataOf(attributeSwitchback("city", count)));
			context.setAttribute("city", "Lisboa");
			assertArrayEquals(expectedSchedule("Lisboa", count), peekSchedule(context));
		}
	}

	@Test
	void unitsSharingAnAttributeValueShareTheSchedule() {
		final Context lisboa = createContext(dataOf(attributeSwitchback("city", 100)));
		lisboa.setAttribute("city", "Lisboa");
		final Context porto = createContext(dataOf(attributeSwitchback("city", 100)));
		porto.setAttribute("city", "Porto");
		final Context otherLisboa = createContext(TestUtils.mapOf(UNIT_TYPE, "another-store"),
				dataOf(attributeSwitchback("city", 100)));
		otherLisboa.setAttribute("city", "Lisboa");

		assertArrayEquals(expectedSchedule("Lisboa", 100), peekSchedule(lisboa));
		assertArrayEquals(expectedSchedule("Lisboa", 100), peekSchedule(otherLisboa));
		assertArrayEquals(expectedSchedule("Porto", 100), peekSchedule(porto));
		assertNotEquals(SwitchbackBuckets.bucketOf(SEED_HI, SEED_LO, "Lisboa", 100),
				SwitchbackBuckets.bucketOf(SEED_HI, SEED_LO, "Porto", 100));
	}

	@Test
	void nestedAndArrayPathsAndIntegerValuesResolve() {
		clock.now = ANCHOR;
		final Context nested = createContext(dataOf(attributeSwitchback("geo/city", 50)));
		nested.setAttribute("geo", TestUtils.mapOf("city", "Porto"));
		assertArrayEquals(expectedSchedule("Porto", 50), peekSchedule(nested));

		clock.now = ANCHOR;
		final Context array = createContext(dataOf(attributeSwitchback("items/1/city", 50)));
		array.setAttribute("items", TestUtils.listOf(TestUtils.mapOf("city", "Lisbon"),
				TestUtils.mapOf("city", "Faro")));
		assertArrayEquals(expectedSchedule("Faro", 50), peekSchedule(array));

		clock.now = ANCHOR;
		final Context integer = createContext(dataOf(attributeSwitchback("region", 10)));
		integer.setAttribute("region", 42L);
		assertArrayEquals(expectedSchedule("42", 10), peekSchedule(integer));

		// Values are read through their JSON encoding, as the Collector decodes them: an integral
		// BigDecimal becomes an integer, a Character a string, and a Float a non-integral double.
		clock.now = ANCHOR;
		final Context decimal = createContext(dataOf(attributeSwitchback("geo/city", 50)));
		decimal.setAttribute("geo", TestUtils.mapOf("city", new BigDecimal("42")));
		assertArrayEquals(expectedSchedule("42", 50), peekSchedule(decimal));

		clock.now = ANCHOR;
		final Context character = createContext(dataOf(attributeSwitchback("city", 100)));
		character.setAttribute("city", 'c');
		assertArrayEquals(expectedSchedule("c", 100), peekSchedule(character));

		clock.now = ANCHOR + 10;
		final Context floating = createContext(dataOf(attributeSwitchback("city", 100)));
		floating.setAttribute("city", 2.0f);
		assertEquals(0, floating.getTreatment("exp_sb"));
		assertArrayEquals(new Exposure[]{exposure(0, ANCHOR + 10, false, false)}, publishExposures(floating));
	}

	// Attribute values the Collector cannot bucket serve variant 0, not eligible, and never throw.
	@Test
	void unbucketableAttributeValuesAreIneligible() {
		final Object[] values = {null, "", true, 1.5, 2.0, TestUtils.mapOf("a", "b"), TestUtils.listOf("x")};
		for (final Object value : values) {
			final Context context = createContext(dataOf(attributeSwitchback("city", 10)));
			context.setAttribute("city", value);
			clock.now = ANCHOR + 10;
			assertEquals(0, context.getTreatment("exp_sb"), String.valueOf(value));
			// Variant 0's own config, like any ineligible assignment.
			assertEquals(0, context.getVariableValue("sb.arm", -1), String.valueOf(value));
		}

		final Context missing = createContext(dataOf(attributeSwitchback("geo/city", 10)));
		missing.setAttribute("geo", TestUtils.mapOf("country", "PT"));
		clock.now = ANCHOR + 10;
		assertEquals(0, missing.getTreatment("exp_sb"));
		assertArrayEquals(new Exposure[]{exposure(0, ANCHOR + 10, false, false)}, publishExposures(missing));
	}

	// A value with no JSON encoding has no cluster key.
	@Test
	void unencodableAttributeValuesAreIneligible() {
		final Map<String, Object> cyclic = new HashMap<String, Object>();
		cyclic.put("self", cyclic);
		final Object[] values = {cyclic, new Object()};
		for (final Object value : values) {
			final Context context = createContext(dataOf(attributeSwitchback("geo/city", 10)));
			context.setAttribute("geo", value);
			assertEquals(0, context.getTreatment("exp_sb"));
		}
	}

	@Test
	void unsupportedPathsAndDisallowedCountsAreIneligible() {
		final Experiment[] experiments = {attributeSwitchback("items/0", 10), attributeSwitchback("items/-1/city", 10),
				attributeSwitchback("", 10), attributeSwitchback("city", 1000), attributeSwitchback("city", 20),
				attributeSwitchback("city", 0), switchback(ARMS, HOUR)};
		experiments[experiments.length - 1].switchbackClusterCount = null;
		for (final Experiment experiment : experiments) {
			final Context context = createContext(dataOf(experiment));
			context.setAttribute("city", "Lisboa");
			context.setAttribute("items", TestUtils.listOf(TestUtils.mapOf("city", "Lisbon")));
			for (int i = 0; i < 3; ++i) {
				clock.now = ANCHOR + i * HOUR;
				assertEquals(0, context.getTreatment("exp_sb"), experiment.toString());
			}
			assertEquals(1, context.getPendingCount(), experiment.toString());
		}
	}

	// The Collector reads the attribute set at or before the exposure instant, latest setAt first.
	@Test
	void latestAttributeSetAtOrBeforeTheResolutionInstantIsUsed() {
		final Context context = createContext(dataOf(attributeSwitchback("city", 100)));
		clock.now = ANCHOR + 2 * HOUR;
		context.setAttribute("city", "Porto");
		clock.now = ANCHOR + 4 * HOUR;
		context.setAttribute("city", "Lisboa");

		clock.now = ANCHOR + HOUR;
		assertEquals(0, context.peekTreatment("exp_sb"));
		clock.now = ANCHOR + 3 * HOUR;
		assertEquals(expected("Porto", 100, 3), context.peekTreatment("exp_sb"));
		clock.now = ANCHOR + 4 * HOUR;
		assertEquals(expected("Lisboa", 100, 4), context.peekTreatment("exp_sb"));
	}

	// Within a period the bucket is fixed at first resolution; the next period reads
	// the attribute again.
	@Test
	void setAttributeMidPeriodDoesNotReassignWithinThePeriod() {
		final Context context = createContext(dataOf(attributeSwitchback("city", 100)));
		clock.now = ANCHOR + 10;
		context.setAttribute("city", "Lisboa");
		assertEquals(expected("Lisboa", 100, 0), context.getTreatment("exp_sb"));

		clock.now = ANCHOR + 20;
		context.setAttribute("city", "Porto");
		assertEquals(expected("Lisboa", 100, 0), context.getTreatment("exp_sb"));
		assertEquals(expected("Lisboa", 100, 0), context.peekTreatment("exp_sb"));
		assertEquals(1, context.getPendingCount());

		final int periods = 12;
		int differing = 0;
		for (int i = 1; i < periods; ++i) {
			clock.now = ANCHOR + i * HOUR;
			assertEquals(expected("Porto", 100, i), context.getTreatment("exp_sb"));
			if (expected("Porto", 100, i) != expected("Lisboa", 100, i)) {
				++differing;
			}
		}
		assertNotEquals(0, differing);
		assertEquals(periods, context.getPendingCount());
	}

	// A unit first resolved without a key stays ineligible for the rest of that period.
	@Test
	void attributeSetAfterAnIneligibleResolutionWaitsForTheNextPeriod() {
		final Context context = createContext(dataOf(attributeSwitchback("city", 100)));
		clock.now = ANCHOR + 10;
		assertEquals(0, context.getTreatment("exp_sb"));
		context.setAttribute("city", "Lisboa");
		clock.now = ANCHOR + 20;
		assertEquals(0, context.getTreatment("exp_sb"));
		assertEquals(1, context.getPendingCount());

		clock.now = ANCHOR + HOUR;
		assertEquals(expected("Lisboa", 100, 1), context.getTreatment("exp_sb"));
		assertArrayEquals(new Exposure[]{exposure(0, ANCHOR + 10, false, false),
				exposure(expected("Lisboa", 100, 1), ANCHOR + HOUR, true, false)}, publishExposures(context));
	}

	// A slot replaced through an ineligible resolution still keeps the period's first key.
	@Test
	void trafficChangesWithinThePeriodKeepThePeriodsBucket() {
		final Context context = createContext(dataOf(attributeSwitchback("city", 100)));
		clock.now = ANCHOR + 10;
		context.setAttribute("city", "Lisboa");
		assertEquals(expected("Lisboa", 100, 0), context.getTreatment("exp_sb"));

		clock.now = ANCHOR + 20;
		context.setAttribute("city", "Porto");
		final Experiment noTraffic = attributeSwitchback("city", 100);
		noTraffic.trafficSplit = new double[]{1.0, 0.0};
		refreshTo(context, noTraffic);
		assertEquals(0, context.getTreatment("exp_sb"));

		refreshTo(context, attributeSwitchback("city", 100));
		assertEquals(expected("Lisboa", 100, 0), context.getTreatment("exp_sb"));
		assertEquals(3, context.getPendingCount());

		clock.now = ANCHOR + HOUR;
		assertEquals(expected("Porto", 100, 1), context.getTreatment("exp_sb"));
	}

	// A traffic miss never reads the key, so traffic restored later in the period reads it then.
	@Test
	void trafficRestoredWithinThePeriodReadsTheKey() {
		final Experiment noTraffic = attributeSwitchback("city", 100);
		noTraffic.trafficSplit = new double[]{1.0, 0.0};
		final Context context = createContext(dataOf(noTraffic));
		clock.now = ANCHOR + 10;
		context.setAttribute("city", "Porto");
		assertEquals(0, context.getTreatment("exp_sb"));

		clock.now = ANCHOR + 20;
		refreshTo(context, attributeSwitchback("city", 100));
		assertEquals(1, expected("Porto", 100, 0));
		assertEquals(expected("Porto", 100, 0), context.getTreatment("exp_sb"));
		assertArrayEquals(new Exposure[]{exposure(0, ANCHOR + 10, false, false),
				exposure(expected("Porto", 100, 0), ANCHOR + 20, true, false)}, publishExposures(context));
	}

	// An audience change re-resolves the period slot (new exposure state) but keeps its bucket.
	@Test
	void audienceRevalidationAfterSetAttributeKeepsThePeriodsBucket() {
		final Experiment experiment = attributeSwitchback("city", 100);
		experiment.audience = "{\"filter\":[{\"eq\":[{\"var\":\"tier\"},{\"value\":\"gold\"}]}]}";
		final Context context = createContext(dataOf(experiment));
		clock.now = ANCHOR + 10;
		context.setAttribute("city", "Lisboa");
		context.setAttribute("tier", "gold");
		assertEquals(expected("Lisboa", 100, 0), context.getTreatment("exp_sb"));

		clock.now = ANCHOR + 20;
		context.setAttribute("city", "Porto");
		context.setAttribute("tier", "silver");
		assertEquals(expected("Lisboa", 100, 0), context.getTreatment("exp_sb"));

		assertArrayEquals(new Exposure[]{exposure(expected("Lisboa", 100, 0), ANCHOR + 10, true, false),
				exposure(expected("Lisboa", 100, 0), ANCHOR + 20, true, true)}, publishExposures(context));
	}

	// A slot first resolved without a key carries that missing key into its replacement.
	@Test
	void audienceRevalidationAfterAnIneligibleResolutionKeepsTheMissingKey() {
		final Experiment experiment = attributeSwitchback("city", 100);
		experiment.audience = "{\"filter\":[{\"eq\":[{\"var\":\"tier\"},{\"value\":\"gold\"}]}]}";
		final Context context = createContext(dataOf(experiment));
		clock.now = ANCHOR + 10;
		context.setAttribute("tier", "gold");
		assertEquals(0, context.getTreatment("exp_sb"));

		clock.now = ANCHOR + 20;
		context.setAttribute("city", "Porto");
		context.setAttribute("tier", "silver");
		assertEquals(1, expected("Porto", 100, 0));
		assertEquals(0, context.getTreatment("exp_sb"));
		assertArrayEquals(new Exposure[]{exposure(0, ANCHOR + 10, false, false),
				exposure(0, ANCHOR + 20, false, true)}, publishExposures(context));

		clock.now = ANCHOR + HOUR;
		assertEquals(expected("Porto", 100, 1), context.peekTreatment("exp_sb"));
		// Period 1 of Porto is variant 0; period 2 is not, so the key is visibly read again.
		clock.now = ANCHOR + 2 * HOUR;
		assertEquals(2, expected("Porto", 100, 2));
		assertEquals(expected("Porto", 100, 2), context.peekTreatment("exp_sb"));
	}

	@Test
	void clusterCountOrAttributeChangesInvalidateThePeriod() {
		final Context context = createContext(dataOf(attributeSwitchback("city", 100)));
		clock.now = ANCHOR + 10;
		context.setAttribute("city", "Lisboa");
		context.setAttribute("geo", TestUtils.mapOf("city", "Porto"));
		assertEquals(expected("Lisboa", 100, 0), context.getTreatment("exp_sb"));
		assertEquals(1, context.getPendingCount());

		refreshTo(context, attributeSwitchback("city", 100));
		assertEquals(expected("Lisboa", 100, 0), context.getTreatment("exp_sb"));
		assertEquals(1, context.getPendingCount());

		refreshTo(context, attributeSwitchback("city", 10));
		assertEquals(expected("Lisboa", 10, 0), context.getTreatment("exp_sb"));
		assertEquals(2, context.getPendingCount());

		refreshTo(context, attributeSwitchback("geo/city", 10));
		assertEquals(expected("Porto", 10, 0), context.getTreatment("exp_sb"));
		assertEquals(3, context.getPendingCount());

		final Experiment unitSource = switchback(ARMS, HOUR);
		unitSource.switchbackClusterCount = 10;
		refreshTo(context, unitSource);
		assertEquals(expected(UID_HASH, 10, 0), context.getTreatment("exp_sb"));
		assertEquals(4, context.getPendingCount());
	}

	// With no cluster unit type, the experiment's unitType alone decides the hashed unit, so a
	// unitType change re-keys the period.
	@Test
	void unitTypeChangeWithoutClusterUnitTypeRekeysThePeriod() {
		final String userUid = "user-0002";
		final String userHash = new String(Hashing.hashUnit(userUid), StandardCharsets.US_ASCII);
		final Experiment byStore = switchback(ARMS, HOUR);
		byStore.switchbackClusterUnitType = null;
		final Context context = createContext(TestUtils.mapOf(UNIT_TYPE, UID, "user_id", userUid), dataOf(byStore));
		assertNotEquals(expected(UID_HASH, 100, 0), expected(userHash, 100, 0));

		clock.now = ANCHOR + 1;
		assertEquals(expected(UID_HASH, 100, 0), context.getTreatment("exp_sb"));

		final Experiment byUser = switchback(ARMS, HOUR);
		byUser.switchbackClusterUnitType = null;
		byUser.unitType = "user_id";
		refreshTo(context, byUser);
		assertEquals(expected(userHash, 100, 0), context.getTreatment("exp_sb"));
	}

	@Test
	void customAssignmentAppliesOnlyToUnitsWithAClusterKey() {
		final Context keyed = createContext(dataOf(attributeSwitchback("city", 10)));
		keyed.setAttribute("city", "Lisboa");
		keyed.setCustomAssignment("exp_sb", 2);
		assertEquals(2, keyed.getTreatment("exp_sb"));

		final Context unkeyed = createContext(dataOf(attributeSwitchback("city", 10)));
		unkeyed.setCustomAssignment("exp_sb", 2);
		assertEquals(0, unkeyed.getTreatment("exp_sb"));
	}

	@Test
	void unitExperimentsIgnoreClusterFields() {
		final Experiment unit = attributeSwitchback("city", 1000);
		unit.randomizationType = null;
		final Experiment reference = switchback(ARMS, HOUR);
		reference.randomizationType = null;
		final Context withFields = createContext(dataOf(unit));
		final Context without = createContext(dataOf(reference));
		assertEquals(without.getTreatment("exp_sb"), withFields.getTreatment("exp_sb"));
	}
}
