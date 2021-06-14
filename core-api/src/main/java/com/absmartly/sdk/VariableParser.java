package com.absmartly.sdk;

import java.util.Map;

import javax.annotation.Nonnull;

public interface VariableParser {
	Map<String, Object> parse(@Nonnull final Context context, @Nonnull final String experimentName,
			@Nonnull final String variantName,
			@Nonnull String variableValue);
}
