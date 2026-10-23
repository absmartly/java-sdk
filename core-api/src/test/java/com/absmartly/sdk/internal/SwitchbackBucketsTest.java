package com.absmartly.sdk.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.absmartly.sdk.internal.hashing.Hashing;
import com.absmartly.sdk.java.nio.charset.StandardCharsets;

// Fixture copied byte-for-byte from absmartly/abs
// lib/utils/src/test/resources/switchback/bucket-golden-vectors.json (git blob 7b1d3fed5985).
class SwitchbackBucketsTest {
	static JsonNode loadVectors() throws IOException {
		final InputStream in = SwitchbackBucketsTest.class.getClassLoader()
				.getResourceAsStream("switchback/bucket-golden-vectors.json");
		try {
			return new ObjectMapper().readTree(in);
		} finally {
			in.close();
		}
	}

	static int bucketOf(String key, long seed, int count) {
		return SwitchbackBuckets.bucketOf((int) (seed >> 32), (int) seed, key, count);
	}

	@Test
	void bucketOfMatchesEveryGoldenVector() throws IOException {
		final JsonNode cases = loadVectors().get("buckets");
		assertEquals(702, cases.size());

		final Set<Integer> counts = new HashSet<Integer>();
		final Set<Long> seeds = new HashSet<Long>();
		for (final JsonNode c : cases) {
			assertTrue(c.get("seed").isTextual(), "64-bit seed must be a decimal string: " + c);
			final long seed = Long.parseLong(c.get("seed").textValue());
			final int count = c.get("count").intValue();
			counts.add(count);
			seeds.add(seed);

			assertEquals(c.get("expectedBucket").intValue(), bucketOf(c.get("key").textValue(), seed, count),
					c.toString());
		}

		assertEquals(new HashSet<Integer>(Arrays.asList(10, 50, 100)), counts);
		assertTrue(seeds.contains(Long.MIN_VALUE) && seeds.contains(Long.MAX_VALUE) && seeds.contains(-1L));
	}

	// Computed with Python hashlib, independently of the generator; the first h is above 2^31.
	@Test
	void matchesVectorsComputedIndependentlyFromMd5() {
		assertEquals(6, bucketOf("1tdwU5K8evYzMovqjExpBA", 0L, 10));
		assertEquals(19, bucketOf("\u6771\u4eac", -123456789012345L, 50));
		assertEquals(43, bucketOf("a\u001fb", Long.MIN_VALUE, 100));
	}

	@Test
	void unitKeyIsTheSdkUnitHash() throws IOException {
		final JsonNode cases = loadVectors().get("unitKeys");
		assertEquals(12, cases.size());
		for (final JsonNode c : cases) {
			final JsonNode uid = c.get("unitUid");
			final String normalized = uid.isIntegralNumber() ? Long.toString(uid.longValue()) : uid.textValue();
			assertEquals(c.get("key").textValue(),
					new String(Hashing.hashUnit(normalized), StandardCharsets.US_ASCII), c.toString());
		}
	}

	@Test
	void normalizeAttributeMatchesEveryGoldenVector() throws IOException {
		final ObjectMapper mapper = new ObjectMapper();
		final JsonNode cases = loadVectors().get("attributeKeys");
		assertEquals(21, cases.size());
		int ineligible = 0;
		for (final JsonNode c : cases) {
			// Decoded the way the SDK's attribute values arrive from JSON: Integer, Long,
			// BigInteger, Double, Boolean, String or null.
			final Object value = mapper.convertValue(c.get("value"), Object.class);
			final JsonNode key = c.get("key");
			if (key.isNull()) {
				++ineligible;
				assertNull(SwitchbackBuckets.normalizeAttribute(value), c.toString());
			} else {
				assertEquals(key.textValue(), SwitchbackBuckets.normalizeAttribute(value), c.toString());
			}
		}
		assertEquals(6, ineligible);
	}

	@Test
	void normalizeAttributeAcceptsEveryIntegerTypeAndRejectsOthers() {
		assertEquals("-3", SwitchbackBuckets.normalizeAttribute((byte) -3));
		assertEquals("300", SwitchbackBuckets.normalizeAttribute((short) 300));
		assertEquals("7", SwitchbackBuckets.normalizeAttribute(7));
		assertEquals("-9223372036854775808", SwitchbackBuckets.normalizeAttribute(Long.MIN_VALUE));
		assertEquals("123456789012345678901234567890",
				SwitchbackBuckets.normalizeAttribute(new BigInteger("123456789012345678901234567890")));
		assertNull(SwitchbackBuckets.normalizeAttribute(2.0f));
		assertNull(SwitchbackBuckets.normalizeAttribute(new BigDecimal("2")));
		assertNull(SwitchbackBuckets.normalizeAttribute(Collections.emptyMap()));
		assertNull(SwitchbackBuckets.normalizeAttribute(Collections.emptyList()));
		assertNull(SwitchbackBuckets.normalizeAttribute('c'));
	}

	@Test
	void onlyTenFiftyAndHundredAreAllowed() {
		for (final int count : new int[]{10, 50, 100}) {
			assertTrue(SwitchbackBuckets.isAllowedCount(count));
		}
		for (final Integer count : new Integer[]{null, 0, 1, 2, 20, 99, 101, 1000, -10}) {
			assertFalse(SwitchbackBuckets.isAllowedCount(count));
		}
		assertThrows(IllegalArgumentException.class, () -> SwitchbackBuckets.bucketOf(0, 0, "k", 1000));
		assertThrows(IllegalArgumentException.class, () -> SwitchbackBuckets.bucketOf(0, 0, "k", 0));
	}
}
