package com.absmartly.sdk.jsonexpr.operators;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class GreaterThanOrEqualOperatorTest extends OperatorTest {
	final GreaterThanOrEqualOperator operator = new GreaterThanOrEqualOperator();

	@Test
	void testEvaluate() {
		assertTrue((Boolean) operator.evaluate(evaluator, listOf(0, 0)));
		verify(evaluator, times(2)).evaluate(any());
		verify(evaluator, times(2)).evaluate(0);
		verify(evaluator, times(1)).compare(0, 0);

		Mockito.clearInvocations(evaluator);

		assertTrue((Boolean) operator.evaluate(evaluator, listOf(1, 0)));
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
	}
}
