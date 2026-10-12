package com.absmartly.sdk.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ExperimentSwitchbackFieldsTest {
	private static Experiment switchback() {
		final Experiment experiment = new Experiment();
		experiment.id = 1;
		experiment.name = "exp";
		experiment.unitType = "store_id";
		experiment.randomizationType = "switchback";
		experiment.switchbackClusterUnitType = "store_id";
		experiment.switchbackStartAt = 1767225600000L;
		experiment.switchbackPeriodMs = 3600000L;
		experiment.switchbackCarryoverDurationMs = 900000L;
		experiment.switchbackBurnInDurationMs = 600000L;
		experiment.switchbackBurnOutDurationMs = 0L;
		experiment.switchbackDesignType = "balanced_block";
		experiment.switchbackEstimand = "global_average_treatment_effect";
		return experiment;
	}

	private interface Mutation {
		void apply(Experiment e);
	}

	@Test
	void equalsAndHashCodeCoverEverySwitchbackField() {
		assertEquals(switchback(), switchback());
		assertEquals(switchback().hashCode(), switchback().hashCode());

		final Mutation[] mutations = new Mutation[]{
				new Mutation() {
					public void apply(Experiment e) {
						e.randomizationType = "unit";
					}
				},
				new Mutation() {
					public void apply(Experiment e) {
						e.switchbackClusterUnitType = "user_id";
					}
				},
				new Mutation() {
					public void apply(Experiment e) {
						e.switchbackStartAt = 1767229200000L;
					}
				},
				new Mutation() {
					public void apply(Experiment e) {
						e.switchbackPeriodMs = 86400000L;
					}
				},
				new Mutation() {
					public void apply(Experiment e) {
						e.switchbackCarryoverDurationMs = null;
					}
				},
				new Mutation() {
					public void apply(Experiment e) {
						e.switchbackBurnInDurationMs = 1L;
					}
				},
				new Mutation() {
					public void apply(Experiment e) {
						e.switchbackBurnOutDurationMs = 1L;
					}
				},
				new Mutation() {
					public void apply(Experiment e) {
						e.switchbackDesignType = "other";
					}
				},
				new Mutation() {
					public void apply(Experiment e) {
						e.switchbackEstimand = "other";
					}
				},
		};

		for (final Mutation mutation : mutations) {
			final Experiment changed = switchback();
			mutation.apply(changed);
			assertNotEquals(switchback(), changed);
			assertNotEquals(changed, switchback());
			assertNotEquals(switchback().hashCode(), changed.hashCode());
		}
	}

	@Test
	void toStringIncludesSwitchbackFields() {
		final String s = switchback().toString();
		assertTrue(s.contains("randomizationType='switchback'"));
		assertTrue(s.contains("switchbackClusterUnitType='store_id'"));
		assertTrue(s.contains("switchbackStartAt=1767225600000"));
		assertTrue(s.contains("switchbackPeriodMs=3600000"));
		assertTrue(s.contains("switchbackCarryoverDurationMs=900000"));
		assertTrue(s.contains("switchbackBurnInDurationMs=600000"));
		assertTrue(s.contains("switchbackBurnOutDurationMs=0"));
		assertTrue(s.contains("switchbackDesignType='balanced_block'"));
		assertTrue(s.contains("switchbackEstimand='global_average_treatment_effect'"));
	}
}
