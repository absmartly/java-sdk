package com.absmartly.sdk.json;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

/**
 * Reads {@link Experiment#assignmentRules}, which the API sends as a JSON string. An object or array in its place is
 * not an error for the whole context: the field is set to null with a warning, so only that experiment's rules are
 * skipped. Other scalars are coerced to their string form, as Jackson does for String fields.
 */
public class AssignmentRulesDeserializer extends JsonDeserializer<String> {
	private static final Logger log = LoggerFactory.getLogger(AssignmentRulesDeserializer.class);

	@Override
	public String deserialize(final JsonParser p, final DeserializationContext ctxt) throws IOException {
		final JsonToken token = p.getCurrentToken();
		if (token == JsonToken.START_OBJECT || token == JsonToken.START_ARRAY) {
			// The parser has already entered the value's own object/array context; the experiment being
			// populated is the current value of the enclosing one.
			final Object current = p.getParsingContext().getParent() != null
					? p.getParsingContext().getParent().getCurrentValue()
					: null;
			p.skipChildren();
			final String name = (current instanceof Experiment) ? ((Experiment) current).name : null;
			log.warn("Ignoring assignmentRules for experiment {}: expected a JSON string but got {}",
					name != null ? "'" + name + "'" : "(unknown)",
					token == JsonToken.START_OBJECT ? "an object" : "an array");
			return null;
		}
		if (token == JsonToken.VALUE_STRING) {
			return p.getText();
		}
		return token.isScalarValue() ? p.getValueAsString() : null;
	}
}
