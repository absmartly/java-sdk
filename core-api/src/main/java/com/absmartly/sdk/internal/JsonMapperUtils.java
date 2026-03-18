package com.absmartly.sdk.internal;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

public class JsonMapperUtils {
	public static ObjectMapper createStandardObjectMapper() {
		return JsonMapper.builder()
				.enable(MapperFeature.USE_STATIC_TYPING)
				.enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
				.build();
	}
}
