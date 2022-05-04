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
		assertTrue((Boolean) operator.evaluate(evaluator, listOf("abcdefghijk", "abc")));
		assertTrue((Boolean) operator.evaluate(evaluator, listOf("abcdefghijk", "def")));
		assertFalse((Boolean) operator.evaluate(evaluator, listOf("abcdefghijk", "xxx")));
		assertNull(operator.evaluate(evaluator, listOf("abcdefghijk", null)));
		assertNull(operator.evaluate(evaluator, listOf(null, "abc")));

		verify(evaluator, times(4)).evaluate("abcdefghijk");
		verify(evaluator, times(1)).evaluate("abc");
		verify(evaluator, times(1)).evaluate("def");
		verify(evaluator, times(1)).evaluate("xxx");

		verify(evaluator, times(1)).stringConvert("abc");
		verify(evaluator, times(1)).stringConvert("def");
		verify(evaluator, times(1)).stringConvert("xxx");
	}

	@Test
	void testArrayEmpty() {
		assertFalse((Boolean) operator.evaluate(evaluator, listOf(listOf(), 1)));
		assertFalse((Boolean) operator.evaluate(evaluator, listOf(listOf(), "1")));
		assertFalse((Boolean) operator.evaluate(evaluator, listOf(listOf(), true)));
		assertFalse((Boolean) operator.evaluate(evaluator, listOf(listOf(), false)));
		assertNull(operator.evaluate(evaluator, listOf(listOf(), null)));

		verify(evaluator, times(0)).booleanConvert(any());
		verify(evaluator, times(0)).numberConvert(any());
		verify(evaluator, times(0)).stringConvert(any());
		verify(evaluator, times(0)).compare(any(), any());
	}

	@Test
	void testArrayCompares() {
		final List<Object> haystack01 = listOf(0, 1);
		final List<Object> haystack12 = listOf(1, 2);

		assertFalse((Boolean) operator.evaluate(evaluator, listOf(haystack01, 2)));
		verify(evaluator, times(2)).evaluate(any());
		verify(evaluator, times(1)).evaluate(haystack01);
		verify(evaluator, times(1)).evaluate(2);
		verify(evaluator, times(2)).compare(anyInt(), ArgumentMatchers.eq(2));

		Mockito.clearInvocations(evaluator);

		assertFalse((Boolean) operator.evaluate(evaluator, listOf(haystack12, 0)));
		verify(evaluator, times(2)).evaluate(any());
		verify(evaluator, times(1)).evaluate(haystack12);
		verify(evaluator, times(1)).evaluate(0);
		verify(evaluator, times(2)).compare(anyInt(), ArgumentMatchers.eq(0));

		Mockito.clearInvocations(evaluator);

		assertTrue((Boolean) operator.evaluate(evaluator, listOf(haystack12, 1)));
		verify(evaluator, times(2)).evaluate(any());
		verify(evaluator, times(1)).evaluate(haystack12);
		verify(evaluator, times(1)).evaluate(1);
		verify(evaluator, times(1)).compare(anyInt(), ArgumentMatchers.eq(1));

		Mockito.clearInvocations(evaluator);

		assertTrue((Boolean) operator.evaluate(evaluator, listOf(haystack12, 2)));
		verify(evaluator, times(2)).evaluate(any());
		verify(evaluator, times(1)).evaluate(haystack12);
		verify(evaluator, times(1)).evaluate(2);
		verify(evaluator, times(2)).compare(anyInt(), ArgumentMatchers.eq(2));

		Mockito.clearInvocations(evaluator);
	}

	@Test
	void testObject() {
		final Map<String, Object> haystackab = mapOf("a", 1, "b", 2);
		final Map<String, Object> haystackbc = mapOf("b", 2, "c", 3, "0", 100);

		assertFalse((Boolean) operator.evaluate(evaluator, listOf(haystackab, "c")));
		verify(evaluator, times(2)).evaluate(any());
		verify(evaluator, times(1)).evaluate(haystackab);
		verify(evaluator, times(1)).stringConvert(any());
		verify(evaluator, times(1)).stringConvert("c");
		verify(evaluator, times(1)).evaluate("c");
		Mockito.clearInvocations(evaluator);

		assertFalse((Boolean) operator.evaluate(evaluator, listOf(haystackbc, "a")));
		verify(evaluator, times(2)).evaluate(any());
		verify(evaluator, times(1)).evaluate(haystackbc);
		verify(evaluator, times(1)).stringConvert(any());
		verify(evaluator, times(1)).stringConvert("a");
		verify(evaluator, times(1)).evaluate("a");
		Mockito.clearInvocations(evaluator);

		assertTrue((Boolean) operator.evaluate(evaluator, listOf(haystackbc, "b")));
		verify(evaluator, times(2)).evaluate(any());
		verify(evaluator, times(1)).evaluate(haystackbc);
		verify(evaluator, times(1)).stringConvert(any());
		verify(evaluator, times(1)).stringConvert("b");
		verify(evaluator, times(1)).evaluate("b");
		Mockito.clearInvocations(evaluator);

		assertTrue((Boolean) operator.evaluate(evaluator, listOf(haystackbc, "c")));
		verify(evaluator, times(2)).evaluate(any());
		verify(evaluator, times(1)).evaluate(haystackbc);
		verify(evaluator, times(1)).stringConvert(any());
		verify(evaluator, times(1)).stringConvert("c");
		verify(evaluator, times(1)).evaluate("c");
		Mockito.clearInvocations(evaluator);

		assertTrue((Boolean) operator.evaluate(evaluator, listOf(haystackbc, 0)));
		verify(evaluator, times(2)).evaluate(any());
		verify(evaluator, times(1)).evaluate(haystackbc);
		verify(evaluator, times(1)).stringConvert(any());
		verify(evaluator, times(1)).stringConvert(0);
		verify(evaluator, times(1)).evaluate(0);
		Mockito.clearInvocations(evaluator);
	}
}
