package com.absmartly.sdk;

import javax.annotation.Nonnull;
import java.util.HashMap;
import java.util.Map;

public class ContextConfig {
	public static ContextConfig create() {
		return new ContextConfig();
	}

	private ContextConfig() {}

	public ContextConfig setUnit(@Nonnull final String unitType, @Nonnull final String uid) {
		if (units_ == null) {
			units_ = new HashMap<String, String>();
		}

		units_.put(unitType, uid);
		return this;
	}

	public ContextConfig setUnits(@Nonnull final Map<String, String> units) {
		for (final Map.Entry<String, String> entry : units.entrySet()) {
			setUnit(entry.getKey(), entry.getValue());
		}
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
			attributes_ = new HashMap<String, Object>();
		}
		attributes_.put(name, value);
		return this;
	}

	public ContextConfig setAttributes(@Nonnull final Map<String, Object> attributes) {
		if (attributes_ == null) {
			attributes_ = new HashMap<String, Object>(attributes.size());
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
			overrides_ = new HashMap<String, Integer>();
		}
		overrides_.put(experimentName, variant);
		return this;
	}

	public ContextConfig setOverrides(@Nonnull final Map<String, Integer> overrides) {
		if (overrides_ == null) {
			overrides_ = new HashMap<String, Integer>(overrides.size());
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

	public ContextConfig setCustomAssignment(@Nonnull final String experimentName, int variant) {
		if (cassigmnents_ == null) {
			cassigmnents_ = new HashMap<String, Integer>();
		}
		cassigmnents_.put(experimentName, variant);
		return this;
	}

	public ContextConfig setCustomAssignments(@Nonnull final Map<String, Integer> customAssignments) {
		if (cassigmnents_ == null) {
			cassigmnents_ = new HashMap<String, Integer>(customAssignments.size());
		}
		cassigmnents_.putAll(customAssignments);
		return this;
	}

	public Object getCustomAssignment(@Nonnull final String experimentName) {
		return this.cassigmnents_.get(experimentName);
	}

	public Map<String, Integer> getCustomAssignments() {
		return this.cassigmnents_;
	}

	public ContextEventLogger getEventLogger() {
		return this.eventLogger_;
	}

	public ContextConfig setEventLogger(ContextEventLogger eventLogger) {
		this.eventLogger_ = eventLogger;
		return this;
	}

	public ContextConfig setPublishDelay(long delayMs) {
		this.publishDelay_ = delayMs;
		return this;
	}

	public long getPublishDelay() {
		return this.publishDelay_;
	}

	private Map<String, String> units_;
	private Map<String, Object> attributes_;
	private Map<String, Integer> overrides_;
	private Map<String, Integer> cassigmnents_;

	private ContextEventLogger eventLogger_;

	private long publishDelay_ = 100;
}
