package com.absmartly.sdk.internal;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

public class JsonMapperUtils {
	public static ObjectMapper createStandardObjectMapper() {
		final ObjectMapper objectMapper = new ObjectMapper();
		objectMapper.enable(MapperFeature.USE_STATIC_TYPING);
		objectMapper.enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);
		return objectMapper;
	}
}
