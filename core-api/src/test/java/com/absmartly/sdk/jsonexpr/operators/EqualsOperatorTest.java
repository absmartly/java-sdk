package com.absmartly.sdk.jsonexpr.operators;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class EqualsOperatorTest extends OperatorTest {
	final EqualsOperator operator = new EqualsOperator();

	@Test
	void testEvaluate() {
		assertTrue((Boolean) operator.evaluate(evaluator, listOf(0, 0)));
		verify(evaluator, times(2)).evaluate(any());
		verify(evaluator, times(2)).evaluate(0);
		verify(evaluator, times(1)).compare(0, 0);

		Mockito.clearInvocations(evaluator);

		assertFalse((Boolean) operator.evaluate(evaluator, listOf(1, 0)));
		verify(evaluator, times(2)).evaluate(any());
		verify(evaluator, times(1)).evaluate(0);
		verify(evaluator, times(1)).evaluate(1);
		verify(evaluator, times(1)).compare(1, 0);

		Mockito.clearInvocations(evaluator);

		assertFalse((Boolean) operator.evaluate(evaluator, listOf(0, 1)));
		verify(evaluator, times(2)).evaluate(any());
		verify(evaluator, times(1)).evaluate(0);
		verify(evaluator, times(1)).evaluate(1);
		verify(evaluator, times(1)).compare(0, 1);

		Mockito.clearInvocations(evaluator);

		assertNull(operator.evaluate(evaluator, listOf(null, null)));
		verify(evaluator, times(1)).evaluate(any());
		verify(evaluator, times(0)).compare(any(), any());

		Mockito.clearInvocations(evaluator);

		assertTrue((Boolean) operator.evaluate(evaluator, listOf(listOf(1, 2), listOf(1, 2))));
		verify(evaluator, times(2)).evaluate(any());
		verify(evaluator, times(1)).compare(any(), any());

		Mockito.clearInvocations(evaluator);

		assertNull(operator.evaluate(evaluator, listOf(listOf(1, 2), listOf(2, 3))));
		verify(evaluator, times(2)).evaluate(any());
		verify(evaluator, times(1)).compare(any(), any());

		Mockito.clearInvocations(evaluator);

		assertTrue((Boolean) operator.evaluate(evaluator, listOf(mapOf("a", 1, "b", 2), mapOf("a", 1, "b", 2))));
		verify(evaluator, times(2)).evaluate(any());
		verify(evaluator, times(1)).compare(any(), any());

		Mockito.clearInvocations(evaluator);

		assertNull(operator.evaluate(evaluator, listOf(mapOf("a", 1, "b", 2), mapOf("a", 3, "b", 4))));
		verify(evaluator, times(2)).evaluate(any());
		verify(evaluator, times(1)).compare(any(), any());
	}
}
