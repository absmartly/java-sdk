package com.absmartly.sdk.json;

import java.util.Arrays;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ContextData {
	public Experiment[] experiments = new Experiment[0];
	public ExperimentHoldout[] holdouts = new ExperimentHoldout[0];

	public ContextData() {}

	@SuppressFBWarnings(value = "EI_EXPOSE_REP2")
	public ContextData(Experiment[] experiments) {
		this.experiments = experiments;
	}

	@SuppressFBWarnings(value = "EI_EXPOSE_REP2")
	public ContextData(Experiment[] experiments, ExperimentHoldout[] holdouts) {
		this.experiments = experiments;
		this.holdouts = holdouts;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o)
			return true;
		if (o == null || getClass() != o.getClass())
			return false;
		ContextData that = (ContextData) o;
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
		return "ContextData{" +
				"experiments=" + Arrays.toString(experiments) +
				", holdouts=" + Arrays.toString(holdouts) +
				'}';
	}
}
