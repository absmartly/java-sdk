package com.absmartly.sdk;

import java.io.Closeable;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java8.util.concurrent.CompletableFuture;
import java8.util.concurrent.CompletionStage;
import java8.util.function.Consumer;
import java8.util.function.Function;
import java8.util.function.Supplier;

import javax.annotation.Nonnull;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.absmartly.sdk.json.ContextData;
import com.absmartly.sdk.json.PublishEvent;

public class Client implements Closeable {
	private static final Logger log = LoggerFactory.getLogger(Client.class);

	static public Client create(@Nonnull final ClientConfig config) {
		return new Client(config, DefaultHTTPClient.create(DefaultHTTPClientConfig.create()));
	}

	static public Client create(@Nonnull final ClientConfig config, @Nonnull final HTTPClient httpClient) {
		return new Client(config, httpClient);
	}

	Client(final ClientConfig config, final HTTPClient httpClient) {
		final String endpoint = config.getEndpoint();
		if ((endpoint == null) || endpoint.isEmpty()) {
			throw new IllegalArgumentException("Missing Endpoint configuration");
		}

		if (!endpoint.startsWith("https://")) {
			if (endpoint.startsWith("http://")) {
				log.warn("ABSmartly SDK endpoint is not using HTTPS. API keys will be transmitted in plaintext: {}",
						endpoint);
			} else {
				throw new IllegalArgumentException("Endpoint must use http:// or https:// protocol: " + endpoint);
			}
		}

		final String apiKey = config.getAPIKey();
		if ((apiKey == null) || apiKey.isEmpty()) {
			throw new IllegalArgumentException("Missing APIKey configuration");
		}

		final String application = config.getApplication();
		if ((application == null) || application.isEmpty()) {
			throw new IllegalArgumentException("Missing Application configuration");
		}

		final String environment = config.getEnvironment();
		if ((environment == null) || environment.isEmpty()) {
			throw new IllegalArgumentException("Missing Environment configuration");
		}

		final String normalizedEndpoint = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1)
				: endpoint;
		url_ = normalizedEndpoint + "/context";
		httpClient_ = httpClient;
		deserializer_ = config.getContextDataDeserializer();
		serializer_ = config.getContextEventSerializer();
		executor_ = config.getExecutor();

		if (deserializer_ == null) {
			deserializer_ = new DefaultContextDataDeserializer();
		}

		if (serializer_ == null) {
			serializer_ = new DefaultContextEventSerializer();
		}

		headers_ = new HashMap<String, String>(6);
		headers_.put("X-API-Key", apiKey);
		headers_.put("X-Application", application);
		headers_.put("X-Environment", environment);
		headers_.put("X-Application-Version", Long.toString(0));
		headers_.put("X-Agent", "absmartly-java-sdk");

		query_ = new HashMap<String, String>(2);
		query_.put("application", application);
		query_.put("environment", environment);
	}

	CompletableFuture<ContextData> getContextData() {
		final CompletableFuture<ContextData> dataFuture = new CompletableFuture<ContextData>();
		final Executor executor = executor_ != null ? executor_ : dataFuture.defaultExecutor();

		CompletableFuture
				.runAsync(new Runnable() {
					@Override
					public void run() {
						httpClient_.get(url_, query_, null).thenAccept(new Consumer<HTTPClient.Response>() {
							@Override
							public void accept(HTTPClient.Response response) {
								final int code = response.getStatusCode();
								if ((code / 100) == 2) {
									final byte[] content = response.getContent();
									if (content == null || content.length == 0) {
										dataFuture.completeExceptionally(new IllegalStateException(
												"Empty response body from context data endpoint"));
									} else {
										final ContextData result = deserializer_.deserialize(content, 0,
												content.length);
										if (result != null) {
											dataFuture.complete(result);
										} else {
											dataFuture.completeExceptionally(new IllegalStateException(
													"Failed to deserialize context data response"));
										}
									}
								} else {
									dataFuture.completeExceptionally(new Exception(response.getStatusMessage()));
								}
							}
						}).exceptionally(new Function<Throwable, Void>() {
							@Override
							public Void apply(Throwable exception) {
								dataFuture.completeExceptionally(exception);
								return null;
							}
						});
					}
				}, executor);

		return dataFuture;
	}

	CompletableFuture<Void> publish(@Nonnull final PublishEvent event) {
		final CompletableFuture<Void> publishFuture = new CompletableFuture<Void>();
		final Executor executor = executor_ != null ? executor_ : publishFuture.defaultExecutor();

		CompletableFuture
				.supplyAsync(new Supplier<byte[]>() {
					@Override
					public byte[] get() {
						return serializer_.serialize(event);
					}
				}, executor)
				.thenCompose(new Function<byte[], CompletionStage<HTTPClient.Response>>() {
					@Override
					public CompletionStage<HTTPClient.Response> apply(byte[] content) {
						return httpClient_.put(url_, null, headers_, content);
					}
				})
				.thenAccept(new Consumer<HTTPClient.Response>() {
					@Override
					public void accept(HTTPClient.Response response) {
						final int code = response.getStatusCode();
						if ((code / 100) == 2) {
							publishFuture.complete(null);
						} else {
							publishFuture.completeExceptionally(new Exception(response.getStatusMessage()));
						}
					}
				})
				.exceptionally(new Function<Throwable, Void>() {
					@Override
					public Void apply(Throwable exception) {
						publishFuture.completeExceptionally(exception);
						return null;
					}
				});

		return publishFuture;
	}

	@Override
	public void close() throws IOException {
		httpClient_.close();
	}

	private final String url_;
	private final Map<String, String> query_;
	private final Map<String, String> headers_;
	private final HTTPClient httpClient_;
	private final Executor executor_;
	private ContextDataDeserializer deserializer_;
	private ContextEventSerializer serializer_;
}
