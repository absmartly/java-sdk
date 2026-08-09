package com.absmartly.sdk;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.absmartly.sdk.json.ContextData;
import com.absmartly.sdk.json.Experiment;
import com.absmartly.sdk.json.ExperimentApplication;
import com.absmartly.sdk.json.ExperimentVariant;

class DefaultContextDataDeserializerTest extends TestUtils {
	@Test
	void deserialize() {
		final byte[] bytes = getResourceBytes("context.json");

		final ContextDataDeserializer deser = new DefaultContextDataDeserializer();
		final ContextData data = deser.deserialize(bytes, 0, bytes.length);

		final Experiment experiment0 = new Experiment();
		experiment0.id = 1;
		experiment0.name = "exp_test_ab";
		experiment0.unitType = "session_id";
		experiment0.iteration = 1;
		experiment0.seedHi = 3603515;
		experiment0.seedLo = 233373850;
		experiment0.split = new double[]{0.5, 0.5};
		experiment0.trafficSeedHi = 449867249;
		experiment0.trafficSeedLo = 455443629;
		experiment0.trafficSplit = new double[]{0.0, 1.0};
		experiment0.fullOnVariant = 0;
		experiment0.applications = new ExperimentApplication[]{new ExperimentApplication("website")};
		experiment0.variants = new ExperimentVariant[]{
				new ExperimentVariant("A", null),
				new ExperimentVariant("B", "{\"banner.border\":1,\"banner.size\":\"large\"}")
		};
		experiment0.audienceStrict = false;
		experiment0.audience = null;

		final Experiment experiment1 = new Experiment();
		experiment1.id = 2;
		experiment1.name = "exp_test_abc";
		experiment1.unitType = "session_id";
		experiment1.iteration = 1;
		experiment1.seedHi = 55006150;
		experiment1.seedLo = 47189152;
		experiment1.split = new double[]{0.34, 0.33, 0.33};
		experiment1.trafficSeedHi = 705671872;
		experiment1.trafficSeedLo = 212903484;
		experiment1.trafficSplit = new double[]{0.0, 1.0};
		experiment1.fullOnVariant = 0;
		experiment1.applications = new ExperimentApplication[]{new ExperimentApplication("website")};
		experiment1.variants = new ExperimentVariant[]{
				new ExperimentVariant("A", null),
				new ExperimentVariant("B", "{\"button.color\":\"blue\"}"),
				new ExperimentVariant("C", "{\"button.color\":\"red\"}")
		};
		experiment1.audienceStrict = false;
		experiment1.audience = "";

		final Experiment experiment2 = new Experiment();
		experiment2.id = 3;
		experiment2.name = "exp_test_not_eligible";
		experiment2.unitType = "user_id";
		experiment2.iteration = 1;
		experiment2.seedHi = 503266407;
		experiment2.seedLo = 144942754;
		experiment2.split = new double[]{0.34, 0.33, 0.33};
		experiment2.trafficSeedHi = 87768905;
		experiment2.trafficSeedLo = 511357582;
		experiment2.trafficSplit = new double[]{0.99, 0.01};
		experiment2.fullOnVariant = 0;
		experiment2.applications = new ExperimentApplication[]{new ExperimentApplication("website")};
		experiment2.variants = new ExperimentVariant[]{
				new ExperimentVariant("A", null),
				new ExperimentVariant("B", "{\"card.width\":\"80%\"}"),
				new ExperimentVariant("C", "{\"card.width\":\"75%\"}")
		};
		experiment2.audienceStrict = false;
		experiment2.audience = "{}";

		final Experiment experiment3 = new Experiment();
		experiment3.id = 4;
		experiment3.name = "exp_test_fullon";
		experiment3.unitType = "session_id";
		experiment3.iteration = 1;
		experiment3.seedHi = 856061641;
		experiment3.seedLo = 990838475;
		experiment3.split = new double[]{0.25, 0.25, 0.25, 0.25};
		experiment3.trafficSeedHi = 360868579;
		experiment3.trafficSeedLo = 330937933;
		experiment3.trafficSplit = new double[]{0.0, 1.0};
		experiment3.fullOnVariant = 2;
		experiment3.applications = new ExperimentApplication[]{new ExperimentApplication("website")};
		experiment3.variants = new ExperimentVariant[]{
				new ExperimentVariant("A", null),
				new ExperimentVariant("B", "{\"submit.color\":\"red\",\"submit.shape\":\"circle\"}"),
				new ExperimentVariant("C", "{\"submit.color\":\"blue\",\"submit.shape\":\"rect\"}"),
				new ExperimentVariant("D", "{\"submit.color\":\"green\",\"submit.shape\":\"square\"}")
		};
		experiment3.audienceStrict = false;
		experiment3.audience = "null";

		final ContextData expected = new ContextData();
		expected.experiments = new Experiment[]{
				experiment0,
				experiment1,
				experiment2,
				experiment3
		};

		assertNotNull(data);
		assertEquals(expected, data);
	}

	@Test
	void deserializeDoesNotThrow() {
		final byte[] bytes = getResourceBytes("context.json");

		final ContextDataDeserializer deser = new DefaultContextDataDeserializer();
		assertDoesNotThrow(() -> {
			final ContextData data = deser.deserialize(bytes, 0, 14);
			assertNull(data);
		});
	}

	@Test
	void deserializeHoldouts() {
		final byte[] bytes = getResourceBytes("holdouts_context.json");

		final ContextDataDeserializer deser = new DefaultContextDataDeserializer();
		final ContextData data = deser.deserialize(bytes, 0, bytes.length);

		final Experiment experiment = new Experiment();
		experiment.id = 1;
		experiment.name = "exp_test_holdout";
		experiment.unitType = "session_id";
		experiment.iteration = 1;
		experiment.seedHi = 3603515;
		experiment.seedLo = 233373850;
		experiment.split = new double[]{0.5, 0.5};
		experiment.trafficSeedHi = 449867249;
		experiment.trafficSeedLo = 455443629;
		experiment.trafficSplit = new double[]{0.0, 1.0};
		experiment.fullOnVariant = 0;
		experiment.applications = new ExperimentApplication[]{new ExperimentApplication("website")};
		experiment.variants = new ExperimentVariant[]{
				new ExperimentVariant("A", null),
				new ExperimentVariant("B", "{\"banner.border\":1,\"banner.size\":\"large\"}")
		};
		experiment.audienceStrict = false;
		experiment.audience = null;

		final Experiment holdoutA = new Experiment();
		holdoutA.id = 11;
		holdoutA.name = "holdout_a";
		holdoutA.unitType = "session_id";
		holdoutA.iteration = 1;
		holdoutA.seedHi = 13;
		holdoutA.seedLo = 111;
		holdoutA.split = new double[]{0.1, 0.9};
		holdoutA.trafficSplit = new double[]{0.0, 1.0};
		holdoutA.fullOnVariant = 0;
		holdoutA.variants = new ExperimentVariant[]{
				new ExperimentVariant("A", null),
				new ExperimentVariant("B", null)
		};
		holdoutA.audienceStrict = false;
		holdoutA.audience = null;
		holdoutA.holdoutType = "full";
		holdoutA.excludedExperimentIds = new int[0];

		final Experiment holdoutB = new Experiment();
		holdoutB.id = 12;
		holdoutB.name = "holdout_b";
		holdoutB.unitType = "session_id";
		holdoutB.iteration = 1;
		holdoutB.seedHi = 1;
		holdoutB.seedLo = 222;
		holdoutB.split = new double[]{0.05, 0.95};
		holdoutB.trafficSplit = new double[]{0.0, 1.0};
		holdoutB.fullOnVariant = 0;
		holdoutB.variants = new ExperimentVariant[]{
				new ExperimentVariant("A", null),
				new ExperimentVariant("B", null)
		};
		holdoutB.audienceStrict = false;
		holdoutB.audience = null;
		holdoutB.holdoutType = "full_on";
		holdoutB.excludedExperimentIds = new int[]{4};

		final ContextData expected = new ContextData(
				new Experiment[]{experiment},
				new Experiment[]{holdoutA, holdoutB});

		assertNotNull(data);
		assertEquals(expected, data);
	}
}
