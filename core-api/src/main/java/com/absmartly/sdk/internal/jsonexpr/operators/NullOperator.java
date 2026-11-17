package com.absmartly.sdk.internal.jsonexpr.operators;

import com.absmartly.sdk.internal.jsonexpr.Evaluator;

public class NullOperator extends UnaryOperator {
	@Override
	public Object unary(Evaluator evaluator, Object arg) {
		return arg == null;
	}
}
