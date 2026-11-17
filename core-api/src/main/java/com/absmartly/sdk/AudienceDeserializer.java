package com.absmartly.sdk;

import java.util.Map;


interface AudienceDeserializer {
	Map<String, Object> deserialize(final byte[] bytes, final int offset, final int length);
}
