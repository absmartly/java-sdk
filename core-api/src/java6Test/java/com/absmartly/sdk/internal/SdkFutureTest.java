package com.absmartly.sdk.internal;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java8.util.concurrent.CompletableFuture;
import java8.util.concurrent.CompletionException;

import org.junit.jupiter.api.Test;

class SdkFutureTest {
	@Test
	void eitherStagesWithCompletedPlainFutureDefaultToSdkExecutor() {
		final CompletableFuture<String> pending = new SdkFuture<String>();
		final CompletableFuture<String> completed = CompletableFuture.completedFuture("other");
		final ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			assertSdkFuture(pending.applyToEither(completed, value -> value));
			assertSdkFuture(pending.applyToEitherAsync(completed, value -> value));
			assertSdkFuture(pending.applyToEitherAsync(completed, value -> value, executor));
			assertSdkFuture(pending.acceptEither(completed, value -> {}));
			assertSdkFuture(pending.acceptEitherAsync(completed, value -> {}));
			assertSdkFuture(pending.acceptEitherAsync(completed, value -> {}, executor));
			assertSdkFuture(pending.runAfterEither(completed, () -> {}));
			assertSdkFuture(pending.runAfterEitherAsync(completed, () -> {}));
			assertSdkFuture(pending.runAfterEitherAsync(completed, () -> {}, executor));

			assertEquals("other",
					pending.applyToEither(completed, value -> value).thenApplyAsync(value -> value).join());

			final CompletableFuture<String> failed = new CompletableFuture<String>();
			failed.completeExceptionally(new IllegalStateException("failed"));
			final CompletionException exception = assertThrows(CompletionException.class,
					() -> pending.applyToEither(failed, value -> value).join());
			assertTrue(exception.getCause() instanceof IllegalStateException);
		} finally {
			executor.shutdownNow();
		}
	}

	private static void assertSdkFuture(CompletableFuture<?> future) {
		assertSame(AsyncExecutor.get(), future.defaultExecutor());
		future.join();
	}
}
