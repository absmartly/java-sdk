package com.absmartly.sdk;

import java.util.Objects;

/** An immutable record of one exposure: the variant a unit saw of an experiment, and why. */
public final class Exposure {
	Exposure(final int id, final String name, final String unit, final int variant, final long exposedAt,
			final boolean assigned, final boolean eligible, final boolean overridden, final boolean fullOn,
			final boolean custom, final boolean audienceMismatch, final boolean ruleOverride) {
		id_ = id;
		name_ = name;
		unit_ = unit;
		variant_ = variant;
		exposedAt_ = exposedAt;
		assigned_ = assigned;
		eligible_ = eligible;
		overridden_ = overridden;
		fullOn_ = fullOn;
		custom_ = custom;
		audienceMismatch_ = audienceMismatch;
		ruleOverride_ = ruleOverride;
	}

	/** The experiment id (0 when the experiment is not running). */
	public int id() {
		return id_;
	}

	/** The experiment name. */
	public String name() {
		return name_;
	}

	/** The unit type the variant was assigned to, or empty when the experiment is not running. */
	public String unit() {
		return unit_;
	}

	public int variant() {
		return variant_;
	}

	/** When the exposure happened, in milliseconds since the epoch. */
	public long exposedAt() {
		return exposedAt_;
	}

	public boolean assigned() {
		return assigned_;
	}

	public boolean eligible() {
		return eligible_;
	}

	public boolean overridden() {
		return overridden_;
	}

	public boolean fullOn() {
		return fullOn_;
	}

	public boolean custom() {
		return custom_;
	}

	public boolean audienceMismatch() {
		return audienceMismatch_;
	}

	/** The variant came from a matching custom assignment rule. */
	public boolean ruleOverride() {
		return ruleOverride_;
	}

	@Override
	public boolean equals(final Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof Exposure)) {
			return false;
		}
		final Exposure that = (Exposure) o;
		return id_ == that.id_ && variant_ == that.variant_ && exposedAt_ == that.exposedAt_
				&& assigned_ == that.assigned_ && eligible_ == that.eligible_ && overridden_ == that.overridden_
				&& fullOn_ == that.fullOn_ && custom_ == that.custom_ && audienceMismatch_ == that.audienceMismatch_
				&& ruleOverride_ == that.ruleOverride_ && name_.equals(that.name_) && unit_.equals(that.unit_);
	}

	@Override
	public int hashCode() {
		return Objects.hash(id_, name_, unit_, variant_, exposedAt_, assigned_, eligible_, overridden_, fullOn_,
				custom_, audienceMismatch_, ruleOverride_);
	}

	@Override
	public String toString() {
		return "Exposure{id=" + id_ + ", name='" + name_ + "', unit='" + unit_ + "', variant=" + variant_
				+ ", exposedAt=" + exposedAt_ + ", assigned=" + assigned_ + ", eligible=" + eligible_
				+ ", overridden=" + overridden_ + ", fullOn=" + fullOn_ + ", custom=" + custom_
				+ ", audienceMismatch=" + audienceMismatch_ + ", ruleOverride=" + ruleOverride_ + '}';
	}

	private final int id_;
	private final String name_;
	private final String unit_;
	private final int variant_;
	private final long exposedAt_;
	private final boolean assigned_;
	private final boolean eligible_;
	private final boolean overridden_;
	private final boolean fullOn_;
	private final boolean custom_;
	private final boolean audienceMismatch_;
	private final boolean ruleOverride_;
}
