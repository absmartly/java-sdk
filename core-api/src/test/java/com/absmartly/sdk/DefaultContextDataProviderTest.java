package com.absmartly.sdk;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;

import com.absmartly.sdk.json.ContextData;

class DefaultContextDataProviderTest {
	@Test
	void getContextData() throws ExecutionException, InterruptedException {
		final HTTPClient httpClient = mock(HTTPClient.class);
		final ContextDataDeserializer deser = mock(ContextDataDeserializer.class);
		final ContextDataProvider provider = new DefaultContextDataProvider("http://api.absmartly.io/v1", httpClient,
				deser);
		final byte[] bytes = "{}".getBytes(StandardCharsets.UTF_8);

		when(httpClient.get("http://api.absmartly.io/v1/context", null))
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
						return bytes;
					}
				}));

		final ContextData expected = new ContextData();
		when(deser.deserialize(bytes, 0, bytes.length)).thenReturn(expected);

		final Future<ContextData> dataFuture = provider.getContextData();
		final ContextData actual = dataFuture.get();

		assertEquals(expected, actual);
		assertSame(expected, actual);
	}

	@Test
	void getContextDataExceptionallyHTTP() {
		final HTTPClient httpClient = mock(HTTPClient.class);
		final ContextDataDeserializer deser = mock(ContextDataDeserializer.class);
		final ContextDataProvider provider = new DefaultContextDataProvider("http://api.absmartly.io/v1", httpClient,
				deser);

		when(httpClient.get("http://api.absmartly.io/v1/context", null))
				.thenReturn(CompletableFuture.completedFuture(
						new DefaultHTTPClient.DefaultResponse(500, "Internal Server Error", null, null)));

		final CompletableFuture<ContextData> dataFuture = provider.getContextData();
		final CompletionException actual = assertThrows(CompletionException.class, dataFuture::join);
		assertTrue(actual.getCause() instanceof Exception);
		assertEquals("Internal Server Error", actual.getCause().getMessage());

		verify(httpClient, times(1)).get("http://api.absmartly.io/v1/context", null);
		verify(deser, times(0)).deserialize(any(), anyInt(), anyInt());
	}

	@Test
	void getContextDataExceptionallyConnection() {
		final HTTPClient httpClient = mock(HTTPClient.class);
		final ContextDataDeserializer deser = mock(ContextDataDeserializer.class);
		final ContextDataProvider provider = new DefaultContextDataProvider("http://api.absmartly.io/v1", httpClient,
				deser);

		final Exception failure = new Exception("FAILED");
		final CompletableFuture<HTTPClient.Response> responseFuture = TestUtils.failedFuture(failure);
		when(httpClient.get("http://api.absmartly.io/v1/context", null)).thenReturn(responseFuture);

		final CompletableFuture<ContextData> dataFuture = provider.getContextData();
		final CompletionException actual = assertThrows(CompletionException.class, dataFuture::join);
		assertSame(actual.getCause(), failure);

		verify(httpClient, times(1)).get("http://api.absmartly.io/v1/context", null);
		verify(deser, times(0)).deserialize(any(), anyInt(), anyInt());
	}
}
