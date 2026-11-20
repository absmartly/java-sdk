package com.absmartly.sdk;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.absmartly.sdk.internal.Clock;
import com.absmartly.sdk.internal.json.ContextPayload;
import com.absmartly.sdk.internal.json.Experiment;

class ContextFixTest extends TestUtils {

	ContextDataProvider dataProvider;
	ContextEventLogger eventLogger;
	ContextEventHandler eventHandler;
	VariableParser variableParser;
	AudienceMatcher audienceMatcher;
	ScheduledExecutorService scheduler;
	Clock clock = Clock.fixed(1_620_000_000_000L);

	@BeforeEach
	void setUp() {
		dataProvider = mock(ContextDataProvider.class);
		eventHandler = mock(ContextEventHandler.class);
		eventLogger = mock(ContextEventLogger.class);
		variableParser = new DefaultVariableParser();
		audienceMatcher = new AudienceMatcher(new DefaultAudienceDeserializer());
		scheduler = mock(ScheduledExecutorService.class);
	}

	Context createReadyContext(ContextPayload data) {
		final TestContextConfig config = TestContextConfig.create()
				.setUnit("session_id", "e791e240fcd3df7d238cfc285f475e8152fcc0ec");

		return TestContexts.create(clock, config, scheduler, CompletableFuture.completedFuture(data), dataProvider,
				eventHandler, eventLogger, variableParser, audienceMatcher);
	}

	@Test
	void setDataWithNullVariantsDoesNotThrowNPE() {
		final ContextPayload data = new ContextPayload();
		final Experiment experiment = new Experiment();
		experiment.id = 1;
		experiment.name = "exp_test";
		experiment.unitType = "session_id";
		experiment.variants = null;
		data.experiments = new Experiment[]{experiment};

		assertDoesNotThrow(() -> createReadyContext(data));
	}

	@Test
	void setDataWithNullCustomFieldValuesDoesNotThrowNPE() {
		final ContextPayload data = new ContextPayload();
		final Experiment experiment = new Experiment();
		experiment.id = 1;
		experiment.name = "exp_test";
		experiment.unitType = "session_id";
		experiment.variants = new com.absmartly.sdk.internal.json.ExperimentVariant[0];
		experiment.customFieldValues = null;
		data.experiments = new Experiment[]{experiment};

		assertDoesNotThrow(() -> createReadyContext(data));
	}

	@Test
	void setDataWithNullExperimentVariantsReturnsDefaultTreatment() {
		final ContextPayload data = new ContextPayload();
		final Experiment experiment = new Experiment();
		experiment.id = 1;
		experiment.name = "exp_test";
		experiment.unitType = "session_id";
		experiment.variants = null;
		experiment.trafficSplit = new double[]{1.0};
		experiment.split = new double[]{1.0};
		data.experiments = new Experiment[]{experiment};

		final Context context = createReadyContext(data);
		assertTrue(context.isReady());

		int treatment = context.treatment("exp_test");
		assertEquals(0, treatment);
	}
}
