package com.absmartly.sdk.jsonexpr.operators;

import static java.util.Collections.EMPTY_LIST;
import static java.util.Collections.EMPTY_MAP;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class ValueOperatorTest extends OperatorTest {
	final ValueOperator operator = new ValueOperator();

	@Test
	void testEvaluate() {
		assertEquals(0, operator.evaluate(evaluator, 0));
		assertEquals(1, operator.evaluate(evaluator, 1));
		assertEquals(true, operator.evaluate(evaluator, true));
		assertEquals(false, operator.evaluate(evaluator, false));
		assertEquals("", operator.evaluate(evaluator, ""));
		assertEquals(EMPTY_MAP, operator.evaluate(evaluator, EMPTY_MAP));
		assertEquals(EMPTY_LIST, operator.evaluate(evaluator, EMPTY_LIST));
		assertNull(operator.evaluate(evaluator, null));

		verify(evaluator, Mockito.timeout(5000).times(0)).evaluate(any());
	}
}
