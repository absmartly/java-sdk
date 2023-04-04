package com.absmartly.sdk;

import java8.util.concurrent.CompletableFuture;

import javax.annotation.Nonnull;

import com.absmartly.sdk.json.PublishEvent;

public class DefaultContextEventHandler implements ContextEventHandler {
	public DefaultContextEventHandler(@Nonnull final Client client) {
		client_ = client;
	}

	@Override
	public CompletableFuture<Void> publish(final Context context, @Nonnull final PublishEvent event) {
		return client_.publish(event);
	}

	@Override
	public void onContextReady() {
	}

	final Client client_;
}
