package com.absmartly.sdk;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLEncoder;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.absmartly.sdk.internal.AsyncExecutor;
import com.absmartly.sdk.internal.Json;
import com.absmartly.sdk.internal.SdkVersion;
import com.absmartly.sdk.internal.json.ContextPayload;
import com.absmartly.sdk.internal.json.PublishPayload;

/** The collector API over a {@link Transport}: GET /context and PUT /context with the SDK headers. */
class Client implements AutoCloseable, ContextDataProvider, ContextEventHandler {
	private static final Logger log = LoggerFactory.getLogger(Client.class);

	Client(final String endpoint, final String apiKey, final String application, final String environment,
			final @Nullable String applicationVersion, final Transport transport, final @Nullable Executor executor) {
		if (endpoint.isEmpty()) {
			throw new IllegalArgumentException("Missing Endpoint configuration");
		}

		if (!endpoint.startsWith("https://")) {
			if (endpoint.startsWith("http://")) {
				log.warn("ABsmartly SDK endpoint is not using HTTPS. API keys will be transmitted in plaintext: {}",
						endpoint);
			} else {
				throw new IllegalArgumentException("Endpoint must use http:// or https:// protocol: " + endpoint);
			}
		}

		if (apiKey.isEmpty()) {
			throw new IllegalArgumentException("Missing APIKey configuration");
		}

		if (application.trim().isEmpty()) {
			throw new IllegalArgumentException("Missing Application configuration");
		}

		if (environment.trim().isEmpty()) {
			throw new IllegalArgumentException("Missing Environment configuration");
		}

		final String normalizedEndpoint = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1)
				: endpoint;
		contextUri_ = URI.create(normalizedEndpoint + "/context");
		fetchUri_ = URI.create(normalizedEndpoint + "/context?application=" + encode(application) + "&environment="
				+ encode(environment));
		transport_ = transport;
		executor_ = executor != null ? executor : AsyncExecutor.get();

		final String normalizedVersion = normalizeApplicationVersion(applicationVersion);
		applicationVersion_ = (normalizedVersion != null) ? normalizedVersion : "0";

		headers_ = new LinkedHashMap<String, String>(6);
		headers_.put("X-API-Key", apiKey);
		headers_.put("X-Application", application);
		headers_.put("X-Environment", environment);
		// The collector parses X-Application-Version as a whole number and rejects the request otherwise, so a
		// non-numeric version such as "2.4.1" is sent as "0".
		headers_.put("X-Application-Version", applicationVersionHeader(applicationVersion_));
		headers_.put("X-Agent", SdkVersion.AGENT);

		application_ = application;
		environment_ = environment;
	}

	private static String encode(final String value) {
		try {
			return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
		} catch (UnsupportedEncodingException e) {
			throw new IllegalStateException(e);
		}
	}

	static @Nullable String normalizeApplicationVersion(final @Nullable String applicationVersion) {
		if (applicationVersion == null) {
			return null;
		}
		final String trimmed = applicationVersion.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}

	@Override
	public CompletableFuture<ContextPayload> getContextData() {
		return send(new Transport.Request("GET", fetchUri_, headers_, null)).thenApplyAsync(
				new Function<Transport.Response, ContextPayload>() {
					@Override
					public ContextPayload apply(final Transport.Response response) {
						final byte[] content = response.body();
						if ((content == null) || (content.length == 0)) {
							throw new DecodingException("Empty response body from context data endpoint", null);
						}
						return Json.decodeContext(content);
					}
				}, executor_);
	}

	@Override
	public CompletableFuture<Void> publish(final Context context, final PublishPayload event) {
		return CompletableFuture.supplyAsync(new java.util.function.Supplier<byte[]>() {
			@Override
			public byte[] get() {
				return Json.encodeBytes(event);
			}
		}, executor_).thenCompose(new Function<byte[], CompletableFuture<Transport.Response>>() {
			@Override
			public CompletableFuture<Transport.Response> apply(final byte[] body) {
				return send(new Transport.Request("PUT", contextUri_, headers_, body));
			}
		}).thenApply(new Function<Transport.Response, Void>() {
			@Override
			public Void apply(final Transport.Response response) {
				return null;
			}
		});
	}

	// Completes with a 2xx response; fails with HttpStatusException for any other status, and with the transport's
	// own ABsmartlyException, or a TransportException around any other failure, when no response arrived.
	private CompletableFuture<Transport.Response> send(final Transport.Request request) {
		final CompletableFuture<Transport.Response> sent;
		try {
			sent = transport_.send(request);
		} catch (final RuntimeException e) {
			final CompletableFuture<Transport.Response> failed = new CompletableFuture<Transport.Response>();
			failed.completeExceptionally(transportFailure(request, e));
			return failed;
		}
		final CompletableFuture<Transport.Response> result = new CompletableFuture<Transport.Response>();
		sent.whenComplete(new java.util.function.BiConsumer<Transport.Response, Throwable>() {
			@Override
			public void accept(final Transport.Response response, final Throwable error) {
				if (error != null) {
					result.completeExceptionally(transportFailure(request, error));
				} else if ((response.statusCode() / 100) == 2) {
					result.complete(response);
				} else {
					result.completeExceptionally(new HttpStatusException(response.statusCode(),
							response.statusMessage().isEmpty() ? HttpStatusException.reasonPhrase(response.statusCode())
									: response.statusMessage()));
				}
			}
		});
		return result;
	}

	private static ABsmartlyException transportFailure(final Transport.Request request, Throwable error) {
		while ((error instanceof CompletionException) && (error.getCause() != null)) {
			error = error.getCause();
		}
		if (error instanceof ABsmartlyException) {
			return (ABsmartlyException) error;
		}
		return new TransportException(request.method() + " " + request.uri().getPath() + " failed: "
				+ ContextLoadException.describe(error), error);
	}

	String getApplication() {
		return application_;
	}

	static String applicationVersionHeader(final String applicationVersion) {
		if (applicationVersion.length() > 18) {
			return "0";
		}
		for (int i = 0; i < applicationVersion.length(); ++i) {
			final char c = applicationVersion.charAt(i);
			if (c < '0' || c > '9') {
				return "0";
			}
		}
		return applicationVersion;
	}

	/**
	 * The configured application version, or "0" when none is set. The X-Application-Version header carries this value
	 * only when it is a whole number; otherwise the header is "0".
	 */
	String getApplicationVersion() {
		return applicationVersion_;
	}

	// Null only in tests that model a client without an environment; a built client always has one.
	@Nullable
	String getEnvironment() {
		return environment_;
	}

	@Override
	public void close() {
		transport_.close();
	}

	private final URI contextUri_;
	private final URI fetchUri_;
	private final String application_;
	private final String applicationVersion_;
	private final String environment_;
	private final Map<String, String> headers_;
	private final Transport transport_;
	private final Executor executor_;
}
