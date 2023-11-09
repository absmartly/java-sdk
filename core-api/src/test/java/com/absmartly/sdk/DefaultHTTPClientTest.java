package com.absmartly.sdk;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java8.util.concurrent.CompletableFuture;
import java8.util.concurrent.CompletionException;

import org.apache.hc.client5.http.async.methods.SimpleHttpRequest;
import org.apache.hc.client5.http.async.methods.SimpleHttpResponse;
import org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient;
import org.apache.hc.client5.http.impl.async.HttpAsyncClientBuilder;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManager;
import org.apache.hc.core5.concurrent.FutureCallback;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http2.HttpVersionPolicy;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.util.TimeValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

class DefaultHTTPClientTest extends TestUtils {
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

			final DefaultHTTPClient httpClient = DefaultHTTPClient.create(DefaultHTTPClientConfig.create());

			verify(asyncHTTPClientBuilder, Mockito.timeout(5000).times(1)).disableCookieManagement();
			verify(asyncHTTPClientBuilder, Mockito.timeout(5000).times(1)).evictExpiredConnections();
			verify(asyncHTTPClientBuilder, Mockito.timeout(5000).times(1))
					.evictIdleConnections(TimeValue.ofSeconds(30));
			verify(asyncHTTPClientBuilder, Mockito.timeout(5000).times(1))
					.setConnectionManager(any(PoolingAsyncClientConnectionManager.class));
			verify(asyncHTTPClientBuilder, Mockito.timeout(5000).times(1))
					.setVersionPolicy(HttpVersionPolicy.NEGOTIATE);
			verify(asyncHTTPClientBuilder, Mockito.timeout(5000).times(1))
					.setRetryStrategy(any(DefaultHTTPClientRetryStrategy.class));
			verify(asyncHTTPClientBuilder, Mockito.timeout(5000).times(1)).build();

			verify(asyncHTTPClient, Mockito.timeout(5000).times(1)).start();

			httpClient.close();

			verify(asyncHTTPClient, Mockito.timeout(5000).times(1)).close(CloseMode.GRACEFUL);
		}
	}

	@Test
	void constructorCustomConfig() {
		try (final MockedStatic<HttpAsyncClientBuilder> builderStatic = Mockito
				.mockStatic(HttpAsyncClientBuilder.class)) {
			//noinspection ResultOfMethodCallIgnored
			builderStatic.when(HttpAsyncClientBuilder::create).thenReturn(asyncHTTPClientBuilder);

			final DefaultHTTPClient httpClient = DefaultHTTPClient.create(DefaultHTTPClientConfig.create()
					.setHTTPVersionPolicy(HTTPVersionPolicy.FORCE_HTTP_2));

			verify(asyncHTTPClientBuilder, Mockito.timeout(5000).times(1)).disableCookieManagement();
			verify(asyncHTTPClientBuilder, Mockito.timeout(5000).times(1)).evictExpiredConnections();
			verify(asyncHTTPClientBuilder, Mockito.timeout(5000).times(1))
					.evictIdleConnections(TimeValue.ofSeconds(30));
			verify(asyncHTTPClientBuilder, Mockito.timeout(5000).times(1))
					.setConnectionManager(any(PoolingAsyncClientConnectionManager.class));
			verify(asyncHTTPClientBuilder, Mockito.timeout(5000).times(1))
					.setRetryStrategy(any(DefaultHTTPClientRetryStrategy.class));
			verify(asyncHTTPClientBuilder, Mockito.timeout(5000).times(1))
					.setVersionPolicy(HttpVersionPolicy.FORCE_HTTP_2);
			verify(asyncHTTPClientBuilder, Mockito.timeout(5000).times(1)).build();

			verify(asyncHTTPClient, Mockito.timeout(5000).times(1)).start();

			httpClient.close();

			verify(asyncHTTPClient, Mockito.timeout(5000).times(1)).close(CloseMode.GRACEFUL);
		}
	}

	void assertRequestEquals(String method, byte[] content, String contentType, Map<String, String> query,
			Map<String, String> headers,
			SimpleHttpRequest request) {
		assertEquals(method, request.getMethod());
		assertEquals(contentType, (request.getContentType() != null) ? request.getContentType().getMimeType() : null);
		assertArrayEquals(content, request.getBodyBytes());

		if (headers != null) {
			assertEquals(headers.size(), request.getHeaders().length);

			headers.forEach(
					(key, value) -> assertDoesNotThrow(() -> assertEquals(value, request.getHeader(key).getValue())));
		} else {
			assertEquals(0, request.getHeaders().length);
		}

		final HashMap<String, String> actualQueryMap = new HashMap<>();

		assertDoesNotThrow(() -> {
			final String queryString = request.getUri().getQuery();
			if ((queryString != null) && !queryString.isEmpty()) {
				final String[] parameters = queryString.split("&");
				for (final String parameter : parameters) {
					final String[] keyValue = parameter.split("=");
					switch (keyValue.length) {
					case 1:
						actualQueryMap.put(keyValue[0], "");
						break;
					case 2:
						actualQueryMap.put(keyValue[0], keyValue[1]);
						break;
					case 0:
						break;
					default:
						actualQueryMap.put(keyValue[0], parameter.substring(keyValue[0].length() + 1));
					}
				}
			}
		});

		if (query != null) {
			assertEquals(query.size(), actualQueryMap.size());

			query.forEach((key, value) -> assertEquals(value, actualQueryMap.get(key)));
		} else {
			assertEquals(0, actualQueryMap.size());
		}
	}

	@Test
	void get() throws ExecutionException, InterruptedException {
		try (final MockedStatic<HttpAsyncClientBuilder> builderStatic = Mockito
				.mockStatic(HttpAsyncClientBuilder.class)) {
			//noinspection ResultOfMethodCallIgnored
			builderStatic.when(HttpAsyncClientBuilder::create).thenReturn(asyncHTTPClientBuilder);

			final DefaultHTTPClient httpClient = DefaultHTTPClient.create(DefaultHTTPClientConfig.create());

			final Map<String, String> requestQuery = mapOf("application", "website", "environment", "dev");
			final Map<String, String> requestHeaders = mapOf(
					"X-Application", "website",
					"X-Environment", "dev");

			when(asyncHTTPClient.execute(any(), any())).thenAnswer(invocation -> {
				assertRequestEquals("GET", null, null, requestQuery, requestHeaders, invocation.getArgument(0));

				final FutureCallback<SimpleHttpResponse> callback = invocation.getArgument(1);
				callback.completed(SimpleHttpResponse.create(200, new byte[]{123, 0}, ContentType.APPLICATION_JSON));

				return null;
			});

			final CompletableFuture<HTTPClient.Response> responseFuture = httpClient
					.get("https://api.absmartly.com/v1/context", requestQuery, requestHeaders);
			final HTTPClient.Response response = responseFuture.get();

			assertEquals(200, response.getStatusCode());
			assertEquals("application/json", response.getContentType());
			assertArrayEquals(new byte[]{123, 0}, response.getContent());

			verify(asyncHTTPClient, Mockito.timeout(5000).times(1)).execute(any(), any());
		}
	}

	@Test
	void getExceptionallyConnection() {
		try (final MockedStatic<HttpAsyncClientBuilder> builderStatic = Mockito
				.mockStatic(HttpAsyncClientBuilder.class)) {
			//noinspection ResultOfMethodCallIgnored
			builderStatic.when(HttpAsyncClientBuilder::create).thenReturn(asyncHTTPClientBuilder);

			final DefaultHTTPClient httpClient = DefaultHTTPClient.create(DefaultHTTPClientConfig.create());

			final Exception failure = new Exception("FAILED");
			when(asyncHTTPClient.execute(any(), any())).thenAnswer(invocation -> {
				assertRequestEquals("GET", null, null, null, null, invocation.getArgument(0));

				final FutureCallback<SimpleHttpResponse> callback = invocation.getArgument(1);
				callback.failed(failure);

				return null;
			});

			final CompletableFuture<HTTPClient.Response> responseFuture = httpClient
					.get("https://api.absmartly.com/v1/context", null, null);
			assertSame(failure, assertThrows(CompletionException.class, responseFuture::join).getCause());

			verify(asyncHTTPClient, Mockito.timeout(5000).times(1)).execute(any(), any());
		}
	}

	@Test
	void put() throws ExecutionException, InterruptedException {
		try (final MockedStatic<HttpAsyncClientBuilder> builderStatic = Mockito
				.mockStatic(HttpAsyncClientBuilder.class)) {
			//noinspection ResultOfMethodCallIgnored
			builderStatic.when(HttpAsyncClientBuilder::create).thenReturn(asyncHTTPClientBuilder);

			final DefaultHTTPClient httpClient = DefaultHTTPClient.create(DefaultHTTPClientConfig.create());

			final Map<String, String> requestQuery = mapOf("application", "website", "environment", "dev");
			final Map<String, String> requestHeaders = mapOf(
					"X-Application", "website",
					"X-Environment", "dev");

			final byte[] requestBody = new byte[]{10, 13};

			when(asyncHTTPClient.execute(any(), any())).thenAnswer(invocation -> {
				assertRequestEquals("PUT", requestBody, "application/json", requestQuery, requestHeaders,
						invocation.getArgument(0));

				final FutureCallback<SimpleHttpResponse> callback = invocation.getArgument(1);
				callback.completed(SimpleHttpResponse.create(200, new byte[]{123, 0}, ContentType.APPLICATION_JSON));

				return null;
			});

			final CompletableFuture<HTTPClient.Response> responseFuture = httpClient
					.put("https://api.absmartly.com/v1/context", requestQuery, requestHeaders, requestBody);
			final HTTPClient.Response response = responseFuture.get();

			assertEquals(200, response.getStatusCode());
			assertEquals("application/json", response.getContentType());
			assertArrayEquals(new byte[]{123, 0}, response.getContent());

			verify(asyncHTTPClient, Mockito.timeout(5000).times(1)).execute(any(), any());
		}
	}

	@Test
	void post() throws ExecutionException, InterruptedException {
		try (final MockedStatic<HttpAsyncClientBuilder> builderStatic = Mockito
				.mockStatic(HttpAsyncClientBuilder.class)) {
			//noinspection ResultOfMethodCallIgnored
			builderStatic.when(HttpAsyncClientBuilder::create).thenReturn(asyncHTTPClientBuilder);

			final DefaultHTTPClient httpClient = DefaultHTTPClient.create(DefaultHTTPClientConfig.create());

			final Map<String, String> requestQuery = mapOf("application", "website", "environment", "dev");
			final Map<String, String> requestHeaders = mapOf(
					"X-Application", "website",
					"X-Environment", "dev");

			final byte[] requestBody = new byte[]{10, 13};

			when(asyncHTTPClient.execute(any(), any())).thenAnswer(invocation -> {
				assertRequestEquals("POST", requestBody, "application/json", requestQuery, requestHeaders,
						invocation.getArgument(0));

				final FutureCallback<SimpleHttpResponse> callback = invocation.getArgument(1);
				callback.completed(SimpleHttpResponse.create(200, new byte[]{123, 0}, ContentType.APPLICATION_JSON));

				return null;
			});

			final CompletableFuture<HTTPClient.Response> responseFuture = httpClient
					.post("https://api.absmartly.com/v1/context", requestQuery, requestHeaders, requestBody);
			final HTTPClient.Response response = responseFuture.get();

			assertEquals(200, response.getStatusCode());
			assertEquals("application/json", response.getContentType());
			assertArrayEquals(new byte[]{123, 0}, response.getContent());

			verify(asyncHTTPClient, Mockito.timeout(5000).times(1)).execute(any(), any());
		}
	}
}
