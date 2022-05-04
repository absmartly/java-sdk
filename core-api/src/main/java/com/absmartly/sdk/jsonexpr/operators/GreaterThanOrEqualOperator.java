package com.absmartly.sdk.jsonexpr.operators;

import com.absmartly.sdk.jsonexpr.Evaluator;

public class GreaterThanOrEqualOperator extends BinaryOperator {
	@Override
	public Object binary(Evaluator evaluator, Object lhs, Object rhs) {
		final Integer result = evaluator.compare(lhs, rhs);
		return (result != null) ? (result >= 0) : null;
	}
}
