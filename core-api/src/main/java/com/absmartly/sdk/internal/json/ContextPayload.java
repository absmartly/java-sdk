package com.absmartly.sdk.internal.json;

import java.util.Arrays;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ContextPayload {
	public Experiment[] experiments = new Experiment[0];
	public Experiment[] holdouts = new Experiment[0];

	public ContextPayload() {}

	public ContextPayload(Experiment[] experiments) {
		this.experiments = experiments;
	}

	public ContextPayload(Experiment[] experiments, Experiment[] holdouts) {
		this.experiments = experiments;
		this.holdouts = holdouts;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o)
			return true;
		if (o == null || getClass() != o.getClass())
			return false;
		ContextPayload that = (ContextPayload) o;
		return Arrays.equals(experiments, that.experiments) && Arrays.equals(holdouts, that.holdouts);
	}

	@Override
	public int hashCode() {
		int result = Arrays.hashCode(experiments);
		result = 31 * result + Arrays.hashCode(holdouts);
		return result;
	}

	@Override
	public String toString() {
		return "ContextPayload{" +
				"experiments=" + Arrays.toString(experiments) +
				", holdouts=" + Arrays.toString(holdouts) +
				'}';
	}
}
