package com.absmartly.sdk.jsonexpr.operators;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class MatchOperatorFixTest extends OperatorTest {
	final MatchOperator operator = new MatchOperator();

	@Test
	void testBoundedThreadPoolRejectsLongPatterns() {
		StringBuilder longPattern = new StringBuilder();
		for (int i = 0; i < 1001; i++) {
			longPattern.append("a");
		}
		assertNull(operator.evaluate(evaluator, listOf("test", longPattern.toString())));
	}

	@Test
	void testRejectsLongInputText() {
		StringBuilder longText = new StringBuilder();
		for (int i = 0; i < 10001; i++) {
			longText.append("a");
		}
		assertNull(operator.evaluate(evaluator, listOf(longText.toString(), "abc")));
	}

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
	void testInterruptibleCharSequenceBasicBehavior() {
		MatchOperator.InterruptibleCharSequence seq = new MatchOperator.InterruptibleCharSequence("hello");
		assertEquals(5, seq.length());
		assertEquals('h', seq.charAt(0));
		assertEquals('e', seq.charAt(1));
		assertEquals("ell", seq.subSequence(1, 4).toString());
		assertEquals("hello", seq.toString());
	}

	@Test
	void testInterruptibleCharSequenceThrowsOnInterrupt() {
		MatchOperator.InterruptibleCharSequence seq = new MatchOperator.InterruptibleCharSequence("hello");
		Thread.currentThread().interrupt();
		try {
			assertThrows(MatchOperator.InterruptibleCharSequence.InterruptedCharAccessException.class,
					() -> seq.charAt(0));
		} finally {
			Thread.interrupted();
		}
	}

	@Test
	void testPatternAtMaxLength() {
		StringBuilder pattern = new StringBuilder();
		for (int i = 0; i < 1000; i++) {
			pattern.append("a");
		}
		assertNotNull(operator.evaluate(evaluator, listOf("aaa", pattern.toString())));
	}

	@Test
	void testTextAtMaxLength() {
		StringBuilder text = new StringBuilder();
		for (int i = 0; i < 10000; i++) {
			text.append("a");
		}
		assertTrue((Boolean) operator.evaluate(evaluator, listOf(text.toString(), "aaa")));
	}
}
