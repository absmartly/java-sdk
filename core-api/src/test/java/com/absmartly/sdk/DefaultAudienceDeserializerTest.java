package com.absmartly.sdk;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.absmartly.sdk.java.nio.charset.StandardCharsets;

class DefaultAudienceDeserializerTest extends TestUtils {
	@Test
	void deserialize() {
		final DefaultAudienceDeserializer deser = new DefaultAudienceDeserializer();
		final String audience = "{\"filter\":[{\"gte\":[{\"var\":\"age\"},{\"value\":20}]}]}";
		final byte[] bytes = audience.getBytes(StandardCharsets.UTF_8);

		final Object expected = mapOf("filter", listOf(mapOf("gte", listOf(mapOf("var", "age"), mapOf("value", 20)))));
		final Object actual = deser.deserialize(bytes, 0, bytes.length);
		assertEquals(expected, actual);
	}

	@Test
	void deserializeDoesNotThrow() {
		final DefaultAudienceDeserializer deser = new DefaultAudienceDeserializer();
		final String audience = "{\"filter\":[{\"gte\":[{\"var\":\"age\"},{\"value\":20}]}]}";
		final byte[] bytes = audience.getBytes(StandardCharsets.UTF_8);

		assertDoesNotThrow(() -> {
			final Object actual = deser.deserialize(bytes, 0, 14);
			assertNull(actual);
		});
	}
}
