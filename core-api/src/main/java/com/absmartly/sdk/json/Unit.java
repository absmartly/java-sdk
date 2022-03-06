package com.absmartly.sdk.json;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import com.absmartly.sdk.java.util.Objects;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class Unit {
	public String type;
	public String uid;

	public Unit() {}

	public Unit(String type, String uid) {
		this.type = type;
		this.uid = uid;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o)
			return true;
		if (o == null || getClass() != o.getClass())
			return false;
		Unit unit = (Unit) o;
		return Objects.equals(type, unit.type) && Objects.equals(uid, unit.uid);
	}

	@Override
	public int hashCode() {
		return Objects.hash(type, uid);
	}

	@Override
	public String toString() {
		return "Unit{" +
				"type='" + type + '\'' +
				", uid=" + uid +
				'}';
	}
}
