package com.absmartly.sdk.jsonexpr.operators;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class NotOperatorTest extends OperatorTest {
	final NotOperator operator = new NotOperator();

	@Test
	void testFalse() {
		assertTrue((Boolean) operator.evaluate(evaluator, false));
		verify(evaluator, Mockito.timeout(5000).times(1)).evaluate(false);
		verify(evaluator, Mockito.timeout(5000).times(1)).booleanConvert(false);
	}

	@Test
	void testTrue() {
		assertFalse((Boolean) operator.evaluate(evaluator, true));
		verify(evaluator, Mockito.timeout(5000).times(1)).evaluate(true);
		verify(evaluator, Mockito.timeout(5000).times(1)).booleanConvert(true);
	}

	@Test
	void testNull() {
		assertTrue((Boolean) operator.evaluate(evaluator, null));
		verify(evaluator, Mockito.timeout(5000).times(1)).evaluate(null);
		verify(evaluator, Mockito.timeout(5000).times(1)).booleanConvert(null);
	}
}
