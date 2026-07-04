package com.absmartly.sdk.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ExperimentHoldoutTest {
	@Test
	void equalsHashCodeAndToString() {
		final ExperimentHoldout a = new ExperimentHoldout(11, 13, 111, new double[]{0.1, 0.9});
		final ExperimentHoldout b = new ExperimentHoldout(11, 13, 111, new double[]{0.1, 0.9});

		assertEquals(a, a);
		assertEquals(a, b);
		assertEquals(a.hashCode(), b.hashCode());

		assertNotEquals(a, null);
		assertNotEquals(a, "not a holdout");
		assertNotEquals(a, new ExperimentHoldout(99, 13, 111, new double[]{0.1, 0.9}));
		assertNotEquals(a, new ExperimentHoldout(11, 99, 111, new double[]{0.1, 0.9}));
		assertNotEquals(a, new ExperimentHoldout(11, 13, 999, new double[]{0.1, 0.9}));
		assertNotEquals(a, new ExperimentHoldout(11, 13, 111, new double[]{0.2, 0.8}));

		final String text = a.toString();
		assertTrue(text.contains("id=11"));
		assertTrue(text.contains("seedHi=13"));
		assertTrue(text.contains("seedLo=111"));
	}
}
