package com.absmartly.sdk.json;

import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class Attribute {
	public String name;
	public Object value;
	public long setAt;

	public Attribute() {}

	public Attribute(String name, Object value, long setAt) {
		this.name = name;
		this.value = value;
		this.setAt = setAt;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o)
			return true;
		if (o == null || getClass() != o.getClass())
			return false;
		Attribute attribute = (Attribute) o;
		return setAt == attribute.setAt && Objects.equals(name, attribute.name)
				&& Objects.equals(value, attribute.value);
	}

	@Override
	public int hashCode() {
		return Objects.hash(name, value, setAt);
	}

	@Override
	public String toString() {
		return "Attribute{" +
				"name='" + name + '\'' +
				", value=" + value +
				", setAt=" + setAt +
				'}';
	}
}
