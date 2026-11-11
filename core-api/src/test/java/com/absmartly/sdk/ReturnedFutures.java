package com.absmartly.sdk;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CompletableFuture;

// The JDK's default async pool works on every supported JDK, so core-api hands out plain futures.
// core-api-java6 replaces this class: its futures must default to the SDK executor instead.
final class ReturnedFutures {
	private ReturnedFutures() {}

	static void assertDefaultExecutor(CompletableFuture<?> future) {
		assertSame(CompletableFuture.class, future.getClass());
		assertSame(new CompletableFuture<Void>().defaultExecutor(), future.defaultExecutor());
	}
}
