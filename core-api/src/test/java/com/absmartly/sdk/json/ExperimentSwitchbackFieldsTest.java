package com.absmartly.sdk.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ExperimentSwitchbackFieldsTest {
	private static Map<String, String> clusters(String... entries) {
		final Map<String, String> map = new LinkedHashMap<String, String>();
		for (int i = 0; i < entries.length; i += 2) {
			map.put(entries[i], entries[i + 1]);
		}
		return map;
	}

	private static Experiment switchback() {
		final Experiment experiment = new Experiment();
		experiment.id = 1;
		experiment.name = "exp";
		experiment.unitType = "store_id";
		experiment.randomizationType = "switchback";
		experiment.switchbackClusters = clusters("store_id", "store/key", "device_id", "geo/city");
		experiment.switchbackClusterValues = new String[]{"porto", "lisbon"};
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
						e.switchbackClusters = null;
					}
				},
				new Mutation() {
					public void apply(Experiment e) {
						e.switchbackClusters = clusters("store_id", "store/key");
					}
				},
				new Mutation() {
					public void apply(Experiment e) {
						e.switchbackClusters = clusters("store_id", "store/key", "device_id", "geo/country");
					}
				},
				// Same entries, other order: the order is semantic.
				new Mutation() {
					public void apply(Experiment e) {
						e.switchbackClusters = clusters("device_id", "geo/city", "store_id", "store/key");
					}
				},
				new Mutation() {
					public void apply(Experiment e) {
						e.switchbackClusterValues = null;
					}
				},
				new Mutation() {
					public void apply(Experiment e) {
						e.switchbackClusterValues = new String[]{"lisbon", "porto"};
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
		assertTrue(s.contains("switchbackClusters={store_id=store/key, device_id=geo/city}"));
		assertTrue(s.contains("switchbackClusterValues=[porto, lisbon]"));
		assertTrue(s.contains("switchbackStartAt=1767225600000"));
		assertTrue(s.contains("switchbackPeriodMs=3600000"));
		assertTrue(s.contains("switchbackCarryoverDurationMs=900000"));
		assertTrue(s.contains("switchbackBurnInDurationMs=600000"));
		assertTrue(s.contains("switchbackBurnOutDurationMs=0"));
		assertTrue(s.contains("switchbackDesignType='balanced_block'"));
		assertTrue(s.contains("switchbackEstimand='global_average_treatment_effect'"));
	}
}
