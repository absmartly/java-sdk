package com.absmartly.sdk;

import java.util.concurrent.CompletableFuture;

import javax.annotation.Nonnull;

import com.absmartly.sdk.json.ContextData;

public class DefaultContextDataProvider implements ContextDataProvider {
	public DefaultContextDataProvider(@Nonnull final Client client) {
		this.client_ = client;
	}

	@Override
	public CompletableFuture<ContextData> getContextData() {
		return client_.getContextData();
	}

	private final Client client_;
}
