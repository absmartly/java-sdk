package com.absmartly.sdk.json;

import java.util.Map;
import java.util.Objects;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class GoalAchievement {
	public String name;
	public long achievedAt;
	public Map<String, Object> properties;

	public GoalAchievement() {}

	public GoalAchievement(@Nonnull String name, long achievedAt, @Nullable Map<String, Object> properties) {
		this.name = name;
		this.achievedAt = achievedAt;
		this.properties = properties;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o)
			return true;
		if (o == null || getClass() != o.getClass())
			return false;
		GoalAchievement that = (GoalAchievement) o;
		return achievedAt == that.achievedAt && Objects.equals(name, that.name)
				&& Objects.equals(properties, that.properties);
	}

	@Override
	public int hashCode() {
		return Objects.hash(name, achievedAt, properties);
	}

	@Override
	public String toString() {
		return "GoalAchievement{" +
				"name='" + name + '\'' +
				", achievedAt=" + achievedAt +
				", properties=" + properties +
				'}';
	}
}
