package com.absmartly.sdk.json;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import com.absmartly.sdk.java.util.Objects;

@JsonInclude(JsonInclude.Include.ALWAYS)
@JsonIgnoreProperties(ignoreUnknown = true)
public class Exposure {
	public int id;
	public String name;
	public String unit;
	public int variant;
	public long exposedAt;
	public boolean assigned;
	public boolean eligible;
	public boolean overridden;
	public boolean fullOn;
	public boolean custom;
	public boolean audienceMismatch;

	public Exposure() {}

	public Exposure(int id, String name, String unit, int variant, long exposedAt, boolean assigned, boolean eligible,
			boolean overridden, boolean fullOn, boolean custom, boolean audienceMismatch) {
		this.id = id;
		this.name = name;
		this.unit = unit;
		this.variant = variant;
		this.exposedAt = exposedAt;
		this.assigned = assigned;
		this.eligible = eligible;
		this.overridden = overridden;
		this.fullOn = fullOn;
		this.custom = custom;
		this.audienceMismatch = audienceMismatch;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o)
			return true;
		if (o == null || getClass() != o.getClass())
			return false;
		Exposure exposure = (Exposure) o;
		return id == exposure.id && variant == exposure.variant && exposedAt == exposure.exposedAt
				&& assigned == exposure.assigned && eligible == exposure.eligible && overridden == exposure.overridden
				&& fullOn == exposure.fullOn && custom == exposure.custom
				&& Objects.equals(audienceMismatch, exposure.audienceMismatch) && Objects.equals(name, exposure.name)
				&& Objects.equals(unit, exposure.unit);
	}

	@Override
	public int hashCode() {
		return Objects.hash(id, name, unit, variant, exposedAt, assigned, eligible, overridden, fullOn, custom,
				audienceMismatch);
	}

	@Override
	public String toString() {
		return "Exposure{" +
				"id=" + id +
				", name='" + name + '\'' +
				", unit='" + unit + '\'' +
				", variant=" + variant +
				", exposedAt=" + exposedAt +
				", assigned=" + assigned +
				", eligible=" + eligible +
				", overridden=" + overridden +
				", fullOn=" + fullOn +
				", custom=" + custom +
				", audienceMismatch=" + audienceMismatch +
				'}';
	}
}
