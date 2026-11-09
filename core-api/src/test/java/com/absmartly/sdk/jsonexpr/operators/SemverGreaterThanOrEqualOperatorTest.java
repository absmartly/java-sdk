package com.absmartly.sdk.jsonexpr.operators;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class SemverGreaterThanOrEqualOperatorTest extends OperatorTest {
	final SemverGreaterThanOrEqualOperator operator = new SemverGreaterThanOrEqualOperator();

	@Test
	void testEvaluate() {
		assertEquals(true, operator.evaluate(evaluator, listOf("1.0.0", "1.0.0")));
		assertEquals(true, operator.evaluate(evaluator, listOf("2.0.0", "1.0.0")));
		assertEquals(false, operator.evaluate(evaluator, listOf("1.0.0", "2.0.0")));
		assertEquals(false, operator.evaluate(evaluator, listOf("1.9.0", "1.10.0")));
		assertEquals(true, operator.evaluate(evaluator, listOf("v1.2", "1.2.0+build")));
		assertEquals(false, operator.evaluate(evaluator, listOf("1.0.0-beta", "1.0.0")));
	}

	@Test
	void testUnparseable() {
		assertNull(operator.evaluate(evaluator, listOf("", "1.0.0")));
		assertNull(operator.evaluate(evaluator, listOf("v", "1.0.0")));
	}

	@Test
	void testNullOperands() {
		assertNull(operator.evaluate(evaluator, listOf(null, "1.0.0")));
		verify(evaluator, Mockito.timeout(5000).times(1)).evaluate(any());
		verify(evaluator, Mockito.timeout(5000).times(0)).stringConvert(any());

		Mockito.clearInvocations(evaluator);

		assertNull(operator.evaluate(evaluator, listOf("1.0.0", null)));
		verify(evaluator, Mockito.timeout(5000).times(2)).evaluate(any());
		verify(evaluator, Mockito.timeout(5000).times(0)).stringConvert(any());
	}
}
