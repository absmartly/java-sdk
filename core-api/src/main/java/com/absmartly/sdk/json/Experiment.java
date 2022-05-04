package com.absmartly.sdk.json;

import java.util.Arrays;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import com.absmartly.sdk.java.util.Objects;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class Experiment {
	public int id;
	public String name;
	public String unitType;
	public int iteration;
	public int seedHi;
	public int seedLo;
	public double[] split;
	public int trafficSeedHi;
	public int trafficSeedLo;
	public double[] trafficSplit;
	public int fullOnVariant;
	public ExperimentApplication[] applications;
	public ExperimentVariant[] variants;
	public boolean audienceStrict;
	public String audience;

	@Override
	public boolean equals(Object o) {
		if (this == o)
			return true;
		if (o == null || getClass() != o.getClass())
			return false;
		Experiment that = (Experiment) o;
		return id == that.id && iteration == that.iteration && seedHi == that.seedHi && seedLo == that.seedLo
				&& trafficSeedHi == that.trafficSeedHi && trafficSeedLo == that.trafficSeedLo
				&& fullOnVariant == that.fullOnVariant && Objects.equals(name, that.name)
				&& Objects.equals(unitType, that.unitType) && Arrays.equals(split, that.split)
				&& Arrays.equals(trafficSplit, that.trafficSplit) && Arrays.equals(applications, that.applications)
				&& Arrays.equals(variants, that.variants) && audienceStrict == that.audienceStrict
				&& Objects.equals(audience, that.audience);
	}

	@Override
	public int hashCode() {
		int result = Objects.hash(id, name, unitType, iteration, seedHi, seedLo, trafficSeedHi, trafficSeedLo,
				fullOnVariant, audienceStrict, audience);
		result = 31 * result + Arrays.hashCode(split);
		result = 31 * result + Arrays.hashCode(trafficSplit);
		result = 31 * result + Arrays.hashCode(applications);
		result = 31 * result + Arrays.hashCode(variants);
		return result;
	}

	@Override
	public String toString() {
		return "ContextExperiment{" +
				"id=" + id +
				", name='" + name + '\'' +
				", unitType='" + unitType + '\'' +
				", iteration=" + iteration +
				", seedHi=" + seedHi +
				", seedLo=" + seedLo +
				", split=" + Arrays.toString(split) +
				", trafficSeedHi=" + trafficSeedHi +
				", trafficSeedLo=" + trafficSeedLo +
				", trafficSplit=" + Arrays.toString(trafficSplit) +
				", fullOnVariant=" + fullOnVariant +
				", applications=" + Arrays.toString(applications) +
				", variants=" + Arrays.toString(variants) +
				", audienceStrict=" + audienceStrict +
				", audience='" + audience + '\'' +
				'}';
	}
}
