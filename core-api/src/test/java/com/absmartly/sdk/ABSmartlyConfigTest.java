package com.absmartly.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

import java.util.concurrent.ScheduledExecutorService;

import org.junit.jupiter.api.Test;

class ABSmartlyConfigTest {
	@Test
	void setEndpoint() {
		final ABSmartlyConfig config = ABSmartlyConfig.create().setEndpoint("https://test.endpoint.com");
		assertEquals("https://test.endpoint.com", config.getEndpoint());
	}

	@Test
	void setAPIKey() {
		final ABSmartlyConfig config = ABSmartlyConfig.create().setAPIKey("api-key-test");
		assertEquals("api-key-test", config.getAPIKey());
	}

	@Test
	void setEnvironment() {
		final ABSmartlyConfig config = ABSmartlyConfig.create().setEnvironment("test");
		assertEquals("test", config.getEnvironment());
	}

	@Test
	void setApplication() {
		final ABSmartlyConfig config = ABSmartlyConfig.create().setApplication("website");
		assertEquals("website", config.getApplication());
	}

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
	void setDefaultHTTPClientConfig() {
		final DefaultHTTPClientConfig defaultHTTPClientConfig = mock(DefaultHTTPClientConfig.class);
		final ABSmartlyConfig config = ABSmartlyConfig.create().setDefaultHTTPClientConfig(defaultHTTPClientConfig);
		assertSame(defaultHTTPClientConfig, config.getDefaultHTTPClientConfig());
	}

	@Test
	void setAll() {
		final ContextEventHandler handler = mock(ContextEventHandler.class);
		final ContextDataProvider provider = mock(ContextDataProvider.class);
		final VariableParser parser = mock(VariableParser.class);
		final ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
		final DefaultHTTPClientConfig defaultHTTPClientConfig = mock(DefaultHTTPClientConfig.class);
		final ABSmartlyConfig config = ABSmartlyConfig.create()
				.setEndpoint("https://test.endpoint.com")
				.setAPIKey("api-key-test")
				.setEnvironment("test")
				.setApplication("website")
				.setVariableParser(parser)
				.setContextDataProvider(provider)
				.setContextEventHandler(handler)
				.setScheduler(scheduler)
				.setDefaultHTTPClientConfig(defaultHTTPClientConfig);
		assertEquals("https://test.endpoint.com", config.getEndpoint());
		assertEquals("api-key-test", config.getAPIKey());
		assertEquals("test", config.getEnvironment());
		assertEquals("website", config.getApplication());
		assertSame(provider, config.getContextDataProvider());
		assertSame(handler, config.getContextEventHandler());
		assertSame(parser, config.getVariableParser());
		assertSame(scheduler, config.getScheduler());
		assertSame(defaultHTTPClientConfig, config.getDefaultHTTPClientConfig());
	}
}
