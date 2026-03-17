package com.absmartly.sdk;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.concurrent.ScheduledExecutorService;
import java8.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.absmartly.sdk.java.time.Clock;
import com.absmartly.sdk.json.ContextData;
import com.absmartly.sdk.json.Experiment;

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

	Context createReadyContext(ContextData data) {
		final ContextConfig config = ContextConfig.create()
				.setUnit("session_id", "e791e240fcd3df7d238cfc285f475e8152fcc0ec");

		return Context.create(clock, config, scheduler, CompletableFuture.completedFuture(data), dataProvider,
				eventHandler, eventLogger, variableParser, audienceMatcher);
	}

	@Test
	void setDataWithNullVariantsDoesNotThrowNPE() {
		final ContextData data = new ContextData();
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
		final ContextData data = new ContextData();
		final Experiment experiment = new Experiment();
		experiment.id = 1;
		experiment.name = "exp_test";
		experiment.unitType = "session_id";
		experiment.variants = new com.absmartly.sdk.json.ExperimentVariant[0];
		experiment.customFieldValues = null;
		data.experiments = new Experiment[]{experiment};

		assertDoesNotThrow(() -> createReadyContext(data));
	}

	@Test
	void brandingInErrorMessages() {
		final ContextData data = new ContextData();
		data.experiments = new Experiment[0];

		final Context context = createReadyContext(data);

		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
		context.close();

		IllegalStateException ex = assertThrows(IllegalStateException.class,
				() -> context.setAttribute("test", "value"));
		assertTrue(ex.getMessage().contains("ABsmartly"));
		assertFalse(ex.getMessage().contains("ABSmartly"));
	}

	@Test
	void setDataWithNullExperimentVariantsReturnsDefaultTreatment() {
		final ContextData data = new ContextData();
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

		int treatment = context.getTreatment("exp_test");
		assertEquals(0, treatment);
	}
}
