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
