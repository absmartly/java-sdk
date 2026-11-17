package com.absmartly.sdk.internal.jsonexpr.operators;

import com.absmartly.sdk.internal.jsonexpr.Evaluator;
import com.absmartly.sdk.internal.jsonexpr.SemverComparator;

public class SemverLessThanOperator extends BinaryOperator {
	@Override
	public Object binary(Evaluator evaluator, Object lhs, Object rhs) {
		final Integer result = SemverComparator.compare(evaluator, lhs, rhs);
		return (result != null) ? (result < 0) : null;
	}
}
