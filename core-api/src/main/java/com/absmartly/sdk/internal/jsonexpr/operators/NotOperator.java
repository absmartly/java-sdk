package com.absmartly.sdk.internal.jsonexpr.operators;

import com.absmartly.sdk.internal.jsonexpr.Evaluator;

public class NotOperator extends UnaryOperator {
	@Override
	public Object unary(Evaluator evaluator, Object args) {
		return !evaluator.booleanConvert(args);
	}
}
