package com.absmartly.sdk.jsonexpr.operators;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;

class OrCombinatorTest extends OperatorTest {

	final OrCombinator combinator = new OrCombinator();

	@Test
	void testCombineTrue() {
		assertTrue((Boolean) combinator.combine(evaluator, listOf(true)));
		verify(evaluator, times(1)).booleanConvert(true);
		verify(evaluator, times(1)).evaluate(true);
	}

	@Test
	void testCombineFalse() {
		assertFalse((Boolean) combinator.combine(evaluator, listOf(false)));
		verify(evaluator, times(1)).booleanConvert(false);
		verify(evaluator, times(1)).evaluate(false);
	}

	@Test
	void testCombineNull() {
		assertFalse((Boolean) combinator.combine(evaluator, listOf((Object) null)));
		verify(evaluator, times(1)).booleanConvert(null);
		verify(evaluator, times(1)).evaluate(null);
	}

	@Test
	void testCombineShortCircuit() {
		assertTrue((Boolean) combinator.combine(evaluator, listOf(true, false, true)));
		verify(evaluator, times(1)).booleanConvert(true);
		verify(evaluator, times(1)).evaluate(true);

		verify(evaluator, times(0)).booleanConvert(false);
		verify(evaluator, times(0)).evaluate(false);
	}

	@Test
	void testCombine() {
		assertTrue((Boolean) combinator.combine(evaluator, listOf(true, true)));
		assertTrue((Boolean) combinator.combine(evaluator, listOf(true, true, true)));

		assertTrue((Boolean) combinator.combine(evaluator, listOf(true, false)));
		assertTrue((Boolean) combinator.combine(evaluator, listOf(false, true)));
		assertFalse((Boolean) combinator.combine(evaluator, listOf(false, false)));
		assertFalse((Boolean) combinator.combine(evaluator, listOf(false, false, false)));
	}
}
