package com.absmartly.sdk.internal;

import java.util.concurrent.Executor;
import java8.util.concurrent.CompletableFuture;
import java8.util.concurrent.CompletionStage;
import java8.util.function.BiConsumer;
import java8.util.function.Consumer;
import java8.util.function.Function;

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

	// The backport builds an either-stage dependent from whichever source is already complete, so a completed plain
	// future would produce a plain dependent. Relaying it through an SdkFuture keeps the dependent an SdkFuture.
	@Override
	public <U> CompletableFuture<U> applyToEither(final CompletionStage<? extends T> other,
			final Function<? super T, U> fn) {
		return super.applyToEither(sdkStage(other), fn);
	}

	@Override
	public <U> CompletableFuture<U> applyToEitherAsync(final CompletionStage<? extends T> other,
			final Function<? super T, U> fn) {
		return super.applyToEitherAsync(sdkStage(other), fn);
	}

	@Override
	public <U> CompletableFuture<U> applyToEitherAsync(final CompletionStage<? extends T> other,
			final Function<? super T, U> fn, final Executor executor) {
		return super.applyToEitherAsync(sdkStage(other), fn, executor);
	}

	@Override
	public CompletableFuture<Void> acceptEither(final CompletionStage<? extends T> other,
			final Consumer<? super T> action) {
		return super.acceptEither(sdkStage(other), action);
	}

	@Override
	public CompletableFuture<Void> acceptEitherAsync(final CompletionStage<? extends T> other,
			final Consumer<? super T> action) {
		return super.acceptEitherAsync(sdkStage(other), action);
	}

	@Override
	public CompletableFuture<Void> acceptEitherAsync(final CompletionStage<? extends T> other,
			final Consumer<? super T> action, final Executor executor) {
		return super.acceptEitherAsync(sdkStage(other), action, executor);
	}

	@Override
	public CompletableFuture<Void> runAfterEither(final CompletionStage<?> other, final Runnable action) {
		return super.runAfterEither(sdkStage(other), action);
	}

	@Override
	public CompletableFuture<Void> runAfterEitherAsync(final CompletionStage<?> other, final Runnable action) {
		return super.runAfterEitherAsync(sdkStage(other), action);
	}

	@Override
	public CompletableFuture<Void> runAfterEitherAsync(final CompletionStage<?> other, final Runnable action,
			final Executor executor) {
		return super.runAfterEitherAsync(sdkStage(other), action, executor);
	}

	static private <U> CompletionStage<U> sdkStage(final CompletionStage<U> stage) {
		if (stage == null || stage instanceof SdkFuture) {
			return stage;
		}

		final SdkFuture<U> relay = new SdkFuture<U>();
		stage.whenComplete(new BiConsumer<U, Throwable>() {
			@Override
			public void accept(U value, Throwable exception) {
				if (exception != null) {
					relay.completeExceptionally(exception);
				} else {
					relay.complete(value);
				}
			}
		});
		return relay;
	}
}
