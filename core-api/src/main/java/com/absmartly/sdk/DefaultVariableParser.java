package com.absmartly.sdk;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import javax.annotation.Nonnull;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.type.TypeFactory;

public class DefaultVariableParser implements VariableParser {
	private static final Logger log = LoggerFactory.getLogger(DefaultVariableParser.class);

	public DefaultVariableParser() {
		final ObjectMapper objectMapper = new ObjectMapper();
		objectMapper.enable(MapperFeature.USE_STATIC_TYPING);
		this.reader_ = objectMapper
				.readerFor(TypeFactory.defaultInstance().constructMapType(HashMap.class, String.class, Object.class));
	}

	public Map<String, Object> parse(@Nonnull final Context context, @Nonnull final String experimentName,
			@Nonnull final String variantName, @Nonnull final String config) {
		try {
			return reader_.readValue(config);
		} catch (IOException e) {
			log.error("", e);
			return null;
		}
	}

	private final ObjectReader reader_;
}
