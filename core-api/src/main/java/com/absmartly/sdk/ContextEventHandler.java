package com.absmartly.sdk;

import java8.util.concurrent.CompletableFuture;

import javax.annotation.Nonnull;

import com.absmartly.sdk.json.PublishEvent;

public interface ContextEventHandler {
	CompletableFuture<Void> publish(final Context context, @Nonnull final PublishEvent event);
	void onContextReady();
}
