package com.absmartly.sdk.cache;

import java.io.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.absmartly.sdk.json.ContextData;
import com.absmartly.sdk.json.PublishEvent;

public abstract class AbstractCache implements LocalCache {

	private final ObjectMapper mapper;

	public AbstractCache() {
		final ObjectMapper objectMapper = new ObjectMapper();
		objectMapper.enable(MapperFeature.USE_STATIC_TYPING);
		this.mapper = objectMapper;
	}

	public String serializeEvent(PublishEvent event) {
		return this.objectSerializer(event);
	}

	public PublishEvent deserializeEvent(String eventStr) {
		return (PublishEvent) this.objectDeserialize(eventStr, PublishEvent.class);
	}

	public String serializeContext(ContextData contextData) {
		return this.objectSerializer(contextData);
	}

	public ContextData deserializeContext(String contextStr) {
		return (ContextData) this.objectDeserialize(contextStr, ContextData.class);
	}

	private String objectSerializer(Object object) {
		try {
			return mapper.writeValueAsString(object);
		} catch (JsonProcessingException e) {
			throw new RuntimeException(e);
		}
	}

	private Object objectDeserialize(String eventStr, Class typeClass) {
		try {
			return mapper.readValue(eventStr, typeClass);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

}
