package com.absmartly.sdk.circuitbreaker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import java8.util.concurrent.CompletableFuture;
import java8.util.concurrent.CompletionStage;
import java8.util.function.Supplier;

import org.junit.jupiter.api.Test;

import com.geckotechnology.simpleCircuitBreaker.CircuitBreaker;
import com.geckotechnology.simpleCircuitBreaker.CircuitBreakerConfig;

import com.absmartly.sdk.ResilienceConfig;
import com.absmartly.sdk.TestUtils;
import com.absmartly.sdk.cache.MemoryCache;

public class CircuitBreakerTest extends TestUtils {
	@Test
	public void testSimpleCircuitBreakerLibrary() {

		CircuitBreakerConfig config = new CircuitBreakerConfig();
		config.setFailureRateThreshold(20);
		config.setSlowCallDurationThreshold(10000);
		config.setWaitDurationInOpenState(2000);
		config.setMaxDurationOpenInHalfOpenState(1100);

		final AtomicInteger i = new AtomicInteger(0);
		final AtomicInteger errorCount = new AtomicInteger(0);
		final AtomicInteger recoveryCount = new AtomicInteger(0);
		final AtomicInteger successCount = new AtomicInteger(0);

		CircuitBreaker circuitBreaker = new CircuitBreaker(
				config);

		circuitBreaker.getBreakerStateEventManager()
				.addBreakerStateEventListener(event -> System.out.println("CircuitBreaker state changed. " + event));

		Supplier<CompletionStage<Void>> supplier = () -> {
			CompletableFuture<Void> future = new CompletableFuture<>();
			try {
				Thread.sleep((int) (Math.random() * 200));
			} catch (InterruptedException e) {
				throw new RuntimeException(e);
			}
			if (i.get() >= 80 && i.get() <= 140) {
				errorCount.incrementAndGet();
				future.completeExceptionally(new RuntimeException("BAM!"));
			} else {
				successCount.incrementAndGet();
				future.complete(null);
			}

			return future;
		};

		Supplier<CompletionStage<Void>> decoratedSupplier = decorateCompletionStage(
				circuitBreaker, supplier);

		while (i.incrementAndGet() <= 300) {
			CompletionStage<Void> result = decoratedSupplier
					.get()
					.whenComplete((unused, throwable) -> {
						if (throwable != null) {
							try {
								Thread.sleep((int) (Math.random() * 100));
							} catch (InterruptedException e) {
								throw new RuntimeException(e);
							}
							recoveryCount.incrementAndGet();
						}
					});
		}

		assertEquals(300, successCount.get() + recoveryCount.get());
		assertTrue(errorCount.get() > 0);

	}

	@Test
	public void testSimpleCircuitBreakerWrappper() {
		final AtomicInteger i = new AtomicInteger(0);
		final AtomicInteger errorCount = new AtomicInteger(0);
		final AtomicInteger recoveryCount = new AtomicInteger(0);
		final AtomicInteger successCount = new AtomicInteger(0);

		CircuitBreakerHelper circuitBreakerHelper = new CircuitBreakerHelper(ResilienceConfig.create(new MemoryCache())
				.setBackoffPeriodInMilliseconds(2000)
				.setFailureRateThreshold(20), event -> {});

		Supplier<CompletionStage<Void>> supplier = (Supplier) () -> {
			CompletableFuture<Void> future = new CompletableFuture<>();
			try {
				Thread.sleep((int) (Math.random() * 200));
			} catch (InterruptedException e) {
				throw new RuntimeException(e);
			}
			if (i.get() >= 80 && i.get() <= 140) {
				errorCount.incrementAndGet();
				future.completeExceptionally(new RuntimeException("BAM!"));
			} else {
				successCount.incrementAndGet();
				future.complete(null);
			}
			return future;
		};

		Supplier<CompletionStage<Void>> decoratedSupplier = circuitBreakerHelper.decorateCompletionStage(supplier);

		while (i.incrementAndGet() <= 300) {
			CompletionStage<Void> result = decoratedSupplier
					.get()
					.whenComplete((unused, throwable) -> {
						if (throwable != null) {
							try {
								Thread.sleep(50);
							} catch (InterruptedException e) {
								throw new RuntimeException(e);
							}
							recoveryCount.incrementAndGet();
						}
					});
		}

		assertEquals(300, successCount.get() + recoveryCount.get());
		assertTrue(errorCount.get() > 0);

	}

	private static <T> Supplier<CompletionStage<T>> decorateCompletionStage(
			CircuitBreaker circuitBreaker,
			Supplier<CompletionStage<T>> supplier) {
		return () -> {

			final CompletableFuture<T> promise = new CompletableFuture<>();

			if (!circuitBreaker.isClosedForThisCall()) {
				//Call settimeout
				try {
					Thread.sleep((int) (Math.random() * 200));
				} catch (InterruptedException e) {
					throw new RuntimeException(e);
				}
				promise.completeExceptionally(new RuntimeException("Circuit Breaker is opened."));
			} else {
				final long start = System.currentTimeMillis();
				try {
					supplier.get().whenComplete((result, throwable) -> {
						long duration = System.currentTimeMillis() - start;
						if (throwable != null) {
							if (throwable instanceof Exception) {
								circuitBreaker.callFailed(duration);
							}
							promise.completeExceptionally(throwable);
						} else {
							circuitBreaker.callSucceeded(duration);
						}
					});
				} catch (Exception exception) {
					long duration = System.currentTimeMillis() - start;
					circuitBreaker.callFailed(duration);
				}
			}

			return promise;
		};
	}

}
