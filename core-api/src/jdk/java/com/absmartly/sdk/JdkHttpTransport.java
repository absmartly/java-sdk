package com.absmartly.sdk;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import org.jspecify.annotations.Nullable;

/**
 * The default {@link Transport}, over {@code java.net.http.HttpClient} (Java 11+). Not available on Android, which has
 * no {@code java.net.http}: supply another transport there.
 *
 * <p>
 * A request that fails with an I/O error or gets a 502 or 503 is retried up to {@link Builder#maxRetries(int)} times
 * with exponential back-off, bounded by {@link Builder#retryInterval(Duration)}, as in 1.x.
 */
public final class JdkHttpTransport implements Transport {
	private static final long MIN_RETRY_INTERVAL_MS = 5;

	public static Builder builder() {
		return new Builder();
	}

	/** A transport with the default settings. */
	public static JdkHttpTransport create() {
		return builder().build();
	}

	private JdkHttpTransport(final Builder builder) {
		final HttpClient.Builder http = HttpClient.newBuilder()
				.connectTimeout(builder.connectTimeout_)
				.followRedirects(HttpClient.Redirect.NEVER);
		if (builder.httpClientVersion_ != null) {
			http.version(builder.httpClientVersion_);
		}
		if (builder.executor_ != null) {
			http.executor(builder.executor_);
		}
		httpClient_ = http.build();
		requestTimeout_ = builder.requestTimeout_;
		maxRetries_ = builder.maxRetries_;
		retryIntervalUs_ = Math.max(0,
				(2000 * (builder.retryInterval_.toMillis() - MIN_RETRY_INTERVAL_MS)) / (1L << builder.maxRetries_));
	}

	@Override
	public CompletableFuture<Response> send(final Request request) {
		return attempt(build(request), 1);
	}

	private HttpRequest build(final Request request) {
		final byte[] body = request.body();
		final HttpRequest.Builder http = HttpRequest.newBuilder(request.uri())
				.method(request.method(), (body != null) ? HttpRequest.BodyPublishers.ofByteArray(body)
						: HttpRequest.BodyPublishers.noBody());
		for (final Map.Entry<String, String> header : request.headers().entrySet()) {
			http.header(header.getKey(), header.getValue());
		}
		if (body != null) {
			http.header("Content-Type", "application/json; charset=UTF-8");
		}
		if (requestTimeout_ != null) {
			http.timeout(requestTimeout_);
		}
		return http.build();
	}

	private CompletableFuture<Response> attempt(final HttpRequest request, final int execCount) {
		return httpClient_.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
				.handle((response, error) -> {
					if (error == null) {
						if (isRetryableStatus(response.statusCode()) && (execCount <= maxRetries_)) {
							return retry(request, execCount);
						}
						return CompletableFuture.completedFuture(
								new Response(response.statusCode(), "", response.body()));
					}
					final Throwable cause = unwrap(error);
					if (cause instanceof HttpTimeoutException) {
						return CompletableFuture.<Response>failedFuture(new RequestTimeoutException(
								request.method() + " " + request.uri().getPath() + " timed out", cause));
					}
					if ((cause instanceof IOException) && (execCount <= maxRetries_)) {
						return retry(request, execCount);
					}
					return CompletableFuture.<Response>failedFuture(cause);
				})
				.thenCompose(future -> future);
	}

	private CompletableFuture<Response> retry(final HttpRequest request, final int execCount) {
		final Executor delayed = CompletableFuture.delayedExecutor(retryDelayMillis(execCount), TimeUnit.MILLISECONDS);
		return CompletableFuture.supplyAsync(() -> null, delayed)
				.thenCompose(ignored -> attempt(request, execCount + 1));
	}

	// The delay before retry number execCount: 5 ms plus a share of retryInterval that doubles on every retry.
	long retryDelayMillis(final int execCount) {
		return MIN_RETRY_INTERVAL_MS + (((1L << (execCount - 1)) * retryIntervalUs_) / 1000);
	}

	static boolean isRetryableStatus(final int statusCode) {
		return (statusCode == 502) || (statusCode == 503);
	}

	private static Throwable unwrap(Throwable error) {
		while ((error instanceof CompletionException) && (error.getCause() != null)) {
			error = error.getCause();
		}
		return error;
	}

	public static final class Builder {
		Builder() {}

		/** The TCP connect timeout (default 3 s). */
		public Builder connectTimeout(final Duration connectTimeout) {
			connectTimeout_ = positive(connectTimeout, "connectTimeout");
			return this;
		}

		/**
		 * A timeout for each request attempt, from send to the end of the response (default: none, as in 1.x). An
		 * expired attempt fails the request with {@link RequestTimeoutException} and is not retried.
		 */
		public Builder requestTimeout(final Duration requestTimeout) {
			requestTimeout_ = positive(requestTimeout, "requestTimeout");
			return this;
		}

		/** How many times a failed request is retried (default 5). */
		public Builder maxRetries(final int maxRetries) {
			if ((maxRetries < 0) || (maxRetries > 30)) {
				throw new IllegalArgumentException("maxRetries must be in [0, 30]");
			}
			maxRetries_ = maxRetries;
			return this;
		}

		/** The upper bound of the back-off before the last retry (default 333 ms). */
		public Builder retryInterval(final Duration retryInterval) {
			if (retryInterval.isNegative()) {
				throw new IllegalArgumentException("retryInterval must not be negative");
			}
			retryInterval_ = retryInterval;
			return this;
		}

		/** Forces HTTP/1.1 or HTTP/2; by default HTTP/2 is negotiated when the server supports it. */
		public Builder httpVersion(final HttpClient.Version httpVersion) {
			httpClientVersion_ = httpVersion;
			return this;
		}

		/** The executor that runs the HTTP client's callbacks (default: the HTTP client's own). */
		public Builder executor(final Executor executor) {
			executor_ = executor;
			return this;
		}

		public JdkHttpTransport build() {
			return new JdkHttpTransport(this);
		}

		private static Duration positive(final Duration duration, final String name) {
			if (duration.isNegative() || duration.isZero()) {
				throw new IllegalArgumentException(name + " must be positive");
			}
			return duration;
		}

		private Duration connectTimeout_ = Duration.ofMillis(3000);
		private @Nullable Duration requestTimeout_;
		private int maxRetries_ = 5;
		private Duration retryInterval_ = Duration.ofMillis(333);
		private HttpClient.@Nullable Version httpClientVersion_;
		private @Nullable Executor executor_;
	}

	private final HttpClient httpClient_;
	private final @Nullable Duration requestTimeout_;
	private final int maxRetries_;
	private final long retryIntervalUs_;
}
