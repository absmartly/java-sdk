package com.absmartly.sdk;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.Test;

class DefaultVariableParserTest {
	@Test
	void parse() {
		final Context context = mock(Context.class);
		final String configValue = new String(TestUtils.getResourceBytes("variables.json"), StandardCharsets.UTF_8);

		final VariableParser variableParser = new DefaultVariableParser();
		final Map<String, Object> variables = variableParser.parse(context, "test_exp", "B", configValue);

		assertEquals(TestUtils.mapOf(
				"a", 1,
				"b", "test",
				"c", TestUtils.mapOf(
						"test", 2,
						"double", 19.123,
						"list", TestUtils.listOf("x", "y", "z"),
						"point", TestUtils.mapOf(
								"x", -1.0,
								"y", 0.0,
								"z", 1.0)),
				"d", true,
				"f", TestUtils.listOf(9234567890L, "a", true, false),
				"g", 9.123), variables);
	}

	@Test
	void parseDoesNotThrow() {
		final Context context = mock(Context.class);
		final String configValue = new String(TestUtils.getResourceBytes("variables.json"), 0, 5,
				StandardCharsets.UTF_8);

		final VariableParser variableParser = new DefaultVariableParser();

		assertDoesNotThrow(() -> {
			final Map<String, Object> variables = variableParser.parse(context, "test_exp", "B", configValue);
			assertNull(variables);
		});
	}
}
