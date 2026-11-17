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
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Reads one switchback field of {@link Experiment}. A value of the wrong shape is not an error for the whole context:
 * the field is set to null with a warning, so 1.9.0 and earlier, which ignored these fields, and this release decode
 * the same payloads. Values of the right shape are read as Jackson reads them by default.
 */
abstract class SwitchbackFieldDeserializer<T> extends JsonDeserializer<T> {
	private static final Logger log = LoggerFactory.getLogger(SwitchbackFieldDeserializer.class);

	abstract JavaType type(DeserializationContext ctxt);

	@Override
	public T deserialize(final JsonParser p, final DeserializationContext ctxt) throws IOException {
		final String field = p.getCurrentName();
		final Object current = p.getParsingContext().getCurrentValue();
		final JsonNode node = p.readValueAsTree();
		try {
			return ctxt.readTreeAsValue(node, type(ctxt));
		} catch (final JsonProcessingException e) {
			final String name = (current instanceof Experiment) ? ((Experiment) current).name : null;
			log.warn("Ignoring {} for experiment {}: {}", field, name != null ? "'" + name + "'" : "(unknown)",
					e.getOriginalMessage());
			return null;
		}
	}

	static final class ForString extends SwitchbackFieldDeserializer<String> {
		@Override
		JavaType type(final DeserializationContext ctxt) {
			return ctxt.constructType(String.class);
		}
	}

	static final class ForLong extends SwitchbackFieldDeserializer<Long> {
		@Override
		JavaType type(final DeserializationContext ctxt) {
			return ctxt.constructType(Long.class);
		}
	}

	static final class ForStringArray extends SwitchbackFieldDeserializer<String[]> {
		@Override
		JavaType type(final DeserializationContext ctxt) {
			return ctxt.constructType(String[].class);
		}
	}

	static final class ForStringMap extends SwitchbackFieldDeserializer<LinkedHashMap<String, String>> {
		@Override
		JavaType type(final DeserializationContext ctxt) {
			return ctxt.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, String.class);
		}
	}
}
