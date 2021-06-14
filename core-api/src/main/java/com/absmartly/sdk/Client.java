package com.absmartly.sdk;

import java.io.Closeable;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nonnull;

import com.absmartly.sdk.json.ContextData;
import com.absmartly.sdk.json.PublishEvent;

public class Client implements Closeable {
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

		url_ = endpoint + "/context";
		httpClient_ = httpClient;
		deserializer_ = config.getContextDataDeserializer();
		serializer_ = config.getContextEventSerializer();

		if (deserializer_ == null) {
			deserializer_ = new DefaultContextDataDeserializer();
		}

		if (serializer_ == null) {
			serializer_ = new DefaultContextEventSerializer();
		}

		headers_ = new HashMap<>(6);
		headers_.put("X-API-Key", apiKey);
		headers_.put("X-Application", application);
		headers_.put("X-Environment", environment);
		headers_.put("X-Application-Version", Long.toString(0));
		headers_.put("X-Agent", "java-sdk");

		query_ = new HashMap<>(2);
		query_.put("application", application);
		query_.put("environment", environment);
	}

	CompletableFuture<ContextData> getContextData() {
		final CompletableFuture<ContextData> dataFuture = new CompletableFuture<>();

		httpClient_.get(url_, query_, null).thenAccept(response -> {
			final int code = response.getStatusCode();
			if ((code / 100) == 2) {
				final byte[] content = response.getContent();
				dataFuture.complete(deserializer_.deserialize(response.getContent(), 0, content.length));
			} else {
				dataFuture.completeExceptionally(new Exception(response.getStatusMessage()));
			}
		}).exceptionally(exception -> {
			dataFuture.completeExceptionally(exception);
			return null;
		});

		return dataFuture;
	}

	CompletableFuture<Void> publish(@Nonnull final PublishEvent event) {
		final CompletableFuture<Void> publishFuture = new CompletableFuture<>();

		CompletableFuture
				.supplyAsync(() -> serializer_.serialize(event))
				.thenCompose(content -> httpClient_.put(url_, null, headers_, content))
				.thenAccept(response -> {
					final int code = response.getStatusCode();
					if ((code / 100) == 2) {
						publishFuture.complete(null);
					} else {
						publishFuture.completeExceptionally(new Exception(response.getStatusMessage()));
					}
				})
				.exceptionally(exception -> {
					publishFuture.completeExceptionally(exception);
					return null;
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
	private HTTPClient httpClient_;
	private ContextDataDeserializer deserializer_;
	private ContextEventSerializer serializer_;
}
