package com.absmartly.sdk.internal.jsonexpr.operators;

import java.util.List;

import com.absmartly.sdk.internal.jsonexpr.Evaluator;

public class AndCombinator extends BooleanCombinator {
	@Override
	public Object combine(Evaluator evaluator, List<Object> exprs) {
		for (final Object expr : exprs) {
			if (!evaluator.booleanConvert(evaluator.evaluate(expr))) {
				return false;
			}
		}
		return true;
	}
}
