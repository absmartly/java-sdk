package com.absmartly.sdk;

import java.io.IOException;

import javax.annotation.Nonnull;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import com.absmartly.sdk.json.ContextData;

public class DefaultContextDataDeserializer implements ContextDataDeserializer {
	private static final Logger log = LoggerFactory.getLogger(DefaultContextDataDeserializer.class);

	public DefaultContextDataDeserializer() {
		final ObjectMapper objectMapper = new ObjectMapper();
		objectMapper.enable(MapperFeature.USE_STATIC_TYPING);
		objectMapper.registerModule(new JavaTimeModule());
		this.reader_ = objectMapper.readerFor(ContextData.class);
	}

	public ContextData deserialize(@Nonnull final byte[] bytes, final int offset, final int length) {
		try {
			return reader_.readValue(bytes, offset, length);
		} catch (IOException e) {
			log.error("", e);
			return null;
		}
	}

	private final ObjectReader reader_;
}
