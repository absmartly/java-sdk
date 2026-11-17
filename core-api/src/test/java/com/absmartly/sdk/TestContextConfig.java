package com.absmartly.sdk;

import java.time.Duration;
import java.util.Map;

/**
 * The 1.x ContextConfig call shape over {@link ContextOptions.Builder}, so the ported 1.x engine tests keep their
 * original arrangement code. Production code uses {@link ContextOptions} directly.
 */
final class TestContextConfig {
	static TestContextConfig create() {
		return new TestContextConfig();
	}

	private TestContextConfig() {}

	TestContextConfig setUnit(final String unitType, final String uid) {
		builder_.unit(unitType, uid);
		return this;
	}

	TestContextConfig setUnit(final String unitType, final Number uid) {
		builder_.unit(unitType, uid);
		return this;
	}

	TestContextConfig setUnits(final Map<String, ?> units) {
		builder_.units(units);
		return this;
	}

	TestContextConfig setAttribute(final String name, final Object value) {
		builder_.attribute(name, value);
		return this;
	}

	TestContextConfig setAttributes(final Map<String, ?> attributes) {
		builder_.attributes(attributes);
		return this;
	}

	TestContextConfig setOverride(final String experimentName, final int variant) {
		builder_.override(experimentName, variant);
		return this;
	}

	TestContextConfig setOverrides(final Map<String, Integer> overrides) {
		builder_.overrides(overrides);
		return this;
	}

	TestContextConfig setCustomAssignment(final String experimentName, final int variant) {
		builder_.customAssignment(experimentName, variant);
		return this;
	}

	TestContextConfig setCustomAssignments(final Map<String, Integer> customAssignments) {
		builder_.customAssignments(customAssignments);
		return this;
	}

	TestContextConfig setPublishDelay(final long delayMs) {
		builder_.publishDelay(Duration.ofMillis(delayMs));
		return this;
	}

	TestContextConfig setRefreshInterval(final long intervalMs) {
		builder_.refreshInterval(Duration.ofMillis(intervalMs));
		return this;
	}

	TestContextConfig setIncludeSystemAttributes(final boolean includeSystemAttributes) {
		builder_.includeSystemAttributes(includeSystemAttributes);
		return this;
	}

	TestContextConfig setEventLogger(final ContextEventLogger eventLogger) {
		eventLogger_ = eventLogger;
		return this;
	}

	ContextEventLogger getEventLogger() {
		return eventLogger_;
	}

	ContextOptions toOptions() {
		return builder_.build();
	}

	private final ContextOptions.Builder builder_ = ContextOptions.builder();
	private ContextEventLogger eventLogger_;
}
