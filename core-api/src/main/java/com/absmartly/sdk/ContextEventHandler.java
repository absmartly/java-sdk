package com.absmartly.sdk;

import java.util.concurrent.CompletableFuture;

import com.absmartly.sdk.internal.json.PublishPayload;

/** Where a context publishes its queued events: the client's PUT /context. */
interface ContextEventHandler {
	CompletableFuture<Void> publish(Context context, PublishPayload event);
}
