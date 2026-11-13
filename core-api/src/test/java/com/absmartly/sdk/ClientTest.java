package com.absmartly.sdk;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import com.absmartly.sdk.internal.SdkVersion;
import com.absmartly.sdk.java.nio.charset.StandardCharsets;
import com.absmartly.sdk.json.ContextData;
import com.absmartly.sdk.json.PublishEvent;

class ClientTest extends TestUtils {
	@Test
	void createThrowsWithInvalidConfig() {
		assertThrows(IllegalArgumentException.class, () -> {
			final ClientConfig config = ClientConfig.create()
					.setAPIKey("test-api-key")
					.setApplication("website")
					.setEnvironment("dev");

			Client.create(config);
		}, "Missing Endpoint configuration");

		assertThrows(IllegalArgumentException.class, () -> {
			final ClientConfig config = ClientConfig.create()
					.setEndpoint("https://localhost/v1")
					.setApplication("website")
					.setEnvironment("dev");

			Client.create(config);
		}, "Missing APIKey configuration");

		assertThrows(IllegalArgumentException.class, () -> {
			final ClientConfig config = ClientConfig.create()
					.setEndpoint("https://localhost/v1")
					.setAPIKey("test-api-key")
					.setEnvironment("dev");

			Client.create(config);
		}, "Missing Application configuration");

		assertThrows(IllegalArgumentException.class, () -> {
			final ClientConfig config = ClientConfig.create()
					.setEndpoint("https://localhost/v1")
					.setAPIKey("test-api-key")
					.setApplication("website");

			Client.create(config);
		}, "Missing Environment configuration");
	}

	@Test
	void createWithDefaults() {
		final ClientConfig config = ClientConfig.create()
				.setEndpoint("https://localhost/v1")
				.setAPIKey("test-api-key")
				.setApplication("website")
				.setEnvironment("dev");

		final byte[] dataBytes = "{}".getBytes(StandardCharsets.UTF_8);
		final ContextData expected = new ContextData();

		final PublishEvent event = new PublishEvent();
		final byte[] publishBytes = new byte[]{0};

		try (final MockedConstruction<DefaultContextDataDeserializer> deserCtor = mockConstruction(
				DefaultContextDataDeserializer.class,
				(mock, context) -> when(mock.deserialize(dataBytes, 0, dataBytes.length)).thenReturn(expected));
				final MockedConstruction<DefaultContextEventSerializer> serCtor = mockConstruction(
						DefaultContextEventSerializer.class,
						(mock, context) -> when(mock.serialize(event)).thenReturn(publishBytes));
				final MockedStatic<DefaultHTTPClient> clientStatic = mockStatic(DefaultHTTPClient.class)) {
			final DefaultHTTPClient httpClient = mock(DefaultHTTPClient.class);
			clientStatic.when(() -> DefaultHTTPClient.create(any())).thenReturn(httpClient);

			final Map<String, String> expectedQuery = mapOf(
					"application", "website",
					"environment", "dev");

			final Map<String, String> expectedHeaders = mapOf(
					"X-API-Key", "test-api-key",
					"X-Application", "website",
					"X-Environment", "dev",
					"X-Application-Version", "0",
					"X-Agent", "absmartly-java-sdk/" + SdkVersion.VERSION);

			when(httpClient.get("https://localhost/v1/context", expectedQuery, sdkHeaders()))
					.thenReturn(getByteResponse(dataBytes));
			when(httpClient.put("https://localhost/v1/context", null, expectedHeaders, publishBytes))
					.thenReturn(getByteResponse(publishBytes));

			final Client client = Client.create(config);

			client.getContextData();

			client.publish(event);
			assertDoesNotThrow(client::close);

			verify(httpClient, Mockito.timeout(5000).times(1)).get("https://localhost/v1/context", expectedQuery,
					sdkHeaders());
			verify(httpClient, Mockito.timeout(5000).times(1)).put("https://localhost/v1/context", null,
					expectedHeaders, publishBytes);
			verify(httpClient, Mockito.timeout(5000).times(1)).close();

			verify(deserCtor.constructed().get(0), Mockito.timeout(5000).times(1)).deserialize(any(), anyInt(),
					anyInt());
			verify(deserCtor.constructed().get(0), Mockito.timeout(5000).times(1)).deserialize(dataBytes, 0,
					dataBytes.length);

			verify(serCtor.constructed().get(0), Mockito.timeout(5000).times(1)).serialize(any());
			verify(serCtor.constructed().get(0), Mockito.timeout(5000).times(1)).serialize(event);
		}
	}

	@Test
	void getContextData() throws ExecutionException, InterruptedException {
		final HTTPClient httpClient = mock(HTTPClient.class);
		final ContextDataDeserializer deser = mock(ContextDataDeserializer.class);
		final Client client = Client.create(ClientConfig.create()
				.setEndpoint("https://localhost/v1")
				.setAPIKey("test-api-key")
				.setApplication("website")
				.setEnvironment("dev")
				.setContextDataDeserializer(deser), httpClient);

		final byte[] bytes = "{}".getBytes(StandardCharsets.UTF_8);

		final Map<String, String> expectedQuery = mapOf(
				"application", "website",
				"environment", "dev");

		when(httpClient.get("https://localhost/v1/context", expectedQuery, sdkHeaders()))
				.thenReturn(getByteResponse(bytes));

		final ContextData expected = new ContextData();
		when(deser.deserialize(bytes, 0, bytes.length)).thenReturn(expected);

		final CompletableFuture<ContextData> dataFuture = client.getContextData();
		final ContextData actual = dataFuture.get();

		assertEquals(expected, actual);
		assertSame(expected, actual);
	}

	@Test
	void getContextDataSendsSameSDKHeadersAsPublish() {
		// cross-sdk scenario 320: the context GET carries the SDK headers, including X-Agent, like the publish PUT
		final HTTPClient httpClient = mock(HTTPClient.class);
		final ContextDataDeserializer deser = mock(ContextDataDeserializer.class);
		final ContextEventSerializer ser = mock(ContextEventSerializer.class);
		final Client client = Client.create(ClientConfig.create()
				.setEndpoint("https://localhost/v1")
				.setAPIKey("test-api-key")
				.setApplication("website")
				.setApplicationVersion("1004")
				.setEnvironment("dev")
				.setContextDataDeserializer(deser)
				.setContextEventSerializer(ser), httpClient);

		final byte[] bytes = "{}".getBytes(StandardCharsets.UTF_8);
		when(deser.deserialize(bytes, 0, bytes.length)).thenReturn(new ContextData());
		when(httpClient.get(any(), any(), any())).thenReturn(getByteResponse(bytes));

		final PublishEvent event = new PublishEvent();
		when(ser.serialize(event)).thenReturn(new byte[]{0});
		when(httpClient.put(any(), any(), any(), any())).thenReturn(getByteResponse(new byte[]{0}));

		client.getContextData().join();
		client.publish(event).join();

		final org.mockito.ArgumentCaptor<Map<String, String>> getHeaders = org.mockito.ArgumentCaptor
				.forClass(Map.class);
		verify(httpClient, Mockito.timeout(5000).times(1)).get(eq("https://localhost/v1/context"),
				eq(mapOf("application", "website", "environment", "dev")), getHeaders.capture());

		final org.mockito.ArgumentCaptor<Map<String, String>> putHeaders = org.mockito.ArgumentCaptor
				.forClass(Map.class);
		verify(httpClient, Mockito.timeout(5000).times(1)).put(any(), any(), putHeaders.capture(), any());

		assertNotNull(getHeaders.getValue());
		assertTrue(getHeaders.getValue().get("X-Agent")
				.matches("^absmartly-[a-z0-9]+-sdk/[0-9]+\\.[0-9]+\\.[0-9]+([-+][0-9A-Za-z.+-]*)?$"));
		assertEquals(mapOf(
				"X-API-Key", "test-api-key",
				"X-Application", "website",
				"X-Environment", "dev",
				"X-Application-Version", "1004",
				"X-Agent", "absmartly-java-sdk/" + SdkVersion.VERSION), getHeaders.getValue());
		assertEquals(putHeaders.getValue(), getHeaders.getValue());
	}

	private CompletableFuture<HTTPClient.Response> getByteResponse(byte[] bytes) {
		return CompletableFuture.completedFuture(new HTTPClient.Response() {
			@Override
			public int getStatusCode() {
				return 200;
			}

			@Override
			public String getStatusMessage() {
				return "OK";
			}

			@Override
			public String getContentType() {
				return "application/json; charset=utf8";
			}

			@Override
			public byte[] getContent() {
				return bytes;
			}
		});
	}

	@Test
	void getContextDataExceptionallyHTTP() {
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

		when(httpClient.get("https://localhost/v1/context", expectedQuery, sdkHeaders()))
				.thenReturn(CompletableFuture.completedFuture(
						new DefaultHTTPClient.DefaultResponse(500, "Internal Server Error", null, null)));

		final CompletableFuture<ContextData> dataFuture = client.getContextData();
		final CompletionException actual = assertThrows(CompletionException.class, dataFuture::join);
		assertTrue(actual.getCause() instanceof Exception);
		assertEquals("Internal Server Error", actual.getCause().getMessage());

		verify(httpClient, Mockito.timeout(5000).times(1)).get("https://localhost/v1/context", expectedQuery,
				sdkHeaders());
		verify(deser, Mockito.timeout(5000).times(0)).deserialize(any(), anyInt(), anyInt());
	}

	@Test
	void getContextDataExceptionallyConnection() {
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

		final Exception failure = new Exception("FAILED");
		final CompletableFuture<HTTPClient.Response> responseFuture = failedFuture(failure);

		when(httpClient.get("https://localhost/v1/context", expectedQuery, sdkHeaders()))
				.thenReturn(responseFuture);

		final CompletableFuture<ContextData> dataFuture = client.getContextData();
		final CompletionException actual = assertThrows(CompletionException.class, dataFuture::join);
		assertSame(actual.getCause(), failure);

		verify(httpClient, Mockito.timeout(5000).times(1)).get("https://localhost/v1/context", expectedQuery,
				sdkHeaders());
		verify(deser, Mockito.timeout(5000).times(0)).deserialize(any(), anyInt(), anyInt());
	}

	@Test
	void publish() {
		final HTTPClient httpClient = mock(HTTPClient.class);
		final ContextEventSerializer ser = mock(ContextEventSerializer.class);
		final Client client = Client.create(ClientConfig.create()
				.setEndpoint("https://localhost/v1")
				.setAPIKey("test-api-key")
				.setApplication("website")
				.setEnvironment("dev")
				.setContextEventSerializer(ser), httpClient);

		final Map<String, String> expectedHeaders = mapOf(
				"X-API-Key", "test-api-key",
				"X-Application", "website",
				"X-Environment", "dev",
				"X-Application-Version", "0",
				"X-Agent", "absmartly-java-sdk/" + SdkVersion.VERSION);

		final PublishEvent event = new PublishEvent();
		final byte[] bytes = new byte[]{0};

		when(ser.serialize(event)).thenReturn(bytes);
		when(httpClient.put("https://localhost/v1/context", null, expectedHeaders, bytes))
				.thenReturn(getByteResponse(new byte[]{0}));

		final CompletableFuture<Void> publishFuture = client.publish(event);
		publishFuture.join();

		verify(ser, Mockito.timeout(5000).times(1)).serialize(event);
		verify(httpClient, Mockito.timeout(5000).times(1)).put(any(), any(), any(), any());
		verify(httpClient, Mockito.timeout(5000).times(1)).put("https://localhost/v1/context", null, expectedHeaders,
				bytes);
	}

	@Test
	void publishExceptionallyHTTP() {
		final HTTPClient httpClient = mock(HTTPClient.class);
		final ContextEventSerializer ser = mock(ContextEventSerializer.class);
		final Client client = Client.create(ClientConfig.create()
				.setEndpoint("https://localhost/v1")
				.setAPIKey("test-api-key")
				.setApplication("website")
				.setEnvironment("dev")
				.setContextEventSerializer(ser), httpClient);

		final Map<String, String> expectedHeaders = mapOf(
				"X-API-Key", "test-api-key",
				"X-Application", "website",
				"X-Environment", "dev",
				"X-Application-Version", "0",
				"X-Agent", "absmartly-java-sdk/" + SdkVersion.VERSION);

		final PublishEvent event = new PublishEvent();
		final byte[] bytes = new byte[]{0};

		when(ser.serialize(event)).thenReturn(bytes);
		when(httpClient.put("https://localhost/v1/context", null, expectedHeaders, bytes))
				.thenReturn(CompletableFuture.completedFuture(
						new DefaultHTTPClient.DefaultResponse(500, "Internal Server Error", null, null)));

		final CompletableFuture<Void> publishFuture = client.publish(event);
		final CompletionException actual = assertThrows(CompletionException.class, publishFuture::join);
		assertTrue(actual.getCause() instanceof Exception);
		assertEquals("Internal Server Error", actual.getCause().getMessage());

		verify(ser, Mockito.timeout(5000).times(1)).serialize(event);
		verify(httpClient, Mockito.timeout(5000).times(1)).put("https://localhost/v1/context", null, expectedHeaders,
				bytes);
	}

	@Test
	void publishExceptionallyConnection() {
		final HTTPClient httpClient = mock(HTTPClient.class);
		final ContextEventSerializer ser = mock(ContextEventSerializer.class);
		final Client client = Client.create(ClientConfig.create()
				.setEndpoint("https://localhost/v1")
				.setAPIKey("test-api-key")
				.setApplication("website")
				.setEnvironment("dev")
				.setContextEventSerializer(ser), httpClient);

		final Map<String, String> expectedHeaders = mapOf(
				"X-API-Key", "test-api-key",
				"X-Application", "website",
				"X-Environment", "dev",
				"X-Application-Version", "0",
				"X-Agent", "absmartly-java-sdk/" + SdkVersion.VERSION);

		final PublishEvent event = new PublishEvent();
		final byte[] bytes = new byte[]{0};

		final Exception failure = new Exception("FAILED");
		final CompletableFuture<HTTPClient.Response> responseFuture = failedFuture(failure);

		when(ser.serialize(event)).thenReturn(bytes);
		when(httpClient.put("https://localhost/v1/context", null, expectedHeaders, bytes)).thenReturn(responseFuture);

		final CompletableFuture<Void> publishFuture = client.publish(event);
		final CompletionException actual = assertThrows(CompletionException.class, publishFuture::join);
		assertSame(actual.getCause(), failure);

		verify(ser, Mockito.timeout(5000).times(1)).serialize(event);
		verify(httpClient, Mockito.timeout(5000).times(1)).put("https://localhost/v1/context", null, expectedHeaders,
				bytes);
	}

	@Test
	void publishSendsVersionedAgentAndApplicationVersion() {
		final HTTPClient httpClient = mock(HTTPClient.class);
		final ContextEventSerializer ser = mock(ContextEventSerializer.class);
		final Client client = Client.create(ClientConfig.create()
				.setEndpoint("https://localhost/v1")
				.setAPIKey("test-api-key")
				.setApplication("website")
				.setApplicationVersion("2.4.1")
				.setEnvironment("dev")
				.setContextEventSerializer(ser), httpClient);

		final Map<String, String> expectedHeaders = mapOf(
				"X-API-Key", "test-api-key",
				"X-Application", "website",
				"X-Environment", "dev",
				"X-Application-Version", "0",
				"X-Agent", "absmartly-java-sdk/" + SdkVersion.VERSION);

		final PublishEvent event = new PublishEvent();
		final byte[] bytes = new byte[]{0};

		when(ser.serialize(event)).thenReturn(bytes);
		when(httpClient.put("https://localhost/v1/context", null, expectedHeaders, bytes))
				.thenReturn(getByteResponse(new byte[]{0}));

		client.publish(event).join();

		verify(httpClient, Mockito.timeout(5000).times(1)).put("https://localhost/v1/context", null, expectedHeaders,
				bytes);
	}

	@Test
	void agentAndApplicationGetters() {
		final Client client = Client.create(ClientConfig.create()
				.setEndpoint("https://localhost/v1")
				.setAPIKey("test-api-key")
				.setApplication("website")
				.setApplicationVersion(1004)
				.setEnvironment("dev"), mock(HTTPClient.class));

		assertEquals("absmartly-java-sdk", client.getAgentName());
		assertEquals(SdkVersion.VERSION, client.getAgentVersion());
		assertEquals("absmartly-java-sdk/" + SdkVersion.VERSION, client.getAgent());
		assertTrue(client.getAgentVersion().matches("\\d+\\.\\d+\\.\\d+.*"));
		assertEquals("website", client.getApplication());
		assertEquals("1004", client.getApplicationVersion());
		assertEquals("dev", client.getEnvironment());
	}

	@Test
	void emptyApplicationVersionSendsZero() {
		final HTTPClient httpClient = mock(HTTPClient.class);
		final ContextEventSerializer ser = mock(ContextEventSerializer.class);
		final Client client = Client.create(ClientConfig.create()
				.setEndpoint("https://localhost/v1")
				.setAPIKey("test-api-key")
				.setApplication("website")
				.setApplicationVersion("")
				.setEnvironment("dev")
				.setContextEventSerializer(ser), httpClient);

		final PublishEvent event = new PublishEvent();
		final byte[] bytes = new byte[]{0};
		when(ser.serialize(event)).thenReturn(bytes);
		when(httpClient.put(any(), any(), any(), any())).thenReturn(getByteResponse(new byte[]{0}));

		client.publish(event).join();

		final org.mockito.ArgumentCaptor<Map<String, String>> headers = org.mockito.ArgumentCaptor.forClass(Map.class);
		verify(httpClient, Mockito.timeout(5000).times(1)).put(any(), any(), headers.capture(), any());
		assertEquals("0", headers.getValue().get("X-Application-Version"));
		assertEquals("0", client.getApplicationVersion());
	}

	@Test
	void applicationVersionHeaderIsSentOnlyWhenNumeric() {
		// The collector parses X-Application-Version as a Long and rejects the request otherwise.
		assertEquals("1004", Client.applicationVersionHeader("1004"));
		assertEquals("0", Client.applicationVersionHeader("0"));
		assertEquals("0", Client.applicationVersionHeader("2.4.1"));
		assertEquals("0", Client.applicationVersionHeader("1.0"));
		assertEquals("0", Client.applicationVersionHeader("abc"));
		assertEquals("0", Client.applicationVersionHeader("-1"));
		assertEquals("0", Client.applicationVersionHeader("99999999999999999999"));
		assertEquals("999999999999999999", Client.applicationVersionHeader("999999999999999999"));
	}

	@Test
	void semverApplicationVersionKeepsFullValueButSendsZeroHeader() {
		final HTTPClient httpClient = mock(HTTPClient.class);
		final ContextEventSerializer ser = mock(ContextEventSerializer.class);
		final Client client = Client.create(ClientConfig.create()
				.setEndpoint("https://localhost/v1")
				.setAPIKey("test-api-key")
				.setApplication("website")
				.setApplicationVersion("2.4.1")
				.setEnvironment("dev")
				.setContextEventSerializer(ser), httpClient);

		final PublishEvent event = new PublishEvent();
		when(ser.serialize(event)).thenReturn(new byte[]{0});
		when(httpClient.put(any(), any(), any(), any())).thenReturn(getByteResponse(new byte[]{0}));

		client.publish(event).join();

		final org.mockito.ArgumentCaptor<Map<String, String>> headers = org.mockito.ArgumentCaptor.forClass(Map.class);
		verify(httpClient, Mockito.timeout(5000).times(1)).put(any(), any(), headers.capture(), any());
		assertEquals("0", headers.getValue().get("X-Application-Version"));
		assertEquals("2.4.1", client.getApplicationVersion());
	}

	private static Map<String, String> sdkHeaders() {
		return mapOf(
				"X-API-Key", "test-api-key",
				"X-Application", "website",
				"X-Environment", "dev",
				"X-Application-Version", "0",
				"X-Agent", "absmartly-java-sdk/" + SdkVersion.VERSION);
	}
}
