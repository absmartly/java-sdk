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

import com.absmartly.sdk.json.PublishEvent;

public class DefaultContextEventSerializer implements ContextEventSerializer {
	private static final Logger log = LoggerFactory.getLogger(DefaultContextEventSerializer.class);

	public DefaultContextEventSerializer() {
		final ObjectMapper objectMapper = new ObjectMapper();
		this.writer_ = objectMapper.writerFor(PublishEvent.class);

		objectMapper.enable(MapperFeature.USE_STATIC_TYPING);
		this.reader_ = objectMapper.readerFor(PublishEvent.class);
	}

	public DefaultContextEventSerializer(final ObjectWriter writer, final ObjectReader reader) {
		this.writer_ = writer;
		this.reader_ = reader;
	}

	@Override
	public byte[] serialize(@Nonnull final PublishEvent event) {
		try {
			return writer_.writeValueAsBytes(event);
		} catch (JsonProcessingException e) {
			log.error("", e);
			return null;
		}
	}

	@Override
	public PublishEvent deserialize(@Nonnull byte[] bytes, int offset, int length) {
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
