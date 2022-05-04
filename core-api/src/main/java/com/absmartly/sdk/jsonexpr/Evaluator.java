package com.absmartly.sdk.jsonexpr;

public interface Evaluator {
	Object evaluate(Object expr);

	Boolean booleanConvert(Object x);

	Number numberConvert(Object x);

	String stringConvert(Object x);

	Object extractVar(String path);

	Integer compare(Object lhs, Object rhs); // returns -1 -> lesser, 0 -> equals, 1 -> greater, null -> undefined comparison
}
