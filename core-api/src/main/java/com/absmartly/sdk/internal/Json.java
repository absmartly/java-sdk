package com.absmartly.sdk.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;

import com.absmartly.sdk.DecodingException;
import com.absmartly.sdk.internal.json.ContextPayload;

/** The SDK's single Jackson configuration. Jackson is shaded, so none of its types leave the internal package. */
public final class Json {
	private Json() {}

	private static final ObjectMapper MAPPER = JsonMapperUtils.createStandardObjectMapper();
	private static final ObjectReader CONTEXT_READER = MAPPER.readerFor(ContextPayload.class);
	private static final ObjectWriter WRITER = new ObjectMapper().writer();

	public static ContextPayload decodeContext(final byte[] bytes) {
		try {
			final ContextPayload payload = CONTEXT_READER.readValue(bytes);
			if (payload == null) {
				throw new DecodingException("Context data is empty", null);
			}
			return payload;
		} catch (IOException e) {
			throw new DecodingException("Failed to decode context data: " + e.getMessage(), e);
		}
	}

	private static final ObjectReader EXACT_VARIABLES_READER = JsonMapperUtils.createStandardObjectMapper()
			.enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
			.enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
			.readerFor(java.util.Map.class);

	/**
	 * The top-level numeric variables of a variant config, as the exact BigDecimal/BigInteger written in the JSON.
	 * Empty when the config cannot be parsed or has no numbers.
	 */
	public static java.util.Map<String, Object> readExactNumbers(final String config) {
		try {
			final java.util.Map<?, ?> variables = EXACT_VARIABLES_READER.readValue(config);
			if (variables == null) {
				return java.util.Collections.emptyMap();
			}
			final java.util.Map<String, Object> numbers = new java.util.HashMap<String, Object>();
			for (final java.util.Map.Entry<?, ?> entry : variables.entrySet()) {
				if (entry.getValue() instanceof Number) {
					numbers.put(String.valueOf(entry.getKey()), entry.getValue());
				}
			}
			return numbers.isEmpty() ? java.util.Collections.<String, Object> emptyMap() : numbers;
		} catch (IOException e) {
			return java.util.Collections.emptyMap();
		}
	}

	public static String encode(final Object value) {
		return new String(encodeBytes(value), StandardCharsets.UTF_8);
	}

	public static byte[] encodeBytes(final Object value) {
		try {
			return WRITER.writeValueAsBytes(value);
		} catch (IOException e) {
			throw new DecodingException("Failed to encode " + value.getClass().getSimpleName() + ": " + e.getMessage(),
					e);
		}
	}

	/** Converts a parsed JSON value (maps, lists, strings, numbers, booleans) to {@code type}. */
	public static <T> T convert(final Object value, final Class<T> type) {
		try {
			return MAPPER.convertValue(value, type);
		} catch (IllegalArgumentException e) {
			throw new DecodingException("Cannot convert to " + type.getName() + ": " + e.getMessage(), e);
		}
	}
}
