package com.absmartly.sdk.jsonexpr.operators;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class MatchOperatorFixTest extends OperatorTest {
	final MatchOperator operator = new MatchOperator();

	@Test
	void testNormalMatchingStillWorks() {
		assertTrue((Boolean) operator.evaluate(evaluator, listOf("abcdefghijk", "abc")));
		assertFalse((Boolean) operator.evaluate(evaluator, listOf("abcdefghijk", "xyz")));
	}

	@Test
	void testInvalidRegexReturnsNull() {
		assertNull(operator.evaluate(evaluator, listOf("test", "[")));
	}

	@Test
	void testNullArgumentsReturnNull() {
		assertNull(operator.evaluate(evaluator, listOf(null, "abc")));
		assertNull(operator.evaluate(evaluator, listOf("abc", null)));
	}

	@Test
	void longPatternIsNotRejected() {
		StringBuilder pattern = new StringBuilder();
		for (int i = 0; i < 1001; i++) {
			pattern.append("a");
		}
		assertEquals(Boolean.TRUE, operator.evaluate(evaluator, listOf(pattern.toString(), pattern.toString())));
	}

	@Test
	void longTextIsNotRejected() {
		StringBuilder text = new StringBuilder();
		for (int i = 0; i < 10001; i++) {
			text.append("a");
		}
		assertEquals(Boolean.TRUE, operator.evaluate(evaluator, listOf(text.toString(), "aaa")));
	}
}
