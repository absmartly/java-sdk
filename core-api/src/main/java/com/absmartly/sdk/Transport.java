package com.absmartly.sdk;

import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.jspecify.annotations.Nullable;

/**
 * Sends the SDK's HTTP requests. The default, {@link JdkHttpTransport}, uses {@code java.net.http.HttpClient}; supply
 * your own with {@link ABsmartlyClient.Builder#transport(Transport)} to use another HTTP stack (Android, which has no
 * {@code java.net.http}, always does), add a proxy or tracing, or serve requests in tests.
 *
 * <p>
 * Return a future that completes with the response for every status code, including non-2xx ones, and completes
 * exceptionally only when no response was received. Retrying is up to the transport.
 */
@FunctionalInterface
public interface Transport extends AutoCloseable {
	CompletableFuture<Response> send(Request request);

	/** Releases the transport's resources. Called by {@link ABsmartlyClient#close()}. The default does nothing. */
	@Override
	default void close() {}

	/** An immutable HTTP request. */
	final class Request {
		public Request(final String method, final URI uri, final Map<String, String> headers,
				final byte @Nullable [] body) {
			method_ = method;
			uri_ = uri;
			headers_ = Collections.unmodifiableMap(new LinkedHashMap<String, String>(headers));
			body_ = body != null ? body.clone() : null;
		}

		/** {@code GET} or {@code PUT}. */
		public String method() {
			return method_;
		}

		/** The full URI, including the query string. */
		public URI uri() {
			return uri_;
		}

		/** The request headers, unmodifiable. */
		public Map<String, String> headers() {
			return headers_;
		}

		/** The JSON body ({@code Content-Type: application/json}), or null for a GET. */
		public byte @Nullable [] body() {
			return body_ != null ? body_.clone() : null;
		}

		@Override
		public String toString() {
			return method_ + " " + uri_;
		}

		private final String method_;
		private final URI uri_;
		private final Map<String, String> headers_;
		private final byte @Nullable [] body_;
	}

	/** An immutable HTTP response. */
	final class Response {
		public Response(final int statusCode, final String statusMessage, final byte @Nullable [] body) {
			statusCode_ = statusCode;
			statusMessage_ = statusMessage;
			body_ = body != null ? body.clone() : null;
		}

		public int statusCode() {
			return statusCode_;
		}

		/** The reason phrase, or empty if the HTTP stack does not provide one (HTTP/2 has none). */
		public String statusMessage() {
			return statusMessage_;
		}

		public byte @Nullable [] body() {
			return body_ != null ? body_.clone() : null;
		}

		@Override
		public String toString() {
			return "Response{" + statusCode_ + " " + statusMessage_ + '}';
		}

		private final int statusCode_;
		private final String statusMessage_;
		private final byte @Nullable [] body_;
	}
}
