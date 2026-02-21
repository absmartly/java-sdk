package com.absmartly.sdk.jsonexpr.operators;

import java.util.List;
import java.util.Map;

import com.absmartly.sdk.jsonexpr.Evaluator;

public class InOperator extends BinaryOperator {
	@Override
	public Object binary(Evaluator evaluator, Object haystack, Object needle) {
		if (haystack instanceof List) {
			for (final Object item : (List<Object>) haystack) {
				final Integer cmp = evaluator.compare(item, needle);
				if (cmp != null && cmp == 0) {
					return true;
				}
			}
			return false;
		} else if (haystack instanceof String) {
			final String needleString = evaluator.stringConvert(needle);
			return needleString != null && ((String) haystack).contains(needleString);
		} else if (haystack instanceof Map) {
			final String needleString = evaluator.stringConvert(needle);
			return needleString != null && ((Map<String, Object>) haystack).containsKey(needleString);
		}
		return null;
	}
}
