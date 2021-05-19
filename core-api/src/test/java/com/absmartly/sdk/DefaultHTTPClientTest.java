package com.absmartly.sdk;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

import org.apache.hc.client5.http.async.methods.SimpleHttpRequest;
import org.apache.hc.client5.http.async.methods.SimpleHttpResponse;
import org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient;
import org.apache.hc.client5.http.impl.async.HttpAsyncClientBuilder;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManager;
import org.apache.hc.core5.concurrent.FutureCallback;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.util.TimeValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

class DefaultHTTPClientTest {
	CloseableHttpAsyncClient asyncHTTPClient;
	HttpAsyncClientBuilder asyncHTTPClientBuilder;

	@BeforeEach
	void setUp() {
		asyncHTTPClient = mock(CloseableHttpAsyncClient.class);
		asyncHTTPClientBuilder = mock(HttpAsyncClientBuilder.class, withSettings().defaultAnswer(invocation -> {
			if (invocation.getMethod().getName().equals("build")) {
				return asyncHTTPClient;
			} else {
				return invocation.getMock();
			}
		}));
	}

	@Test
	void constructorAndClose() {
		try (final MockedStatic<HttpAsyncClientBuilder> builderStatic = Mockito
				.mockStatic(HttpAsyncClientBuilder.class)) {
			//noinspection ResultOfMethodCallIgnored
			builderStatic.when(HttpAsyncClientBuilder::create).thenReturn(asyncHTTPClientBuilder);

			final DefaultHTTPClient httpClient = new DefaultHTTPClient(DefaultHTTPClientConfig.create());

			verify(asyncHTTPClientBuilder, times(1)).disableCookieManagement();
			verify(asyncHTTPClientBuilder, times(1)).evictExpiredConnections();
			verify(asyncHTTPClientBuilder, times(1)).evictIdleConnections(TimeValue.ofSeconds(30));
			verify(asyncHTTPClientBuilder, times(1))
					.setConnectionManager(any(PoolingAsyncClientConnectionManager.class));
			verify(asyncHTTPClientBuilder, times(1)).setRetryStrategy(any(DefaultHTTPClientRetryStrategy.class));
			verify(asyncHTTPClientBuilder, times(1)).build();

			verify(asyncHTTPClient, times(1)).start();

			httpClient.close();

			verify(asyncHTTPClient, times(1)).close(CloseMode.GRACEFUL);
		}
	}

	void assertRequestEquals(String method, byte[] content, String contentType, Map<String, Object> headers,
			SimpleHttpRequest request) {
		assertEquals(method, request.getMethod());
		assertEquals(contentType, (request.getContentType() != null) ? request.getContentType().getMimeType() : null);
		assertArrayEquals(content, request.getBodyBytes());

		assertEquals(headers != null ? headers.size() : 0, request.getHeaders().length);

		for (final Header header : request.getHeaders()) {
			assertEquals(header.getValue(), Objects.toString(header.getValue()));
		}
	}

	@Test
	void get() throws ExecutionException, InterruptedException {
		try (final MockedStatic<HttpAsyncClientBuilder> builderStatic = Mockito
				.mockStatic(HttpAsyncClientBuilder.class)) {
			//noinspection ResultOfMethodCallIgnored
			builderStatic.when(HttpAsyncClientBuilder::create).thenReturn(asyncHTTPClientBuilder);

			final DefaultHTTPClient httpClient = new DefaultHTTPClient(DefaultHTTPClientConfig.create());

			when(asyncHTTPClient.execute(any(), any())).thenAnswer(invocation -> {
				assertRequestEquals("GET", null, null, null, invocation.getArgument(0));

				final FutureCallback<SimpleHttpResponse> callback = invocation.getArgument(1);
				callback.completed(SimpleHttpResponse.create(200, new byte[]{123, 0}, ContentType.APPLICATION_JSON));

				return null;
			});

			final CompletableFuture<HTTPClient.Response> responseFuture = httpClient
					.get("https://api.absmartly.com/v1/context", null);
			final HTTPClient.Response response = responseFuture.get();

			assertEquals(200, response.getStatusCode());
			assertEquals("application/json", response.getContentType());
			assertArrayEquals(new byte[]{123, 0}, response.getContent());

			verify(asyncHTTPClient, times(1)).execute(any(), any());
		}
	}

	@Test
	void getExceptionallyConnection() throws ExecutionException, InterruptedException {
		try (final MockedStatic<HttpAsyncClientBuilder> builderStatic = Mockito
				.mockStatic(HttpAsyncClientBuilder.class)) {
			//noinspection ResultOfMethodCallIgnored
			builderStatic.when(HttpAsyncClientBuilder::create).thenReturn(asyncHTTPClientBuilder);

			final DefaultHTTPClient httpClient = new DefaultHTTPClient(DefaultHTTPClientConfig.create());

			final Exception failure = new Exception("FAILED");
			when(asyncHTTPClient.execute(any(), any())).thenAnswer(invocation -> {
				assertRequestEquals("GET", null, null, null, invocation.getArgument(0));

				final FutureCallback<SimpleHttpResponse> callback = invocation.getArgument(1);
				callback.failed(failure);

				return null;
			});

			final CompletableFuture<HTTPClient.Response> responseFuture = httpClient
					.get("https://api.absmartly.com/v1/context", null);
			assertSame(failure, assertThrows(CompletionException.class, responseFuture::join).getCause());

			verify(asyncHTTPClient, times(1)).execute(any(), any());
		}
	}

	@Test
	void put() throws ExecutionException, InterruptedException {
		try (final MockedStatic<HttpAsyncClientBuilder> builderStatic = Mockito
				.mockStatic(HttpAsyncClientBuilder.class)) {
			//noinspection ResultOfMethodCallIgnored
			builderStatic.when(HttpAsyncClientBuilder::create).thenReturn(asyncHTTPClientBuilder);

			final DefaultHTTPClient httpClient = new DefaultHTTPClient(DefaultHTTPClientConfig.create());

			final Map<String, Object> requestHeaders = TestUtils.mapOf(
					"X-Application", "website",
					"X-Environment", "dev");

			final byte[] requestBody = new byte[]{10, 13};

			when(asyncHTTPClient.execute(any(), any())).thenAnswer(invocation -> {
				assertRequestEquals("PUT", requestBody, "application/json", requestHeaders, invocation.getArgument(0));

				final FutureCallback<SimpleHttpResponse> callback = invocation.getArgument(1);
				callback.completed(SimpleHttpResponse.create(200, new byte[]{123, 0}, ContentType.APPLICATION_JSON));

				return null;
			});

			final CompletableFuture<HTTPClient.Response> responseFuture = httpClient
					.put("https://api.absmartly.com/v1/context", requestHeaders, requestBody);
			final HTTPClient.Response response = responseFuture.get();

			assertEquals(200, response.getStatusCode());
			assertEquals("application/json", response.getContentType());
			assertArrayEquals(new byte[]{123, 0}, response.getContent());

			verify(asyncHTTPClient, times(1)).execute(any(), any());
		}
	}

	@Test
	void post() throws ExecutionException, InterruptedException {
		try (final MockedStatic<HttpAsyncClientBuilder> builderStatic = Mockito
				.mockStatic(HttpAsyncClientBuilder.class)) {
			//noinspection ResultOfMethodCallIgnored
			builderStatic.when(HttpAsyncClientBuilder::create).thenReturn(asyncHTTPClientBuilder);

			final DefaultHTTPClient httpClient = new DefaultHTTPClient(DefaultHTTPClientConfig.create());

			final Map<String, Object> requestHeaders = TestUtils.mapOf(
					"X-Application", "website",
					"X-Environment", "dev");

			final byte[] requestBody = new byte[]{10, 13};

			when(asyncHTTPClient.execute(any(), any())).thenAnswer(invocation -> {
				assertRequestEquals("POST", requestBody, "application/json", requestHeaders, invocation.getArgument(0));

				final FutureCallback<SimpleHttpResponse> callback = invocation.getArgument(1);
				callback.completed(SimpleHttpResponse.create(200, new byte[]{123, 0}, ContentType.APPLICATION_JSON));

				return null;
			});

			final CompletableFuture<HTTPClient.Response> responseFuture = httpClient
					.post("https://api.absmartly.com/v1/context", requestHeaders, requestBody);
			final HTTPClient.Response response = responseFuture.get();

			assertEquals(200, response.getStatusCode());
			assertEquals("application/json", response.getContentType());
			assertArrayEquals(new byte[]{123, 0}, response.getContent());

			verify(asyncHTTPClient, times(1)).execute(any(), any());
		}
	}
}
