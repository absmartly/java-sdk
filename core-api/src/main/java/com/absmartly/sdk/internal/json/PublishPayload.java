package com.absmartly.sdk.internal.json;

import java.util.Arrays;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class PublishPayload {
	public boolean hashed;
	public Unit[] units;
	public long publishedAt;
	public ExposurePayload[] exposures;
	public GoalAchievement[] goals;
	public AttributePayload[] attributes;

	public PublishPayload() {}

	@Override
	public boolean equals(Object o) {
		if (this == o)
			return true;
		if (o == null || getClass() != o.getClass())
			return false;
		PublishPayload that = (PublishPayload) o;
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
		return "PublishPayload{" +
				"hashedUnits=" + hashed +
				", units=" + Arrays.toString(units) +
				", publishedAt=" + publishedAt +
				", exposures=" + Arrays.toString(exposures) +
				", goals=" + Arrays.toString(goals) +
				", attributes=" + Arrays.toString(attributes) +
				'}';
	}
}
