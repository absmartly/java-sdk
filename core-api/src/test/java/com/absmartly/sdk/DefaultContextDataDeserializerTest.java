package com.absmartly.sdk;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import com.absmartly.sdk.json.ContextData;
import com.absmartly.sdk.json.Experiment;
import com.absmartly.sdk.json.ExperimentApplication;
import com.absmartly.sdk.json.ExperimentVariant;

class DefaultContextDataDeserializerTest {
	@Test
	void deserialize() {
		final byte[] bytes = TestUtils.getResourceBytes("context.json");

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
	void deserializeDoesNotThrow() throws IOException {
		final byte[] bytes = TestUtils.getResourceBytes("context.json");

		final ContextDataDeserializer deser = new DefaultContextDataDeserializer();
		assertDoesNotThrow(() -> {
			final ContextData data = deser.deserialize(bytes, 0, 14);
			assertNull(data);
		});
	}
}
