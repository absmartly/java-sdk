package com.absmartly.sdk.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.absmartly.sdk.TestUtils;

// Fixture copied byte-for-byte from absmartly/abs
// lib/utils/src/test/resources/switchback/attribute-path-cases.json (git blob c2da44286878).
class SwitchbackAttributePathTest {
	static JsonNode loadCases() throws IOException {
		final InputStream in = SwitchbackAttributePathTest.class.getClassLoader()
				.getResourceAsStream("switchback/attribute-path-cases.json");
		try {
			return new ObjectMapper().readTree(in);
		} finally {
			in.close();
		}
	}

	@Test
	void matchesEveryPathCase() throws IOException {
		final ObjectMapper mapper = new ObjectMapper();
		final JsonNode fixture = loadCases();
		final Map<String, Object> attributes = mapper.convertValue(fixture.get("attributes"),
				new TypeReference<Map<String, Object>>() {});
		final JsonNode cases = fixture.get("cases");
		assertEquals(7, cases.size());

		for (final JsonNode c : cases) {
			final SwitchbackAttributePath path = SwitchbackAttributePath.parseSupported(c.get("path").textValue());
			if (!c.get("supported").booleanValue()) {
				assertNull(path, c.toString());
				continue;
			}
			assertNotNull(path, c.toString());
			assertEquals(mapper.convertValue(c.get("value"), Object.class), path.resolve(attributes), c.toString());
		}
	}

	@Test
	void quotedSegmentsAreLiteralKeys() {
		final Map<String, Object> attributes = TestUtils.mapOf("geo", TestUtils.mapOf("0", "zero", "a/b", "slash"));
		assertEquals("zero", SwitchbackAttributePath.parseSupported("geo/'0'").resolve(attributes));
		assertEquals("slash", SwitchbackAttributePath.parseSupported("geo/\"a/b\"").resolve(attributes));
		// An unterminated quoted tail is dropped, so the path resolves to the parent object.
		assertEquals(attributes.get("geo"), SwitchbackAttributePath.parseSupported("geo/'0").resolve(attributes));
	}

	@Test
	void separatorsAreCollapsed() {
		final Map<String, Object> attributes = TestUtils.mapOf("geo", TestUtils.mapOf("city", "Porto"));
		assertEquals("Porto", SwitchbackAttributePath.parseSupported("/geo//city/").resolve(attributes));
		assertEquals(attributes.get("geo"), SwitchbackAttributePath.parseSupported("geo").resolve(attributes));
	}

	@Test
	void rejectsUnsupportedPaths() {
		for (final String path : new String[]{null, "", "/", "//", "0/city", "items/0", "items/0/1/city",
				"items/-1/city", "'"}) {
			assertNull(SwitchbackAttributePath.parseSupported(path), String.valueOf(path));
		}
	}

	@Test
	void indexSegmentsReadOnlyListsAndKeySegmentsReadOnlyMaps() {
		final Map<String, Object> attributes = TestUtils.mapOf("items",
				TestUtils.listOf(TestUtils.mapOf("city", "Lisbon")), "geo", TestUtils.mapOf("0", TestUtils.mapOf("city", "zero")),
				"name", "scalar");
		assertNull(SwitchbackAttributePath.parseSupported("geo/0/city").resolve(attributes));
		assertNull(SwitchbackAttributePath.parseSupported("items/1/city").resolve(attributes));
		assertNull(SwitchbackAttributePath.parseSupported("name/x").resolve(attributes));
		assertNull(SwitchbackAttributePath.parseSupported("missing/x").resolve(attributes));
		assertEquals("Lisbon", SwitchbackAttributePath.parseSupported("items/-0/city").resolve(attributes));
	}

	@Test
	void tryParseIntFollowsGuava() {
		assertEquals(Integer.valueOf(0), SwitchbackAttributePath.tryParseInt("0"));
		assertEquals(Integer.valueOf(0), SwitchbackAttributePath.tryParseInt("-0"));
		assertEquals(Integer.valueOf(7), SwitchbackAttributePath.tryParseInt("007"));
		assertEquals(Integer.valueOf(Integer.MAX_VALUE), SwitchbackAttributePath.tryParseInt("2147483647"));
		assertEquals(Integer.valueOf(Integer.MIN_VALUE), SwitchbackAttributePath.tryParseInt("-2147483648"));
		for (final String s : new String[]{"", "-", "+1", "1a", " 1", "2147483648", "-2147483649",
				"99999999999999999999", "\u0660", "1.0"}) {
			assertNull(SwitchbackAttributePath.tryParseInt(s), s);
		}
	}
}
