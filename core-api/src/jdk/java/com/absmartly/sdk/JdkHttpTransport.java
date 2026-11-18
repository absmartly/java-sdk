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
 * other status and an expired {@link Builder#requestTimeout(Duration)} are not retried. Redirects are followed only
 * to the same scheme, host and port (up to 5), so the API key header never reaches another origin; a redirect
 * elsewhere is returned as the 3xx response. The JDK client itself also retries an idempotent GET once when its
 * connection is reset, so a GET can be sent up to twice per attempt.
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
				.followRedirects(HttpClient.Redirect.NEVER);
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

	private static final int MAX_REDIRECTS = 5;

	// Each attempt (a first try or a retry) gets the full requestTimeout; the redirect hops within it share that one
	// deadline, so a chain of redirects cannot stretch an attempt past the timeout.
	private CompletableFuture<Response> attempt(final HttpRequest request, final int execCount) {
		final long deadline = (requestTimeout_ != null) ? (System.nanoTime() + requestTimeout_.toNanos()) : 0;
		return attempt(request, execCount, 0, deadline);
	}

	// Redirects are followed here, not by the JDK client: it would send every request header, the API key included,
	// to whatever host a redirect names. Only a redirect to the same scheme, host and port is followed; any other is
	// returned to the caller as the 3xx response it is. A 303, and a 301 or 302 answering a POST, turn into a GET
	// without a body, as the JDK client does; every other redirect resends the same method and body, so a redirected
	// publish (a PUT) is never silently dropped.
	private CompletableFuture<Response> followRedirect(final HttpRequest request, final HttpResponse<byte[]> response,
			final int execCount, final int redirects, final long deadline) {
		final java.util.Optional<String> location = response.headers().firstValue("Location");
		if (!location.isPresent() || (redirects >= MAX_REDIRECTS)) {
			return null;
		}
		final java.net.URI target;
		try {
			target = request.uri().resolve(location.get());
		} catch (final IllegalArgumentException e) {
			return null;
		}
		if (!sameOrigin(request.uri(), target)) {
			return null;
		}
		final int status = response.statusCode();
		final boolean keepMethod = (status == 307) || (status == 308)
				|| (((status == 301) || (status == 302)) && !"POST".equalsIgnoreCase(request.method()));
		final HttpRequest.Builder next = HttpRequest.newBuilder(target);
		request.headers().map().forEach((name, values) -> {
			if (keepMethod || !"Content-Type".equalsIgnoreCase(name)) {
				values.forEach(value -> next.header(name, value));
			}
		});
		request.timeout().ifPresent(next::timeout);
		request.version().ifPresent(next::version);
		if (keepMethod) {
			next.method(request.method(), request.bodyPublisher().orElse(HttpRequest.BodyPublishers.noBody()));
		} else {
			next.GET();
		}
		return attempt(next.build(), execCount, redirects + 1, deadline);
	}

	static boolean sameOrigin(final java.net.URI from, final java.net.URI to) {
		return (from.getScheme() != null) && from.getScheme().equalsIgnoreCase(to.getScheme())
				&& (from.getHost() != null) && from.getHost().equalsIgnoreCase(to.getHost())
				&& (effectivePort(from) == effectivePort(to));
	}

	private static int effectivePort(final java.net.URI uri) {
		if (uri.getPort() >= 0) {
			return uri.getPort();
		}
		return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
	}

	private static boolean isRedirect(final int statusCode) {
		return (statusCode == 301) || (statusCode == 302) || (statusCode == 303) || (statusCode == 307)
				|| (statusCode == 308);
	}

	private CompletableFuture<Response> attempt(final HttpRequest request, final int execCount, final int redirects,
			final long deadline) {
		final CompletableFuture<HttpResponse<byte[]>> sent = httpClient_.sendAsync(request,
				HttpResponse.BodyHandlers.ofByteArray());
		// HttpRequest.timeout stops applying once the headers arrive, so the deadline is also enforced on the whole
		// exchange, up to the last byte of the body. The deadline goes on a copy: orTimeout completes the future it is
		// called on, and the original must stay incomplete so cancelling it still reaches the exchange. orTimeout fires
		// on the JDK's shared delay thread, so everything after it runs on the callback executor.
		final CompletableFuture<HttpResponse<byte[]>> timed = (requestTimeout_ != null)
				? sent.copy().orTimeout(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)
				: sent;
		return onCallbackExecutor(timed, request)
				.handle((response, error) -> {
					if (error == null) {
						if (isRetryableStatus(response.statusCode()) && (execCount <= maxRetries_)) {
							return retry(request, execCount);
						}
						if (isRedirect(response.statusCode())) {
							final CompletableFuture<Response> redirected = followRedirect(request, response, execCount,
									redirects, deadline);
							if (redirected != null) {
								return redirected;
							}
						}
						return CompletableFuture.completedFuture(
								new Response(response.statusCode(), "", response.body()));
					}
					final Throwable cause = unwrap(error);
					if (cause instanceof java.util.concurrent.TimeoutException) {
						// Stops the exchange where the JDK supports it: from JDK 16 cancelling closes the connection;
						// JDK 11's client ignores it and finishes reading the response in the background.
						sent.cancel(true);
						return timedOut(request, " timed out", cause);
					}
					if (isRetryableFailure(cause) && (execCount <= maxRetries_)) {
						return retry(request, execCount);
					}
					if (cause instanceof HttpTimeoutException) {
						return timedOut(request,
								(cause instanceof HttpConnectTimeoutException) ? " timed out connecting"
										: " timed out",
								cause);
					}
					return CompletableFuture.<Response> failedFuture(cause);
				})
				.thenCompose(future -> future);
	}

	private static CompletableFuture<Response> timedOut(final HttpRequest request, final String what,
			final Throwable cause) {
		return CompletableFuture.failedFuture(
				new RequestTimeoutException(request.method() + " " + request.uri().getPath() + what, cause));
	}

	// Moves a completion onto the callback executor. An executor that rejects the task (shut down, or saturated)
	// fails the request with a TransportException instead of leaving its future incomplete forever.
	private <T> CompletableFuture<T> onCallbackExecutor(final CompletableFuture<T> source, final HttpRequest request) {
		final CompletableFuture<T> moved = new CompletableFuture<T>();
		source.whenComplete((value, error) -> dispatch(() -> {
			if (error != null) {
				moved.completeExceptionally(error);
			} else {
				moved.complete(value);
			}
		}, moved, request));
		return moved;
	}

	private void dispatch(final Runnable task, final CompletableFuture<?> target, final HttpRequest request) {
		try {
			callbackExecutor_.execute(task);
		} catch (final RuntimeException rejected) {
			target.completeExceptionally(new TransportException(request.method() + " " + request.uri().getPath()
					+ " could not be scheduled on the transport executor", rejected));
		}
	}

	// The back-off runs on the JDK's shared delay thread, which then hands the retry to the callback executor; a
	// rejection there fails the request rather than being lost on the delay thread.
	private CompletableFuture<Response> retry(final HttpRequest request, final int execCount) {
		final CompletableFuture<Void> due = new CompletableFuture<Void>();
		CompletableFuture.delayedExecutor(retryDelayMillis(execCount), TimeUnit.MILLISECONDS, Runnable::run)
				.execute(() -> dispatch(() -> due.complete(null), due, request));
		return due.thenCompose(ignored -> attempt(request, execCount + 1));
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
		 * A timeout for each request attempt, from send to the end of the response, including any same-origin
		 * redirects it follows (default: none, as in 1.x).
		 * An expired attempt fails the request with {@link RequestTimeoutException} and is not retried. A connect
		 * timeout ({@link #connectTimeout(Duration)}) is retried, and raises {@link RequestTimeoutException} when the
		 * retries run out. On JDK 11 the JDK client cannot be stopped mid-response, so a timed-out body keeps
		 * downloading in the background (the caller still gets the timeout); newer JDKs close the connection.
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
