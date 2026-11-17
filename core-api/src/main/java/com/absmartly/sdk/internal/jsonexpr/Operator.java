package com.absmartly.sdk.internal.jsonexpr;

public interface Operator {
	Object evaluate(Evaluator evaluator, Object args);
}
