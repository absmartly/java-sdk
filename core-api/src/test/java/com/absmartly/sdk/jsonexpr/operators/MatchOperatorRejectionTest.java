package com.absmartly.sdk.jsonexpr.operators;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

class MatchOperatorRejectionTest extends OperatorTest {

	/**
	 * Verifies that a saturated pool causes binary() to return null and that the regex
	 * callable never executes on the calling thread (AbortPolicy, not CallerRunsPolicy).
	 */
	@Test
	void poolSaturationReturnsNullWithoutRunningRegexOnCaller() throws InterruptedException {
		// 1-worker, zero-capacity queue: the second submit is rejected synchronously.
		ThreadPoolExecutor tinyPool = new ThreadPoolExecutor(
				1, 1, 60L, TimeUnit.SECONDS,
				new SynchronousQueue<Runnable>(),
				new ThreadPoolExecutor.AbortPolicy());

		CountDownLatch workerRunning = new CountDownLatch(1);
		CountDownLatch releaseWorker = new CountDownLatch(1);
		AtomicBoolean regexRan = new AtomicBoolean(false);
		Thread callerThread = Thread.currentThread();

		try {
			// Occupy the sole pool worker so the next submit is rejected.
			tinyPool.submit(new Callable<Void>() {
				@Override
				public Void call() throws InterruptedException {
					workerRunning.countDown();
					releaseWorker.await(10, TimeUnit.SECONDS);
					return null;
				}
			});

			// Wait until the worker is actively executing (not merely accepted).
			assertTrue(workerRunning.await(5, TimeUnit.SECONDS),
					"pool worker must start within 5 s");

			// Wrap the saturated pool so the regex Callable sets regexRan if it ever runs.
			ExecutorService saturated = new ForwardingExecutorService(tinyPool) {
				@Override
				public <T> Future<T> submit(final Callable<T> task) {
					return super.submit(new Callable<T>() {
						@Override
						public T call() throws Exception {
							regexRan.set(true);
							return task.call();
						}
					});
				}
			};

			MatchOperator op = new MatchOperator(saturated);
			Object result = op.binary(evaluator, "text", "abc");

			// Pool was saturated; submit threw RejectedExecutionException; binary() must return null.
			assertNull(result, "saturated pool must return null; regex must not execute");

			// The wrapped Callable was never enqueued (rejected before scheduling),
			// so regexRan stays false — proving it did not run on the caller or any thread.
			assertFalse(regexRan.get(), "rejected regex callable must not run on any thread");
		} finally {
			releaseWorker.countDown();
			tinyPool.shutdown();
			tinyPool.awaitTermination(5, TimeUnit.SECONDS);
		}
	}

	/** Minimal delegation base so the override above only needs to define submit(Callable). */
	private static class ForwardingExecutorService implements ExecutorService {
		private final ExecutorService delegate;

		ForwardingExecutorService(ExecutorService delegate) {
			this.delegate = delegate;
		}

		@Override
		public <T> Future<T> submit(Callable<T> task) {
			return delegate.submit(task);
		}

		@Override public void execute(Runnable cmd) { delegate.execute(cmd); }
		@Override public void shutdown() { delegate.shutdown(); }
		@Override public java.util.List<Runnable> shutdownNow() { return delegate.shutdownNow(); }
		@Override public boolean isShutdown() { return delegate.isShutdown(); }
		@Override public boolean isTerminated() { return delegate.isTerminated(); }
		@Override public boolean awaitTermination(long t, TimeUnit u) throws InterruptedException {
			return delegate.awaitTermination(t, u);
		}
		@Override public <T> Future<T> submit(Runnable task, T result) {
			return delegate.submit(task, result);
		}
		@Override public Future<?> submit(Runnable task) { return delegate.submit(task); }
		@Override public <T> java.util.List<Future<T>> invokeAll(
				java.util.Collection<? extends Callable<T>> tasks) throws InterruptedException {
			return delegate.invokeAll(tasks);
		}
		@Override public <T> java.util.List<Future<T>> invokeAll(
				java.util.Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
				throws InterruptedException {
			return delegate.invokeAll(tasks, timeout, unit);
		}
		@Override public <T> T invokeAny(java.util.Collection<? extends Callable<T>> tasks)
				throws InterruptedException, ExecutionException {
			return delegate.invokeAny(tasks);
		}
		@Override public <T> T invokeAny(java.util.Collection<? extends Callable<T>> tasks,
				long timeout, TimeUnit unit)
				throws InterruptedException, ExecutionException, TimeoutException {
			return delegate.invokeAny(tasks, timeout, unit);
		}
	}
}
