package com.absmartly.sdk.jsonexpr.operators;

import com.absmartly.sdk.jsonexpr.Evaluator;

public class NullOperator extends UnaryOperator {
	@Override
	public Object unary(Evaluator evaluator, Object arg) {
		return arg == null;
	}
}
