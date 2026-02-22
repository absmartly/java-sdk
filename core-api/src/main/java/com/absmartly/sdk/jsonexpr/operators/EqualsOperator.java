package com.absmartly.sdk.jsonexpr.operators;

import java.util.List;

import com.absmartly.sdk.jsonexpr.Evaluator;
import com.absmartly.sdk.jsonexpr.Operator;

public class EqualsOperator implements Operator {
	@Override
	public Object evaluate(Evaluator evaluator, Object args) {
		if (args instanceof List) {
			final List<Object> argsList = (List<Object>) args;
			final Object lhs = argsList.size() > 0 ? evaluator.evaluate(argsList.get(0)) : null;
			final Object rhs = argsList.size() > 1 ? evaluator.evaluate(argsList.get(1)) : null;
			if (lhs == null && rhs == null) {
				return true;
			}
			if (lhs == null || rhs == null) {
				return null;
			}
			final Integer result = evaluator.compare(lhs, rhs);
			return (result != null) ? (result == 0) : null;
		}
		return null;
	}
}
