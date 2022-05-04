package com.absmartly.sdk;

import java.util.Map;

import javax.annotation.Nonnull;

public interface AudienceDeserializer {
	Map<String, Object> deserialize(@Nonnull final byte[] bytes, final int offset, final int length);
}
