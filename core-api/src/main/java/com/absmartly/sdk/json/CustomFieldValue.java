package com.absmartly.sdk.json;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class CustomFieldValue {
	public String name;
	public String type;
	public String value;

	public CustomFieldValue() {}

	@Override
	public boolean equals(Object o) {
		if (this == o)
			return true;
		if (o == null || getClass() != o.getClass())
			return false;

		CustomFieldValue that = (CustomFieldValue) o;

		if (name != null ? !name.equals(that.name) : that.name != null)
			return false;
		if (type != null ? !type.equals(that.type) : that.type != null)
			return false;
		return value != null ? value.equals(that.value) : that.value == null;
	}

	@Override
	public int hashCode() {
		int result = name != null ? name.hashCode() : 0;
		result = 31 * result + (type != null ? type.hashCode() : 0);
		result = 31 * result + (value != null ? value.hashCode() : 0);
		return result;
	}

	@Override
	public String toString() {
		return "CustomFieldValue{" +
				"name='" + name + '\'' +
				", type='" + type + '\'' +
				", value='" + value + '\'' +
				'}';
	}
}
