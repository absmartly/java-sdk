package com.absmartly.sdk;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class AudienceMatcherTest extends TestUtils {
	final AudienceMatcher matcher = new AudienceMatcher(new DefaultAudienceDeserializer());

	@Test
	void evaluateReturnsNullOnEmptyAudience() {
		assertNull(matcher.evaluate("", null));
		assertNull(matcher.evaluate("{}", null));
		assertNull(matcher.evaluate("null", null));
	}

	@Test
	void evaluateReturnsNullIfFilterNotMapOrList() {
		assertNull(matcher.evaluate("{\"filter\":null}", null));
		assertNull(matcher.evaluate("{\"filter\":false}", null));
		assertNull(matcher.evaluate("{\"filter\":5}", null));
		assertNull(matcher.evaluate("{\"filter\":\"a\"}", null));
	}

	@Test
	void evaluateReturnsBoolean() {
		assertTrue(matcher.evaluate("{\"filter\":[{\"value\":5}]}", null).get());
		assertTrue(matcher.evaluate("{\"filter\":[{\"value\":true}]}", null).get());
		assertTrue(matcher.evaluate("{\"filter\":[{\"value\":1}]}", null).get());
		assertFalse(matcher.evaluate("{\"filter\":[{\"value\":null}]}", null).get());
		assertFalse(matcher.evaluate("{\"filter\":[{\"value\":0}]}", null).get());

		assertFalse(
				matcher.evaluate("{\"filter\":[{\"not\":{\"var\":\"returning\"}}]}", mapOf("returning", true)).get());
		assertTrue(
				matcher.evaluate("{\"filter\":[{\"not\":{\"var\":\"returning\"}}]}", mapOf("returning", false)).get());
	}
}
