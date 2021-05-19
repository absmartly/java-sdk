package com.absmartly.sdk.json;

import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ExperimentVariant {
	public String name;
	public String config;

	public ExperimentVariant() {}

	public ExperimentVariant(String name, String config) {
		this.name = name;
		this.config = config;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o)
			return true;
		if (o == null || getClass() != o.getClass())
			return false;
		ExperimentVariant that = (ExperimentVariant) o;
		return Objects.equals(name, that.name) && Objects.equals(config, that.config);
	}

	@Override
	public int hashCode() {
		return Objects.hash(name, config);
	}

	@Override
	public String toString() {
		return "ExperimentVariant{" +
				"name='" + name + '\'' +
				", config='" + config + '\'' +
				'}';
	}
}
