package com.absmartly.sdk;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.absmartly.sdk.AudienceMatcher.RuleAction;
import com.absmartly.sdk.jsonexpr.JsonExpr;

class AudienceMatcherTest extends TestUtils {
	final AudienceMatcher matcher = new AudienceMatcher(new DefaultAudienceDeserializer());

	@Test
	void evaluateReturnsNullOnEmptyAudience() {
		assertNull(matcher.evaluate("", null));
		assertNull(matcher.evaluate("{}", null));
		assertNull(matcher.evaluate("null", null));
	}

	@Test
	void evaluateReturnsNullIfFilterNotMapOrList() {
		assertNull(matcher.evaluate("{\"filter\":null}", null));
		assertNull(matcher.evaluate("{\"filter\":false}", null));
		assertNull(matcher.evaluate("{\"filter\":5}", null));
		assertNull(matcher.evaluate("{\"filter\":\"a\"}", null));
	}

	@Test
	void evaluateReturnsBoolean() {
		assertTrue(matcher.evaluate("{\"filter\":[{\"value\":5}]}", null).get());
		assertTrue(matcher.evaluate("{\"filter\":[{\"value\":true}]}", null).get());
		assertTrue(matcher.evaluate("{\"filter\":[{\"value\":1}]}", null).get());
		assertFalse(matcher.evaluate("{\"filter\":[{\"value\":null}]}", null).get());
		assertFalse(matcher.evaluate("{\"filter\":[{\"value\":0}]}", null).get());

		assertFalse(
				matcher.evaluate("{\"filter\":[{\"not\":{\"var\":\"returning\"}}]}", mapOf("returning", true)).get());
		assertTrue(
				matcher.evaluate("{\"filter\":[{\"not\":{\"var\":\"returning\"}}]}", mapOf("returning", false)).get());
	}

	static final String US = "{\"and\":[{\"eq\":[{\"var\":\"country\"},{\"value\":\"US\"}]}]}";
	static final String GB = "{\"and\":[{\"eq\":[{\"var\":\"country\"},{\"value\":\"GB\"}]}]}";

	static String rules(String... rules) {
		return "{\"rules\":[" + String.join(",", rules) + "]}";
	}

	static String assign(String conditions, String environments, Object variant) {
		final StringBuilder b = new StringBuilder("{\"name\":\"rule\",\"type\":\"assign\"");
		if (conditions != null) {
			b.append(",\"conditions\":").append(conditions);
		}
		if (environments != null) {
			b.append(",\"environments\":").append(environments);
		}
		if (variant != null) {
			b.append(",\"variant\":").append(variant);
		}
		return b.append("}").toString();
	}

	static String split(String percentages) {
		return "{\"name\":\"split\",\"type\":\"split\",\"conditions\":" + US + ",\"environments\":[],\"percentages\":"
				+ percentages + "}";
	}

	static final String FALLBACK = assign(null, "[]", 2);

	final Map<String, Object> us = mapOf("country", (Object) "US");

	RuleAction evaluateUS(String... rules) {
		return matcher.evaluateRules(rules(rules), "production", us);
	}

	@Test
	void evaluateRulesReturnsNullWithoutRules() {
		assertNull(matcher.evaluateRules("{}", "production", mapOf()));
		assertNull(matcher.evaluateRules("{\"filter\":[]}", "production", mapOf()));
		assertNull(matcher.evaluateRules("{\"rules\":[]}", "production", mapOf()));
		assertNull(matcher.evaluateRules(null, "production", mapOf()));
	}

	@Test
	void evaluateRulesMatchesConditions() {
		final String rules = rules(assign(US, "[]", 1));
		assertEquals(RuleAction.assign(1), matcher.evaluateRules(rules, "production", us));
		assertNull(matcher.evaluateRules(rules, "production", mapOf("country", (Object) "GB")));
	}

	@Test
	void evaluateRulesEnvironments() {
		assertNull(evaluateUS(assign(US, "[\"staging\"]", 1)));

		final String both = rules(assign(US, "[\"production\",\"staging\"]", 2));
		assertEquals(RuleAction.assign(2), matcher.evaluateRules(both, "production", us));
		assertEquals(RuleAction.assign(2), matcher.evaluateRules(both, "staging", us));

		final String all = rules(assign("{\"value\":true}", "[]", 1));
		assertEquals(RuleAction.assign(1), matcher.evaluateRules(all, "production", mapOf()));
		assertEquals(RuleAction.assign(1), matcher.evaluateRules(all, "staging", mapOf()));
		assertEquals(RuleAction.assign(1), matcher.evaluateRules(all, null, mapOf()));

		assertNull(matcher.evaluateRules(rules(assign("{\"value\":true}", "[\"production\"]", 1)), null, mapOf()));
	}

	@Test
	void evaluateRulesEnvironmentNamesAreCaseSensitive() {
		final String rules = rules(assign(US, "[\"Production\"]", 1));
		assertNull(matcher.evaluateRules(rules, "production", us));
		assertEquals(RuleAction.assign(1), matcher.evaluateRules(rules, "Production", us));
	}

	@Test
	void evaluateRulesSkipsNonListEnvironments() {
		assertEquals(RuleAction.assign(2), evaluateUS(assign(null, "\"production\"", 1), FALLBACK));
		assertEquals(RuleAction.assign(1), evaluateUS(assign(null, "null", 1)));
		assertEquals(RuleAction.assign(1), evaluateUS(assign(null, null, 1)));
	}

	@Test
	void evaluateRulesFirstMatchWins() {
		assertEquals(RuleAction.assign(1), evaluateUS(assign(US, "[]", 1), assign(US, "[]", 2)));
		assertEquals(RuleAction.assign(2), evaluateUS(assign(GB, "[]", 1), assign(US, "[]", 2)));
	}

	@Test
	void evaluateRulesWithoutConditionsMatchesAll() {
		assertEquals(RuleAction.assign(3), evaluateUS(assign("null", "[]", 3)));
		assertEquals(RuleAction.assign(3), evaluateUS(assign(null, "[]", 3)));
	}

	@Test
	void evaluateRulesMalformed() {
		assertNull(matcher.evaluateRules("not json", "production", mapOf()));
		assertNull(matcher.evaluateRules("", "production", mapOf()));
		assertNull(matcher.evaluateRules("{\"rules\":\"not an array\"}", "production", mapOf()));
		assertNull(matcher.evaluateRules("{\"rules\":[null]}", "production", mapOf()));
		assertNull(matcher.evaluateRules("{\"rules\":[5, \"x\"]}", "production", mapOf()));
	}

	@Test
	void evaluateRulesInvalidVariant() {
		assertNull(evaluateUS(assign(null, "[]", null)));
		assertNull(evaluateUS(assign(null, "[]", "\"bad\"")));
		assertEquals(RuleAction.assign(2), evaluateUS(assign(null, "[]", "\"not a number\""), FALLBACK));
		assertEquals(RuleAction.assign(2), evaluateUS(assign(null, "[]", null), FALLBACK));
		assertEquals(RuleAction.assign(2), evaluateUS(assign(null, "[]", 1.5), FALLBACK));
		assertEquals(RuleAction.assign(2), evaluateUS(assign(null, "[]", "1e100"), FALLBACK));
		assertEquals(RuleAction.assign(1), evaluateUS(assign(null, "[]", "1.0")));
	}

	@Test
	void evaluateRulesTypes() {
		assertEquals(RuleAction.assign(2),
				evaluateUS("{\"name\":\"r\",\"type\":\"other\",\"environments\":[],\"variant\":1}", FALLBACK));
		assertNull(evaluateUS("{\"name\":\"r\",\"environments\":[],\"variant\":1}"));
	}

	@Test
	void evaluateRulesVariantZeroAndNegative() {
		assertEquals(RuleAction.assign(0), evaluateUS(assign("{\"value\":true}", "[]", 0)));
		assertEquals(RuleAction.assign(-1), evaluateUS(assign("null", "[]", -1)));
	}

	@Test
	void evaluateRulesSkipsNonObjectConditions() {
		assertEquals(RuleAction.assign(2), evaluateUS(assign("\"invalid\"", "[]", 1), FALLBACK));
		assertEquals(RuleAction.assign(2), evaluateUS(assign("[{\"value\":true}]", "[]", 1), FALLBACK));
		assertEquals(RuleAction.assign(2), evaluateUS(assign("[]", "[]", 1), FALLBACK));
	}

	@Test
	void evaluateRulesSkipsThrowingConditions() {
		final JsonExpr jsonExpr = spy(new JsonExpr());
		doThrow(new IllegalStateException("condition failed")).doCallRealMethod().when(jsonExpr)
				.evaluateBooleanExpr(any(), any());
		final AudienceMatcher throwing = new AudienceMatcher(new DefaultAudienceDeserializer(), jsonExpr);

		assertEquals(RuleAction.assign(2), throwing.evaluateRules(
				rules(assign("{\"and\":[{\"value\":true}]}", "[]", 1), FALLBACK), "production", mapOf()));
		verify(jsonExpr, times(1)).evaluateBooleanExpr(any(), any());
	}

	@Test
	void evaluateRulesSplitMatches() {
		assertEquals(RuleAction.split(new double[]{0.2, 0.3, 0.5}), evaluateUS(split("\"20/30/50\"")));
		assertEquals(RuleAction.split(new double[]{0.3333, 0.3333, 0.3333}),
				evaluateUS(split("\"33.33/33.33/33.33\"")));
		assertNull(matcher.evaluateRules(rules(split("\"50/50\"")), "production", mapOf("country", (Object) "GB")));
	}

	@Test
	void evaluateRulesSkipsInvalidSplits() {
		assertEquals(RuleAction.assign(2), evaluateUS(split("[50,50]"), FALLBACK));
		assertEquals(RuleAction.assign(2), evaluateUS(split("\"50/abc\""), FALLBACK));
		assertEquals(RuleAction.assign(2), evaluateUS(split("\"150/-50\""), FALLBACK));
		assertEquals(RuleAction.assign(2), evaluateUS(split("\"50/49\""), FALLBACK));
		assertEquals(RuleAction.assign(2), evaluateUS(split("\"Infinity/50\""), FALLBACK));
		assertEquals(RuleAction.assign(2), evaluateUS(split("\"\""), FALLBACK));
	}

	@ParameterizedTest
	@ValueSource(strings = {"50/50.014", "50/49.986"})
	void evaluateRulesAcceptsSplitWithinTolerance(String percentages) {
		final String[] parts = percentages.split("/");
		final double[] expected = new double[]{Double.parseDouble(parts[0]) / 100, Double.parseDouble(parts[1]) / 100};
		assertEquals(RuleAction.split(expected), evaluateUS(split("\"" + percentages + "\""), FALLBACK));
	}

	@ParameterizedTest
	@ValueSource(strings = {"50/50.015", "50/49.985"})
	void evaluateRulesSkipsSplitOutsideTolerance(String percentages) {
		assertEquals(RuleAction.assign(2), evaluateUS(split("\"" + percentages + "\""), FALLBACK));
	}

	@Test
	void parseFloatMatchesJavaScript() {
		assertEquals(50.0, AudienceMatcher.parseFloat("50"));
		assertEquals(50.0, AudienceMatcher.parseFloat(" 50abc"));
		assertEquals(0.5, AudienceMatcher.parseFloat(".5"));
		assertEquals(-1.5, AudienceMatcher.parseFloat("-1.5"));
		assertEquals(1500.0, AudienceMatcher.parseFloat("1.5e3x"));
		assertEquals(1.0, AudienceMatcher.parseFloat("1e"));
		assertEquals(2.0, AudienceMatcher.parseFloat("+2"));
		assertEquals(Double.POSITIVE_INFINITY, AudienceMatcher.parseFloat("Infinity"));
		assertEquals(Double.NEGATIVE_INFINITY, AudienceMatcher.parseFloat("-Infinity"));
		assertTrue(Double.isNaN(AudienceMatcher.parseFloat("abc")));
		assertTrue(Double.isNaN(AudienceMatcher.parseFloat("")));
		assertTrue(Double.isNaN(AudienceMatcher.parseFloat(".")));
	}

	@Test
	void ruleActionAccessors() {
		final RuleAction assign = RuleAction.assign(3);
		assertFalse(assign.isSplit());
		assertEquals(3, assign.getVariant());
		assertEquals("RuleAction{variant=3}", assign.toString());

		final double[] fractions = new double[]{0.5, 0.5};
		final RuleAction split = RuleAction.split(fractions);
		fractions[0] = 1.0;
		assertTrue(split.isSplit());
		assertArrayEquals(new double[]{0.5, 0.5}, split.getSplit());
		split.getSplit()[0] = 1.0;
		assertArrayEquals(new double[]{0.5, 0.5}, split.getSplit());
		assertEquals("RuleAction{split=[0.5, 0.5]}", split.toString());

		assertEquals(split, split);
		assertNotEquals(split, assign);
		assertNotEquals(assign, null);
		assertNotEquals(assign, "x");
		assertEquals(RuleAction.assign(3).hashCode(), assign.hashCode());
	}

	// Deeply nested expression trees, built directly so Jackson's own depth limit is not involved.
	static Object deepNot(int depth) {
		Object expr = mapOf("value", (Object) true);
		for (int i = 0; i < depth; ++i) {
			expr = mapOf("not", expr);
		}
		return expr;
	}

	@Test
	void ruleConditionsThatOverflowTheStackAreSkipped() {
		final Map<String, Object> deep = new HashMap<String, Object>();
		deep.put("name", "deep");
		deep.put("type", "assign");
		deep.put("variant", 1);
		deep.put("conditions", deepNot(200_000));
		final Map<String, Object> fallback = new HashMap<String, Object>();
		fallback.put("name", "fallback");
		fallback.put("type", "assign");
		fallback.put("variant", 2);
		final List<Object> rules = new ArrayList<Object>();
		rules.add(deep);
		rules.add(fallback);

		final AudienceMatcher.ParsedRules parsed = new AudienceMatcher.ParsedRules(rules);
		assertEquals(RuleAction.assign(2), matcher.evaluateRules(parsed, "production", us, "exp"));
	}

	@Test
	void audienceThatOverflowsTheStackIsIndeterminate() {
		final AudienceDeserializer deserializer = mock(AudienceDeserializer.class);
		when(deserializer.deserialize(any(), anyInt(), anyInt())).thenReturn(mapOf("filter", deepNot(200_000)));
		assertNull(new AudienceMatcher(deserializer).evaluate("{}", mapOf()));
	}

	@Test
	void audienceThatThrowsIsIndeterminate() {
		final JsonExpr jsonExpr = mock(JsonExpr.class);
		when(jsonExpr.evaluateBooleanExpr(any(), any())).thenThrow(new IllegalStateException("boom"));
		assertNull(new AudienceMatcher(new DefaultAudienceDeserializer(), jsonExpr)
				.evaluate("{\"filter\":[{\"value\":true}]}", mapOf()));
	}

	@Test
	void parseRulesFlagsInvalidJsonAndIgnoresMissingRules() {
		assertNull(matcher.parseRules(null));
		assertNull(matcher.parseRules(""));
		assertTrue(matcher.parseRules("{not json").isInvalid());
		assertFalse(matcher.parseRules("{}").isInvalid());
		assertNull(matcher.evaluateRules(matcher.parseRules("{}"), "production", us, "exp"));
		assertNull(matcher.evaluateRules(matcher.parseRules("{not json"), "production", us, "exp"));
		assertNull(matcher.evaluateRules((AudienceMatcher.ParsedRules) null, "production", us, "exp"));
	}

	@Test
	void ruleActionAccessorsRejectTheWrongKind() {
		assertThrows(IllegalStateException.class, () -> RuleAction.assign(1).getSplit());
		assertThrows(IllegalStateException.class, () -> RuleAction.split(new double[]{1.0}).getVariant());
		assertEquals(1, RuleAction.assign(1).getVariant());
		assertArrayEquals(new double[]{1.0}, RuleAction.split(new double[]{1.0}).getSplit());
	}
}
