package com.absmartly.sdk;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Map;
import java8.util.concurrent.CompletableFuture;
import java8.util.concurrent.CompletionException;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.absmartly.sdk.java.nio.charset.StandardCharsets;
import com.absmartly.sdk.json.ContextData;

class ClientFixTest extends TestUtils {

	@Test
	void endpointTrailingSlashNormalized() {
		final HTTPClient httpClient = mock(HTTPClient.class);
		final ContextDataDeserializer deser = mock(ContextDataDeserializer.class);
		final Client client = Client.create(ClientConfig.create()
				.setEndpoint("https://localhost/v1/")
				.setAPIKey("test-api-key")
				.setApplication("website")
				.setEnvironment("dev")
				.setContextDataDeserializer(deser), httpClient);

		final byte[] bytes = "{}".getBytes(StandardCharsets.UTF_8);
		final ContextData expected = new ContextData();

		final Map<String, String> expectedQuery = mapOf(
				"application", "website",
				"environment", "dev");

		when(httpClient.get("https://localhost/v1/context", expectedQuery, null))
				.thenReturn(CompletableFuture.completedFuture(new DefaultHTTPClient.DefaultResponse(200, "OK",
						"application/json", bytes)));
		when(deser.deserialize(bytes, 0, bytes.length)).thenReturn(expected);

		final CompletableFuture<ContextData> dataFuture = client.getContextData();
		final ContextData actual = dataFuture.join();

		assertSame(expected, actual);
		verify(httpClient, Mockito.timeout(5000).times(1)).get("https://localhost/v1/context", expectedQuery, null);
	}

	@Test
	void endpointWithoutTrailingSlashUnchanged() {
		final HTTPClient httpClient = mock(HTTPClient.class);
		final ContextDataDeserializer deser = mock(ContextDataDeserializer.class);
		final Client client = Client.create(ClientConfig.create()
				.setEndpoint("https://localhost/v1")
				.setAPIKey("test-api-key")
				.setApplication("website")
				.setEnvironment("dev")
				.setContextDataDeserializer(deser), httpClient);

		final byte[] bytes = "{}".getBytes(StandardCharsets.UTF_8);
		final ContextData expected = new ContextData();

		final Map<String, String> expectedQuery = mapOf(
				"application", "website",
				"environment", "dev");

		when(httpClient.get("https://localhost/v1/context", expectedQuery, null))
				.thenReturn(CompletableFuture.completedFuture(new DefaultHTTPClient.DefaultResponse(200, "OK",
						"application/json", bytes)));
		when(deser.deserialize(bytes, 0, bytes.length)).thenReturn(expected);

		final CompletableFuture<ContextData> dataFuture = client.getContextData();
		final ContextData actual = dataFuture.join();

		assertSame(expected, actual);
	}

	@Test
	void httpEndpointIsAccepted() {
		final HTTPClient httpClient = mock(HTTPClient.class);
		assertDoesNotThrow(() -> {
			Client.create(ClientConfig.create()
					.setEndpoint("http://localhost/v1")
					.setAPIKey("test-api-key")
					.setApplication("website")
					.setEnvironment("dev"), httpClient);
		});
	}

	@Test
	void invalidProtocolThrows() {
		final HTTPClient httpClient = mock(HTTPClient.class);
		assertThrows(IllegalArgumentException.class, () -> {
			Client.create(ClientConfig.create()
					.setEndpoint("ftp://localhost/v1")
					.setAPIKey("test-api-key")
					.setApplication("website")
					.setEnvironment("dev"), httpClient);
		});
	}

	@Test
	void getContextDataNullDeserializationThrowsExceptionally() {
		final HTTPClient httpClient = mock(HTTPClient.class);
		final ContextDataDeserializer deser = mock(ContextDataDeserializer.class);
		final Client client = Client.create(ClientConfig.create()
				.setEndpoint("https://localhost/v1")
				.setAPIKey("test-api-key")
				.setApplication("website")
				.setEnvironment("dev")
				.setContextDataDeserializer(deser), httpClient);

		final byte[] bytes = "invalid".getBytes(StandardCharsets.UTF_8);

		final Map<String, String> expectedQuery = mapOf(
				"application", "website",
				"environment", "dev");

		when(httpClient.get("https://localhost/v1/context", expectedQuery, null))
				.thenReturn(CompletableFuture.completedFuture(new DefaultHTTPClient.DefaultResponse(200, "OK",
						"application/json", bytes)));
		when(deser.deserialize(bytes, 0, bytes.length)).thenReturn(null);

		final CompletableFuture<ContextData> dataFuture = client.getContextData();
		final CompletionException actual = assertThrows(CompletionException.class, dataFuture::join);
		assertTrue(actual.getCause() instanceof IllegalStateException);
		assertEquals("Failed to deserialize context data response", actual.getCause().getMessage());
	}

	@Test
	void getContextDataEmptyResponseThrowsExceptionally() {
		final HTTPClient httpClient = mock(HTTPClient.class);
		final ContextDataDeserializer deser = mock(ContextDataDeserializer.class);
		final Client client = Client.create(ClientConfig.create()
				.setEndpoint("https://localhost/v1")
				.setAPIKey("test-api-key")
				.setApplication("website")
				.setEnvironment("dev")
				.setContextDataDeserializer(deser), httpClient);

		final Map<String, String> expectedQuery = mapOf(
				"application", "website",
				"environment", "dev");

		when(httpClient.get("https://localhost/v1/context", expectedQuery, null))
				.thenReturn(CompletableFuture.completedFuture(new DefaultHTTPClient.DefaultResponse(200, "OK",
						"application/json", new byte[0])));

		final CompletableFuture<ContextData> dataFuture = client.getContextData();
		final CompletionException actual = assertThrows(CompletionException.class, dataFuture::join);
		assertTrue(actual.getCause() instanceof IllegalStateException);
	}
}
