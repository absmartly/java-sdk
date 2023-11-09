package com.absmartly.sdk.jsonexpr.operators;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class VarOperatorTest extends OperatorTest {
	final VarOperator operator = new VarOperator();

	@Test
	void testEvaluate() {
		assertEquals("abc", operator.evaluate(evaluator, "a/b/c"));

		verify(evaluator, Mockito.timeout(5000).times(1)).extractVar(any());
		verify(evaluator, Mockito.timeout(5000).times(1)).extractVar("a/b/c");
		verify(evaluator, Mockito.timeout(5000).times(0)).evaluate(any());
	}
}
