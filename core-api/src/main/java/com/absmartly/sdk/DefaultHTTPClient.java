package com.absmartly.sdk;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.apache.hc.client5.http.async.methods.SimpleHttpRequest;
import org.apache.hc.client5.http.async.methods.SimpleHttpResponse;
import org.apache.hc.client5.http.async.methods.SimpleRequestBuilder;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient;
import org.apache.hc.client5.http.impl.async.HttpAsyncClientBuilder;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManager;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManagerBuilder;
import org.apache.hc.core5.concurrent.FutureCallback;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;

public class DefaultHTTPClient implements HTTPClient {
	public static DefaultHTTPClient create(DefaultHTTPClientConfig config) {
		return new DefaultHTTPClient(config);
	}

	private DefaultHTTPClient(DefaultHTTPClientConfig config) {
		final PoolingAsyncClientConnectionManager connectionManager = PoolingAsyncClientConnectionManagerBuilder
				.create()
				.setMaxConnTotal(200)
				.setMaxConnPerRoute(20)
				.setValidateAfterInactivity(TimeValue.ofMilliseconds(5_000))
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
		public DefaultResponse(int statusCode, String statusMessage, String contentType, byte[] content) {
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
	public CompletableFuture<Response> get(@Nonnull String url, @Nullable Map<String, Object> headers) {
		final SimpleHttpRequest get = SimpleRequestBuilder.get(url).build();

		return request(get, headers);
	}

	@Override
	public CompletableFuture<Response> put(@Nonnull String url, @Nullable Map<String, Object> headers,
			@Nonnull byte[] body) {
		final SimpleHttpRequest put = SimpleRequestBuilder.put(url).build();

		put.setBody(body, ContentType.APPLICATION_JSON);
		return request(put, headers);
	}

	@Override
	public CompletableFuture<Response> post(@Nonnull String url, @Nullable Map<String, Object> headers,
			@Nonnull byte[] body) {
		final SimpleHttpRequest post = SimpleRequestBuilder.post(url).build();

		post.setBody(body, ContentType.APPLICATION_JSON);
		return request(post, headers);
	}

	private CompletableFuture<Response> request(SimpleHttpRequest request,
			@Nullable Map<String, Object> additionalHeaders) {
		final CompletableFuture<Response> future = new CompletableFuture<>();

		if (additionalHeaders != null) {
			additionalHeaders.forEach(request::setHeader);
		}

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

		return future;
	}

	@Override
	public void close() {
		httpClient_.close(CloseMode.GRACEFUL);
	}

	private final CloseableHttpAsyncClient httpClient_;
}
