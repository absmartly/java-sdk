package com.absmartly.sdk.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.*;

import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java8.util.function.Function;

import org.junit.jupiter.api.Test;
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

		verify(map, times(2)).get(any());
		verify(map, times(2)).get(1);

		verify(rwlock, times(1)).readLock();
		verify(rwlock, times(1)).writeLock();
		verify(rlock, times(1)).lock();
		verify(rlock, times(1)).unlock();
		verify(wlock, times(1)).lock();
		verify(wlock, times(1)).unlock();

		verify(computer, times(1)).apply(any());
		verify(computer, times(1)).apply(1);
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

		verify(map, times(1)).get(any());
		verify(map, times(1)).get(1);

		verify(rwlock, times(1)).readLock();
		verify(rwlock, times(0)).writeLock();
		verify(rlock, times(1)).lock();
		verify(rlock, times(1)).unlock();

		verify(computer, times(0)).apply(any());
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

		verify(map, times(2)).get(any());
		verify(map, times(2)).get(1);

		verify(rwlock, times(1)).readLock();
		verify(rwlock, times(1)).writeLock();
		verify(rlock, times(1)).lock();
		verify(rlock, times(1)).unlock();
		verify(wlock, times(1)).lock();
		verify(wlock, times(1)).unlock();

		verify(computer, times(0)).apply(any());
	}

	@Test
	void getRW() {
		final Map<Integer, Integer> map = mock(Map.class);
		final ReentrantReadWriteLock.ReadLock lock = mock(ReentrantReadWriteLock.ReadLock.class);
		final ReentrantReadWriteLock rwlock = mock(ReentrantReadWriteLock.class);
		when(rwlock.readLock()).thenReturn(lock);

		final Integer result = Concurrency.getRW(rwlock, map, 1);
		assertNull(result);

		verify(map, times(1)).get(any());
		verify(map, times(1)).get(1);

		verify(rwlock, times(1)).readLock();
		verify(rwlock, times(0)).writeLock();
		verify(lock, times(1)).lock();
		verify(lock, times(1)).unlock();
	}

	@Test
	void putRW() {
		final Map<Integer, Integer> map = mock(Map.class);
		final ReentrantReadWriteLock.WriteLock lock = mock(ReentrantReadWriteLock.WriteLock.class);
		final ReentrantReadWriteLock rwlock = mock(ReentrantReadWriteLock.class);
		when(rwlock.writeLock()).thenReturn(lock);

		final Integer result = Concurrency.putRW(rwlock, map, 1, 5);
		assertNull(result);

		verify(map, times(1)).put(any(), any());
		verify(map, times(1)).put(1, 5);

		verify(rwlock, times(0)).readLock();
		verify(rwlock, times(1)).writeLock();
		verify(lock, times(1)).lock();
		verify(lock, times(1)).unlock();
	}
}
