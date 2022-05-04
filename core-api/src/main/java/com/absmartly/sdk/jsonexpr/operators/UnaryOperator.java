package com.absmartly.sdk.jsonexpr.operators;

import com.absmartly.sdk.jsonexpr.Evaluator;
import com.absmartly.sdk.jsonexpr.Operator;

public abstract class UnaryOperator implements Operator {
	@Override
	public Object evaluate(Evaluator evaluator, Object args) {
		final Object arg = evaluator.evaluate(args);
		return unary(evaluator, arg);
	}

	public abstract Object unary(Evaluator evaluator, Object arg);
}
