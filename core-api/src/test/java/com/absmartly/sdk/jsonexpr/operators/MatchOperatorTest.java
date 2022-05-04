package com.absmartly.sdk.jsonexpr.operators;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class MatchOperatorTest extends OperatorTest {
	final MatchOperator operator = new MatchOperator();

	@Test
	void testEvaluate() {
		assertTrue((Boolean) operator.evaluate(evaluator, listOf("abcdefghijk", "")));
		assertTrue((Boolean) operator.evaluate(evaluator, listOf("abcdefghijk", "abc")));
		assertTrue((Boolean) operator.evaluate(evaluator, listOf("abcdefghijk", "ijk")));
		assertTrue((Boolean) operator.evaluate(evaluator, listOf("abcdefghijk", "^abc")));
		assertTrue((Boolean) operator.evaluate(evaluator, listOf(",l5abcdefghijk", "ijk$")));
		assertTrue((Boolean) operator.evaluate(evaluator, listOf("abcdefghijk", "def")));
		assertTrue((Boolean) operator.evaluate(evaluator, listOf("abcdefghijk", "b.*j")));
		assertFalse((Boolean) operator.evaluate(evaluator, listOf("abcdefghijk", "xyz")));

		assertNull(operator.evaluate(evaluator, listOf(null, "abc")));
		assertNull(operator.evaluate(evaluator, listOf("abcdefghijk", null)));
	}
}
