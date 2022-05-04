package com.absmartly.sdk.jsonexpr;

import static java.util.Collections.EMPTY_LIST;
import static java.util.Collections.EMPTY_MAP;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import com.absmartly.sdk.TestUtils;

class ExprEvaluatorTest extends TestUtils {
	@Test
	void testEvaluateConsidersListAsAndCombinator() {
		final Operator andOperator = mock(Operator.class);
		final Operator orOperator = mock(Operator.class);

		when(andOperator.evaluate(any(), any())).thenReturn(true);

		final ExprEvaluator evaluator = new ExprEvaluator(mapOf("and", andOperator, "or", orOperator), EMPTY_MAP);

		final List<Object> args = listOf(mapOf("value", true), mapOf("value", false));
		assertNotNull(evaluator.evaluate(args));

		verify(orOperator, times(0)).evaluate(any(), any());
		verify(andOperator, times(1)).evaluate(any(Evaluator.class), ArgumentMatchers.eq(args));
	}

	@Test
	void testEvaluateReturnsNullIfOperatorNotFound() {
		final Operator valueOperator = mock(Operator.class);

		when(valueOperator.evaluate(any(), any())).thenReturn(true);

		final ExprEvaluator evaluator = new ExprEvaluator(mapOf("value", valueOperator), EMPTY_MAP);
		assertNull(evaluator.evaluate(mapOf("not_found", true)));

		verify(valueOperator, times(0)).evaluate(any(), any());
	}

	@Test
	void testEvaluateCallsOperatorWithArgs() {
		final Operator valueOperator = mock(Operator.class);

		final List<Object> args = listOf(1, 2, 3);

		when(valueOperator.evaluate(any(Evaluator.class), ArgumentMatchers.eq(args))).thenReturn(args);

		final ExprEvaluator evaluator = new ExprEvaluator(mapOf("value", valueOperator), EMPTY_MAP);
		assertEquals(args, evaluator.evaluate(mapOf("value", args)));

		verify(valueOperator, times(1)).evaluate(any(Evaluator.class), ArgumentMatchers.eq(args));
	}

	@Test
	void testBooleanConvert() {
		final ExprEvaluator evaluator = new ExprEvaluator(EMPTY_MAP, EMPTY_MAP);

		assertEquals(true, evaluator.booleanConvert(EMPTY_MAP));
		assertEquals(true, evaluator.booleanConvert(EMPTY_LIST));
		assertEquals(false, evaluator.booleanConvert(null));

		assertEquals(true, evaluator.booleanConvert(true));
		assertEquals(true, evaluator.booleanConvert(1));
		assertEquals(true, evaluator.booleanConvert(2));
		assertEquals(true, evaluator.booleanConvert("abc"));
		assertEquals(true, evaluator.booleanConvert("1"));

		assertEquals(false, evaluator.booleanConvert(false));
		assertEquals(false, evaluator.booleanConvert(0));
		assertEquals(false, evaluator.booleanConvert(""));
		assertEquals(false, evaluator.booleanConvert("0"));
		assertEquals(false, evaluator.booleanConvert("false"));
	}

	@Test
	void testNumberConvert() {
		final ExprEvaluator evaluator = new ExprEvaluator(EMPTY_MAP, EMPTY_MAP);

		assertNull(evaluator.numberConvert(EMPTY_MAP));
		assertNull(evaluator.numberConvert(EMPTY_LIST));
		assertNull(evaluator.numberConvert(null));
		assertNull(evaluator.numberConvert(""));
		assertNull(evaluator.numberConvert("abcd"));
		assertNull(evaluator.numberConvert("x1234"));

		assertEquals(1.0, evaluator.numberConvert(true));
		assertEquals(0.0, evaluator.numberConvert(false));

		assertEquals(-1.0, evaluator.numberConvert(-1.0));
		assertEquals(0.0, evaluator.numberConvert(0.0));
		assertEquals(1.0, evaluator.numberConvert(1.0));
		assertEquals(1.5, evaluator.numberConvert(1.5));
		assertEquals(2.0, evaluator.numberConvert(2.0));
		assertEquals(3.0, evaluator.numberConvert(3.0));

		assertEquals(-1.0, evaluator.numberConvert(-1));
		assertEquals(0.0, evaluator.numberConvert(0));
		assertEquals(1.0, evaluator.numberConvert(1));
		assertEquals(2.0, evaluator.numberConvert(2));
		assertEquals(3.0, evaluator.numberConvert(3));
		assertEquals(2147483647.0, evaluator.numberConvert(Integer.MAX_VALUE));
		assertEquals(-2147483647.0, evaluator.numberConvert(-Integer.MAX_VALUE));
		assertEquals(9007199254740991.0, evaluator.numberConvert(9007199254740991L));
		assertEquals(-9007199254740991.0, evaluator.numberConvert(-9007199254740991L));

		assertEquals(-1.0, evaluator.numberConvert("-1"));
		assertEquals(0.0, evaluator.numberConvert("0"));
		assertEquals(1.0, evaluator.numberConvert("1"));
		assertEquals(1.5, evaluator.numberConvert("1.5"));
		assertEquals(2.0, evaluator.numberConvert("2"));
		assertEquals(3.0, evaluator.numberConvert("3.0"));
	}

	@Test
	void testStringConvert() {
		final ExprEvaluator evaluator = new ExprEvaluator(EMPTY_MAP, EMPTY_MAP);

		assertNull(evaluator.stringConvert(null));
		assertNull(evaluator.stringConvert(EMPTY_MAP));
		assertNull(evaluator.stringConvert(EMPTY_LIST));

		assertEquals("true", evaluator.stringConvert(true));
		assertEquals("false", evaluator.stringConvert(false));

		assertEquals("", evaluator.stringConvert(""));
		assertEquals("abc", evaluator.stringConvert("abc"));

		assertEquals("-1", evaluator.stringConvert(-1.0));
		assertEquals("0", evaluator.stringConvert(0.0));
		assertEquals("1", evaluator.stringConvert(1.0));
		assertEquals("1.5", evaluator.stringConvert(1.5));
		assertEquals("2", evaluator.stringConvert(2.0));
		assertEquals("3", evaluator.stringConvert(3.0));
		assertEquals("2147483647", evaluator.stringConvert(2147483647.0));
		assertEquals("-2147483647", evaluator.stringConvert(-2147483647.0));
		assertEquals("9007199254740991", evaluator.stringConvert(9007199254740991.0));
		assertEquals("-9007199254740991", evaluator.stringConvert(-9007199254740991.0));
		assertEquals("0.900719925474099", evaluator.stringConvert(0.9007199254740991));
		assertEquals("-0.900719925474099", evaluator.stringConvert(-0.9007199254740991));

		assertEquals("-1", evaluator.stringConvert(-1));
		assertEquals("0", evaluator.stringConvert(0));
		assertEquals("1", evaluator.stringConvert(1));
		assertEquals("2", evaluator.stringConvert(2));
		assertEquals("3", evaluator.stringConvert(3));
		assertEquals("2147483647", evaluator.stringConvert(2147483647));
		assertEquals("-2147483647", evaluator.stringConvert(-2147483647));
		assertEquals("9007199254740991", evaluator.stringConvert(9007199254740991L));
		assertEquals("-9007199254740991", evaluator.stringConvert(-9007199254740991L));
	}

	@Test
	void testExtractVar() {
		final Map<String, Object> vars = mapOf(
				"a", 1,
				"b", true,
				"c", false,
				"d", listOf(1, 2, 3),
				"e", listOf(1, mapOf("z", 2), 3),
				"f", mapOf("y", mapOf("x", 3, "0", 10)));

		final ExprEvaluator evaluator = new ExprEvaluator(EMPTY_MAP, vars);

		assertEquals(1, evaluator.extractVar("a"));
		assertEquals(true, evaluator.extractVar("b"));
		assertEquals(false, evaluator.extractVar("c"));
		assertEquals(listOf(1, 2, 3), evaluator.extractVar("d"));
		assertEquals(listOf(1, mapOf("z", 2), 3), evaluator.extractVar("e"));
		assertEquals(mapOf("y", mapOf("x", 3, "0", 10)), evaluator.extractVar("f"));

		assertNull(evaluator.extractVar("a/0"));
		assertNull(evaluator.extractVar("a/b"));
		assertNull(evaluator.extractVar("b/0"));
		assertNull(evaluator.extractVar("b/e"));

		assertEquals(1, evaluator.extractVar("d/0"));
		assertEquals(2, evaluator.extractVar("d/1"));
		assertEquals(3, evaluator.extractVar("d/2"));
		assertNull(evaluator.extractVar("d/3"));

		assertEquals(1, evaluator.extractVar("e/0"));
		assertEquals(2, evaluator.extractVar("e/1/z"));
		assertEquals(3, evaluator.extractVar("e/2"));
		assertNull(evaluator.extractVar("e/1/0"));

		assertEquals(mapOf("x", 3, "0", 10), evaluator.extractVar("f/y"));
		assertEquals(3, evaluator.extractVar("f/y/x"));
		assertEquals(10, evaluator.extractVar("f/y/0"));
	}

	@Test
	void testCompareNull() {
		final ExprEvaluator evaluator = new ExprEvaluator(EMPTY_MAP, EMPTY_MAP);

		assertEquals(0, evaluator.compare(null, null));

		assertNull(evaluator.compare(null, 0));
		assertNull(evaluator.compare(null, 1));
		assertNull(evaluator.compare(null, true));
		assertNull(evaluator.compare(null, false));
		assertNull(evaluator.compare(null, ""));
		assertNull(evaluator.compare(null, "abc"));
		assertNull(evaluator.compare(null, EMPTY_MAP));
		assertNull(evaluator.compare(null, EMPTY_LIST));
	}

	@Test
	void testCompareObjects() {
		final ExprEvaluator evaluator = new ExprEvaluator(EMPTY_MAP, EMPTY_MAP);

		assertNull(evaluator.compare(EMPTY_MAP, 0));
		assertNull(evaluator.compare(EMPTY_MAP, 1));
		assertNull(evaluator.compare(EMPTY_MAP, true));
		assertNull(evaluator.compare(EMPTY_MAP, false));
		assertNull(evaluator.compare(EMPTY_MAP, ""));
		assertNull(evaluator.compare(EMPTY_MAP, "abc"));
		assertEquals(0, evaluator.compare(EMPTY_MAP, EMPTY_MAP));
		assertEquals(0, evaluator.compare(mapOf("a", 1), mapOf("a", 1)));
		assertNull(evaluator.compare(mapOf("a", 1), mapOf("b", 2)));
		assertNull(evaluator.compare(EMPTY_MAP, EMPTY_LIST));

		assertNull(evaluator.compare(EMPTY_LIST, 0));
		assertNull(evaluator.compare(EMPTY_LIST, 1));
		assertNull(evaluator.compare(EMPTY_LIST, true));
		assertNull(evaluator.compare(EMPTY_LIST, false));
		assertNull(evaluator.compare(EMPTY_LIST, ""));
		assertNull(evaluator.compare(EMPTY_LIST, "abc"));
		assertNull(evaluator.compare(EMPTY_LIST, EMPTY_MAP));
		assertEquals(0, evaluator.compare(EMPTY_LIST, EMPTY_LIST));
		assertEquals(0, evaluator.compare(listOf(1, 2), listOf(1, 2)));
		assertNull(evaluator.compare(listOf(1, 2), listOf(3, 4)));
	}

	@Test
	void testCompareBooleans() {
		final ExprEvaluator evaluator = new ExprEvaluator(EMPTY_MAP, EMPTY_MAP);

		assertEquals(0, evaluator.compare(false, 0));
		assertEquals(-1, evaluator.compare(false, 1));
		assertEquals(-1, evaluator.compare(false, true));
		assertEquals(0, evaluator.compare(false, false));
		assertEquals(0, evaluator.compare(false, ""));
		assertEquals(-1, evaluator.compare(false, "abc"));
		assertEquals(-1, evaluator.compare(false, EMPTY_MAP));
		assertEquals(-1, evaluator.compare(false, EMPTY_LIST));

		assertEquals(1, evaluator.compare(true, 0));
		assertEquals(0, evaluator.compare(true, 1));
		assertEquals(0, evaluator.compare(true, true));
		assertEquals(1, evaluator.compare(true, false));
		assertEquals(1, evaluator.compare(true, ""));
		assertEquals(0, evaluator.compare(true, "abc"));
		assertEquals(0, evaluator.compare(true, EMPTY_MAP));
		assertEquals(0, evaluator.compare(true, EMPTY_LIST));
	}

	@Test
	void testCompareNumbers() {
		final ExprEvaluator evaluator = new ExprEvaluator(EMPTY_MAP, EMPTY_MAP);

		assertEquals(0, evaluator.compare(0, 0));
		assertEquals(-1, evaluator.compare(0, 1));
		assertEquals(-1, evaluator.compare(0, true));
		assertEquals(0, evaluator.compare(0, false));
		assertNull(evaluator.compare(0, ""));
		assertNull(evaluator.compare(0, "abc"));
		assertNull(evaluator.compare(0, EMPTY_MAP));
		assertNull(evaluator.compare(0, EMPTY_LIST));

		assertEquals(1, evaluator.compare(1, 0));
		assertEquals(0, evaluator.compare(1, 1));
		assertEquals(0, evaluator.compare(1, true));
		assertEquals(1, evaluator.compare(1, false));
		assertNull(evaluator.compare(1, ""));
		assertNull(evaluator.compare(1, "abc"));
		assertNull(evaluator.compare(1, EMPTY_MAP));
		assertNull(evaluator.compare(1, EMPTY_LIST));

		assertEquals(0, evaluator.compare(1.0, 1));
		assertEquals(1, evaluator.compare(1.5, 1));
		assertEquals(1, evaluator.compare(2.0, 1));
		assertEquals(1, evaluator.compare(3.0, 1));

		assertEquals(0, evaluator.compare(1, 1.0));
		assertEquals(-1, evaluator.compare(1, 1.5));
		assertEquals(-1, evaluator.compare(1, 2.0));
		assertEquals(-1, evaluator.compare(1, 3.0));

		assertEquals(0, evaluator.compare(9007199254740991L, 9007199254740991L));
		assertEquals(-1, evaluator.compare(0, 9007199254740991L));
		assertEquals(1, evaluator.compare(9007199254740991L, 0));

		assertEquals(0, evaluator.compare(9007199254740991.0, 9007199254740991.0));
		assertEquals(-1, evaluator.compare(0.0, 9007199254740991.0));
		assertEquals(1, evaluator.compare(9007199254740991.0, 0.0));
	}

	@Test
	void testCompareStrings() {
		final ExprEvaluator evaluator = new ExprEvaluator(EMPTY_MAP, EMPTY_MAP);

		assertEquals(0, evaluator.compare("", ""));
		assertEquals(0, evaluator.compare("abc", "abc"));
		assertEquals(0, evaluator.compare("0", 0));
		assertEquals(0, evaluator.compare("1", 1));
		assertEquals(0, evaluator.compare("true", true));
		assertEquals(0, evaluator.compare("false", false));
		assertNull(evaluator.compare("", EMPTY_MAP));
		assertNull(evaluator.compare("abc", EMPTY_MAP));
		assertNull(evaluator.compare("", EMPTY_LIST));
		assertNull(evaluator.compare("abc", EMPTY_LIST));

		assertEquals(-1, evaluator.compare("abc", "bcd"));
		assertEquals(1, evaluator.compare("bcd", "abc"));
		assertEquals(-1, evaluator.compare("0", "1"));
		assertEquals(1, evaluator.compare("1", "0"));
		assertEquals(8, evaluator.compare("9", "100"));
		assertEquals(-8, evaluator.compare("100", "9"));
	}
}
