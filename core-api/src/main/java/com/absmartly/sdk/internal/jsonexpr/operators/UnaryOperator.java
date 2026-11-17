package com.absmartly.sdk.internal.jsonexpr.operators;

import com.absmartly.sdk.internal.jsonexpr.Evaluator;
import com.absmartly.sdk.internal.jsonexpr.Operator;

public abstract class UnaryOperator implements Operator {
	@Override
	public Object evaluate(Evaluator evaluator, Object args) {
		final Object arg = evaluator.evaluate(args);
		return unary(evaluator, arg);
	}

	public abstract Object unary(Evaluator evaluator, Object arg);
}
