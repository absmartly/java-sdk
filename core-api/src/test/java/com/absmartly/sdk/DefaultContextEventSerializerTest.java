package com.absmartly.sdk;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectWriter;

import com.absmartly.sdk.json.Attribute;
import com.absmartly.sdk.json.GoalAchievement;
import com.absmartly.sdk.json.PublishEvent;
import com.absmartly.sdk.json.Unit;

class DefaultContextEventSerializerTest {
	@Test
	void serialize() {
		final PublishEvent event = new PublishEvent();
		event.hashed = true;
		event.publishedAt = 123456789L;
		event.units = new Unit[]{
				new Unit("session_id", "pAE3a1i5Drs5mKRNq56adA"),
				new Unit("user_id", "JfnnlDI7RTiF9RgfG2JNCw"),
		};

		final Map<String, Object> stringMap = TestUtils.mapOf(
				"amount", 6,
				"value", 5.0,
				"tries", 1,
				"nested", TestUtils.mapOf("value", 5),
				"nested_arr", TestUtils.mapOf("nested", TestUtils.listOf(1, 2, "test")));

		event.goals = new GoalAchievement[]{
				new GoalAchievement("goal1", 123456000L, new TreeMap<>(stringMap)),
				new GoalAchievement("goal2", 123456789L, null),
		};

		event.attributes = new Attribute[]{
				new Attribute("attr1", "value1", 123456000L),
				new Attribute("attr2", "value2", 123456789L),
				new Attribute("attr2", null, 123450000L),
				new Attribute("attr3", TestUtils.mapOf("nested", TestUtils.mapOf("value", 5)), 123470000L),
				new Attribute("attr4", TestUtils.mapOf("nested", TestUtils.listOf(1, 2, "test")), 123480000L),
		};

		final ContextEventSerializer ser = new DefaultContextEventSerializer();
		final byte[] bytes = ser.serialize(event);

		assertEquals(
				"{\"hashed\":true,\"units\":[{\"type\":\"session_id\",\"uid\":\"pAE3a1i5Drs5mKRNq56adA\"},{\"type\":\"user_id\",\"uid\":\"JfnnlDI7RTiF9RgfG2JNCw\"}],\"publishedAt\":123456789,\"goals\":[{\"name\":\"goal1\",\"achievedAt\":123456000,\"properties\":{\"amount\":6,\"nested\":{\"value\":5},\"nested_arr\":{\"nested\":[1,2,\"test\"]},\"tries\":1,\"value\":5.0}},{\"name\":\"goal2\",\"achievedAt\":123456789}],\"attributes\":[{\"name\":\"attr1\",\"value\":\"value1\",\"setAt\":123456000},{\"name\":\"attr2\",\"value\":\"value2\",\"setAt\":123456789},{\"name\":\"attr2\",\"setAt\":123450000},{\"name\":\"attr3\",\"value\":{\"nested\":{\"value\":5}},\"setAt\":123470000},{\"name\":\"attr4\",\"value\":{\"nested\":[1,2,\"test\"]},\"setAt\":123480000}]}",
				new String(bytes, StandardCharsets.UTF_8));
	}

	@Test
	void serializeDoesNotThrow() throws JsonProcessingException {
		final PublishEvent event = new PublishEvent();
		final ObjectWriter writer = mock(ObjectWriter.class);

		when(writer.writeValueAsBytes(event)).thenThrow(mock(JsonProcessingException.class));
		final ContextEventSerializer ser = new DefaultContextEventSerializer(writer);
		assertDoesNotThrow(() -> {
			final byte[] bytes = ser.serialize(event);
			assertNull(bytes);
		});
	}
}
