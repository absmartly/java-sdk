package com.absmartly.sdk.internal.jsonexpr.operators;

import java.util.Map;

import com.absmartly.sdk.internal.jsonexpr.Evaluator;
import com.absmartly.sdk.internal.jsonexpr.Operator;

public class VarOperator implements Operator {
	@Override
	public Object evaluate(Evaluator evaluator, Object path) {
		if (path instanceof Map) {
			path = ((Map<String, Object>) path).get("path");
		}

		return (path instanceof String) ? evaluator.extractVar((String) path) : null;
	}
}
