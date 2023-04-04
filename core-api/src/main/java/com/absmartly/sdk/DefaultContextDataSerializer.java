package com.absmartly.sdk;

import java.io.IOException;

import javax.annotation.Nonnull;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;

import com.absmartly.sdk.json.ContextData;

public class DefaultContextDataSerializer implements ContextDataSerializer {
	private static final Logger log = LoggerFactory.getLogger(DefaultContextDataSerializer.class);

	public DefaultContextDataSerializer() {
		final ObjectMapper objectMapper = new ObjectMapper();
		objectMapper.enable(MapperFeature.USE_STATIC_TYPING);

		this.writer_ = objectMapper.writerFor(ContextData.class);
		this.reader_ = objectMapper.readerFor(ContextData.class);
	}

	public DefaultContextDataSerializer(final ObjectWriter writer, final ObjectReader reader) {
		this.writer_ = writer;
		this.reader_ = reader;
	}

	@Override
	public byte[] serialize(@Nonnull ContextData contextData) {
		try {
			return writer_.writeValueAsBytes(contextData);
		} catch (JsonProcessingException e) {
			log.error("", e);
			return null;
		}
	}

	public ContextData deserialize(@Nonnull final byte[] bytes, final int offset, final int length) {
		try {
			return reader_.readValue(bytes, offset, length);
		} catch (IOException e) {
			log.error("", e);
			return null;
		}
	}

	final private ObjectWriter writer_;
	private final ObjectReader reader_;
}
