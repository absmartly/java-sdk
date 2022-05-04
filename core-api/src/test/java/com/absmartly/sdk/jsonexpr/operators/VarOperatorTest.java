package com.absmartly.sdk.jsonexpr.operators;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;

class VarOperatorTest extends OperatorTest {
	final VarOperator operator = new VarOperator();

	@Test
	void testEvaluate() {
		assertEquals("abc", operator.evaluate(evaluator, "a/b/c"));

		verify(evaluator, times(1)).extractVar(any());
		verify(evaluator, times(1)).extractVar("a/b/c");
		verify(evaluator, times(0)).evaluate(any());
	}
}
