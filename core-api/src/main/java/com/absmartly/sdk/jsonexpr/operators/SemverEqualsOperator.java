package com.absmartly.sdk.jsonexpr.operators;

import com.absmartly.sdk.jsonexpr.Evaluator;
import com.absmartly.sdk.jsonexpr.SemverComparator;

public class SemverEqualsOperator extends BinaryOperator {
	@Override
	public Object binary(Evaluator evaluator, Object lhs, Object rhs) {
		final Integer result = SemverComparator.compare(evaluator, lhs, rhs);
		return (result != null) ? (result == 0) : null;
	}
}
