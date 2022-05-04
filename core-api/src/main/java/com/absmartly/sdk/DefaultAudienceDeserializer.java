package com.absmartly.sdk;

import java.io.IOException;
import java.util.Map;

import javax.annotation.Nonnull;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;

public class DefaultAudienceDeserializer implements AudienceDeserializer {
	private static final Logger log = LoggerFactory.getLogger(DefaultAudienceDeserializer.class);

	private final ObjectReader reader_;

	public DefaultAudienceDeserializer() {
		final ObjectMapper objectMapper = new ObjectMapper();
		objectMapper.enable(MapperFeature.USE_STATIC_TYPING);
		this.reader_ = objectMapper.readerForMapOf(Object.class);
	}

	@Override
	public Map<String, Object> deserialize(@Nonnull byte[] bytes, int offset, int length) {
		try {
			return reader_.readValue(bytes, offset, length);
		} catch (IOException e) {
			log.error("", e);
			return null;
		}
	}
}
