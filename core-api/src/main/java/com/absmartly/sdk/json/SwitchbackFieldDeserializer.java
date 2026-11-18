package com.absmartly.sdk.json;

import java.io.IOException;
import java.util.LinkedHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.util.TokenBuffer;

/**
 * Reads one switchback field of {@link Experiment}. A value of the wrong shape is not an error for the whole context:
 * the field is set to null with a warning, so 1.9.0 and earlier, which ignored these fields, and this release decode
 * the same payloads. Values of the right shape are read as Jackson reads them by default.
 * <p>
 * Public so that the README's ProGuard rule keeps it; uses only Jackson 2.12 API, as the 1.9.0 decoder did.
 */
public abstract class SwitchbackFieldDeserializer<T> extends JsonDeserializer<T> {
	private static final Logger log = LoggerFactory.getLogger(SwitchbackFieldDeserializer.class);

	abstract JavaType type(DeserializationContext ctxt);

	@Override
	public T deserialize(final JsonParser p, final DeserializationContext ctxt) throws IOException {
		final String field = p.getCurrentName();
		final Object current = p.getParsingContext().getCurrentValue();
		// Buffered as tokens rather than a tree: building a tree applies the mapper's duplicate-key
		// check, which would fail the whole context on a value 1.9.0 ignored.
		final TokenBuffer buffer = new TokenBuffer(p, ctxt);
		buffer.copyCurrentStructure(p);
		try {
			final JsonParser value = buffer.asParser(p.getCodec());
			value.nextToken();
			return ctxt.readValue(value, type(ctxt));
		} catch (final JsonProcessingException e) {
			return ignore(field, current, e.getOriginalMessage());
		} catch (final RuntimeException e) {
			// Jackson 2.15 to 2.17 throw ClassCastException reading an out-of-range Long from a buffer.
			return ignore(field, current, e.toString());
		}
	}

	private T ignore(final String field, final Object current, final String reason) {
		final String name = (current instanceof Experiment) ? ((Experiment) current).name : null;
		log.warn("Ignoring {} for experiment {}: {}", field, name != null ? "'" + name + "'" : "(unknown)", reason);
		return null;
	}

	public static final class ForString extends SwitchbackFieldDeserializer<String> {
		@Override
		JavaType type(final DeserializationContext ctxt) {
			return ctxt.constructType(String.class);
		}
	}

	public static final class ForLong extends SwitchbackFieldDeserializer<Long> {
		@Override
		JavaType type(final DeserializationContext ctxt) {
			return ctxt.constructType(Long.class);
		}
	}

	public static final class ForStringArray extends SwitchbackFieldDeserializer<String[]> {
		@Override
		JavaType type(final DeserializationContext ctxt) {
			return ctxt.constructType(String[].class);
		}
	}

	public static final class ForStringMap extends SwitchbackFieldDeserializer<LinkedHashMap<String, String>> {
		@Override
		JavaType type(final DeserializationContext ctxt) {
			return ctxt.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, String.class);
		}
	}
}
