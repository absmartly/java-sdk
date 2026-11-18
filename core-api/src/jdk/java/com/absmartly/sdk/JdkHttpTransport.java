package com.absmartly.sdk;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
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

import com.absmartly.sdk.internal.AsyncExecutor;

/**
 * The default {@link Transport}, over {@code java.net.http.HttpClient} (Java 11+). Not available on Android, which has
 * no {@code java.net.http}: supply another transport there.
 *
 * <p>
 * Retries follow the 1.x Apache transport: a request that gets a 502 or 503, fails with an I/O error after connecting
 * (for example a dropped connection) or times out while connecting is retried up to {@link Builder#maxRetries(int)}
 * times with exponential back-off, bounded by {@link Builder#retryInterval(Duration)}. A refused connection, any
 * other status and an expired {@link Builder#requestTimeout(Duration)} are not retried. Redirects are followed, except
 * from https to http. The JDK client itself also retries an idempotent GET once when its connection is reset, so a
 * GET can be sent up to twice per attempt.
 *
 * <p>
 * Plain {@code http://} endpoints use HTTP/1.1 (no h2c upgrade), as in 1.x; https negotiates HTTP/2 unless
 * {@link Builder#httpVersion(HttpClient.Version)} forces a version.
 */
public final class JdkHttpTransport implements Transport {
	private static final long MIN_RETRY_INTERVAL_MS = 5;
	// Any longer timeout overflows the millisecond deadline; it is far beyond any useful value anyway.
	private static final Duration MAX_REQUEST_TIMEOUT = Duration.ofDays(365);

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
				.followRedirects(HttpClient.Redirect.NORMAL);
		if (builder.httpClientVersion_ != null) {
			http.version(builder.httpClientVersion_);
		}
		callbackExecutor_ = (builder.executor_ != null) ? builder.executor_ : AsyncExecutor.get();
		if (builder.executor_ != null) {
			http.executor(builder.executor_);
		}
		httpClient_ = http.build();
		httpVersion_ = builder.httpClientVersion_;
		requestTimeout_ = builder.requestTimeout_;
		maxRetries_ = builder.maxRetries_;
		retryIntervalUs_ = Math.max(0,
				(2000 * (builder.retryInterval_.toMillis() - MIN_RETRY_INTERVAL_MS)) / (1L << builder.maxRetries_));
	}

	@Override
	public CompletableFuture<Response> send(final Request request) {
		if (closed_) {
			return CompletableFuture.failedFuture(new IllegalStateException("JdkHttpTransport is closed"));
		}
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
		if ((body != null)
				&& request.headers().keySet().stream().noneMatch(name -> "Content-Type".equalsIgnoreCase(name))) {
			http.header("Content-Type", "application/json; charset=UTF-8");
		}
		if (requestTimeout_ != null) {
			http.timeout(requestTimeout_);
		}
		if ((httpVersion_ == null) && "http".equalsIgnoreCase(request.uri().getScheme())) {
			http.version(HttpClient.Version.HTTP_1_1);
		}
		return http.build();
	}

	private CompletableFuture<Response> attempt(final HttpRequest request, final int execCount) {
		final CompletableFuture<HttpResponse<byte[]>> sent = httpClient_.sendAsync(request,
				HttpResponse.BodyHandlers.ofByteArray());
		// HttpRequest.timeout stops applying once the headers arrive, so the deadline is also enforced on the whole
		// exchange, up to the last byte of the body. The deadline goes on a copy: orTimeout completes the future it is
		// called on, and the original must stay incomplete so cancelling it still reaches the exchange. orTimeout fires
		// on the JDK's shared delay thread, so everything after it runs on the callback executor.
		final CompletableFuture<HttpResponse<byte[]>> timed = (requestTimeout_ != null)
				? sent.copy().orTimeout(requestTimeout_.toMillis(), TimeUnit.MILLISECONDS)
				: sent;
		return timed
				.handleAsync((response, error) -> {
					if (error == null) {
						if (isRetryableStatus(response.statusCode()) && (execCount <= maxRetries_)) {
							return retry(request, execCount);
						}
						return CompletableFuture.completedFuture(
								new Response(response.statusCode(), "", response.body()));
					}
					final Throwable cause = unwrap(error);
					if (cause instanceof java.util.concurrent.TimeoutException) {
						// Stops the exchange where the JDK supports it: from JDK 16 cancelling closes the connection;
						// JDK 11's client ignores it and finishes reading the response in the background.
						sent.cancel(true);
						return CompletableFuture.<Response> failedFuture(new RequestTimeoutException(
								request.method() + " " + request.uri().getPath() + " timed out", cause));
					}
					if (isRetryableFailure(cause) && (execCount <= maxRetries_)) {
						return retry(request, execCount);
					}
					if (cause instanceof HttpTimeoutException) {
						final String what = (cause instanceof HttpConnectTimeoutException) ? " timed out connecting"
								: " timed out";
						return CompletableFuture.<Response> failedFuture(new RequestTimeoutException(
								request.method() + " " + request.uri().getPath() + what, cause));
					}
					return CompletableFuture.<Response> failedFuture(cause);
				}, callbackExecutor_)
				.thenCompose(future -> future);
	}

	private CompletableFuture<Response> retry(final HttpRequest request, final int execCount) {
		final Executor delayed = CompletableFuture.delayedExecutor(retryDelayMillis(execCount), TimeUnit.MILLISECONDS,
				callbackExecutor_);
		return CompletableFuture.supplyAsync(() -> null, delayed)
				.thenCompose(ignored -> attempt(request, execCount + 1));
	}

	// The delay before retry number execCount: 5 ms plus a share of retryInterval that doubles on every retry.
	long retryDelayMillis(final int execCount) {
		return MIN_RETRY_INTERVAL_MS + (((1L << (execCount - 1)) * retryIntervalUs_) / 1000);
	}

	// As in 1.x: an I/O failure on an open connection and a connect timeout are retried; a refused connection and an
	// expired request timeout are not. HttpConnectTimeoutException is an HttpTimeoutException, so it is checked first.
	static boolean isRetryableFailure(final Throwable cause) {
		if (cause instanceof HttpConnectTimeoutException) {
			return true;
		}
		if ((cause instanceof HttpTimeoutException) || (cause instanceof ConnectException)) {
			return false;
		}
		return cause instanceof IOException;
	}

	/**
	 * Stops accepting requests. The JDK 11 {@code HttpClient} has no close method; its threads exit once idle and
	 * unreferenced.
	 */
	@Override
	public void close() {
		closed_ = true;
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
		 * A timeout for each request attempt, from send to the end of the response (default: none, as in 1.x).
		 * An expired attempt fails the request with {@link RequestTimeoutException} and is not retried. A connect
		 * timeout ({@link #connectTimeout(Duration)}) is retried, and raises {@link RequestTimeoutException} when the
		 * retries run out.
		 */
		public Builder requestTimeout(final Duration requestTimeout) {
			requestTimeout_ = positive(requestTimeout, "requestTimeout");
			if (requestTimeout.compareTo(MAX_REQUEST_TIMEOUT) > 0) {
				throw new IllegalArgumentException("requestTimeout must be at most " + MAX_REQUEST_TIMEOUT);
			}
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

		/** Forces HTTP/1.1 or HTTP/2. By default https negotiates HTTP/2 when the server supports it, and plain
		 * http uses HTTP/1.1.
		 */
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
	private final Executor callbackExecutor_;
	private volatile boolean closed_;
	private final @Nullable Duration requestTimeout_;
	private final HttpClient.@Nullable Version httpVersion_;
	private final int maxRetries_;
	private final long retryIntervalUs_;
}
