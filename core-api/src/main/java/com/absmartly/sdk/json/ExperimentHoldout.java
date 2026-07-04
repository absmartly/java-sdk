package com.absmartly.sdk.json;

import java.util.Arrays;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ExperimentHoldout {
	public int id;
	public int seedHi;
	public int seedLo;
	public double[] split;

	public ExperimentHoldout() {}

	@SuppressFBWarnings(value = "EI_EXPOSE_REP2")
	public ExperimentHoldout(int id, int seedHi, int seedLo, double[] split) {
		this.id = id;
		this.seedHi = seedHi;
		this.seedLo = seedLo;
		this.split = split;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o)
			return true;
		if (o == null || getClass() != o.getClass())
			return false;

		ExperimentHoldout that = (ExperimentHoldout) o;

		if (id != that.id)
			return false;
		if (seedHi != that.seedHi)
			return false;
		if (seedLo != that.seedLo)
			return false;
		return Arrays.equals(split, that.split);
	}

	@Override
	public int hashCode() {
		int result = id;
		result = 31 * result + seedHi;
		result = 31 * result + seedLo;
		result = 31 * result + Arrays.hashCode(split);
		return result;
	}

	@Override
	public String toString() {
		return "ExperimentHoldout{" +
				"id=" + id +
				", seedHi=" + seedHi +
				", seedLo=" + seedLo +
				", split=" + Arrays.toString(split) +
				'}';
	}
}
