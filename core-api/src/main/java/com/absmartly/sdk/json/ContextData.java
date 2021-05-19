package com.absmartly.sdk.json;

import java.util.Arrays;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ContextData {
	public Experiment[] experiments;

	public ContextData() {}

	public ContextData(Experiment[] experiments) {
		this.experiments = experiments;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o)
			return true;
		if (o == null || getClass() != o.getClass())
			return false;
		ContextData that = (ContextData) o;
		return Arrays.equals(experiments, that.experiments);
	}

	@Override
	public int hashCode() {
		return Arrays.hashCode(experiments);
	}

	@Override
	public String toString() {
		return "ContextGetResponse{" +
				"experiments=" + Arrays.toString(experiments) +
				'}';
	}
}
