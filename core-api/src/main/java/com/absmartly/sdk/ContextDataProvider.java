package com.absmartly.sdk;

import java.util.concurrent.CompletableFuture;

import com.absmartly.sdk.internal.json.ContextPayload;

/** Where a context fetches its data from: the client's GET /context, or a fixed payload in tests. */
interface ContextDataProvider {
	CompletableFuture<ContextPayload> getContextData();
}
