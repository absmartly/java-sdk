package com.absmartly.sdk;

import java.util.concurrent.CompletableFuture;

import com.absmartly.sdk.json.PublishEvent;

public interface ContextEventHandler {
	CompletableFuture<Void> publish(PublishEvent event);
}
