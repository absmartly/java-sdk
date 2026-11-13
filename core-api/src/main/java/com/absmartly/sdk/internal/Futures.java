package com.absmartly.sdk.internal;

import java.util.concurrent.CompletableFuture;

/**
 * Creates the futures the SDK hands out. core-api-java6 replaces this class with one that returns SdkFutures.
 */
public final class Futures {
	private Futures() {}

	static public <T> CompletableFuture<T> incomplete() {
		return new CompletableFuture<T>();
	}

	static public <T> CompletableFuture<T> completed(final T value) {
		return CompletableFuture.completedFuture(value);
	}
}
