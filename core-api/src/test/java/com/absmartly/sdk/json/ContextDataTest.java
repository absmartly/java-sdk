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

	private static Experiment holdout(int id, String unitType) {
		final Experiment holdout = new Experiment();
		holdout.id = id;
		holdout.name = "holdout_" + id;
		holdout.unitType = unitType;
		holdout.split = new double[]{0.1, 0.9};
		holdout.variants = new ExperimentVariant[0];
		holdout.holdoutType = "full";
		return holdout;
	}

	@Test
	void equalsHashCodeAndToStringWithHoldouts() {
		final Experiment[] experiments = new Experiment[]{experiment(1, "exp")};
		final Experiment[] holdouts = new Experiment[]{holdout(11, "session_id")};

		final ContextData a = new ContextData(experiments, holdouts);
		final ContextData b = new ContextData(experiments, new Experiment[]{holdout(11, "session_id")});

		assertEquals(a, a);
		assertEquals(a, b);
		assertEquals(a.hashCode(), b.hashCode());

		// same experiments but different holdouts must compare unequal
		final ContextData differentHoldouts = new ContextData(experiments, new Experiment[]{holdout(22, "session_id")});
		assertNotEquals(a, differentHoldouts);
		assertNotEquals(a.hashCode(), differentHoldouts.hashCode());

		assertTrue(a.toString().contains("holdouts="));
	}
}
