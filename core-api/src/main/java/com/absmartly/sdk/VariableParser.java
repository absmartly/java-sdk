package com.absmartly.sdk;

import java.util.Map;

import javax.annotation.Nonnull;

public interface VariableParser {
	Map<String, Object> parse(@Nonnull String experimentName, @Nonnull String variantName,
			@Nonnull String variableValue);
}
