package com.absmartly.sdk.jsonexpr.operators;

import java.util.List;

import com.absmartly.sdk.jsonexpr.Evaluator;
import com.absmartly.sdk.jsonexpr.Operator;

public abstract class BinaryOperator implements Operator {
	@Override
	public Object evaluate(Evaluator evaluator, Object args) {
		if (args instanceof List) {
			final List<Object> argsList = (List<Object>) args;
			final Object lhs = argsList.size() > 0 ? evaluator.evaluate(argsList.get(0)) : null;
			if (lhs != null) {
				final Object rhs = argsList.size() > 1 ? evaluator.evaluate(argsList.get(1)) : null;
				if (rhs != null) {
					return binary(evaluator, lhs, rhs);
				}
			}
		}
		return null;
	}

	public abstract Object binary(Evaluator evaluator, Object lhs, Object rhs);
}
