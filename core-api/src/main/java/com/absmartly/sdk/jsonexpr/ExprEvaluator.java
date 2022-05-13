package com.absmartly.sdk.jsonexpr;

import static java.util.Collections.EMPTY_MAP;

import java.text.DecimalFormat;
import java.util.List;
import java.util.Map;

public class ExprEvaluator implements Evaluator {
	final static ThreadLocal<DecimalFormat> formatter = new ThreadLocal<DecimalFormat>() {
		@Override
		public DecimalFormat initialValue() {
			final DecimalFormat formatter = new DecimalFormat("#");
			formatter.setMaximumFractionDigits(15);
			formatter.setMinimumFractionDigits(0);
			formatter.setMinimumIntegerDigits(1);
			formatter.setGroupingUsed(false);
			return formatter;
		}
	};

	public ExprEvaluator(Map<String, Operator> operators, Map<String, Object> vars) {
		this.vars = vars;
		this.operators = operators;
	}

	@Override
	public Object evaluate(Object expr) {
		if (expr instanceof List) {
			return operators.get("and").evaluate(this, expr);
		} else if (expr instanceof Map) {
			final Map<String, Object> map = (Map<String, Object>) expr;
			for (final Map.Entry<String, Object> entry : map.entrySet()) {
				final Operator op = operators.get(entry.getKey());
				if (op != null) {
					return op.evaluate(this, entry.getValue());
				}
				break;
			}
		}
		return null;
	}

	@Override
	public Boolean booleanConvert(Object x) {
		if (x instanceof Boolean) {
			return (Boolean) x;
		} else if (x instanceof String) {
			return !x.equals("false") && !x.equals("0") && !x.equals("");
		} else if (x instanceof Number) {
			return ((Number) x).longValue() != 0;
		}

		return x != null;
	}

	@Override
	public Double numberConvert(Object x) {
		if (x instanceof Number) {
			return (x instanceof Double) ? (Double) x : ((Number) x).doubleValue();
		} else if (x instanceof Boolean) {
			return (Boolean) x ? 1.0 : 0.0;
		} else if (x instanceof String) {
			try {
				return Double.parseDouble((String) x); // use javascript semantics: numbers are doubles
			} catch (Throwable ignored) {}
		}

		return null;
	}

	@Override
	public String stringConvert(Object x) {
		if (x instanceof String) {
			return (String) x;
		} else if (x instanceof Boolean) {
			return x.toString();
		} else if (x instanceof Number) {
			return formatter.get().format(x);
		}
		return null;
	}

	@Override
	public Object extractVar(String path) {
		final String[] frags = path.split("/");

		Object target = vars != null ? vars : EMPTY_MAP;

		for (final String frag : frags) {
			Object value = null;
			if (target instanceof List) {
				final List<Object> list = (List<Object>) target;
				try {
					value = list.get(Integer.parseInt(frag));
				} catch (Throwable ignored) {}
			} else if (target instanceof Map) {
				final Map<String, Object> map = (Map<String, Object>) target;
				value = map.get(frag);
			}

			if (value != null) {
				target = value;
				continue;
			}

			return null;
		}

		return target;
	}

	@Override
	public Integer compare(Object lhs, Object rhs) {
		if (lhs == null) {
			return rhs == null ? 0 : null;
		} else if (rhs == null) {
			return null;
		}

		if (lhs instanceof Number) {
			final Double rvalue = numberConvert(rhs);
			if (rvalue != null) {
				return Double.compare(((Number) lhs).doubleValue(), rvalue);
			}
		} else if (lhs instanceof String) {
			final String rvalue = stringConvert(rhs);
			if (rvalue != null) {
				return ((String) lhs).compareTo(rvalue);
			}
		} else if (lhs instanceof Boolean) {
			final Boolean rvalue = booleanConvert(rhs);
			if (rvalue != null) {
				return ((Boolean) lhs).compareTo(rvalue);
			}
		} else if ((lhs.getClass() == rhs.getClass()) && (lhs.equals(rhs))) {
			return 0;
		}

		return null;
	}

	private final Map<String, Object> vars;
	private final Map<String, Operator> operators;
}
