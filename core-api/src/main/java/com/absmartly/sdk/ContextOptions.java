package com.absmartly.sdk;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.absmartly.sdk.event.EventListener;

/**
 * The immutable options of one context: its units, initial attributes, overrides and custom assignments, and its
 * publish and refresh timing. Build with {@link #builder()}, or {@link #units(Map)} when units are all you need.
 */
public final class ContextOptions {
	/** The default delay between the first queued event and its automatic publish. */
	public static final Duration DEFAULT_PUBLISH_DELAY = Duration.ofMillis(100);

	public static Builder builder() {
		return new Builder();
	}

	/** Options with just these units, for example {@code ContextOptions.units(Map.of("user_id", id))}. */
	public static ContextOptions units(final Map<String, ?> units) {
		return builder().units(units).build();
	}

	private ContextOptions(final Builder builder) {
		units_ = Collections.unmodifiableMap(new LinkedHashMap<String, Object>(builder.units_));
		attributes_ = com.absmartly.sdk.internal.JsonValues.snapshot(builder.attributes_);
		overrides_ = Collections.unmodifiableMap(new LinkedHashMap<String, Integer>(builder.overrides_));
		customAssignments_ = Collections
				.unmodifiableMap(new LinkedHashMap<String, Integer>(builder.customAssignments_));
		publishDelay_ = builder.publishDelay_;
		refreshInterval_ = builder.refreshInterval_;
		includeSystemAttributes_ = builder.includeSystemAttributes_;
		eventListener_ = builder.eventListener_;
	}

	/** Unit type to UID (a String or a Number), in insertion order. */
	public Map<String, Object> units() {
		return units_;
	}

	public Map<String, @Nullable Object> attributes() {
		return attributes_;
	}

	public Map<String, Integer> overrides() {
		return overrides_;
	}

	public Map<String, Integer> customAssignments() {
		return customAssignments_;
	}

	/** See {@link Builder#publishDelay(Duration)}. */
	public Duration publishDelay() {
		return publishDelay_;
	}

	/** See {@link Builder#refreshInterval(Duration)}. */
	public Duration refreshInterval() {
		return refreshInterval_;
	}

	public boolean includeSystemAttributes() {
		return includeSystemAttributes_;
	}

	public @Nullable EventListener eventListener() {
		return eventListener_;
	}

	long publishDelayMillis() {
		return publishDelay_.isNegative() ? -1 : publishDelay_.toMillis();
	}

	long refreshIntervalMillis() {
		return refreshInterval_.toMillis();
	}

	@Override
	public String toString() {
		return "ContextOptions{units=" + units_.keySet() + ", attributes=" + attributes_.keySet() + ", overrides="
				+ overrides_ + ", customAssignments=" + customAssignments_ + ", publishDelay=" + publishDelay_
				+ ", refreshInterval=" + refreshInterval_ + ", includeSystemAttributes=" + includeSystemAttributes_
				+ '}';
	}

	public static final class Builder {
		Builder() {}

		/** Sets a unit. The UID is validated when the context is created (it must not be blank). */
		public Builder unit(final String unitType, final String uid) {
			units_.put(unitType, uid);
			return this;
		}

		/** Sets a numeric unit; it is hashed and published in its canonical string form. */
		public Builder unit(final String unitType, final Number uid) {
			units_.put(unitType, uid);
			return this;
		}

		/** Sets several units; each UID must be a String or a Number. */
		public Builder units(final Map<String, ?> units) {
			for (final Map.Entry<String, ?> entry : units.entrySet()) {
				units_.put(entry.getKey(), entry.getValue());
			}
			return this;
		}

		public Builder attribute(final String name, final @Nullable Object value) {
			attributes_.put(name, value);
			return this;
		}

		public Builder attributes(final Map<String, ?> attributes) {
			attributes_.putAll(attributes);
			return this;
		}

		/** Forces a variant of an experiment, for QA. The exposure is flagged {@code overridden}. */
		public Builder override(final String experimentName, final int variant) {
			overrides_.put(experimentName, variant);
			return this;
		}

		public Builder overrides(final Map<String, Integer> overrides) {
			overrides_.putAll(overrides);
			return this;
		}

		/** Assigns a variant of an experiment by your own logic. The exposure is flagged {@code custom}. */
		public Builder customAssignment(final String experimentName, final int variant) {
			customAssignments_.put(experimentName, variant);
			return this;
		}

		public Builder customAssignments(final Map<String, Integer> customAssignments) {
			customAssignments_.putAll(customAssignments);
			return this;
		}

		/**
		 * How long after the first queued exposure or goal the context publishes automatically (default
		 * {@link #DEFAULT_PUBLISH_DELAY}). A negative duration turns automatic publishing off: events are then only
		 * sent by {@link Context#publish()} and {@link Context#close()}.
		 */
		public Builder publishDelay(final Duration publishDelay) {
			publishDelay_ = publishDelay;
			return this;
		}

		/** How often the context refreshes its data in the background. Zero (the default) never refreshes. */
		public Builder refreshInterval(final Duration refreshInterval) {
			if (refreshInterval.isNegative()) {
				throw new IllegalArgumentException("refreshInterval must not be negative");
			}
			refreshInterval_ = refreshInterval;
			return this;
		}

		/**
		 * Prepends {@code sdk_name} and {@code sdk_version} to the attributes of every publish. Off by default. The
		 * configured application and environment are always available to audiences and assignment rules, whatever
		 * this flag.
		 */
		public Builder includeSystemAttributes(final boolean includeSystemAttributes) {
			includeSystemAttributes_ = includeSystemAttributes;
			return this;
		}

		/**
		 * A listener for this context's events, attached before the context starts loading so it also receives the
		 * {@link com.absmartly.sdk.event.ReadyEvent} or {@link com.absmartly.sdk.event.ErrorEvent} of the load.
		 */
		public Builder eventListener(final EventListener eventListener) {
			eventListener_ = eventListener;
			return this;
		}

		public ContextOptions build() {
			return new ContextOptions(this);
		}

		private final Map<String, Object> units_ = new LinkedHashMap<String, Object>();
		private final Map<String, @Nullable Object> attributes_ = new LinkedHashMap<String, @Nullable Object>();
		private final Map<String, Integer> overrides_ = new LinkedHashMap<String, Integer>();
		private final Map<String, Integer> customAssignments_ = new LinkedHashMap<String, Integer>();
		private Duration publishDelay_ = DEFAULT_PUBLISH_DELAY;
		private Duration refreshInterval_ = Duration.ZERO;
		private boolean includeSystemAttributes_;
		private @Nullable EventListener eventListener_;
	}

	private final Map<String, Object> units_;
	private final Map<String, @Nullable Object> attributes_;
	private final Map<String, Integer> overrides_;
	private final Map<String, Integer> customAssignments_;
	private final Duration publishDelay_;
	private final Duration refreshInterval_;
	private final boolean includeSystemAttributes_;
	private final @Nullable EventListener eventListener_;
}
