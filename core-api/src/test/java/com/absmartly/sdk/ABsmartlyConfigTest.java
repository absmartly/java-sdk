package com.absmartly.sdk;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

import java.util.concurrent.ScheduledExecutorService;

import org.junit.jupiter.api.Test;

class ABsmartlyConfigTest extends TestUtils {
	@Test
	void setContextDataProvider() {
		final ContextDataProvider provider = mock(ContextDataProvider.class);
		final ABsmartlyConfig config = ABsmartlyConfig.create().setContextDataProvider(provider);
		assertSame(provider, config.getContextDataProvider());
	}

	@Test
	void setContextEventHandler() {
		final ContextEventHandler handler = mock(ContextEventHandler.class);
		final ABsmartlyConfig config = ABsmartlyConfig.create().setContextEventHandler(handler);
		assertSame(handler, config.getContextEventHandler());
	}

	@Test
	void setVariableParser() {
		final VariableParser variableParser = mock(VariableParser.class);
		final ABsmartlyConfig config = ABsmartlyConfig.create().setVariableParser(variableParser);
		assertSame(variableParser, config.getVariableParser());
	}

	@Test
	void setScheduler() {
		final ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
		final ABsmartlyConfig config = ABsmartlyConfig.create().setScheduler(scheduler);
		assertSame(scheduler, config.getScheduler());
	}

	@Test
	void setContextEventLogger() {
		final ContextEventLogger logger = mock(ContextEventLogger.class);
		final ABsmartlyConfig config = ABsmartlyConfig.create().setContextEventLogger(logger);
		assertSame(logger, config.getContextEventLogger());
	}

	@Test
	void setAll() {
		final ContextEventHandler handler = mock(ContextEventHandler.class);
		final ContextDataProvider provider = mock(ContextDataProvider.class);
		final VariableParser parser = mock(VariableParser.class);
		final ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
		final Client client = mock(Client.class);
		final ABsmartlyConfig config = ABsmartlyConfig.create()
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
