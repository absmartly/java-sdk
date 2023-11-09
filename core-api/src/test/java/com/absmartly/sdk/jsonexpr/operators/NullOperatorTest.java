package com.absmartly.sdk.jsonexpr.operators;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class NullOperatorTest extends OperatorTest {
	final NullOperator operator = new NullOperator();

	@Test
	void testNull() {
		assertTrue((Boolean) operator.evaluate(evaluator, null));
		verify(evaluator, Mockito.timeout(5000).times(1)).evaluate(null);
	}

	@Test
	void testNotNull() {
		assertFalse((Boolean) operator.evaluate(evaluator, true));
		verify(evaluator, Mockito.timeout(5000).times(1)).evaluate(true);

		assertFalse((Boolean) operator.evaluate(evaluator, false));
		verify(evaluator, Mockito.timeout(5000).times(1)).evaluate(false);

		assertFalse((Boolean) operator.evaluate(evaluator, 0));
		verify(evaluator, Mockito.timeout(5000).times(1)).evaluate(0);
	}
}
