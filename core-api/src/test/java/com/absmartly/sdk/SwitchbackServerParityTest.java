package com.absmartly.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java8.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeAll;
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

// Serves contexts through the public Context API and compares every exposure with the Collector's
// ContextAPI.switchbackVariant: -1 (variant 0, not eligible) for an unservable experiment or a
// missing bucket key, otherwise assignSwitchback(bucketOf(key), periodIndex, seed, armCount). The
// expected bucket is read from bucket-golden-vectors.json, never computed by SDK code, and the
// expected path outcome from attribute-path-cases.json.
class SwitchbackServerParityTest {
	static final String UNIT_TYPE = "store_id";
	static final long HOUR = 3_600_000L;
	static final long ANCHOR = 1_767_225_600_000L;
	static final int[] COUNTS = {10, 50, 100};
	static final int[] ARM_COUNTS = {2, 3, 10};
	static final int PERIODS = 4;

	static final ObjectMapper MAPPER = new ObjectMapper();
	static JsonNode bucketVectors;
	static JsonNode pathCases;
	// "key\u0000seed\u0000count" -> expectedBucket.
	static final Map<String, Integer> BUCKETS = new HashMap<String, Integer>();
	static final List<Long> SEEDS = new ArrayList<Long>();

	@BeforeAll
	static void loadFixtures() throws IOException {
		bucketVectors = read("switchback/bucket-golden-vectors.json");
		pathCases = read("switchback/attribute-path-cases.json");
		for (final JsonNode c : bucketVectors.get("buckets")) {
			final long seed = Long.parseLong(c.get("seed").textValue());
			BUCKETS.put(bucketKey(c.get("key").textValue(), seed, c.get("count").intValue()),
					c.get("expectedBucket").intValue());
			if (!SEEDS.contains(seed)) {
				SEEDS.add(seed);
			}
		}
		assertEquals(9, SEEDS.size());
	}

	static JsonNode read(String resource) throws IOException {
		final InputStream in = SwitchbackServerParityTest.class.getClassLoader().getResourceAsStream(resource);
		try {
			return MAPPER.readTree(in);
		} finally {
			in.close();
		}
	}

	static String bucketKey(String key, long seed, int count) {
		return key + '\u0000' + seed + '\u0000' + count;
	}

	// ContextAPI.switchbackVariant after the unservable and missing-key checks: the schedule of the
	// key's fixture bucket. SwitchbackAssigner is pinned by switchback/golden-vectors.json.
	static int collectorVariant(String key, long seed, int count, long periodIndex, int armCount) {
		final Integer bucket = BUCKETS.get(bucketKey(key, seed, count));
		assertNotNull(bucket, "no fixture bucket for " + key + " seed " + seed + " count " + count);
		return SwitchbackAssigner.assign(String.valueOf(bucket), periodIndex, seed, armCount);
	}

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

	static Experiment experiment(long seed, int armCount, Integer count, String attribute) {
		final Experiment experiment = new Experiment();
		experiment.id = 1;
		experiment.name = "exp_sb";
		experiment.unitType = UNIT_TYPE;
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
		experiment.switchbackClusterUnitType = UNIT_TYPE;
		experiment.switchbackStartAt = ANCHOR;
		experiment.switchbackPeriodMs = HOUR;
		experiment.switchbackDesignType = "balanced_block";
		experiment.switchbackClusterCount = count;
		experiment.switchbackClusterAttribute = attribute;
		return experiment;
	}

	// Serves PERIODS periods with getTreatment and returns one variant per period, plus the
	// exposures: one per period, or a single one for an unservable grid, whose periods share one
	// slot.
	static Served serve(Experiment experiment, String uid, Map<String, Object> attributes) {
		final FixedClock clock = new FixedClock();
		final ExposureCapture capture = new ExposureCapture();
		final ContextConfig config = ContextConfig.create();
		config.setUnits(TestUtils.mapOf(UNIT_TYPE, uid));
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
		final List<Integer> variants = new ArrayList<Integer>();
		for (int p = 0; p < PERIODS; ++p) {
			clock.now = ANCHOR + p * HOUR + 1;
			variants.add(context.getTreatment("exp_sb"));
		}
		return new Served(variants, capture.exposures);
	}

	static final class Served {
		final List<Integer> variants;
		final List<Exposure> exposures;

		Served(List<Integer> variants, List<Exposure> exposures) {
			this.variants = variants;
			this.exposures = exposures;
		}
	}

	static void assertServed(Served served, String key, long seed, int count, int armCount, String message) {
		assertTrue(!served.exposures.isEmpty(), message);
		for (int p = 0; p < PERIODS; ++p) {
			final int expected = (key == null) ? 0 : collectorVariant(key, seed, count, p, armCount);
			assertEquals(expected, served.variants.get(p).intValue(), message);
		}
		for (int i = 0; i < served.exposures.size(); ++i) {
			final Exposure exposure = served.exposures.get(i);
			if (key == null) {
				assertEquals(0, exposure.variant, message);
				assertEquals(false, exposure.eligible, message);
			} else {
				assertEquals(PERIODS, served.exposures.size(), message);
				assertEquals(collectorVariant(key, seed, count, i, armCount), exposure.variant, message);
				assertEquals(true, exposure.eligible, message);
			}
			assertEquals(true, exposure.assigned, message);
		}
	}

	// Unit source: the bucket key is UnitHasher.hash(uid). The SDK keeps unit uids as strings and
	// rejects a blank uid in setUnit, so numeric uids are served in base 10 and "" is skipped.
	@Test
	void unitSourceMatchesTheCollectorForEveryUnitKeySeedAndCount() {
		int served = 0;
		for (final JsonNode c : bucketVectors.get("unitKeys")) {
			final JsonNode uidNode = c.get("unitUid");
			final String uid = uidNode.isIntegralNumber() ? Long.toString(uidNode.longValue()) : uidNode.textValue();
			if (uid.trim().isEmpty()) {
				continue;
			}
			for (final long seed : SEEDS) {
				for (final int count : COUNTS) {
					final int armCount = ARM_COUNTS[served % ARM_COUNTS.length];
					assertServed(serve(experiment(seed, armCount, count, null), uid, null),
							c.get("key").textValue(), seed, count, armCount, c + " seed " + seed + " count " + count);
					++served;
				}
			}
		}
		assertEquals(11 * 9 * 3, served);
	}

	// Attribute source at a top-level, a nested and an array path: the key is
	// normalizeAttribute(value), or none for "", null, booleans and floats.
	@Test
	void attributeSourceMatchesTheCollectorForEveryAttributeValueSeedAndCount() {
		int served = 0;
		int ineligible = 0;
		for (final JsonNode c : bucketVectors.get("attributeKeys")) {
			final Object value = MAPPER.convertValue(c.get("value"), Object.class);
			final String key = c.get("key").isNull() ? null : c.get("key").textValue();
			final Map<String, Object> flat = TestUtils.mapOf("cluster", value);
			final Map<String, Object> nested = TestUtils.mapOf("geo", TestUtils.mapOf("city", value));
			final Map<String, Object> array = TestUtils.mapOf("items",
					TestUtils.listOf(TestUtils.mapOf("city", "other"), TestUtils.mapOf("city", value)));

			final String[] paths = {"cluster", "geo/city", "items/1/city"};
			final List<Map<String, Object>> attributes = TestUtils.listOf(flat, nested, array);
			for (final long seed : SEEDS) {
				for (final int count : COUNTS) {
					final int armCount = ARM_COUNTS[served % ARM_COUNTS.length];
					final int shape = served % paths.length;
					assertServed(serve(experiment(seed, armCount, count, paths[shape]), "u1", attributes.get(shape)),
							key, seed, count, armCount, c + " path " + paths[shape] + " seed " + seed);
					++served;
					if (key == null) {
						++ineligible;
					}
				}
			}
		}
		assertEquals(21 * 9 * 3, served);
		assertEquals(6 * 9 * 3, ineligible);
	}

	// Each path case is served over the fixture's attributes. An unsupported path is unservable and
	// a path resolving to null is ineligible. Any other path serves the schedule of its value's
	// bucket. The case values are not bucket-fixture keys, so their buckets for counts 10, 50 and
	// 100 under the seeds used below were computed with Python hashlib MD5 over the platform
	// formula, which reproduces all 702 bucket-fixture vectors.
	static final Map<String, int[]> PATH_CASE_BUCKETS = TestUtils.mapOf("items/0/city", new int[]{8, 10, 58},
			"geo/+1", new int[]{7, 12, 77}, "geo/city", new int[]{2, 5, 82});

	@Test
	void attributePathCasesMatchTheCollector() {
		final Map<String, Object> attributes = MAPPER.convertValue(pathCases.get("attributes"),
				new TypeReference<Map<String, Object>>() {});
		int eligibleCases = 0;
		for (final JsonNode c : pathCases.get("cases")) {
			final String path = c.get("path").textValue();
			final Object value = MAPPER.convertValue(c.get("value"), Object.class);
			for (int n = 0; n < COUNTS.length; ++n) {
				final int count = COUNTS[n];
				final long seed = SEEDS.get(count % SEEDS.size());
				final Served served = serve(experiment(seed, 3, count, path), "u1", attributes);
				if (!c.get("supported").booleanValue() || (value == null)) {
					assertServed(served, null, seed, count, 3, c.toString());
					continue;
				}
				final int bucket = PATH_CASE_BUCKETS.get(path)[n];
				assertEquals(PERIODS, served.exposures.size(), c.toString());
				for (int p = 0; p < PERIODS; ++p) {
					assertEquals(true, served.exposures.get(p).eligible, c.toString());
					assertEquals(SwitchbackAssigner.assign(String.valueOf(bucket), p, seed, 3),
							served.variants.get(p).intValue(), c + " count " + count + " period " + p);
				}
				++eligibleCases;
			}
		}
		assertEquals(3 * COUNTS.length, eligibleCases);
	}

	// The Collector's CLUSTER_COUNT unservable rule: any count outside {10, 50, 100}, or none.
	@Test
	void disallowedCountsAreUnservable() {
		final String key = bucketVectors.get("unitKeys").get(0).get("key").textValue();
		final String uid = bucketVectors.get("unitKeys").get(0).get("unitUid").textValue();
		for (final Integer count : new Integer[]{null, 0, 1, 20, 99, 1000}) {
			assertServed(serve(experiment(0L, 2, count, null), uid, null), null, 0L, 10, 2, String.valueOf(count));
		}
		assertServed(serve(experiment(0L, 2, 10, null), uid, null), key, 0L, 10, 2, "control");
	}

	// The Collector resolves attributes by trimmed name, latest setAt winning.
	@Test
	void repeatedAttributeNamesResolveToTheLatest() {
		final JsonNode keys = bucketVectors.get("attributeKeys");
		final String first = keys.get(0).get("key").textValue();
		final String second = keys.get(1).get("key").textValue();
		final Map<String, Object> attributes = TestUtils.mapOf(" cluster ", first);
		assertServed(serve(experiment(0L, 3, 50, "cluster"), "u1", attributes), first, 0L, 50, 3, "trimmed name");

		final FixedClock clock = new FixedClock();
		final ExposureCapture capture = new ExposureCapture();
		final ContextConfig config = ContextConfig.create();
		config.setUnits(TestUtils.mapOf(UNIT_TYPE, "u1"));
		config.setEventLogger(capture);
		final ContextData data = new ContextData();
		data.experiments = new Experiment[]{experiment(0L, 3, 50, "cluster")};
		final Context context = Context.create(clock, config, mock(ScheduledExecutorService.class),
				CompletableFuture.completedFuture(data), mock(ContextDataProvider.class),
				mock(ContextEventHandler.class), null, new DefaultVariableParser(),
				new AudienceMatcher(new DefaultAudienceDeserializer()));
		context.setAttribute("cluster", first);
		context.setAttribute("cluster", second);
		clock.now = ANCHOR + 1;
		context.getTreatment("exp_sb");
		assertEquals(collectorVariant(second, 0L, 50, 0, 3), capture.exposures.get(0).variant);
		assertTrue(capture.exposures.get(0).eligible);
	}
}
