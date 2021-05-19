package com.absmartly.sdk;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.absmartly.sdk.json.PublishEvent;

public class DefaultContextEventHandler implements ContextEventHandler {
	public DefaultContextEventHandler(String endpoint, String apiKey, String application, String environment,
			HTTPClient httpClient, ContextEventSerializer serializer) {
		url_ = endpoint + "/context";
		httpClient_ = httpClient;
		serializer_ = serializer;

		headers_ = new HashMap<>(6);
		headers_.put("X-API-Key", apiKey);
		headers_.put("X-Application", application);
		headers_.put("X-Environment", environment);
		headers_.put("X-Application-Version", Long.toString(0));
		headers_.put("X-Agent", "java-sdk");
	}

	@Override
	public CompletableFuture<Void> publish(PublishEvent event) {
		final CompletableFuture<Void> publishFuture = new CompletableFuture<>();

		CompletableFuture
				.supplyAsync(() -> serializer_.serialize(event))
				.thenCompose(content -> httpClient_.put(url_, headers_, content))
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

	private final String url_;
	private final Map<String, Object> headers_;
	private final HTTPClient httpClient_;
	private final ContextEventSerializer serializer_;
}
