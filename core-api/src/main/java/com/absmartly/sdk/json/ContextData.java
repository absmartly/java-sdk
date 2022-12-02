package com.absmartly.sdk.json;

import java.util.Arrays;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ContextData {
	public Experiment[] experiments = new Experiment[0];

	public ContextData() {}

	@SuppressFBWarnings(value = "EI_EXPOSE_REP2")
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
		return "ContextData{" +
				"experiments=" + Arrays.toString(experiments) +
				'}';
	}
}
