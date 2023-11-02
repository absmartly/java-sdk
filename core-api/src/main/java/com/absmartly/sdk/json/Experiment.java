package com.absmartly.sdk.json;

import java.util.Arrays;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

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
	public CustomFieldValue[] customFieldValues;

	public Experiment() {}

	@Override
	public boolean equals(Object o) {
		if (this == o)
			return true;
		if (o == null || getClass() != o.getClass())
			return false;

		Experiment that = (Experiment) o;

		if (id != that.id)
			return false;
		if (iteration != that.iteration)
			return false;
		if (seedHi != that.seedHi)
			return false;
		if (seedLo != that.seedLo)
			return false;
		if (trafficSeedHi != that.trafficSeedHi)
			return false;
		if (trafficSeedLo != that.trafficSeedLo)
			return false;
		if (fullOnVariant != that.fullOnVariant)
			return false;
		if (audienceStrict != that.audienceStrict)
			return false;
		if (name != null ? !name.equals(that.name) : that.name != null)
			return false;
		if (unitType != null ? !unitType.equals(that.unitType) : that.unitType != null)
			return false;
		if (!Arrays.equals(split, that.split))
			return false;
		if (!Arrays.equals(trafficSplit, that.trafficSplit))
			return false;
		if (!Arrays.equals(applications, that.applications))
			return false;
		if (!Arrays.equals(variants, that.variants))
			return false;
		if (audience != null ? !audience.equals(that.audience) : that.audience != null)
			return false;
		return Arrays.equals(customFieldValues, that.customFieldValues);
	}

	@Override
	public int hashCode() {
		int result = id;
		result = 31 * result + (name != null ? name.hashCode() : 0);
		result = 31 * result + (unitType != null ? unitType.hashCode() : 0);
		result = 31 * result + iteration;
		result = 31 * result + seedHi;
		result = 31 * result + seedLo;
		result = 31 * result + Arrays.hashCode(split);
		result = 31 * result + trafficSeedHi;
		result = 31 * result + trafficSeedLo;
		result = 31 * result + Arrays.hashCode(trafficSplit);
		result = 31 * result + fullOnVariant;
		result = 31 * result + Arrays.hashCode(applications);
		result = 31 * result + Arrays.hashCode(variants);
		result = 31 * result + (audienceStrict ? 1 : 0);
		result = 31 * result + (audience != null ? audience.hashCode() : 0);
		result = 31 * result + Arrays.hashCode(customFieldValues);
		return result;
	}

	@Override
	public String toString() {
		return "Experiment{" +
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
				", customFieldValues=" + Arrays.toString(customFieldValues) +
				'}';
	}
}
