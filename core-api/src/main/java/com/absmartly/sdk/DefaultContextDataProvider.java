package com.absmartly.sdk;

import java.util.concurrent.CompletableFuture;

import com.absmartly.sdk.json.ContextData;

public class DefaultContextDataProvider implements ContextDataProvider {
	public DefaultContextDataProvider(String endpoint, HTTPClient httpClient, ContextDataDeserializer deserializer) {
		this.url_ = endpoint + "/context";
		this.httpClient_ = httpClient;
		this.deserializer_ = deserializer;
	}

	@Override
	public CompletableFuture<ContextData> getContextData() {
		final CompletableFuture<ContextData> dataFuture = new CompletableFuture<>();

		httpClient_.get(url_, null).thenAccept(response -> {
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

	private final String url_;
	private final HTTPClient httpClient_;
	private final ContextDataDeserializer deserializer_;
}
