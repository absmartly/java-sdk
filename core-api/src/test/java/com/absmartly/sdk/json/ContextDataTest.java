package com.absmartly.sdk.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ContextDataTest {
	private static Experiment experiment(int id, String name) {
		final Experiment experiment = new Experiment();
		experiment.id = id;
		experiment.name = name;
		experiment.unitType = "session_id";
		experiment.variants = new ExperimentVariant[0];
		return experiment;
	}

	@Test
	void equalsHashCodeAndToStringWithHoldouts() {
		final Experiment[] experiments = new Experiment[]{experiment(1, "exp")};
		final ExperimentHoldout[] holdouts = new ExperimentHoldout[]{
				new ExperimentHoldout(11, 13, 111, new double[]{0.1, 0.9})
		};

		final ContextData a = new ContextData(experiments, holdouts);
		final ContextData b = new ContextData(experiments, new ExperimentHoldout[]{
				new ExperimentHoldout(11, 13, 111, new double[]{0.1, 0.9})
		});

		assertEquals(a, a);
		assertEquals(a, b);
		assertEquals(a.hashCode(), b.hashCode());

		// same experiments but different holdouts must compare unequal
		final ContextData differentHoldouts = new ContextData(experiments, new ExperimentHoldout[]{
				new ExperimentHoldout(22, 13, 111, new double[]{0.1, 0.9})
		});
		assertNotEquals(a, differentHoldouts);
		assertNotEquals(a.hashCode(), differentHoldouts.hashCode());

		assertTrue(a.toString().contains("holdouts="));
	}
}
