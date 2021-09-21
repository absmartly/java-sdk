package com.absmartly.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;

import org.junit.jupiter.api.Test;

class ContextConfigTest {
	@Test
	void setUnit() {
		final ContextConfig config = ContextConfig.create()
				.setUnit("session_id", "0ab1e23f4eee");
		assertEquals("0ab1e23f4eee", config.getUnit("session_id"));
	}

	@Test
	void setUnits() {
		final Map<String, String> units = TestUtils.mapOf("session_id", "0ab1e23f4eee", "user_id",
				Long.toString(0xabcdef));
		final ContextConfig config = ContextConfig.create()
				.setUnits(units);

		assertEquals("0ab1e23f4eee", config.getUnit("session_id"));
		assertEquals(Long.toString(0xabcdef), config.getUnit("user_id"));
		assertEquals(units, config.getUnits());
	}

	@Test
	void setAttribute() {
		final ContextConfig config = ContextConfig.create()
				.setAttribute("user_agent", "Chrome")
				.setAttribute("age", 9);
		assertEquals("Chrome", config.getAttribute("user_agent"));
		assertEquals(9, config.getAttribute("age"));
	}

	@Test
	void setAttributes() {
		final Map<String, Object> attributes = TestUtils.mapOf("user_agent", "Chrome", "age", 9);
		final ContextConfig config = ContextConfig.create()
				.setAttributes(attributes);
		assertEquals("Chrome", config.getAttribute("user_agent"));
		assertEquals(9, config.getAttribute("age"));
		assertEquals(attributes, config.getAttributes());
	}

	@Test
	void setOverride() {
		final ContextConfig config = ContextConfig.create()
				.setOverride("exp_test", 2);
		assertEquals(2, config.getOverride("exp_test"));
	}

	@Test
	void setOverrides() {
		final Map<String, Integer> overrides = TestUtils.mapOf("exp_test", 2, "exp_test_new", 1);
		final ContextConfig config = ContextConfig.create()
				.setOverrides(overrides);
		assertEquals(2, config.getOverride("exp_test"));
		assertEquals(1, config.getOverride("exp_test_new"));
		assertEquals(overrides, config.getOverrides());
	}

	@Test
	void setCustomAssignment() {
		final ContextConfig config = ContextConfig.create()
				.setCustomAssignment("exp_test", 2);
		assertEquals(2, config.getCustomAssignment("exp_test"));
	}

	@Test
	void setCustomAssignments() {
		final Map<String, Integer> cassignments = TestUtils.mapOf("exp_test", 2, "exp_test_new", 1);
		final ContextConfig config = ContextConfig.create()
				.setCustomAssignments(cassignments);
		assertEquals(2, config.getCustomAssignment("exp_test"));
		assertEquals(1, config.getCustomAssignment("exp_test_new"));
		assertEquals(cassignments, config.getCustomAssignments());
	}

	@Test
	void setPublishDelay() {
		final ContextConfig config = ContextConfig.create()
				.setPublishDelay(999);
		assertEquals(999, config.getPublishDelay());
	}
}
