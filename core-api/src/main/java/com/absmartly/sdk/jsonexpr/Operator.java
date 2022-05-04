package com.absmartly.sdk.jsonexpr;

public interface Operator {
	Object evaluate(Evaluator evaluator, Object args);
}
