package com.absmartly.sdk;

import java.util.Map;


interface VariableParser {
	Map<String, Object> parse(final Context context, final String experimentName,
			final String variantName,
			String variableValues);

	Object parse(final Context context, final String experimentName, String variableValue);
}
