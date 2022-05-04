package com.absmartly.sdk.jsonexpr.operators;

import com.absmartly.sdk.jsonexpr.Evaluator;
import com.absmartly.sdk.jsonexpr.Operator;

public class ValueOperator implements Operator {
	@Override
	public Object evaluate(Evaluator evaluator, Object value) {
		return value;
	}
}
