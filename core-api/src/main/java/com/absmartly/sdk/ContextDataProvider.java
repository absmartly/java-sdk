package com.absmartly.sdk;

import java.util.concurrent.CompletableFuture;

import com.absmartly.sdk.json.ContextData;

public interface ContextDataProvider {
	CompletableFuture<ContextData> getContextData();
}
