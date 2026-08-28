package com.absmartly.sdk;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.util.concurrent.ScheduledExecutorService;
import java8.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import com.absmartly.sdk.json.ContextData;

/**
 * Exercises the restored old-API facade classes (ABSmartly, ABSmartlyConfig) the way a
 * pre-rename consumer would: static factory, fluent chain returning ABSmartlyConfig at every
 * step, and a round-trip through the ABSmartly lifecycle. Assigning each setter result to an
 * ABSmartlyConfig-typed local is a compile-time proof that the covariant bridge descriptors
 * are present and correct.
 */
class ABSmartlyCompatTest extends TestUtils {

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

		// ABSmartlyConfig.create() starts the chain; each step is assigned to an
		// ABSmartlyConfig-typed local, which is a compile-time descriptor check.
		ABSmartlyConfig step0 = ABSmartlyConfig.create();
		ABSmartlyConfig step1 = step0.setClient(client);
		ABSmartlyConfig step2 = step1.setContextDataProvider(provider);
		ABSmartlyConfig step3 = step2.setContextEventHandler(handler);
		ABSmartlyConfig step4 = step3.setVariableParser(parser);
		ABSmartlyConfig step5 = step4.setScheduler(scheduler);
		ABSmartlyConfig step6 = step5.setContextEventLogger(logger);
		ABSmartlyConfig config = step6.setAudienceDeserializer(deserializer);

		// Each step must return the same config object (fluent identity).
		assertSame(step0, step1);
		assertSame(step0, config);

		// getContextEventHandler must round-trip the handler set via setContextEventHandler.
		assertSame(handler, config.getContextEventHandler());

		// Provide a completed data future so createContext does not block.
		final ContextData data = new ContextData();
		when(provider.getContextData()).thenReturn(CompletableFuture.completedFuture(data));

		// ABSmartly.create(ABSmartlyConfig) must accept the old config type.
		// provider and handler are both set, so no Client network access occurs.
		ABSmartly absmartly = ABSmartly.create(config);
		assertNotNull(absmartly);

		// createContext is inherited from ABsmartly; verify it delegates to the injected provider.
		final ContextConfig contextConfig = ContextConfig.create();
		final Context context = absmartly.createContext(contextConfig);
		assertNotNull(context);
		verify(provider, times(1)).getContextData();

		// close() is inherited; it must not throw.
		absmartly.close();
	}
}
