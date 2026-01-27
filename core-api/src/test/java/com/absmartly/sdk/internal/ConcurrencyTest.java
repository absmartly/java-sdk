package com.absmartly.sdk.internal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java8.util.function.Function;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import com.absmartly.sdk.TestUtils;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

@SuppressFBWarnings(value = "RV_RETURN_VALUE_IGNORED_NO_SIDE_EFFECT")
class ConcurrencyTest extends TestUtils {
	@Test
	void computeIfAbsentRW() {
		final Map<Integer, Integer> map = mock(Map.class);
		final Function<Integer, Integer> computer = mock(Function.class);
		final ReentrantReadWriteLock.ReadLock rlock = mock(ReentrantReadWriteLock.ReadLock.class);
		final ReentrantReadWriteLock.WriteLock wlock = mock(ReentrantReadWriteLock.WriteLock.class);
		final ReentrantReadWriteLock rwlock = mock(ReentrantReadWriteLock.class);
		when(rwlock.readLock()).thenReturn(rlock);
		when(rwlock.writeLock()).thenReturn(wlock);

		when(computer.apply(1)).thenReturn(5);

		final Integer result = Concurrency.computeIfAbsentRW(rwlock, map, 1, computer);
		assertEquals(5, result);

		verify(map, Mockito.timeout(5000).times(2)).get(any());
		verify(map, Mockito.timeout(5000).times(2)).get(1);

		verify(rwlock, Mockito.timeout(5000).times(1)).readLock();
		verify(rwlock, Mockito.timeout(5000).times(1)).writeLock();
		verify(rlock, Mockito.timeout(5000).times(1)).lock();
		verify(rlock, Mockito.timeout(5000).times(1)).unlock();
		verify(wlock, Mockito.timeout(5000).times(1)).lock();
		verify(wlock, Mockito.timeout(5000).times(1)).unlock();

		verify(computer, Mockito.timeout(5000).times(1)).apply(any());
		verify(computer, Mockito.timeout(5000).times(1)).apply(1);
	}

	@Test
	void computeIfAbsentRWPresent() {
		final Map<Integer, Integer> map = mock(Map.class);
		final Function<Integer, Integer> computer = mock(Function.class);
		final ReentrantReadWriteLock.ReadLock rlock = mock(ReentrantReadWriteLock.ReadLock.class);
		final ReentrantReadWriteLock.WriteLock wlock = mock(ReentrantReadWriteLock.WriteLock.class);
		final ReentrantReadWriteLock rwlock = mock(ReentrantReadWriteLock.class);
		when(rwlock.readLock()).thenReturn(rlock);
		when(rwlock.writeLock()).thenReturn(wlock);

		when(map.get(1)).thenReturn(5);

		final Integer result = Concurrency.computeIfAbsentRW(rwlock, map, 1, computer);
		assertEquals(5, result);

		verify(map, Mockito.timeout(5000).times(1)).get(any());
		verify(map, Mockito.timeout(5000).times(1)).get(1);

		verify(rwlock, Mockito.timeout(5000).times(1)).readLock();
		verify(rwlock, Mockito.timeout(5000).times(0)).writeLock();
		verify(rlock, Mockito.timeout(5000).times(1)).lock();
		verify(rlock, Mockito.timeout(5000).times(1)).unlock();

		verify(computer, Mockito.timeout(5000).times(0)).apply(any());
	}

	@Test
	void computeIfAbsentRWPresentAfterLock() {
		final Map<Integer, Integer> map = mock(Map.class);
		final Function<Integer, Integer> computer = mock(Function.class);
		final ReentrantReadWriteLock.ReadLock rlock = mock(ReentrantReadWriteLock.ReadLock.class);
		final ReentrantReadWriteLock.WriteLock wlock = mock(ReentrantReadWriteLock.WriteLock.class);
		final ReentrantReadWriteLock rwlock = mock(ReentrantReadWriteLock.class);
		when(rwlock.readLock()).thenReturn(rlock);
		when(rwlock.writeLock()).thenAnswer(new Answer<ReentrantReadWriteLock.WriteLock>() {
			@Override
			public ReentrantReadWriteLock.WriteLock answer(InvocationOnMock invocation) throws Throwable {
				when(map.get(1)).thenReturn(5);
				return wlock;
			}
		});

		final Integer result = Concurrency.computeIfAbsentRW(rwlock, map, 1, computer);
		assertEquals(5, result);

		verify(map, Mockito.timeout(5000).times(2)).get(any());
		verify(map, Mockito.timeout(5000).times(2)).get(1);

		verify(rwlock, Mockito.timeout(5000).times(1)).readLock();
		verify(rwlock, Mockito.timeout(5000).times(1)).writeLock();
		verify(rlock, Mockito.timeout(5000).times(1)).lock();
		verify(rlock, Mockito.timeout(5000).times(1)).unlock();
		verify(wlock, Mockito.timeout(5000).times(1)).lock();
		verify(wlock, Mockito.timeout(5000).times(1)).unlock();

		verify(computer, Mockito.timeout(5000).times(0)).apply(any());
	}

	@Test
	void getRW() {
		final Map<Integer, Integer> map = mock(Map.class);
		final ReentrantReadWriteLock.ReadLock lock = mock(ReentrantReadWriteLock.ReadLock.class);
		final ReentrantReadWriteLock rwlock = mock(ReentrantReadWriteLock.class);
		when(rwlock.readLock()).thenReturn(lock);

		final Integer result = Concurrency.getRW(rwlock, map, 1);
		assertNull(result);

		verify(map, Mockito.timeout(5000).times(1)).get(any());
		verify(map, Mockito.timeout(5000).times(1)).get(1);

		verify(rwlock, Mockito.timeout(5000).times(1)).readLock();
		verify(rwlock, Mockito.timeout(5000).times(0)).writeLock();
		verify(lock, Mockito.timeout(5000).times(1)).lock();
		verify(lock, Mockito.timeout(5000).times(1)).unlock();
	}

	@Test
	void putRW() {
		final Map<Integer, Integer> map = mock(Map.class);
		final ReentrantReadWriteLock.WriteLock lock = mock(ReentrantReadWriteLock.WriteLock.class);
		final ReentrantReadWriteLock rwlock = mock(ReentrantReadWriteLock.class);
		when(rwlock.writeLock()).thenReturn(lock);

		final Integer result = Concurrency.putRW(rwlock, map, 1, 5);
		assertNull(result);

		verify(map, Mockito.timeout(5000).times(1)).put(any(), any());
		verify(map, Mockito.timeout(5000).times(1)).put(1, 5);

		verify(rwlock, Mockito.timeout(5000).times(0)).readLock();
		verify(rwlock, Mockito.timeout(5000).times(1)).writeLock();
		verify(lock, Mockito.timeout(5000).times(1)).lock();
		verify(lock, Mockito.timeout(5000).times(1)).unlock();
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	void testConcurrentGetOperations() throws InterruptedException {
		final ReentrantReadWriteLock rwlock = new ReentrantReadWriteLock();
		final Map<String, Integer> map = new ConcurrentHashMap<>();
		map.put("key1", 100);
		map.put("key2", 200);
		map.put("key3", 300);

		final int threadCount = 10;
		final int operationsPerThread = 1000;
		final ExecutorService executor = Executors.newFixedThreadPool(threadCount);
		final CountDownLatch startLatch = new CountDownLatch(1);
		final CountDownLatch doneLatch = new CountDownLatch(threadCount);
		final AtomicInteger successCount = new AtomicInteger(0);

		for (int i = 0; i < threadCount; i++) {
			executor.submit(() -> {
				try {
					startLatch.await();
					for (int j = 0; j < operationsPerThread; j++) {
						final Integer value = Concurrency.getRW(rwlock, map, "key" + ((j % 3) + 1));
						if (value != null) {
							successCount.incrementAndGet();
						}
					}
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				} finally {
					doneLatch.countDown();
				}
			});
		}

		startLatch.countDown();
		doneLatch.await();
		executor.shutdown();

		assertEquals(threadCount * operationsPerThread, successCount.get());
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	void testConcurrentPutOperations() throws InterruptedException {
		final ReentrantReadWriteLock rwlock = new ReentrantReadWriteLock();
		final Map<String, Integer> map = new ConcurrentHashMap<>();

		final int threadCount = 10;
		final int operationsPerThread = 100;
		final ExecutorService executor = Executors.newFixedThreadPool(threadCount);
		final CountDownLatch startLatch = new CountDownLatch(1);
		final CountDownLatch doneLatch = new CountDownLatch(threadCount);

		for (int i = 0; i < threadCount; i++) {
			final int threadId = i;
			executor.submit(() -> {
				try {
					startLatch.await();
					for (int j = 0; j < operationsPerThread; j++) {
						final String key = "thread" + threadId + "_key" + j;
						Concurrency.putRW(rwlock, map, key, threadId * 1000 + j);
					}
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				} finally {
					doneLatch.countDown();
				}
			});
		}

		startLatch.countDown();
		doneLatch.await();
		executor.shutdown();

		assertEquals(threadCount * operationsPerThread, map.size());
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	void testConcurrentComputeIfAbsent() throws InterruptedException {
		final ReentrantReadWriteLock rwlock = new ReentrantReadWriteLock();
		final Map<String, Integer> map = new ConcurrentHashMap<>();
		final AtomicInteger computeCount = new AtomicInteger(0);

		final int threadCount = 10;
		final ExecutorService executor = Executors.newFixedThreadPool(threadCount);
		final CountDownLatch startLatch = new CountDownLatch(1);
		final CountDownLatch doneLatch = new CountDownLatch(threadCount);

		final Function<String, Integer> computer = key -> {
			computeCount.incrementAndGet();
			return 42;
		};

		for (int i = 0; i < threadCount; i++) {
			executor.submit(() -> {
				try {
					startLatch.await();
					final Integer result = Concurrency.computeIfAbsentRW(rwlock, map, "shared_key", computer);
					assertEquals(42, result);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				} finally {
					doneLatch.countDown();
				}
			});
		}

		startLatch.countDown();
		doneLatch.await();
		executor.shutdown();

		assertEquals(1, map.size());
		assertEquals(1, computeCount.get());
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	void testConcurrentReadWriteMixed() throws InterruptedException {
		final ReentrantReadWriteLock rwlock = new ReentrantReadWriteLock();
		final Map<String, Integer> map = new ConcurrentHashMap<>();
		map.put("counter", 0);

		final int threadCount = 20;
		final int operationsPerThread = 100;
		final ExecutorService executor = Executors.newFixedThreadPool(threadCount);
		final CountDownLatch startLatch = new CountDownLatch(1);
		final CountDownLatch doneLatch = new CountDownLatch(threadCount);
		final AtomicInteger readCount = new AtomicInteger(0);
		final AtomicInteger writeCount = new AtomicInteger(0);

		for (int i = 0; i < threadCount; i++) {
			final boolean isWriter = i % 2 == 0;
			executor.submit(() -> {
				try {
					startLatch.await();
					for (int j = 0; j < operationsPerThread; j++) {
						if (isWriter) {
							Concurrency.putRW(rwlock, map, "key" + j, j);
							writeCount.incrementAndGet();
						} else {
							Concurrency.getRW(rwlock, map, "key" + (j % 50));
							readCount.incrementAndGet();
						}
					}
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				} finally {
					doneLatch.countDown();
				}
			});
		}

		startLatch.countDown();
		doneLatch.await();
		executor.shutdown();

		assertEquals((threadCount / 2) * operationsPerThread, readCount.get());
		assertEquals((threadCount / 2) * operationsPerThread, writeCount.get());
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	void testThreadSafeListOperations() throws InterruptedException {
		final ReentrantReadWriteLock rwlock = new ReentrantReadWriteLock();
		final List<Integer> list = Collections.synchronizedList(new ArrayList<>());

		final int threadCount = 10;
		final int operationsPerThread = 100;
		final ExecutorService executor = Executors.newFixedThreadPool(threadCount);
		final CountDownLatch startLatch = new CountDownLatch(1);
		final CountDownLatch doneLatch = new CountDownLatch(threadCount);

		for (int i = 0; i < threadCount; i++) {
			final int threadId = i;
			executor.submit(() -> {
				try {
					startLatch.await();
					for (int j = 0; j < operationsPerThread; j++) {
						Concurrency.addRW(rwlock, list, threadId * 1000 + j);
					}
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				} finally {
					doneLatch.countDown();
				}
			});
		}

		startLatch.countDown();
		doneLatch.await();
		executor.shutdown();

		assertEquals(threadCount * operationsPerThread, list.size());
	}
}
