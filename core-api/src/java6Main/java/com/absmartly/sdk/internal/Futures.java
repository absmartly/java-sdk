package com.absmartly.sdk.internal;

import java8.util.concurrent.CompletableFuture;

/**
 * Creates the futures the SDK hands out. They are {@link SdkFuture}s, so a caller's *Async stage without an executor
 * never reaches the backport's default pool.
 */
public final class Futures {
	private Futures() {}

	static public <T> CompletableFuture<T> incomplete() {
		return new SdkFuture<T>();
	}

	static public <T> CompletableFuture<T> completed(final T value) {
		return SdkFuture.completed(value);
	}
}
