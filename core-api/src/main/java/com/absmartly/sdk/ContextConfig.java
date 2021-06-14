package com.absmartly.sdk;

import java.util.HashMap;
import java.util.Map;

import javax.annotation.Nonnull;

public class ContextConfig {
	public static ContextConfig create() {
		return new ContextConfig();
	}

	private ContextConfig() {}

	public ContextConfig setUnit(@Nonnull final String unitType, @Nonnull final String uid) {
		units_.put(unitType, uid);
		return this;
	}

	public ContextConfig setUnits(@Nonnull final Map<String, String> units) {
		units_.putAll(units);
		return this;
	}

	public String getUnit(@Nonnull final String unitType) {
		return units_.get(unitType);
	}

	public Map<String, String> getUnits() {
		return units_;
	}

	public ContextConfig setAttribute(@Nonnull final String name, @Nonnull final Object value) {
		if (attributes_ == null) {
			attributes_ = new HashMap<>();
		}
		attributes_.put(name, value);
		return this;
	}

	public ContextConfig setAttributes(@Nonnull final Map<String, Object> attributes) {
		if (attributes_ == null) {
			attributes_ = new HashMap<>(attributes.size());
		}
		attributes_.putAll(attributes);
		return this;
	}

	public Object getAttribute(@Nonnull final String name) {
		return this.attributes_.get(name);
	}

	public Map<String, Object> getAttributes() {
		return this.attributes_;
	}

	public ContextConfig setOverride(@Nonnull final String experimentName, int variant) {
		if (overrides_ == null) {
			overrides_ = new HashMap<>();
		}
		overrides_.put(experimentName, variant);
		return this;
	}

	public ContextConfig setOverrides(@Nonnull final Map<String, Integer> overrides) {
		if (overrides_ == null) {
			overrides_ = new HashMap<>(overrides.size());
		}
		overrides_.putAll(overrides);
		return this;
	}

	public Object getOverride(@Nonnull final String experimentName) {
		return this.overrides_.get(experimentName);
	}

	public Map<String, Integer> getOverrides() {
		return this.overrides_;
	}

	public ContextConfig setPublishDelay(long delayMs) {
		this.publishDelay_ = delayMs;
		return this;
	}

	public long getPublishDelay() {
		return this.publishDelay_;
	}

	private final Map<String, String> units_ = new HashMap<>();
	private Map<String, Object> attributes_;
	private Map<String, Integer> overrides_;

	private long publishDelay_ = 100;
}
