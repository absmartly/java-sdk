package com.absmartly.sdk;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.Test;

import com.absmartly.sdk.json.PublishEvent;

class DefaultContextEventHandlerTest {
	@Test
	void publish() {
		final HTTPClient httpClient = mock(HTTPClient.class);
		final ContextEventSerializer ser = mock(ContextEventSerializer.class);
		final ContextEventHandler handler = new DefaultContextEventHandler("https://api.absmartly.io/v1",
				"test-api-key", "website", "dev", httpClient, ser);

		final Map<String, Object> expectedHeaders = TestUtils.mapOf(
				"X-API-Key", "test-api-key",
				"X-Application", "website",
				"X-Environment", "dev",
				"X-Application-Version", "0",
				"X-Agent", "java-sdk");

		final PublishEvent event = new PublishEvent();
		final byte[] bytes = new byte[]{0};

		when(ser.serialize(event)).thenReturn(bytes);
		when(httpClient.put("https://api.absmartly.io/v1/context", expectedHeaders, bytes))
				.thenReturn(CompletableFuture.completedFuture(new HTTPClient.Response() {
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
						return new byte[]{0};
					}
				}));

		final CompletableFuture<Void> publishFuture = handler.publish(event);
		publishFuture.join();

		verify(ser, times(1)).serialize(event);
		verify(httpClient, times(1)).put(any(), any(), any());
		verify(httpClient, times(1)).put("https://api.absmartly.io/v1/context", expectedHeaders, bytes);
	}

	@Test
	void publishExceptionallyHTTP() {
		final HTTPClient httpClient = mock(HTTPClient.class);
		final ContextEventSerializer ser = mock(ContextEventSerializer.class);
		final ContextEventHandler handler = new DefaultContextEventHandler("https://api.absmartly.io/v1",
				"test-api-key", "website", "dev", httpClient, ser);

		final Map<String, Object> expectedHeaders = TestUtils.mapOf(
				"X-API-Key", "test-api-key",
				"X-Application", "website",
				"X-Environment", "dev",
				"X-Application-Version", "0",
				"X-Agent", "java-sdk");

		final PublishEvent event = new PublishEvent();
		final byte[] bytes = new byte[]{0};

		when(ser.serialize(event)).thenReturn(bytes);
		when(httpClient.put("https://api.absmartly.io/v1/context", expectedHeaders, bytes))
				.thenReturn(CompletableFuture.completedFuture(
						new DefaultHTTPClient.DefaultResponse(500, "Internal Server Error", null, null)));

		final CompletableFuture<Void> publishFuture = handler.publish(event);
		final CompletionException actual = assertThrows(CompletionException.class, publishFuture::join);
		assertTrue(actual.getCause() instanceof Exception);
		assertEquals("Internal Server Error", actual.getCause().getMessage());

		verify(ser, times(1)).serialize(event);
		verify(httpClient, times(1)).put("https://api.absmartly.io/v1/context", expectedHeaders, bytes);
	}

	@Test
	void publishExceptionallyConnection() {
		final HTTPClient httpClient = mock(HTTPClient.class);
		final ContextEventSerializer ser = mock(ContextEventSerializer.class);
		final ContextEventHandler handler = new DefaultContextEventHandler("https://api.absmartly.io/v1",
				"test-api-key", "website", "dev", httpClient, ser);

		final Map<String, Object> expectedHeaders = TestUtils.mapOf(
				"X-API-Key", "test-api-key",
				"X-Application", "website",
				"X-Environment", "dev",
				"X-Application-Version", "0",
				"X-Agent", "java-sdk");

		final PublishEvent event = new PublishEvent();
		final byte[] bytes = new byte[]{0};

		final Exception failure = new Exception("FAILED");
		final CompletableFuture<HTTPClient.Response> responseFuture = TestUtils.failedFuture(failure);

		when(ser.serialize(event)).thenReturn(bytes);
		when(httpClient.put("https://api.absmartly.io/v1/context", expectedHeaders, bytes)).thenReturn(responseFuture);

		final CompletableFuture<Void> publishFuture = handler.publish(event);
		final CompletionException actual = assertThrows(CompletionException.class, publishFuture::join);
		assertSame(actual.getCause(), failure);

		verify(ser, times(1)).serialize(event);
		verify(httpClient, times(1)).put("https://api.absmartly.io/v1/context", expectedHeaders, bytes);
	}
}
