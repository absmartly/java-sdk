package com.absmartly.sdk;

import java.util.Map;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

/** An immutable record of one goal achievement queued by {@link Context#track(String, Map)}. */
public final class Goal {
	Goal(final String name, final long achievedAt, final @Nullable Map<String, Object> properties) {
		name_ = name;
		achievedAt_ = achievedAt;
		properties_ = properties;
	}

	public String name() {
		return name_;
	}

	/** When the goal was achieved, in milliseconds since the epoch. */
	public long achievedAt() {
		return achievedAt_;
	}

	/** The goal properties (unmodifiable, sorted by name), or null when none were given. */
	public @Nullable Map<String, Object> properties() {
		return properties_;
	}

	@Override
	public boolean equals(final Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof Goal)) {
			return false;
		}
		final Goal that = (Goal) o;
		return achievedAt_ == that.achievedAt_ && name_.equals(that.name_)
				&& Objects.equals(properties_, that.properties_);
	}

	@Override
	public int hashCode() {
		return Objects.hash(name_, achievedAt_, properties_);
	}

	@Override
	public String toString() {
		return "Goal{name='" + name_ + "', achievedAt=" + achievedAt_ + ", properties=" + properties_ + '}';
	}

	private final String name_;
	private final long achievedAt_;
	private final @Nullable Map<String, Object> properties_;
}
