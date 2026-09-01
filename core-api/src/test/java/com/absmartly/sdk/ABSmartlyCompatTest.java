package com.absmartly.sdk;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.util.concurrent.ScheduledExecutorService;
import java8.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import com.absmartly.sdk.json.ContextData;

class ABSmartlyCompatTest {

	@Test
	@SuppressWarnings("deprecation")
	void oldApiFluentChainAndLifecycleWork() throws IOException {
		final ContextDataProvider provider = mock(ContextDataProvider.class);
		final ContextEventHandler handler = mock(ContextEventHandler.class);
		final VariableParser parser = mock(VariableParser.class);
		final ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
		final ContextEventLogger logger = mock(ContextEventLogger.class);
		final AudienceDeserializer deserializer = mock(AudienceDeserializer.class);
		final Client client = mock(Client.class);

		// Separate assignments verify each setter's covariant return type at compile time.
		ABSmartlyConfig step0 = ABSmartlyConfig.create();
		ABSmartlyConfig step1 = step0.setClient(client);
		ABSmartlyConfig step2 = step1.setContextDataProvider(provider);
		ABSmartlyConfig step3 = step2.setContextEventHandler(handler);
		ABSmartlyConfig step4 = step3.setVariableParser(parser);
		ABSmartlyConfig step5 = step4.setScheduler(scheduler);
		ABSmartlyConfig step6 = step5.setContextEventLogger(logger);
		ABSmartlyConfig config = step6.setAudienceDeserializer(deserializer);

		assertSame(step0, step1);
		assertSame(step0, config);
		assertSame(handler, config.getContextEventHandler());

		final ContextData data = new ContextData();
		when(provider.getContextData()).thenReturn(CompletableFuture.completedFuture(data));

		final ABSmartly absmartly = ABSmartly.create(config);
		assertNotNull(absmartly);

		final ContextConfig contextConfig = ContextConfig.create();
		final Context context = absmartly.createContext(contextConfig);
		assertNotNull(context);
		verify(provider, times(1)).getContextData();

		absmartly.close();
	}
}
