package com.absmartly.sdk;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.absmartly.sdk.event.EventListener;

class ContextOptionsTest extends TestUtils {
	@Test
	void defaults() {
		final ContextOptions options = ContextOptions.builder().build();
		assertTrue(options.units().isEmpty());
		assertTrue(options.attributes().isEmpty());
		assertTrue(options.overrides().isEmpty());
		assertTrue(options.customAssignments().isEmpty());
		assertEquals(Duration.ofMillis(100), options.publishDelay());
		assertEquals(100, options.publishDelayMillis());
		assertEquals(Duration.ZERO, options.refreshInterval());
		assertEquals(0, options.refreshIntervalMillis());
		assertFalse(options.includeSystemAttributes());
		assertNull(options.eventListener());
	}

	@Test
	void unitsShortcut() {
		final ContextOptions options = ContextOptions.units(mapOf("session_id", "abc", "user_id", 125));
		assertEquals(mapOf("session_id", "abc", "user_id", 125), options.units());
	}

	@Test
	void builderSetsEverything() {
		final EventListener listener = event -> {};
		final ContextOptions options = ContextOptions.builder()
				.unit("session_id", "0ab1e23f4eee")
				.unit("user_id", 42)
				.units(mapOf("email", "a@b.c"))
				.attribute("user_agent", "Chrome")
				.attributes(mapOf("age", 9))
				.attribute("nothing", null)
				.override("exp_a", 2)
				.overrides(mapOf("exp_b", 1))
				.customAssignment("exp_c", 3)
				.customAssignments(mapOf("exp_d", 0))
				.publishDelay(Duration.ofSeconds(2))
				.refreshInterval(Duration.ofMinutes(5))
				.includeSystemAttributes(true)
				.eventListener(listener)
				.build();

		assertEquals(Arrays.asList("session_id", "user_id", "email"),
				Arrays.asList(options.units().keySet().toArray()));
		assertEquals(42, options.units().get("user_id"));
		final Map<String, Object> attributes = new HashMap<>();
		attributes.put("user_agent", "Chrome");
		attributes.put("age", 9);
		attributes.put("nothing", null);
		assertEquals(attributes, options.attributes());
		assertEquals(mapOf("exp_a", 2, "exp_b", 1), options.overrides());
		assertEquals(mapOf("exp_c", 3, "exp_d", 0), options.customAssignments());
		assertEquals(2000, options.publishDelayMillis());
		assertEquals(300000, options.refreshIntervalMillis());
		assertTrue(options.includeSystemAttributes());
		assertSame(listener, options.eventListener());
		assertTrue(options.toString().contains("session_id"));
	}

	@Test
	void optionsAreImmutable() {
		final ContextOptions.Builder builder = ContextOptions.builder().unit("session_id", "abc");
		final ContextOptions options = builder.build();
		builder.unit("user_id", "late");
		assertEquals(1, options.units().size());
		assertThrows(UnsupportedOperationException.class, () -> options.units().put("x", "y"));
		assertThrows(UnsupportedOperationException.class, () -> options.overrides().put("x", 1));
		assertThrows(UnsupportedOperationException.class, () -> options.attributes().clear());
		assertThrows(UnsupportedOperationException.class, () -> options.customAssignments().clear());
	}

	@Test
	void negativePublishDelayDisablesAutoPublish() {
		assertEquals(-1, ContextOptions.builder().publishDelay(Duration.ofMillis(-5)).build().publishDelayMillis());
		assertEquals(0, ContextOptions.builder().publishDelay(Duration.ZERO).build().publishDelayMillis());
	}

	@Test
	void negativeRefreshIntervalIsRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> ContextOptions.builder().refreshInterval(Duration.ofMillis(-1)));
	}

	@Test
	void testConfigEventLogger() {
		final ContextEventLogger logger = (context, type, data) -> {};
		assertSame(logger, TestContextConfig.create().setEventLogger(logger).getEventLogger());
	}
}
