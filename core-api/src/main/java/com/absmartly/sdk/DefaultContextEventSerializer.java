package com.absmartly.sdk;

import javax.annotation.Nonnull;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;

import com.absmartly.sdk.json.PublishEvent;

public class DefaultContextEventSerializer implements ContextEventSerializer {
	private static final Logger log = LoggerFactory.getLogger(DefaultContextEventSerializer.class);

	public DefaultContextEventSerializer() {
		final ObjectMapper objectMapper = new ObjectMapper();
		this.writer_ = objectMapper.writerFor(PublishEvent.class);
	}

	public DefaultContextEventSerializer(final ObjectWriter writer) {
		this.writer_ = writer;
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

	final private ObjectWriter writer_;
}
