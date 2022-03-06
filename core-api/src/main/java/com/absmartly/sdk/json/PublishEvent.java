package com.absmartly.sdk.json;

import java.util.Arrays;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import com.absmartly.sdk.java.util.Objects;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class PublishEvent {
	public boolean hashed;
	public Unit[] units;
	public long publishedAt;
	public Exposure[] exposures;
	public GoalAchievement[] goals;
	public Attribute[] attributes;

	public PublishEvent() {}

	@Override
	public boolean equals(Object o) {
		if (this == o)
			return true;
		if (o == null || getClass() != o.getClass())
			return false;
		PublishEvent that = (PublishEvent) o;
		return hashed == that.hashed && publishedAt == that.publishedAt && Arrays.equals(units, that.units)
				&& Arrays.equals(exposures, that.exposures) && Arrays.equals(goals, that.goals)
				&& Arrays.equals(attributes, that.attributes);
	}

	@Override
	public int hashCode() {
		int result = Objects.hash(hashed, publishedAt);
		result = 31 * result + Arrays.hashCode(units);
		result = 31 * result + Arrays.hashCode(exposures);
		result = 31 * result + Arrays.hashCode(goals);
		result = 31 * result + Arrays.hashCode(attributes);
		return result;
	}

	@Override
	public String toString() {
		return "PublishRequest{" +
				"hashedUnits=" + hashed +
				", units=" + Arrays.toString(units) +
				", publishedAt=" + publishedAt +
				", exposures=" + Arrays.toString(exposures) +
				", goals=" + Arrays.toString(goals) +
				", attributes=" + Arrays.toString(attributes) +
				'}';
	}
}
