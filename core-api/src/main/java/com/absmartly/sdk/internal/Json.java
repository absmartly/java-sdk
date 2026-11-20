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

	/** Marks a top-level number whose exact value could not be decoded: a typed read of it falls back. */
	public static final Object UNDECODABLE = new Object();

	/**
	 * The top-level numeric variables of a variant config, as the exact BigDecimal/BigInteger written in the JSON.
	 * Each top-level value is decoded on its own, so a value the exact decoder cannot represent (an exponent beyond
	 * BigDecimal's range, anywhere inside it) affects only its own key, which maps to {@link #UNDECODABLE}. Empty
	 * when the config is not a JSON object.
	 */
	public static java.util.Map<String, Object> readExactNumbers(final String config) {
		final java.util.Map<String, Object> numbers = new java.util.HashMap<String, Object>();
		try (com.fasterxml.jackson.core.JsonParser parser = MAPPER.getFactory().createParser(config)) {
			if (parser.nextToken() != com.fasterxml.jackson.core.JsonToken.START_OBJECT) {
				return java.util.Collections.emptyMap();
			}
			while (parser.nextToken() == com.fasterxml.jackson.core.JsonToken.FIELD_NAME) {
				final String key = parser.getCurrentName();
				final com.fasterxml.jackson.core.JsonToken value = parser.nextToken();
				if ((value == com.fasterxml.jackson.core.JsonToken.VALUE_NUMBER_INT)
						|| (value == com.fasterxml.jackson.core.JsonToken.VALUE_NUMBER_FLOAT)) {
					numbers.put(key, exactNumber(parser, value));
				} else {
					parser.skipChildren();
				}
			}
		} catch (IOException | RuntimeException e) {
			// The config itself is malformed; DefaultVariableParser has already logged and rejected it.
			return java.util.Collections.emptyMap();
		}
		return numbers.isEmpty() ? java.util.Collections.<String, Object> emptyMap() : numbers;
	}

	private static Object exactNumber(final com.fasterxml.jackson.core.JsonParser parser,
			final com.fasterxml.jackson.core.JsonToken token) {
		try {
			return (token == com.fasterxml.jackson.core.JsonToken.VALUE_NUMBER_INT) ? parser.getBigIntegerValue()
					: parser.getDecimalValue();
		} catch (IOException | RuntimeException e) {
			return UNDECODABLE;
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
