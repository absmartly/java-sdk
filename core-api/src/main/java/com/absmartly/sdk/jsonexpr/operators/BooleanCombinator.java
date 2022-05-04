package com.absmartly.sdk.jsonexpr.operators;

import java.util.List;

import com.absmartly.sdk.jsonexpr.Evaluator;
import com.absmartly.sdk.jsonexpr.Operator;

public abstract class BooleanCombinator implements Operator {
	@Override
	public Object evaluate(Evaluator evaluator, Object args) {
		if (args instanceof List) {
			final List<Object> argsList = (List<Object>) args;
			return combine(evaluator, argsList);
		}
		return null;
	}

	public abstract Object combine(Evaluator evaluator, List<Object> args);
}
