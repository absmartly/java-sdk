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
		assertTrue(matcher.evaluate("{\"filter\":[{\"value\":5}]}", null));
		assertTrue(matcher.evaluate("{\"filter\":[{\"value\":true}]}", null));
		assertTrue(matcher.evaluate("{\"filter\":[{\"value\":1}]}", null));
		assertFalse(matcher.evaluate("{\"filter\":[{\"value\":null}]}", null));
		assertFalse(matcher.evaluate("{\"filter\":[{\"value\":0}]}", null));

		assertFalse(matcher.evaluate("{\"filter\":[{\"not\":{\"var\":\"returning\"}}]}", mapOf("returning", true)));
		assertTrue(matcher.evaluate("{\"filter\":[{\"not\":{\"var\":\"returning\"}}]}", mapOf("returning", false)));
	}
}
