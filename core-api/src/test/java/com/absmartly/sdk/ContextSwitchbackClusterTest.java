package com.absmartly.sdk;

import static com.absmartly.sdk.ContextSwitchbackTest.ANCHOR;
import static com.absmartly.sdk.ContextSwitchbackTest.HOUR;
import static com.absmartly.sdk.ContextSwitchbackTest.SEED_HI;
import static com.absmartly.sdk.ContextSwitchbackTest.SEED_LO;
import static com.absmartly.sdk.ContextSwitchbackTest.UID;
import static com.absmartly.sdk.ContextSwitchbackTest.UNIT_TYPE;
import static com.absmartly.sdk.ContextSwitchbackTest.dataOf;
import static com.absmartly.sdk.ContextSwitchbackTest.switchback;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java8.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import com.absmartly.sdk.SwitchbackTestSupport.MutableClock;
import com.absmartly.sdk.internal.SwitchbackAssigner;
import com.absmartly.sdk.internal.hashing.Hashing;
import com.absmartly.sdk.java.nio.charset.StandardCharsets;
import com.absmartly.sdk.json.ContextData;
import com.absmartly.sdk.json.Experiment;
import com.absmartly.sdk.json.Exposure;
import com.absmartly.sdk.json.PublishEvent;

// Cluster key selection inside Context. Variants are derived from the raw key and
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

	Context createContext(Map<String, ?> units, ContextData data) {
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

	PublishEvent publishEvent(Context context) {
		context.publish();
		final ArgumentCaptor<PublishEvent> captor = ArgumentCaptor.forClass(PublishEvent.class);
		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(Mockito.eq(context), captor.capture());
		return captor.getValue();
	}

	Exposure[] publishExposures(Context context) {
		return publishEvent(context).exposures;
	}

	static Experiment attributeSwitchback(String path) {
		final Experiment experiment = switchback(ARMS, HOUR);
		experiment.switchbackClusters = SwitchbackTestSupport.clusters(UNIT_TYPE, path);
		return experiment;
	}

	// The Collector's draw: the raw cluster key, unhashed. SwitchbackAssigner is pinned by
	// switchback/golden-vectors.json.
	static int expected(String key, long periodIndex) {
		return SwitchbackAssigner.assign(key, periodIndex, SEED, ARMS);
	}

	static int[] expectedSchedule(String key) {
		final int[] result = new int[12];
		for (int i = 0; i < result.length; ++i) {
			result[i] = expected(key, i);
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

	// The unit uid is never the cluster key: with no cluster value the unit is ineligible.
	@Test
	void unitUidIsNeverTheClusterKey() {
		final Context context = createContext(dataOf(attributeSwitchback("city")));
		for (int i = 0; i < 3; ++i) {
			clock.now = ANCHOR + i * HOUR;
			assertEquals(0, context.getTreatment("exp_sb"));
		}
		assertEquals(0, context.getPendingCount());
	}

	// A numeric UID serves and publishes under its canonical string (Hashing.unitToString,
	// JavaScript toFixed(0)), exactly as the same string UID does; the cluster key is the attribute.
	@Test
	void numericUnitUidClustersLikeItsCanonicalString() {
		final Object[][] cases = {{123456789L, "123456789"}, {123456789.0, "123456789"},
				{9007199254740993L, "9007199254740992"}, {125.75, "126"}};
		for (final Object[] pair : cases) {
			final Context numeric = createContext(TestUtils.mapOf(UNIT_TYPE, pair[0]),
					dataOf(attributeSwitchback("city")));
			clock.now = ANCHOR;
			numeric.setAttribute("city", "Lisboa");
			final PublishEvent numericEvent = exposeAndPublish(numeric);
			final int[] numericSchedule = peekSchedule(numeric);
			final Context text = createContext(TestUtils.mapOf(UNIT_TYPE, pair[1]),
					dataOf(attributeSwitchback("city")));
			clock.now = ANCHOR;
			text.setAttribute("city", "Lisboa");
			final PublishEvent textEvent = exposeAndPublish(text);

			final String unitHash = new String(Hashing.hashUnit((String) pair[1]), StandardCharsets.US_ASCII);
			assertArrayEquals(expectedSchedule("Lisboa"), numericSchedule);
			assertArrayEquals(peekSchedule(text), numericSchedule);
			assertArrayEquals(textEvent.units, numericEvent.units);
			assertArrayEquals(textEvent.exposures, numericEvent.exposures);
			assertEquals(unitHash, numericEvent.units[0].uid);
		}
	}

	// Independent reference (Python MD5/base64url) for the published hash of numeric UIDs, and the
	// raw-key schedule of "123456789" as a numeric attribute value (murmur3 reference).
	@Test
	void numericUnitUidMatchesTheIndependentReference() {
		final Object[][] cases = {{123456789L, "JfnnlDI7RTiF9RgfG2JNCw"},
				{9007199254740993L, "OngwCmjeKhIQyeNybDy4eg"}};
		final int[] schedule = {0, 2, 1, 0, 1, 2, 0, 1, 2, 2, 1, 0};
		for (final Object[] golden : cases) {
			final Context context = createContext(TestUtils.mapOf(UNIT_TYPE, golden[0]),
					dataOf(attributeSwitchback("region")));
			clock.now = ANCHOR;
			context.setAttribute("region", 123456789);
			final PublishEvent event = exposeAndPublish(context);

			assertEquals(golden[1], event.units[0].uid);
			assertArrayEquals(new Exposure[]{exposure(schedule[0], ANCHOR, true, false)}, event.exposures);
			assertArrayEquals(schedule, peekSchedule(context));
		}
	}

	PublishEvent exposeAndPublish(Context context) {
		clock.now = ANCHOR;
		context.getTreatment("exp_sb");
		return publishEvent(context);
	}

	// Periods 0..11 of the raw key "Lisboa", from the independent murmur3 reference.
	@Test
	void attributeSourceServesTheRawAttributeValue() {
		clock.now = ANCHOR;
		final Context context = createContext(dataOf(attributeSwitchback("city")));
		context.setAttribute("city", "Lisboa");
		assertArrayEquals(new int[]{0, 2, 1, 2, 1, 0, 1, 0, 2, 0, 1, 2}, peekSchedule(context));
		assertArrayEquals(expectedSchedule("Lisboa"), peekSchedule(context));
	}

	@Test
	void unitsSharingAnAttributeValueShareTheSchedule() {
		final Context lisboa = createContext(dataOf(attributeSwitchback("city")));
		lisboa.setAttribute("city", "Lisboa");
		final Context porto = createContext(dataOf(attributeSwitchback("city")));
		porto.setAttribute("city", "Porto");
		final Context otherLisboa = createContext(TestUtils.mapOf(UNIT_TYPE, "another-store"),
				dataOf(attributeSwitchback("city")));
		otherLisboa.setAttribute("city", "Lisboa");

		assertArrayEquals(expectedSchedule("Lisboa"), peekSchedule(lisboa));
		assertArrayEquals(expectedSchedule("Lisboa"), peekSchedule(otherLisboa));
		assertArrayEquals(expectedSchedule("Porto"), peekSchedule(porto));
		assertFalse(Arrays.equals(expectedSchedule("Lisboa"), expectedSchedule("Porto")));
	}

	@Test
	void nestedAndArrayPathsAndIntegerValuesResolve() {
		clock.now = ANCHOR;
		final Context nested = createContext(dataOf(attributeSwitchback("geo/city")));
		nested.setAttribute("geo", TestUtils.mapOf("city", "Porto"));
		assertArrayEquals(expectedSchedule("Porto"), peekSchedule(nested));

		clock.now = ANCHOR;
		final Context array = createContext(dataOf(attributeSwitchback("items/1/city")));
		array.setAttribute("items", TestUtils.listOf(TestUtils.mapOf("city", "Lisbon"),
				TestUtils.mapOf("city", "Faro")));
		assertArrayEquals(expectedSchedule("Faro"), peekSchedule(array));

		clock.now = ANCHOR;
		final Context integer = createContext(dataOf(attributeSwitchback("region")));
		integer.setAttribute("region", 42L);
		assertArrayEquals(expectedSchedule("42"), peekSchedule(integer));

		// Values are read through their JSON encoding, as the Collector decodes them: an integral
		// BigDecimal becomes an integer, a Character a string, and a Float a non-integral double.
		clock.now = ANCHOR;
		final Context decimal = createContext(dataOf(attributeSwitchback("geo/city")));
		decimal.setAttribute("geo", TestUtils.mapOf("city", new BigDecimal("42")));
		assertArrayEquals(expectedSchedule("42"), peekSchedule(decimal));

		clock.now = ANCHOR;
		final Context character = createContext(dataOf(attributeSwitchback("city")));
		character.setAttribute("city", 'c');
		assertArrayEquals(expectedSchedule("c"), peekSchedule(character));

		clock.now = ANCHOR + 10;
		final Context floating = createContext(dataOf(attributeSwitchback("city")));
		floating.setAttribute("city", 2.0f);
		assertEquals(0, floating.getTreatment("exp_sb"));
		assertEquals(0, floating.getPendingCount());
	}

	// Attribute values that yield no cluster key serve variant 0, not eligible, record no exposure
	// and never throw.
	@Test
	void valuesWithoutAClusterKeyAreIneligible() {
		final Object[] values = {null, "", true, 1.5, 2.0, TestUtils.mapOf("a", "b"), TestUtils.listOf("x")};
		for (final Object value : values) {
			final Context context = createContext(dataOf(attributeSwitchback("city")));
			context.setAttribute("city", value);
			clock.now = ANCHOR + 10;
			assertEquals(0, context.getTreatment("exp_sb"), String.valueOf(value));
			// Variant 0's own config, like any ineligible assignment.
			assertEquals(0, context.getVariableValue("sb.arm", -1), String.valueOf(value));
			assertEquals(0, context.getPendingCount(), String.valueOf(value));
		}

		final Context missing = createContext(dataOf(attributeSwitchback("geo/city")));
		missing.setAttribute("geo", TestUtils.mapOf("country", "PT"));
		clock.now = ANCHOR + 10;
		assertEquals(0, missing.getTreatment("exp_sb"));
		assertEquals(0, missing.getPendingCount());
	}

	// A value with no JSON encoding has no cluster key.
	@Test
	void unencodableAttributeValuesAreIneligible() {
		final Map<String, Object> cyclic = new HashMap<String, Object>();
		cyclic.put("self", cyclic);
		final Object[] values = {cyclic, new Object()};
		for (final Object value : values) {
			final Context context = createContext(dataOf(attributeSwitchback("geo/city")));
			context.setAttribute("geo", value);
			assertEquals(0, context.getTreatment("exp_sb"));
			assertEquals(0, context.getPendingCount());
		}
	}

	// Unservable configurations serve an ineligible control to linked units, never a unit-assignment
	// draw, and record nothing.
	@Test
	void unservableConfigurationsAreIneligible() {
		final List<Experiment> experiments = new ArrayList<Experiment>();
		for (final String path : new String[]{"items/0", "items/-1/city", "", "geo.city", "'city", "\"city"}) {
			experiments.add(attributeSwitchback(path));
		}
		final Experiment noPeriod = attributeSwitchback("city");
		noPeriod.switchbackPeriodMs = null;
		experiments.add(noPeriod);
		final Experiment zeroPeriod = attributeSwitchback("city");
		zeroPeriod.switchbackPeriodMs = 0L;
		experiments.add(zeroPeriod);
		final Experiment negativePeriod = attributeSwitchback("city");
		negativePeriod.switchbackPeriodMs = -HOUR;
		experiments.add(negativePeriod);
		final Experiment oneArm = attributeSwitchback("city");
		oneArm.split = new double[]{1.0};
		experiments.add(oneArm);
		final Experiment pathlessLink = attributeSwitchback("city");
		pathlessLink.switchbackClusters = SwitchbackTestSupport.clusters(UNIT_TYPE, null);
		experiments.add(pathlessLink);
		for (final Experiment experiment : experiments) {
			final Context context = createContext(dataOf(experiment));
			context.setAttribute("city", "Lisboa");
			context.setAttribute("items", TestUtils.listOf(TestUtils.mapOf("city", "Lisbon")));
			for (int i = 0; i < 3; ++i) {
				clock.now = ANCHOR + i * HOUR;
				assertEquals(0, context.getTreatment("exp_sb"), experiment.toString());
			}
			assertEquals(0, context.getPendingCount(), experiment.toString());
		}
	}

	// A missing or empty cluster map takes the Collector's legacy path: a unit of the declared
	// unitType only identifies an ineligible control (variant 0's config, no exposure); without one
	// the experiment does not apply.
	@Test
	void missingOrEmptyClusterMapIsOnlyALegacyIneligibleControl() {
		final Experiment none = attributeSwitchback("city");
		none.switchbackClusters = null;
		final Experiment empty = attributeSwitchback("city");
		empty.switchbackClusters = new HashMap<String, String>();
		for (final Experiment experiment : new Experiment[]{none, empty}) {
			final Context context = createContext(dataOf(experiment));
			context.setAttribute("city", "Lisboa");
			assertEquals(0, context.getTreatment("exp_sb"));
			assertEquals(0, context.getVariableValue("sb.arm", -1));
			assertEquals(0, context.getPendingCount());

			final Context other = createContext(TestUtils.mapOf("user_id", "u1"), dataOf(experiment));
			other.setAttribute("city", "Lisboa");
			assertEquals(0, other.getTreatment("exp_sb"));
			assertEquals(-1, other.getVariableValue("sb.arm", -1));
			assertEquals(0, other.getPendingCount());
		}
	}

	// The Collector reads the attribute set at or before the exposure instant, latest setAt first.
	@Test
	void latestAttributeSetAtOrBeforeTheResolutionInstantIsUsed() {
		final Context context = createContext(dataOf(attributeSwitchback("city")));
		clock.now = ANCHOR + 2 * HOUR;
		context.setAttribute("city", "Porto");
		clock.now = ANCHOR + 4 * HOUR;
		context.setAttribute("city", "Lisboa");

		clock.now = ANCHOR + HOUR;
		assertEquals(0, context.peekTreatment("exp_sb"));
		clock.now = ANCHOR + 3 * HOUR;
		assertEquals(expected("Porto", 3), context.peekTreatment("exp_sb"));
		clock.now = ANCHOR + 4 * HOUR;
		assertEquals(expected("Lisboa", 4), context.peekTreatment("exp_sb"));
	}

	// Within a period the cluster key is fixed at first resolution; the next period reads
	// the attribute again.
	@Test
	void setAttributeMidPeriodDoesNotReassignWithinThePeriod() {
		final Context context = createContext(dataOf(attributeSwitchback("city")));
		clock.now = ANCHOR + 10;
		context.setAttribute("city", "Lisboa");
		assertEquals(expected("Lisboa", 0), context.getTreatment("exp_sb"));

		clock.now = ANCHOR + 20;
		context.setAttribute("city", "Porto");
		assertEquals(expected("Lisboa", 0), context.getTreatment("exp_sb"));
		assertEquals(expected("Lisboa", 0), context.peekTreatment("exp_sb"));
		assertEquals(1, context.getPendingCount());

		final int periods = 12;
		int differing = 0;
		for (int i = 1; i < periods; ++i) {
			clock.now = ANCHOR + i * HOUR;
			assertEquals(expected("Porto", i), context.getTreatment("exp_sb"));
			if (expected("Porto", i) != expected("Lisboa", i)) {
				++differing;
			}
		}
		assertNotEquals(0, differing);
		assertEquals(periods, context.getPendingCount());
	}

	// A unit first resolved without a key stays ineligible (and unrecorded) for the rest of that
	// period.
	@Test
	void attributeSetAfterAnIneligibleResolutionWaitsForTheNextPeriod() {
		final Context context = createContext(dataOf(attributeSwitchback("city")));
		clock.now = ANCHOR + 10;
		assertEquals(0, context.getTreatment("exp_sb"));
		context.setAttribute("city", "Lisboa");
		clock.now = ANCHOR + 20;
		assertEquals(0, context.getTreatment("exp_sb"));
		assertEquals(0, context.getPendingCount());

		clock.now = ANCHOR + HOUR;
		assertEquals(expected("Lisboa", 1), context.getTreatment("exp_sb"));
		assertArrayEquals(new Exposure[]{exposure(expected("Lisboa", 1), ANCHOR + HOUR, true, false)},
				publishExposures(context));
	}

	// A slot replaced through an ineligible resolution still keeps the period's first key.
	@Test
	void trafficChangesWithinThePeriodKeepThePeriodsBucket() {
		final Context context = createContext(dataOf(attributeSwitchback("city")));
		clock.now = ANCHOR + 10;
		context.setAttribute("city", "Lisboa");
		assertEquals(expected("Lisboa", 0), context.getTreatment("exp_sb"));

		clock.now = ANCHOR + 20;
		context.setAttribute("city", "Porto");
		final Experiment noTraffic = attributeSwitchback("city");
		noTraffic.trafficSplit = new double[]{1.0, 0.0};
		refreshTo(context, noTraffic);
		assertEquals(0, context.getTreatment("exp_sb"));

		refreshTo(context, attributeSwitchback("city"));
		assertEquals(expected("Lisboa", 0), context.getTreatment("exp_sb"));
		assertEquals(3, context.getPendingCount());

		clock.now = ANCHOR + HOUR;
		assertEquals(expected("Porto", 1), context.getTreatment("exp_sb"));
	}

	// The cluster is resolved before traffic, as the Collector does: a traffic miss of an eligible
	// cluster is a recorded ineligible exposure under the serving unit, and traffic restored later in
	// the period serves the key read at first resolution.
	@Test
	void trafficRestoredWithinThePeriodKeepsTheKey() {
		final Experiment noTraffic = attributeSwitchback("city");
		noTraffic.trafficSplit = new double[]{1.0, 0.0};
		final Context context = createContext(dataOf(noTraffic));
		clock.now = ANCHOR + 10;
		context.setAttribute("city", "Faro");
		assertEquals(0, context.getTreatment("exp_sb"));

		clock.now = ANCHOR + 20;
		context.setAttribute("city", "Lisboa");
		refreshTo(context, attributeSwitchback("city"));
		assertEquals(1, expected("Faro", 0));
		assertEquals(expected("Faro", 0), context.getTreatment("exp_sb"));
		assertArrayEquals(new Exposure[]{exposure(0, ANCHOR + 10, false, false),
				exposure(expected("Faro", 0), ANCHOR + 20, true, false)}, publishExposures(context));
	}

	// An audience change re-resolves the period slot (new exposure state) but keeps its key.
	@Test
	void audienceRevalidationAfterSetAttributeKeepsThePeriodsBucket() {
		final Experiment experiment = attributeSwitchback("city");
		experiment.audience = "{\"filter\":[{\"eq\":[{\"var\":\"tier\"},{\"value\":\"gold\"}]}]}";
		final Context context = createContext(dataOf(experiment));
		clock.now = ANCHOR + 10;
		context.setAttribute("city", "Lisboa");
		context.setAttribute("tier", "gold");
		assertEquals(expected("Lisboa", 0), context.getTreatment("exp_sb"));

		clock.now = ANCHOR + 20;
		context.setAttribute("city", "Porto");
		context.setAttribute("tier", "silver");
		assertEquals(expected("Lisboa", 0), context.getTreatment("exp_sb"));

		assertArrayEquals(new Exposure[]{exposure(expected("Lisboa", 0), ANCHOR + 10, true, false),
				exposure(expected("Lisboa", 0), ANCHOR + 20, true, true)}, publishExposures(context));
	}

	// A slot first resolved without a key carries that missing key into its replacement.
	@Test
	void audienceRevalidationAfterAnIneligibleResolutionKeepsTheMissingKey() {
		final Experiment experiment = attributeSwitchback("city");
		experiment.audience = "{\"filter\":[{\"eq\":[{\"var\":\"tier\"},{\"value\":\"gold\"}]}]}";
		final Context context = createContext(dataOf(experiment));
		clock.now = ANCHOR + 10;
		context.setAttribute("tier", "gold");
		assertEquals(0, context.getTreatment("exp_sb"));

		clock.now = ANCHOR + 20;
		context.setAttribute("city", "Faro");
		context.setAttribute("tier", "silver");
		assertEquals(1, expected("Faro", 0));
		assertEquals(0, context.getTreatment("exp_sb"));
		assertEquals(0, context.getPendingCount());

		clock.now = ANCHOR + HOUR;
		assertEquals(expected("Faro", 1), context.peekTreatment("exp_sb"));
		// Period 1 of Faro is variant 0; period 2 is not, so the key is visibly read again.
		clock.now = ANCHOR + 2 * HOUR;
		assertEquals(2, expected("Faro", 2));
		assertEquals(expected("Faro", 2), context.peekTreatment("exp_sb"));
	}

	@Test
	void clusterPathChangesInvalidateThePeriod() {
		final Context context = createContext(dataOf(attributeSwitchback("city")));
		clock.now = ANCHOR + 10;
		context.setAttribute("city", "Lisboa");
		context.setAttribute("geo", TestUtils.mapOf("city", "Porto"));
		assertEquals(expected("Lisboa", 0), context.getTreatment("exp_sb"));
		assertEquals(1, context.getPendingCount());

		refreshTo(context, attributeSwitchback("city"));
		assertEquals(expected("Lisboa", 0), context.getTreatment("exp_sb"));
		assertEquals(1, context.getPendingCount());

		refreshTo(context, attributeSwitchback("geo/city"));
		assertEquals(expected("Porto", 0), context.getTreatment("exp_sb"));
		assertEquals(2, context.getPendingCount());

		final Experiment filtered = attributeSwitchback("geo/city");
		filtered.switchbackClusterValues = new String[]{"Porto", "Faro"};
		refreshTo(context, filtered);
		assertEquals(expected("Porto", 0), context.getTreatment("exp_sb"));
		assertEquals(3, context.getPendingCount());
	}

	// Re-linking the experiment to another unit type re-keys the period and moves the exposure unit.
	@Test
	void linkedUnitTypeChangeRekeysThePeriod() {
		final Context context = createContext(TestUtils.mapOf(UNIT_TYPE, UID, "user_id", "user-0002"),
				dataOf(attributeSwitchback("city")));
		clock.now = ANCHOR + 1;
		context.setAttribute("city", "Lisboa");
		context.setAttribute("ucity", "Faro");
		assertEquals(expected("Lisboa", 0), context.getTreatment("exp_sb"));

		final Experiment byUser = attributeSwitchback("ucity");
		byUser.switchbackClusters = SwitchbackTestSupport.clusters("user_id", "ucity");
		refreshTo(context, byUser);
		assertEquals(expected("Faro", 0), context.getTreatment("exp_sb"));
		final Exposure[] exposures = publishExposures(context);
		assertEquals(2, exposures.length);
		assertEquals(UNIT_TYPE, exposures[0].unit);
		assertEquals("user_id", exposures[1].unit);
	}

	@Test
	void customAssignmentAppliesOnlyToUnitsWithAClusterKey() {
		final Context keyed = createContext(dataOf(attributeSwitchback("city")));
		keyed.setAttribute("city", "Lisboa");
		keyed.setCustomAssignment("exp_sb", 2);
		assertEquals(2, keyed.getTreatment("exp_sb"));

		final Context unkeyed = createContext(dataOf(attributeSwitchback("city")));
		unkeyed.setCustomAssignment("exp_sb", 2);
		assertEquals(0, unkeyed.getTreatment("exp_sb"));
	}

	// Inertness: a unit experiment ("unit" or absent) carrying every switchback field serves and
	// publishes exactly what the same experiment without them does.
	@Test
	void unitExperimentsIgnoreClusterFields() {
		for (final String type : new String[]{"unit", null}) {
			final Experiment unit = attributeSwitchback("city");
			unit.randomizationType = type;
			unit.switchbackClusters = SwitchbackTestSupport.clusters("user_id", "city", UNIT_TYPE, "'x");
			unit.switchbackClusterValues = new String[]{"nothing"};
			unit.switchbackPeriodMs = 0L;
			// A split rule randomizes over unitType for a unit experiment, never a cluster unit.
			unit.assignmentRules = "{\"rules\":[{\"type\":\"split\",\"environments\":[],"
					+ "\"percentages\":\"34/33/33\"}]}";
			final Experiment reference = new Experiment();
			reference.id = unit.id;
			reference.name = unit.name;
			reference.unitType = unit.unitType;
			reference.iteration = unit.iteration;
			reference.seedHi = unit.seedHi;
			reference.seedLo = unit.seedLo;
			reference.split = unit.split;
			reference.trafficSeedHi = unit.trafficSeedHi;
			reference.trafficSeedLo = unit.trafficSeedLo;
			reference.trafficSplit = unit.trafficSplit;
			reference.applications = unit.applications;
			reference.variants = unit.variants;
			reference.randomizationType = type;
			reference.assignmentRules = unit.assignmentRules;

			final Exposure[][] published = new Exposure[2][];
			final Experiment[] experiments = {unit, reference};
			for (int e = 0; e < experiments.length; ++e) {
				Mockito.reset(eventHandler);
				when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.<Void> completedFuture(null));
				final Context context = createContext(TestUtils.mapOf(UNIT_TYPE, UID, "user_id", "u"),
						dataOf(experiments[e]));
				context.setAttribute("city", "Lisboa");
				clock.now = ANCHOR - HOUR;
				final int first = context.getTreatment("exp_sb");
				for (int i = 1; i < 6; ++i) {
					clock.now = ANCHOR - HOUR + i * HOUR;
					assertEquals(first, context.getTreatment("exp_sb"));
				}
				published[e] = publishExposures(context);
			}
			assertEquals(1, published[0].length);
			assertTrue(published[0][0].eligible);
			assertTrue(published[0][0].ruleOverride);
			assertEquals(ANCHOR - HOUR, published[0][0].exposedAt);
			assertArrayEquals(published[1], published[0]);
		}
	}
}
