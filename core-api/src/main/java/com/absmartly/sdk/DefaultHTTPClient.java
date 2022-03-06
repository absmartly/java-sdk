package com.absmartly.sdk;

import java.util.Map;
import java8.util.concurrent.CompletableFuture;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.net.ssl.SSLContext;

import org.apache.hc.client5.http.async.methods.SimpleHttpRequest;
import org.apache.hc.client5.http.async.methods.SimpleHttpResponse;
import org.apache.hc.client5.http.async.methods.SimpleRequestBuilder;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient;
import org.apache.hc.client5.http.impl.async.HttpAsyncClientBuilder;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManager;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.DefaultClientTlsStrategy;
import org.apache.hc.core5.concurrent.FutureCallback;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.ssl.SSLContexts;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;

public class DefaultHTTPClient implements HTTPClient {
	public static DefaultHTTPClient create(final DefaultHTTPClientConfig config) {
		return new DefaultHTTPClient(config);
	}

	private DefaultHTTPClient(final DefaultHTTPClientConfig config) throws SecurityException {
		final SSLContext sslContext;

		try {
			sslContext = config.getSecurityProvider() != null
					? SSLContexts.custom().setProvider(config.getSecurityProvider()).build()
					: SSLContexts.createDefault();
		} catch (Throwable e) {
			throw new SecurityException("Error initializing SSL context", e);
		}

		final PoolingAsyncClientConnectionManager connectionManager = PoolingAsyncClientConnectionManagerBuilder
				.create()
				.setMaxConnTotal(200)
				.setMaxConnPerRoute(20)
				.setValidateAfterInactivity(TimeValue.ofMilliseconds(5000))
				.setTlsStrategy(new DefaultClientTlsStrategy(sslContext))
				.build();

		final CloseableHttpAsyncClient httpClient = HttpAsyncClientBuilder.create()
				.disableCookieManagement()
				.evictExpiredConnections()
				.evictIdleConnections(TimeValue.ofMilliseconds(config.getConnectionKeepAlive()))
				.setConnectionManager(connectionManager)
				.setDefaultRequestConfig(RequestConfig.custom()
						.setConnectionKeepAlive(TimeValue.ofMilliseconds(config.getConnectionKeepAlive()))
						.setConnectTimeout(Timeout.ofMilliseconds(config.getConnectTimeout()))
						.setConnectionRequestTimeout(Timeout.ofMilliseconds(config.getConnectionRequestTimeout()))
						.build())
				.setRetryStrategy(new DefaultHTTPClientRetryStrategy(config.getMaxRetries(), config.getRetryInterval()))
				.build();

		httpClient.start();

		this.httpClient_ = httpClient;
	}

	static class DefaultResponse implements HTTPClient.Response {
		public DefaultResponse(final int statusCode, final String statusMessage, final String contentType,
				final byte[] content) {
			this.statusCode = statusCode;
			this.statusMessage = statusMessage;
			this.contentType = contentType;
			this.content = content;
		}

		public int getStatusCode() {
			return statusCode;
		}

		public String getStatusMessage() {
			return statusMessage;
		}

		public String getContentType() {
			return contentType;
		}

		public byte[] getContent() {
			return content;
		}

		private final int statusCode;
		private final String statusMessage;
		private final String contentType;
		private final byte[] content;
	}

	@Override
	public CompletableFuture<Response> get(@Nonnull final String url, @Nullable final Map<String, String> query,
			@Nullable final Map<String, String> headers) {
		return request(buildRequest(SimpleRequestBuilder.get(url), query, headers, null));
	}

	@Override
	public CompletableFuture<Response> put(@Nonnull final String url, @Nullable final Map<String, String> query,
			@Nullable final Map<String, String> headers,
			@Nonnull byte[] body) {
		return request(buildRequest(SimpleRequestBuilder.put(url), query, headers, body));
	}

	@Override
	public CompletableFuture<Response> post(@Nonnull final String url, @Nullable final Map<String, String> query,
			@Nullable final Map<String, String> headers, @Nonnull final byte[] body) {
		return request(buildRequest(SimpleRequestBuilder.post(url), query, headers, body));
	}

	private CompletableFuture<Response> request(final SimpleHttpRequest request) {
		final CompletableFuture<Response> future = new CompletableFuture<Response>();

		try {
			httpClient_.execute(request, new FutureCallback<SimpleHttpResponse>() {
				@Override
				public void completed(SimpleHttpResponse result) {
					final int statusCode = result.getCode();
					final String responseMessage = result.getReasonPhrase();
					final ContentType contentType = result.getContentType();
					final byte[] body = result.getBodyBytes();

					future.complete(new DefaultResponse(statusCode, responseMessage,
							(contentType != null) ? contentType.getMimeType() : null, body));
				}

				@Override
				public void failed(Exception e) {
					future.completeExceptionally(e);
				}

				@Override
				public void cancelled() {
					future.cancel(false);
				}
			});
		} catch (Throwable e) {
			future.completeExceptionally(e);
		}

		return future;
	}

	private SimpleHttpRequest buildRequest(final SimpleRequestBuilder request, final Map<String, String> query,
			final Map<String, String> headers, final byte[] body) {
		if (query != null) {
			for (Map.Entry<String, String> entry : query.entrySet()) {
				String name = entry.getKey();
				String value = entry.getValue();
				request.addParameter(name, value);
			}
		}

		if (headers != null) {
			for (Map.Entry<String, String> entry : headers.entrySet()) {
				String key = entry.getKey();
				String value = entry.getValue();
				request.setHeader(key, value);
			}
		}

		if (body != null) {
			request.setBody(body, ContentType.APPLICATION_JSON);
		}

		return request.build();
	}

	@Override
	public void close() {
		httpClient_.close(CloseMode.GRACEFUL);
	}

	private final CloseableHttpAsyncClient httpClient_;
}
