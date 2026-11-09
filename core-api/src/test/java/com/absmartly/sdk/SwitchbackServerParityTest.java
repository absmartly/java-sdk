package com.absmartly.sdk;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java8.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.absmartly.sdk.internal.SwitchbackAssigner;
import com.absmartly.sdk.java.time.Clock;
import com.absmartly.sdk.json.ContextData;
import com.absmartly.sdk.json.Experiment;
import com.absmartly.sdk.json.ExperimentApplication;
import com.absmartly.sdk.json.ExperimentVariant;
import com.absmartly.sdk.json.Exposure;

// Replays the Collector's R33 switchback serving rules (ContextAPI.switchbackServing and
// switchbackVariant, exercised by the Collector's ContextAPISwitchbackTest through its shared
// SdkSwitchbackRule fixture) through the public Context API. Each case states, independently of SDK
// code, the serving unit type and the raw cluster key the Collector picks, or that it records
// nothing; the expected variant is SwitchbackAssigner over that raw key, pinned by
// switchback/golden-vectors.json, and a few schedules are also pinned to an independent murmur3
// reference.
//
// The Collector's "two units of the same type" case has no SDK counterpart: a context holds one uid
// per unit type and setUnit rejects a second, which sameTypeSecondUidIsRejected asserts.
class SwitchbackServerParityTest {
	static final long HOUR = 3_600_000L;
	static final long ANCHOR = 1_767_225_600_000L;
	static final int PERIODS = 4;
	static final int ARMS = 3;
	static final long SEED = 1_234_567_890_123L;

	static final String STORE = "store_id";
	static final String DEVICE = "device_id";
	static final String USER = "user_id";

	static final ObjectMapper MAPPER = new ObjectMapper();

	static final class FixedClock extends Clock {
		long now = ANCHOR;

		@Override
		public long millis() {
			return now;
		}
	}

	static final class ExposureCapture implements ContextEventLogger {
		final List<Exposure> exposures = new ArrayList<Exposure>();

		@Override
		public void handleEvent(Context context, EventType type, Object data) {
			if (type == EventType.Exposure) {
				exposures.add((Exposure) data);
			}
		}
	}

	static Map<String, String> clusters(String... entries) {
		final Map<String, String> map = new LinkedHashMap<String, String>();
		for (int i = 0; i < entries.length; i += 2) {
			map.put(entries[i], entries[i + 1]);
		}
		return map;
	}

	static Experiment experiment(long seed, int armCount, Map<String, String> clusters, String[] values) {
		final Experiment experiment = new Experiment();
		experiment.id = 1;
		experiment.name = "exp_sb";
		// R33 payloads may still carry unitType; it is never used for switchback assignment.
		experiment.unitType = USER;
		experiment.iteration = 1;
		experiment.seedHi = (int) (seed >> 32);
		experiment.seedLo = (int) seed;
		experiment.split = new double[armCount];
		experiment.variants = new ExperimentVariant[armCount];
		for (int i = 0; i < armCount; ++i) {
			experiment.split[i] = 1.0 / armCount;
			experiment.variants[i] = new ExperimentVariant(String.valueOf(i), null);
		}
		experiment.trafficSplit = new double[]{0.0, 1.0};
		experiment.applications = new ExperimentApplication[]{new ExperimentApplication("website")};
		experiment.randomizationType = "switchback";
		experiment.switchbackClusters = clusters;
		experiment.switchbackClusterValues = values;
		experiment.switchbackStartAt = ANCHOR;
		experiment.switchbackPeriodMs = HOUR;
		experiment.switchbackDesignType = "balanced_block";
		return experiment;
	}

	static Experiment experiment(Map<String, String> clusters) {
		return experiment(SEED, ARMS, clusters, null);
	}

	static final class Served {
		final List<Integer> variants = new ArrayList<Integer>();
		List<Exposure> exposures;
	}

	// Serves PERIODS periods with getTreatment, one call per period.
	static Served serve(Experiment experiment, Map<String, ?> units, Map<String, Object> attributes) {
		final FixedClock clock = new FixedClock();
		final ExposureCapture capture = new ExposureCapture();
		final ContextConfig config = ContextConfig.create();
		config.setUnits(units);
		config.setEventLogger(capture);
		final ContextData data = new ContextData();
		data.experiments = new Experiment[]{experiment};
		final ContextEventHandler handler = mock(ContextEventHandler.class);
		when(handler.publish(any(), any())).thenReturn(CompletableFuture.<Void> completedFuture(null));
		final Context context = Context.create(clock, config, mock(ScheduledExecutorService.class),
				CompletableFuture.completedFuture(data), mock(ContextDataProvider.class), handler, null,
				new DefaultVariableParser(), new AudienceMatcher(new DefaultAudienceDeserializer()));
		if (attributes != null) {
			context.setAttributes(attributes);
		}
		final Served served = new Served();
		for (int p = 0; p < PERIODS; ++p) {
			clock.now = ANCHOR + p * HOUR + 1;
			served.variants.add(context.getTreatment("exp_sb"));
		}
		served.exposures = capture.exposures;
		return served;
	}

	static int collectorVariant(String rawKey, long seed, long periodIndex, int armCount) {
		return SwitchbackAssigner.assign(rawKey, periodIndex, seed, armCount);
	}

	// The Collector served `key` under a unit of `servingType`: one eligible exposure per period
	// under that unit, variant from the raw key.
	static void assertEligible(Served served, String servingType, String key, String message) {
		assertEquals(PERIODS, served.exposures.size(), message);
		for (int p = 0; p < PERIODS; ++p) {
			final int expected = collectorVariant(key, SEED, p, ARMS);
			assertEquals(expected, served.variants.get(p).intValue(), message + " period " + p);
			final Exposure exposure = served.exposures.get(p);
			assertEquals(servingType, exposure.unit, message);
			assertEquals(expected, exposure.variant, message);
			assertTrue(exposure.assigned, message);
			assertTrue(exposure.eligible, message);
			assertEquals(ANCHOR + p * HOUR + 1, exposure.exposedAt, message);
		}
	}

	// The Collector records nothing: control in every period, no exposure.
	static void assertSilentControl(Served served, String message) {
		for (int p = 0; p < PERIODS; ++p) {
			assertEquals(0, served.variants.get(p).intValue(), message);
		}
		assertTrue(served.exposures.isEmpty(), message + ": " + served.exposures);
	}

	static Map<String, Object> attrs(Object... entries) {
		final Map<String, Object> map = new LinkedHashMap<String, Object>();
		for (int i = 0; i < entries.length; i += 2) {
			map.put((String) entries[i], entries[i + 1]);
		}
		return map;
	}

	static final Map<String, String> TWO_SOURCES = clusters(STORE, "store_city", DEVICE, "geo/city");

	static Map<String, Object> twoUnits() {
		return TestUtils.mapOf(STORE, "s1", DEVICE, "d1");
	}

	// Independent murmur3 reference: periods 0..3 of the raw keys under SEED with 3 arms.
	@Test
	void rawKeySchedulesMatchTheIndependentReference() {
		assertEligible(serve(experiment(clusters(STORE, "city")), TestUtils.mapOf(STORE, "s1"),
				attrs("city", "Lisboa")), STORE, "Lisboa", "Lisboa");
		for (int p = 0; p < PERIODS; ++p) {
			assertEquals(new int[]{0, 1, 2, 2}[p], collectorVariant("Lisboa", SEED, p, ARMS));
			assertEquals(new int[]{2, 1, 0, 1}[p], collectorVariant("Porto", SEED, p, ARMS));
			assertEquals(new int[]{0, 2, 1, 2}[p], collectorVariant("42", SEED, p, ARMS));
		}
	}

	@Test
	void agreementServesTheFirstSourceUnit() {
		assertEligible(serve(experiment(TWO_SOURCES), twoUnits(),
				attrs("store_city", "Lisboa", "geo", TestUtils.mapOf("city", "Lisboa"))), STORE, "Lisboa",
				"agreement");
	}

	@Test
	void disagreementIsIneligibleAndRecordsNothing() {
		assertSilentControl(serve(experiment(TWO_SOURCES), twoUnits(),
				attrs("store_city", "Lisboa", "geo", TestUtils.mapOf("city", "Porto"))), "disagreement");
	}

	// A unit yielding no value is skipped; the next unit with a value serves.
	@Test
	void missingValueIsSkippedAndTheNextValueServes() {
		assertEligible(serve(experiment(TWO_SOURCES), twoUnits(), attrs("geo", TestUtils.mapOf("city", "Porto"))),
				DEVICE, "Porto", "first source missing");
		assertEligible(serve(experiment(TWO_SOURCES), twoUnits(), attrs("store_city", "Porto")), STORE, "Porto",
				"second source missing");
		assertSilentControl(serve(experiment(TWO_SOURCES), twoUnits(), attrs("other", "x")), "no value at all");
		assertSilentControl(serve(experiment(TWO_SOURCES), twoUnits(), null), "no attributes");
	}

	// A unit linked by a source but absent from the context does not take part; with its value
	// present, the present unit still serves alone.
	@Test
	void onlyLinkedUnitsPresentInTheContextTakePart() {
		assertEligible(serve(experiment(TWO_SOURCES), TestUtils.mapOf(DEVICE, "d1"),
				attrs("store_city", "Lisboa", "geo", TestUtils.mapOf("city", "Porto"))), DEVICE, "Porto",
				"store unit absent");
	}

	@Test
	void filteredValueIsIneligibleAndAllowedValueServes() {
		final Map<String, Object> lisboa = attrs("store_city", "Lisboa");
		assertSilentControl(serve(experiment(SEED, ARMS, TWO_SOURCES, new String[]{"Porto", "Faro"}), twoUnits(),
				lisboa), "filtered");
		assertEligible(serve(experiment(SEED, ARMS, TWO_SOURCES, new String[]{"Porto", "Lisboa"}), twoUnits(),
				lisboa), STORE, "Lisboa", "allowed");
		// A null or empty allow-list admits every value.
		assertEligible(serve(experiment(SEED, ARMS, TWO_SOURCES, null), twoUnits(), lisboa), STORE, "Lisboa",
				"null filter");
		assertEligible(serve(experiment(SEED, ARMS, TWO_SOURCES, new String[0]), twoUnits(), lisboa), STORE,
				"Lisboa", "empty filter");
		// The filter compares the normalised text of numeric values.
		assertEligible(serve(experiment(SEED, ARMS, TWO_SOURCES, new String[]{"42"}), twoUnits(),
				attrs("store_city", 42)), STORE, "42", "numeric filter");
		// A filtered first value is not replaced by a later allowed one: they disagree.
		assertSilentControl(serve(experiment(SEED, ARMS, TWO_SOURCES, new String[]{"Porto"}), twoUnits(),
				attrs("store_city", "Lisboa", "geo", TestUtils.mapOf("city", "Porto"))), "filtered first value");
	}

	@Test
	void sameTypeSecondUidIsRejected() {
		final Context context = Context.create(new FixedClock(), ContextConfig.create(),
				mock(ScheduledExecutorService.class), CompletableFuture.completedFuture(new ContextData()),
				mock(ContextDataProvider.class), mock(ContextEventHandler.class), null, new DefaultVariableParser(),
				new AudienceMatcher(new DefaultAudienceDeserializer()));
		context.setUnit(STORE, "s1");
		assertThrows(IllegalArgumentException.class, () -> context.setUnit(STORE, "s2"));
		assertEligible(serve(experiment(clusters(STORE, "city")), TestUtils.mapOf(STORE, "s1"),
				attrs("city", "Faro")), STORE, "Faro", "single unit of the type");
	}

	// The declared order (unit_type_id ascending) decides, not the order units were set or named.
	@Test
	void lowerUnitTypeIdSourceServesFirst() {
		final Map<String, String> deviceFirst = clusters(DEVICE, "geo/city", STORE, "store_city");
		final Map<String, Object> agreeing = attrs("store_city", "Lisboa", "geo", TestUtils.mapOf("city", "Lisboa"));
		final Map<String, Object> units = new LinkedHashMap<String, Object>();
		units.put(STORE, "s1");
		units.put(DEVICE, "d1");
		assertEligible(serve(experiment(deviceFirst), units, agreeing), DEVICE, "Lisboa", "device declared first");
		assertEligible(serve(experiment(TWO_SOURCES), units, agreeing), STORE, "Lisboa", "store declared first");
	}

	// Booleans, floating-point numbers, objects, arrays, null and "" yield no value: skipped, never a
	// disagreement.
	@Test
	void nonKeyValuesAreIgnored() {
		final Object[] ignored = {true, false, 1.5, 2.0, 2.0f, TestUtils.mapOf("a", "b"), TestUtils.listOf("x"),
				TestUtils.listOf(), null, ""};
		for (final Object value : ignored) {
			final String message = "ignored " + value;
			assertEligible(serve(experiment(TWO_SOURCES), twoUnits(),
					attrs("store_city", value, "geo", TestUtils.mapOf("city", "Porto"))), DEVICE, "Porto", message);
			assertSilentControl(serve(experiment(clusters(STORE, "store_city")), TestUtils.mapOf(STORE, "s1"),
					attrs("store_city", value)), message + " alone");
		}
	}

	// Integral numbers are keyed by their base-10 text, so they agree with that text.
	@Test
	void numericValuesAreKeyedAsText() {
		final Object[] fortyTwo = {42, 42L, (short) 42, (byte) 42, BigInteger.valueOf(42), "42"};
		for (final Object store : fortyTwo) {
			for (final Object device : fortyTwo) {
				assertEligible(serve(experiment(TWO_SOURCES), twoUnits(),
						attrs("store_city", store, "geo", TestUtils.mapOf("city", device))), STORE, "42",
						store.getClass().getSimpleName() + "/" + device.getClass().getSimpleName());
			}
		}
		assertEligible(serve(experiment(clusters(STORE, "n")), TestUtils.mapOf(STORE, "s1"),
				attrs("n", new BigInteger("123456789012345678901234567890"))), STORE,
				"123456789012345678901234567890", "big integer");
		assertEligible(serve(experiment(clusters(STORE, "n")), TestUtils.mapOf(STORE, "s1"), attrs("n", -7L)), STORE,
				"-7", "negative");
		assertSilentControl(serve(experiment(TWO_SOURCES), twoUnits(),
				attrs("store_city", 42, "geo", TestUtils.mapOf("city", "042"))), "42 vs 042 disagree");
	}

	// A malformed path (unterminated quote, a '.' anywhere, quoted or not) makes the whole experiment
	// unservable: ineligible control for any linked unit, even when another source is valid.
	@Test
	void malformedPathsAreUnservable() {
		final Map<String, Object> attributes = attrs("store_city", "Lisboa", "geo", TestUtils.mapOf("city", "Porto"),
				"geo.city", "Faro");
		for (final String path : new String[]{"'geo/city", "\"geo", "geo/'city", "geo.city", "geo/a.b", "'geo.city'",
				"\"geo.city\""}) {
			assertSilentControl(serve(experiment(clusters(STORE, "store_city", DEVICE, path)), twoUnits(),
					attributes), path);
			assertSilentControl(serve(experiment(clusters(DEVICE, path)), twoUnits(), attributes), path + " alone");
		}
	}

	// No context unit of any linked type: the experiment does not apply; unitType is never used.
	@Test
	void noLinkedUnitIsAbsent() {
		final Served served = serve(experiment(TWO_SOURCES), TestUtils.mapOf(USER, "u1"),
				attrs("store_city", "Lisboa"));
		assertSilentControl(served, "no linked unit");
	}

	// Traffic eligibility is sticky on the serving unit's hash: it equals that of a unit experiment
	// on the serving unit's type with the same traffic seed and split, in every period.
	@Test
	void trafficFollowsTheServingUnitHash() {
		int eligible = 0;
		int ineligible = 0;
		for (int u = 0; u < 24; ++u) {
			final Experiment switchback = experiment(TWO_SOURCES);
			switchback.trafficSplit = new double[]{0.5, 0.5};
			switchback.trafficSeedHi = 3;
			switchback.trafficSeedLo = 4;
			final Experiment twin = experiment(TWO_SOURCES);
			twin.name = "exp_unit";
			twin.id = 2;
			twin.randomizationType = null;
			twin.unitType = DEVICE;
			twin.trafficSplit = switchback.trafficSplit;
			twin.trafficSeedHi = 3;
			twin.trafficSeedLo = 4;

			final ContextConfig config = ContextConfig.create();
			config.setUnits(TestUtils.mapOf(STORE, "s1", DEVICE, "device-" + u));
			final ExposureCapture capture = new ExposureCapture();
			config.setEventLogger(capture);
			final ContextData data = new ContextData();
			data.experiments = new Experiment[]{switchback, twin};
			final FixedClock clock = new FixedClock();
			final Context context = Context.create(clock, config, mock(ScheduledExecutorService.class),
					CompletableFuture.completedFuture(data), mock(ContextDataProvider.class),
					mock(ContextEventHandler.class), null, new DefaultVariableParser(),
					new AudienceMatcher(new DefaultAudienceDeserializer()));
			// Only the device unit has a value, so it serves.
			context.setAttribute("geo", TestUtils.mapOf("city", "Porto"));
			context.getTreatment("exp_unit");
			final boolean twinEligible = capture.exposures.get(0).eligible;
			for (int p = 0; p < PERIODS; ++p) {
				clock.now = ANCHOR + p * HOUR;
				final int variant = context.getTreatment("exp_sb");
				final Exposure exposure = capture.exposures.get(p + 1);
				assertEquals(DEVICE, exposure.unit);
				assertEquals(twinEligible, exposure.eligible);
				assertEquals(twinEligible ? collectorVariant("Porto", SEED, p, ARMS) : 0, variant);
			}
			if (twinEligible) {
				++eligible;
			} else {
				++ineligible;
			}
		}
		assertTrue(eligible > 0);
		assertTrue(ineligible > 0);
	}

	// Each attribute-path-cases.json case, served as a cluster path over the fixture's attributes:
	// unsupported paths are unservable, a null value is ineligible, and any other value serves.
	@Test
	void attributePathCasesMatchTheCollector() throws IOException {
		final JsonNode pathCases = MAPPER.readTree(readResource("switchback/attribute-path-cases.json"));
		final Map<String, Object> attributes = MAPPER.convertValue(pathCases.get("attributes"),
				new TypeReference<Map<String, Object>>() {});
		int eligibleCases = 0;
		for (final JsonNode c : pathCases.get("cases")) {
			final String path = c.get("path").textValue();
			final Object value = MAPPER.convertValue(c.get("value"), Object.class);
			final Served served = serve(experiment(clusters(STORE, path)), TestUtils.mapOf(STORE, "s1"), attributes);
			if (!c.get("supported").booleanValue() || (value == null)) {
				assertSilentControl(served, c.toString());
				continue;
			}
			assertEligible(served, STORE, (String) value, c.toString());
			++eligibleCases;
		}
		assertEquals(3, eligibleCases);
	}

	// Context creation keeps the latest supplied value of a repeated attribute name; names are
	// matched trimmed.
	@Test
	void repeatedAttributeNamesResolveToTheLatest() {
		assertEligible(serve(experiment(clusters(STORE, "cluster")), TestUtils.mapOf(STORE, "s1"),
				attrs(" cluster ", "Porto")), STORE, "Porto", "trimmed name");

		final FixedClock clock = new FixedClock();
		final ExposureCapture capture = new ExposureCapture();
		final ContextConfig config = ContextConfig.create();
		config.setUnits(TestUtils.mapOf(STORE, "s1"));
		config.setEventLogger(capture);
		final ContextData data = new ContextData();
		data.experiments = new Experiment[]{experiment(clusters(STORE, "cluster"))};
		final Context context = Context.create(clock, config, mock(ScheduledExecutorService.class),
				CompletableFuture.completedFuture(data), mock(ContextDataProvider.class),
				mock(ContextEventHandler.class), null, new DefaultVariableParser(),
				new AudienceMatcher(new DefaultAudienceDeserializer()));
		context.setAttribute("cluster", "Lisboa");
		context.setAttribute("cluster", "Porto");
		clock.now = ANCHOR + 1;
		assertEquals(collectorVariant("Porto", SEED, 0, ARMS), context.getTreatment("exp_sb"));
		assertTrue(capture.exposures.get(0).eligible);
	}

	// Shared fixtures are byte-identical copies of absmartly/abs lib/utils/src/test/resources/switchback
	// (git blobs b314abeb9e97 and 7b1d3fed5985).
	@Test
	void sharedFixturesAreByteIdentical() throws IOException, NoSuchAlgorithmException {
		assertEquals("66dda4bafddaa119b030beafff8dc56f5750a8387919ffbe0842c93525ef32b6",
				sha256(readResource("switchback/golden-vectors.json")));
		assertEquals("2ee7eb0053997b48b8992980ffe11fe3accb2db01471aa9a617420e1609c6518",
				sha256(readResource("switchback/attribute-path-cases.json")));
		assertNull(SwitchbackServerParityTest.class.getClassLoader()
				.getResource("switchback/bucket-golden-vectors.json"));
	}

	static byte[] readResource(String resource) throws IOException {
		final InputStream in = SwitchbackServerParityTest.class.getClassLoader().getResourceAsStream(resource);
		try {
			final ByteArrayOutputStream out = new ByteArrayOutputStream();
			final byte[] buffer = new byte[8192];
			int n;
			while ((n = in.read(buffer)) > 0) {
				out.write(buffer, 0, n);
			}
			return out.toByteArray();
		} finally {
			in.close();
		}
	}

	static String sha256(byte[] bytes) throws NoSuchAlgorithmException {
		final byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
		final StringBuilder hex = new StringBuilder();
		for (final byte b : digest) {
			hex.append(String.format("%02x", b & 0xff));
		}
		return hex.toString();
	}

	static {
		assertFalse(TWO_SOURCES.isEmpty());
		assertArrayEquals(new Object[]{STORE, DEVICE}, TWO_SOURCES.keySet().toArray());
	}
}
