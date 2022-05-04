package com.absmartly.sdk;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

import java.util.concurrent.ScheduledExecutorService;

import org.junit.jupiter.api.Test;

class ABSmartlyConfigTest extends TestUtils {
	@Test
	void setContextDataProvider() {
		final ContextDataProvider provider = mock(ContextDataProvider.class);
		final ABSmartlyConfig config = ABSmartlyConfig.create().setContextDataProvider(provider);
		assertSame(provider, config.getContextDataProvider());
	}

	@Test
	void setContextEventHandler() {
		final ContextEventHandler handler = mock(ContextEventHandler.class);
		final ABSmartlyConfig config = ABSmartlyConfig.create().setContextEventHandler(handler);
		assertSame(handler, config.getContextEventHandler());
	}

	@Test
	void setVariableParser() {
		final VariableParser variableParser = mock(VariableParser.class);
		final ABSmartlyConfig config = ABSmartlyConfig.create().setVariableParser(variableParser);
		assertSame(variableParser, config.getVariableParser());
	}

	@Test
	void setScheduler() {
		final ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
		final ABSmartlyConfig config = ABSmartlyConfig.create().setScheduler(scheduler);
		assertSame(scheduler, config.getScheduler());
	}

	@Test
	void setContextEventLogger() {
		final ContextEventLogger logger = mock(ContextEventLogger.class);
		final ABSmartlyConfig config = ABSmartlyConfig.create().setContextEventLogger(logger);
		assertSame(logger, config.getContextEventLogger());
	}

	@Test
	void setAll() {
		final ContextEventHandler handler = mock(ContextEventHandler.class);
		final ContextDataProvider provider = mock(ContextDataProvider.class);
		final VariableParser parser = mock(VariableParser.class);
		final ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
		final Client client = mock(Client.class);
		final ABSmartlyConfig config = ABSmartlyConfig.create()
				.setVariableParser(parser)
				.setContextDataProvider(provider)
				.setContextEventHandler(handler)
				.setScheduler(scheduler)
				.setClient(client);
		assertSame(provider, config.getContextDataProvider());
		assertSame(handler, config.getContextEventHandler());
		assertSame(parser, config.getVariableParser());
		assertSame(scheduler, config.getScheduler());
		assertSame(client, config.getClient());
	}
}
