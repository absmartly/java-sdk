package com.absmartly.sdk.jsonexpr.operators;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

class MatchOperatorRejectionTest extends OperatorTest {

	@Test
	void poolSaturationReturnsNullWithoutRunningRegexOnCaller() throws InterruptedException {
		final CountDownLatch workerRunning = new CountDownLatch(1);
		final CountDownLatch releaseWorker = new CountDownLatch(1);
		final AtomicBoolean regexRan = new AtomicBoolean(false);
		final ThreadPoolExecutor tinyPool = new ThreadPoolExecutor(
				1, 1, 60L, TimeUnit.SECONDS,
				new SynchronousQueue<Runnable>(),
				new ThreadPoolExecutor.AbortPolicy()) {
			@Override
			protected <T> RunnableFuture<T> newTaskFor(final Callable<T> task) {
				if (workerRunning.getCount() == 0) {
					return super.newTaskFor(new Callable<T>() {
						@Override
						public T call() throws Exception {
							regexRan.set(true);
							return task.call();
						}
					});
				}
				return super.newTaskFor(task);
			}
		};

		try {
			tinyPool.submit(new Callable<Void>() {
				@Override
				public Void call() throws InterruptedException {
					workerRunning.countDown();
					releaseWorker.await(10, TimeUnit.SECONDS);
					return null;
				}
			});

			assertTrue(workerRunning.await(5, TimeUnit.SECONDS),
					"pool worker must start within 5 s");

			final MatchOperator op = new MatchOperator(tinyPool);
			final Object result = op.binary(evaluator, "text", "abc");

			assertNull(result, "saturated pool must return null; regex must not execute");
			assertFalse(regexRan.get(), "rejected regex callable must not run on any thread");
		} finally {
			releaseWorker.countDown();
			tinyPool.shutdown();
			tinyPool.awaitTermination(5, TimeUnit.SECONDS);
		}
	}
}
