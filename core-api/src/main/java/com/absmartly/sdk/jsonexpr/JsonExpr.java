package com.absmartly.sdk.jsonexpr;

import java.util.HashMap;
import java.util.Map;

import com.absmartly.sdk.jsonexpr.operators.*;

public class JsonExpr {
	private static final Map<String, Operator> operators;

	static {
		operators = new HashMap<String, Operator>(16);

		operators.put("and", new AndCombinator());

		operators.put("or", new OrCombinator());

		operators.put("value", new ValueOperator());

		operators.put("var", new VarOperator());

		operators.put("null", new NullOperator());

		operators.put("not", new NotOperator());

		operators.put("in", new InOperator());

		operators.put("match", new MatchOperator());

		operators.put("eq", new EqualsOperator());

		operators.put("gt", new GreaterThanOperator());

		operators.put("gte", new GreaterThanOrEqualOperator());

		operators.put("lt", new LessThanOperator());

		operators.put("lte", new LessThanOrEqualOperator());
	}

	public boolean evaluateBooleanExpr(Object expr, Map<String, Object> vars) {
		final ExprEvaluator evaluator = new ExprEvaluator(operators, vars);
		return evaluator.booleanConvert(evaluator.evaluate(expr));
	}

	public Object evaluateExpr(Object expr, Map<String, Object> vars) {
		final ExprEvaluator evaluator = new ExprEvaluator(operators, vars);
		return evaluator.evaluate(expr);
	}
}
