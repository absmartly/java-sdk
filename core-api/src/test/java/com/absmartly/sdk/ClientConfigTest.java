package com.absmartly.sdk;

import org.junit.jupiter.api.Test;

import java.util.Properties;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

class ClientConfigTest extends TestUtils {
	@Test
	void setEndpoint() {
		final ClientConfig config = ClientConfig.create().setEndpoint("https://test.endpoint.com");
		assertEquals("https://test.endpoint.com", config.getEndpoint());
	}

	@Test
	void setAPIKey() {
		final ClientConfig config = ClientConfig.create().setAPIKey("api-key-test");
		assertEquals("api-key-test", config.getAPIKey());
	}

	@Test
	void setEnvironment() {
		final ClientConfig config = ClientConfig.create().setEnvironment("test");
		assertEquals("test", config.getEnvironment());
	}

	@Test
	void setApplication() {
		final ClientConfig config = ClientConfig.create().setApplication("website");
		assertEquals("website", config.getApplication());
	}

	@Test
	void setContextDataDeserializer() {
		final ContextDataDeserializer deserializer = mock(ContextDataDeserializer.class);
		final ClientConfig config = ClientConfig.create().setContextDataDeserializer(deserializer);
		assertEquals(deserializer, config.getContextDataDeserializer());
	}

	@Test
	void setContextEventSerializer() {
		final ContextEventSerializer serializer = mock(ContextEventSerializer.class);
		final ClientConfig config = ClientConfig.create().setContextEventSerializer(serializer);
		assertEquals(serializer, config.getContextEventSerializer());
	}

	@Test
	void setExecutor() {
		final Executor executor = mock(Executor.class);
		final ClientConfig config = ClientConfig.create().setExecutor(executor);
		assertSame(executor, config.getExecutor());
	}

	@Test
	void setAll() {
		final ContextEventSerializer serializer = mock(ContextEventSerializer.class);
		final ContextDataDeserializer deserializer = mock(ContextDataDeserializer.class);
		final Executor executor = mock(Executor.class);
		final ClientConfig config = ClientConfig.create()
				.setEndpoint("https://test.endpoint.com")
				.setAPIKey("api-key-test")
				.setEnvironment("test")
				.setApplication("website")
				.setContextDataDeserializer(deserializer)
				.setContextEventSerializer(serializer)
				.setExecutor(executor);

		assertEquals("https://test.endpoint.com", config.getEndpoint());
		assertEquals("api-key-test", config.getAPIKey());
		assertEquals("test", config.getEnvironment());
		assertEquals("website", config.getApplication());
		assertSame(deserializer, config.getContextDataDeserializer());
		assertSame(serializer, config.getContextEventSerializer());
		assertSame(executor, config.getExecutor());
	}

	@Test
	void createFromProperties() {
		final Properties props = new Properties();
		props.putAll(TestUtils.mapOf(
			"absmartly.endpoint", "https://test.endpoint.com",
			"absmartly.environment", "test",
			"absmartly.apikey", "api-key-test",
			"absmartly.application", "website"
		));

		final ContextEventSerializer serializer = mock(ContextEventSerializer.class);
		final ContextDataDeserializer deserializer = mock(ContextDataDeserializer.class);
		final Executor executor = mock(Executor.class);
		final ClientConfig config = ClientConfig.createFromProperties(props, "absmartly.")
			.setContextDataDeserializer(deserializer)
			.setContextEventSerializer(serializer)
			.setExecutor(executor);

		assertEquals("https://test.endpoint.com", config.getEndpoint());
		assertEquals("api-key-test", config.getAPIKey());
		assertEquals("test", config.getEnvironment());
		assertEquals("website", config.getApplication());
		assertSame(deserializer, config.getContextDataDeserializer());
		assertSame(serializer, config.getContextEventSerializer());
		assertSame(executor, config.getExecutor());
	}
}
