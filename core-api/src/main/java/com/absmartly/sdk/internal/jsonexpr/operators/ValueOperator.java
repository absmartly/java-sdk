package com.absmartly.sdk.internal.jsonexpr.operators;

import com.absmartly.sdk.internal.jsonexpr.Evaluator;
import com.absmartly.sdk.internal.jsonexpr.Operator;

public class ValueOperator implements Operator {
	@Override
	public Object evaluate(Evaluator evaluator, Object value) {
		return value;
	}
}
