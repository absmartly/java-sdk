package com.absmartly.sdk.internal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

// Fixture copied byte-for-byte from absmartly/abs
// lib/analytics/src/test/resources/switchback/golden-vectors.json (git blob b314abeb9e97).
class SwitchbackAssignerGoldenVectorsTest {
	private static JsonNode loadVectors() throws IOException {
		final InputStream in = SwitchbackAssignerGoldenVectorsTest.class.getClassLoader()
				.getResourceAsStream("switchback/golden-vectors.json");
		try {
			return new ObjectMapper().readTree(in);
		} finally {
			in.close();
		}
	}

	// The platform hashes a numeric cluster unit as Long.toString(n); strings pass through.
	private static String clusterUnit(JsonNode node) {
		if (node.isIntegralNumber()) {
			return Long.toString(node.longValue());
		}
		assertTrue(node.isTextual(), "clusterUnit must be a string or integer: " + node);
		return node.textValue();
	}

	// 64-bit fields are decimal strings; a numeric node would mean the fixture regressed to a
	// lossy encoding.
	private static long exactLong(JsonNode node) {
		assertTrue(node.isTextual(), "64-bit field must be a decimal string: " + node);
		return Long.parseLong(node.textValue());
	}

	@Test
	void assignSwitchbackMatchesEveryGoldenVector() throws IOException {
		final JsonNode cases = loadVectors().get("assignSwitchback");
		assertEquals(882, cases.size());

		final Set<Integer> armCounts = new HashSet<Integer>();
		int boundaryCases = 0;
		for (final JsonNode c : cases) {
			final String unit = clusterUnit(c.get("clusterUnit"));
			final long periodIndex = exactLong(c.get("periodIndex"));
			final long seed = exactLong(c.get("seed"));
			final int armCount = c.get("armCount").intValue();
			armCounts.add(armCount);
			if (periodIndex == Long.MIN_VALUE || periodIndex == Long.MAX_VALUE || seed == Long.MIN_VALUE
					|| seed == Long.MAX_VALUE) {
				++boundaryCases;
			}

			assertEquals(c.get("expectedVariant").intValue(),
					SwitchbackAssigner.assign(unit, periodIndex, seed, armCount),
					c.toString());
		}

		assertTrue(armCounts.contains(2) && armCounts.contains(3) && armCounts.contains(10), armCounts.toString());
		assertTrue(boundaryCases > 0);
	}

	@Test
	void fisherYatesPermutationMatchesEveryGoldenVector() throws IOException {
		final JsonNode cases = loadVectors().get("fisherYatesPermutation");
		assertEquals(504, cases.size());

		for (final JsonNode c : cases) {
			final String unit = clusterUnit(c.get("clusterUnit"));
			final long cycleIndex = exactLong(c.get("cycleIndex"));
			final long seed = exactLong(c.get("seed"));
			final int armCount = c.get("armCount").intValue();

			final JsonNode expectedNode = c.get("expectedPermutation");
			final int[] expected = new int[expectedNode.size()];
			for (int i = 0; i < expected.length; ++i) {
				expected[i] = expectedNode.get(i).intValue();
			}

			final int[] actual = SwitchbackAssigner.fisherYatesPermutation(armCount,
					SwitchbackAssigner.baseHash(unit, cycleIndex, seed));
			assertArrayEquals(expected, actual, c.toString());
		}
	}

	@Test
	void everyCycleCoversEveryArmExactlyOnce() {
		final int[] armCounts = {2, 3, 10};
		for (final int armCount : armCounts) {
			for (long cycle = -3; cycle < 20; ++cycle) {
				final boolean[] seen = new boolean[armCount];
				for (int slot = 0; slot < armCount; ++slot) {
					final int variant = SwitchbackAssigner.assign("store_42", cycle * armCount + slot,
							0x1234_5678_9abcL,
							armCount);
					assertTrue(!seen[variant], "arm repeated within a cycle");
					seen[variant] = true;
				}
			}
		}
	}

	@Test
	void rejectsFewerThanTwoArms() {
		assertThrows(IllegalArgumentException.class, () -> SwitchbackAssigner.assign("u", 0, 0, 1));
		assertThrows(IllegalArgumentException.class, () -> SwitchbackAssigner.fisherYatesPermutation(0, 0));
	}

	@Test
	void floorDiv() {
		assertEquals(0, SwitchbackAssigner.floorDiv(0, 3));
		assertEquals(0, SwitchbackAssigner.floorDiv(2, 3));
		assertEquals(1, SwitchbackAssigner.floorDiv(3, 3));
		assertEquals(-1, SwitchbackAssigner.floorDiv(-1, 3));
		assertEquals(-1, SwitchbackAssigner.floorDiv(-3, 3));
		assertEquals(-2, SwitchbackAssigner.floorDiv(-4, 3));
		assertEquals(Math.floorDiv(Long.MIN_VALUE, 3L), SwitchbackAssigner.floorDiv(Long.MIN_VALUE, 3));
		assertEquals(Math.floorDiv(Long.MAX_VALUE, 10L), SwitchbackAssigner.floorDiv(Long.MAX_VALUE, 10));
	}
}
