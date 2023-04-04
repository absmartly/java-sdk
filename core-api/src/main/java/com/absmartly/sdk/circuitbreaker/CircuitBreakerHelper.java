package com.absmartly.sdk.circuitbreaker;

import java8.util.concurrent.CompletableFuture;
import java8.util.concurrent.CompletionStage;
import java8.util.function.BiConsumer;
import java8.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.geckotechnology.simpleCircuitBreaker.*;

import com.absmartly.sdk.ResilienceConfig;

public class CircuitBreakerHelper {

	private static final Logger log = LoggerFactory.getLogger(CircuitBreakerHelper.class);
	private final CircuitBreaker circuitBreaker;

	public CircuitBreakerHelper(ResilienceConfig config, BreakerStateEventListener eventListener) {
		this.circuitBreaker = new CircuitBreaker(getCircuitBreakerConfig(config));
		circuitBreaker.getBreakerStateEventManager().addBreakerStateEventListener(eventListener);
	}

	private static CircuitBreakerConfig getCircuitBreakerConfig(ResilienceConfig resilienceConfig) {
		CircuitBreakerConfig config = new CircuitBreakerConfig();
		config.setFailureRateThreshold(resilienceConfig.getFailureRateThreshold());
		config.setSlowCallDurationThreshold(0);
		config.setWaitDurationInOpenState(resilienceConfig.getBackoffPeriodInMilliseconds());
		return config;
	}

	private static void onEvent(CircuitBreakerStateChangeEvent event) {
		System.out.println("StateTransition" + event);
	}

	public <T> Supplier<CompletionStage<T>> decorateCompletionStage(
			final Supplier<CompletionStage<T>> supplier) {
		return new Supplier<CompletionStage<T>>() {
			@Override
			public CompletionStage<T> get() {

				final CompletableFuture<T> promise = new CompletableFuture<T>();

				if (!circuitBreaker.isClosedForThisCall()) {
					promise.completeExceptionally(new RuntimeException("Circuit Breaker is opened."));
				} else {
					final long start = System.currentTimeMillis();
					try {
						supplier.get().whenComplete(new BiConsumer<T, Throwable>() {
							@Override
							public void accept(T result, Throwable throwable) {
								long duration = System.currentTimeMillis() - start;
								if (throwable != null) {
									circuitBreaker.callFailed(duration);
									promise.completeExceptionally(throwable);
								} else {
									circuitBreaker.callSucceeded(duration);
								}
							}
						});
					} catch (Exception exception) {
						long duration = System.currentTimeMillis() - start;
						circuitBreaker.callFailed(duration);
						promise.completeExceptionally(exception);
					}
				}

				return promise;
			}
		};
	}

	public <T> CompletableFuture<T> decorateCompletionFuture(
			Supplier<CompletableFuture<T>> supplier) {
		CompletableFuture<T> future = null;
		if (!circuitBreaker.isClosedForThisCall()) {
			future = CompletableFuture.failedFuture(new RuntimeException("Circuit Breaker is opened."));
		} else {
			final long start = System.currentTimeMillis();
			try {
				future = supplier.get();
				future.whenComplete(new BiConsumer<T, Throwable>() {
					@Override
					public void accept(T result, Throwable throwable) {
						long duration = System.currentTimeMillis() - start;
						if (throwable != null) {
							circuitBreaker.callFailed(duration);
						} else {
							circuitBreaker.callSucceeded(duration);
						}
					}
				});
			} catch (Exception exception) {
				long duration = System.currentTimeMillis() - start;
				circuitBreaker.callFailed(duration);
				return CompletableFuture.failedFuture(exception);
			}
		}

		return future;
	}

	public CircuitBreaker getCircuitBreaker() {
		return this.circuitBreaker;
	}
}
