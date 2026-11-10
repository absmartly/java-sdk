package com.absmartly.sdk.internal;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Executor for the SDK's internal async stages. The streamsupport backport's default async pool (its own ForkJoinPool)
 * fails to initialize on JDK 24+, so every *Async call in the SDK must be given an explicit executor.
 */
public class AsyncExecutor {
	static public Executor get() {
		return Holder.INSTANCE;
	}

	private static class Holder {
		static final Executor INSTANCE = Executors.newCachedThreadPool(new ThreadFactory() {
			@Override
			public Thread newThread(Runnable runnable) {
				final Thread thread = new Thread(runnable, "absmartly-sdk-async-" + threadCount_.incrementAndGet());
				thread.setDaemon(true);
				return thread;
			}

			private final AtomicInteger threadCount_ = new AtomicInteger();
		});
	}
}
