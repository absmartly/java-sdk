package com.absmartly.sdk.internal.json;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Objects;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class AttributePayload {
	public String name;
	public Object value;
	public long setAt;

	public AttributePayload() {}

	public AttributePayload(String name, Object value, long setAt) {
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
		AttributePayload attribute = (AttributePayload) o;
		return setAt == attribute.setAt && Objects.equals(name, attribute.name) && Objects.equals(value,
				attribute.value);
	}

	@Override
	public int hashCode() {
		return Objects.hash(name, value, setAt);
	}

	@Override
	public String toString() {
		return "AttributePayload{" +
				"name='" + name + '\'' +
				", value=" + value +
				", setAt=" + setAt +
				'}';
	}
}
