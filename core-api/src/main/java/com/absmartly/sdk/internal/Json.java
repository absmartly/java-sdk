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
	 * One top-level variable of a variant config, with numbers as the exact BigDecimal/BigInteger written in the JSON.
	 * Null when the config cannot be parsed or has no such key.
	 */
	public static @org.jspecify.annotations.Nullable Object readExactVariable(final String config, final String key) {
		try {
			final java.util.Map<?, ?> variables = EXACT_VARIABLES_READER.readValue(config);
			return (variables != null) ? variables.get(key) : null;
		} catch (IOException e) {
			return null;
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
