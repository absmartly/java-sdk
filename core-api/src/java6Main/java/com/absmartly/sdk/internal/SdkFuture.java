package com.absmartly.sdk.internal;

import java.util.concurrent.Executor;
import java8.util.concurrent.CompletableFuture;

/**
 * The future the SDK hands out. Dependent stages are also SdkFutures, so a caller's *Async method without an executor
 * runs on {@link AsyncExecutor} instead of the backport's default pool, which fails to initialize on JDK 24+.
 */
public class SdkFuture<T> extends CompletableFuture<T> {
	static public <T> SdkFuture<T> completed(final T value) {
		final SdkFuture<T> future = new SdkFuture<T>();
		future.complete(value);
		return future;
	}

	@Override
	public <U> CompletableFuture<U> newIncompleteFuture() {
		return new SdkFuture<U>();
	}

	@Override
	public Executor defaultExecutor() {
		return AsyncExecutor.get();
	}
}
