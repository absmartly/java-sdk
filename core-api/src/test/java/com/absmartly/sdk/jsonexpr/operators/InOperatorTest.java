package com.absmartly.sdk.jsonexpr.operators;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

class InOperatorTest extends OperatorTest {
	final InOperator operator = new InOperator();

	@Test
	void testString() {
		assertTrue((Boolean) operator.evaluate(evaluator, listOf("abc", "abcdefghijk")));
		assertTrue((Boolean) operator.evaluate(evaluator, listOf("def", "abcdefghijk")));
		assertFalse((Boolean) operator.evaluate(evaluator, listOf("xxx", "abcdefghijk")));
		assertNull(operator.evaluate(evaluator, listOf(null, "abcdefghijk")));
		assertNull(operator.evaluate(evaluator, listOf("abc", null)));
	}

	@Test
	void testArrayEmpty() {
		assertFalse((Boolean) operator.evaluate(evaluator, listOf(1, listOf())));
		assertFalse((Boolean) operator.evaluate(evaluator, listOf("1", listOf())));
		assertFalse((Boolean) operator.evaluate(evaluator, listOf(true, listOf())));
		assertFalse((Boolean) operator.evaluate(evaluator, listOf(false, listOf())));
		assertNull(operator.evaluate(evaluator, listOf(null, listOf())));
	}

	@Test
	void testArrayCompares() {
		final List<Object> haystack01 = listOf(0, 1);
		final List<Object> haystack12 = listOf(1, 2);

		assertFalse((Boolean) operator.evaluate(evaluator, listOf(2, haystack01)));

		Mockito.clearInvocations(evaluator);

		assertFalse((Boolean) operator.evaluate(evaluator, listOf(0, haystack12)));

		Mockito.clearInvocations(evaluator);

		assertTrue((Boolean) operator.evaluate(evaluator, listOf(1, haystack12)));

		Mockito.clearInvocations(evaluator);

		assertTrue((Boolean) operator.evaluate(evaluator, listOf(2, haystack12)));

		Mockito.clearInvocations(evaluator);
	}

	@Test
	void testObject() {
		final Map<String, Object> haystackab = mapOf("a", 1, "b", 2);
		final Map<String, Object> haystackbc = mapOf("b", 2, "c", 3, "0", 100);

		assertFalse((Boolean) operator.evaluate(evaluator, listOf("c", haystackab)));
		Mockito.clearInvocations(evaluator);

		assertFalse((Boolean) operator.evaluate(evaluator, listOf("a", haystackbc)));
		Mockito.clearInvocations(evaluator);

		assertTrue((Boolean) operator.evaluate(evaluator, listOf("b", haystackbc)));
		Mockito.clearInvocations(evaluator);

		assertTrue((Boolean) operator.evaluate(evaluator, listOf("c", haystackbc)));
		Mockito.clearInvocations(evaluator);

		assertTrue((Boolean) operator.evaluate(evaluator, listOf(0, haystackbc)));
		Mockito.clearInvocations(evaluator);
	}
}
