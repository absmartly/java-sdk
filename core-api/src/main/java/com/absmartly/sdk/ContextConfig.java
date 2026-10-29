package com.absmartly.sdk;

import java.util.HashMap;
import java.util.Map;

import javax.annotation.Nonnull;

import com.absmartly.sdk.internal.hashing.Hashing;

public class ContextConfig {
	public static ContextConfig create() {
		return new ContextConfig();
	}

	private ContextConfig() {}

	public ContextConfig setUnit(@Nonnull final String unitType, @Nonnull final String uid) {
		return putUnit(unitType, uid);
	}

	public ContextConfig setUnit(@Nonnull final String unitType, @Nonnull final Number uid) {
		return putUnit(unitType, uid);
	}

	public ContextConfig setUnits(@Nonnull final Map<String, ?> units) {
		for (final Map.Entry<String, ?> entry : units.entrySet()) {
			putUnit(entry.getKey(), entry.getValue());
		}
		return this;
	}

	public String getUnit(@Nonnull final String unitType) {
		return unitString(getUnitValue(unitType));
	}

	public Object getUnitValue(@Nonnull final String unitType) {
		return units_ != null ? units_.get(unitType) : null;
	}

	public Map<String, String> getUnits() {
		return units_ != null ? unitStrings(units_) : null;
	}

	// Shared with Context: a numeric UID is returned in its canonical string form, the same string that
	// is hashed.
	static String unitString(final Object uid) {
		return uid != null ? Hashing.unitToString(uid) : null;
	}

	static Map<String, String> unitStrings(final Map<String, Object> uids) {
		final Map<String, String> units = new HashMap<String, String>(uids.size());
		for (final Map.Entry<String, Object> entry : uids.entrySet()) {
			units.put(entry.getKey(), unitString(entry.getValue()));
		}
		return units;
	}

	public Map<String, Object> getUnitValues() {
		return units_;
	}

	private ContextConfig putUnit(final String unitType, final Object uid) {
		if (units_ == null) {
			units_ = new HashMap<String, Object>();
		}

		units_.put(unitType, uid);
		return this;
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
		return this.attributes_ != null ? this.attributes_.get(name) : null;
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
		return this.overrides_ != null ? this.overrides_.get(experimentName) : null;
	}

	public Map<String, Integer> getOverrides() {
		return this.overrides_;
	}

	public ContextConfig setCustomAssignment(@Nonnull final String experimentName, int variant) {
		if (cassignments_ == null) {
			cassignments_ = new HashMap<String, Integer>();
		}
		cassignments_.put(experimentName, variant);
		return this;
	}

	public ContextConfig setCustomAssignments(@Nonnull final Map<String, Integer> customAssignments) {
		if (cassignments_ == null) {
			cassignments_ = new HashMap<String, Integer>(customAssignments.size());
		}
		cassignments_.putAll(customAssignments);
		return this;
	}

	public Object getCustomAssignment(@Nonnull final String experimentName) {
		return this.cassignments_ != null ? this.cassignments_.get(experimentName) : null;
	}

	public Map<String, Integer> getCustomAssignments() {
		return this.cassignments_;
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

	public ContextConfig setRefreshInterval(long intervalMs) {
		this.refreshInterval_ = intervalMs;
		return this;
	}

	public long getRefreshInterval() {
		return this.refreshInterval_;
	}

	private Map<String, Object> units_;
	private Map<String, Object> attributes_;
	private Map<String, Integer> overrides_;
	private Map<String, Integer> cassignments_;

	private ContextEventLogger eventLogger_;

	private long publishDelay_ = 100;
	private long refreshInterval_ = 0;
}
