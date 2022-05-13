package com.absmartly.sdk;

import java.util.List;
import java.util.Map;

import com.absmartly.sdk.java.nio.charset.StandardCharsets;
import com.absmartly.sdk.jsonexpr.JsonExpr;

public class AudienceMatcher {
	public AudienceMatcher(AudienceDeserializer deserializer) {
		deserializer_ = deserializer;
		jsonExpr_ = new JsonExpr();
	}

	public static class Result {
		public Result(boolean result) {
			this.result = result;
		}

		public boolean get() {
			return result;
		}

		private final boolean result;
	}

	public Result evaluate(String audience, Map<String, Object> attributes) {
		final byte[] bytes = audience.getBytes(StandardCharsets.UTF_8);
		final Map<String, Object> audienceMap = deserializer_.deserialize(bytes, 0, bytes.length);
		if (audienceMap != null) {
			final Object filter = audienceMap.get("filter");
			if (filter instanceof Map || filter instanceof List) {
				return new Result(jsonExpr_.evaluateBooleanExpr(filter, attributes));
			}
		}

		return null;
	}

	private final AudienceDeserializer deserializer_;
	private final JsonExpr jsonExpr_;
}
