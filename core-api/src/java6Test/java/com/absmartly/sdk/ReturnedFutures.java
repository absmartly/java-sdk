package com.absmartly.sdk;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CompletableFuture;

import com.absmartly.sdk.internal.AsyncExecutor;

final class ReturnedFutures {
	private ReturnedFutures() {}

	static void assertDefaultExecutor(CompletableFuture<?> future) {
		assertSame(AsyncExecutor.get(), future.defaultExecutor());
	}
}
