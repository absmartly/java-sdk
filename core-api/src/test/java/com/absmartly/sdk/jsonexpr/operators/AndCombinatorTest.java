package com.absmartly.sdk.jsonexpr.operators;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class AndCombinatorTest extends OperatorTest {
	final AndCombinator combinator = new AndCombinator();

	@Test
	void testCombineTrue() {
		assertTrue((Boolean) combinator.combine(evaluator, listOf(true)));
		verify(evaluator, Mockito.timeout(5000).times(1)).booleanConvert(true);
		verify(evaluator, Mockito.timeout(5000).times(1)).evaluate(true);
	}

	@Test
	void testCombineFalse() {
		assertFalse((Boolean) combinator.combine(evaluator, listOf(false)));
		verify(evaluator, Mockito.timeout(5000).times(1)).booleanConvert(false);
		verify(evaluator, Mockito.timeout(5000).times(1)).evaluate(false);
	}

	@Test
	void testCombineNull() {
		assertFalse((Boolean) combinator.combine(evaluator, listOf((Object) null)));
		verify(evaluator, Mockito.timeout(5000).times(1)).booleanConvert(null);
		verify(evaluator, Mockito.timeout(5000).times(1)).evaluate(null);
	}

	@Test
	void testCombineShortCircuit() {
		assertFalse((Boolean) combinator.combine(evaluator, listOf(true, false, true)));
		verify(evaluator, Mockito.timeout(5000).times(1)).booleanConvert(true);
		verify(evaluator, Mockito.timeout(5000).times(1)).evaluate(true);

		verify(evaluator, Mockito.timeout(5000).times(1)).booleanConvert(false);
		verify(evaluator, Mockito.timeout(5000).times(1)).evaluate(false);
	}

	@Test
	void testCombine() {
		assertTrue((Boolean) combinator.combine(evaluator, listOf(true, true)));
		assertTrue((Boolean) combinator.combine(evaluator, listOf(true, true, true)));

		assertFalse((Boolean) combinator.combine(evaluator, listOf(true, false)));
		assertFalse((Boolean) combinator.combine(evaluator, listOf(false, true)));
		assertFalse((Boolean) combinator.combine(evaluator, listOf(false, false)));
		assertFalse((Boolean) combinator.combine(evaluator, listOf(false, false, false)));
	}
}
