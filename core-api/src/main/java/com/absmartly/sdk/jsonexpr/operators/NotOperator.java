package com.absmartly.sdk.jsonexpr.operators;

import com.absmartly.sdk.jsonexpr.Evaluator;

public class NotOperator extends UnaryOperator {
	@Override
	public Object unary(Evaluator evaluator, Object args) {
		return !evaluator.booleanConvert(args);
	}
}
