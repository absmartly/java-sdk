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
	public CustomFieldValue[] customFieldValues;

	// Set only for entries served in the top-level `holdouts` array; null for ordinary experiments.
	public String holdoutType;

	// Set only for entries served in the top-level `experiments` array: the ids of the holdouts
	// (from the top-level `holdouts` array) that cover this experiment. Null or empty means the
	// experiment is not covered by any holdout. Absent on holdout entries themselves.
	public int[] holdoutIds;

	// Switchback configuration. randomizationType is "unit" or "switchback"; null means unit.
	// Instants and durations are epoch/duration milliseconds. Carryover and burn windows are
	// analysis-only and never read by assignment.
	public String randomizationType;
	public String switchbackClusterUnitType;
	public Long switchbackStartAt;
	public Long switchbackPeriodMs;
	public Long switchbackCarryoverDurationMs;
	public Long switchbackBurnInDurationMs;
	public Long switchbackBurnOutDurationMs;
	public String switchbackDesignType;
	public String switchbackEstimand;
	// Hashed cluster buckets: count is 10, 50 or 100; a null attribute hashes the experiment unit.
	public Integer switchbackClusterCount;
	public String switchbackClusterAttribute;

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
		if (!Arrays.equals(customFieldValues, that.customFieldValues))
			return false;
		if (holdoutType != null ? !holdoutType.equals(that.holdoutType) : that.holdoutType != null)
			return false;
		if (!Arrays.equals(holdoutIds, that.holdoutIds))
			return false;
		if (!Objects.equals(randomizationType, that.randomizationType))
			return false;
		if (!Objects.equals(switchbackClusterUnitType, that.switchbackClusterUnitType))
			return false;
		if (!Objects.equals(switchbackStartAt, that.switchbackStartAt))
			return false;
		if (!Objects.equals(switchbackPeriodMs, that.switchbackPeriodMs))
			return false;
		if (!Objects.equals(switchbackCarryoverDurationMs, that.switchbackCarryoverDurationMs))
			return false;
		if (!Objects.equals(switchbackBurnInDurationMs, that.switchbackBurnInDurationMs))
			return false;
		if (!Objects.equals(switchbackBurnOutDurationMs, that.switchbackBurnOutDurationMs))
			return false;
		if (!Objects.equals(switchbackDesignType, that.switchbackDesignType))
			return false;
		if (!Objects.equals(switchbackEstimand, that.switchbackEstimand))
			return false;
		if (!Objects.equals(switchbackClusterCount, that.switchbackClusterCount))
			return false;
		return Objects.equals(switchbackClusterAttribute, that.switchbackClusterAttribute);
	}

	private static int hash(Object o) {
		return (o != null) ? o.hashCode() : 0;
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
		result = 31 * result + (holdoutType != null ? holdoutType.hashCode() : 0);
		result = 31 * result + Arrays.hashCode(holdoutIds);
		result = 31 * result + hash(randomizationType);
		result = 31 * result + hash(switchbackClusterUnitType);
		result = 31 * result + hash(switchbackStartAt);
		result = 31 * result + hash(switchbackPeriodMs);
		result = 31 * result + hash(switchbackCarryoverDurationMs);
		result = 31 * result + hash(switchbackBurnInDurationMs);
		result = 31 * result + hash(switchbackBurnOutDurationMs);
		result = 31 * result + hash(switchbackDesignType);
		result = 31 * result + hash(switchbackEstimand);
		result = 31 * result + hash(switchbackClusterCount);
		result = 31 * result + hash(switchbackClusterAttribute);
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
				", holdoutType='" + holdoutType + '\'' +
				", holdoutIds=" + Arrays.toString(holdoutIds) +
				", randomizationType='" + randomizationType + '\'' +
				", switchbackClusterUnitType='" + switchbackClusterUnitType + '\'' +
				", switchbackStartAt=" + switchbackStartAt +
				", switchbackPeriodMs=" + switchbackPeriodMs +
				", switchbackCarryoverDurationMs=" + switchbackCarryoverDurationMs +
				", switchbackBurnInDurationMs=" + switchbackBurnInDurationMs +
				", switchbackBurnOutDurationMs=" + switchbackBurnOutDurationMs +
				", switchbackDesignType='" + switchbackDesignType + '\'' +
				", switchbackEstimand='" + switchbackEstimand + '\'' +
				", switchbackClusterCount=" + switchbackClusterCount +
				", switchbackClusterAttribute='" + switchbackClusterAttribute + '\'' +
				'}';
	}
}
