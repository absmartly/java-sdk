package com.absmartly.sdk.internal;

import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java8.util.function.Function;

public class Concurrency {
	static public <K, V> V computeIfAbsentRW(ReentrantReadWriteLock lock, Map<K, V> map, K key,
			Function<K, V> computer) {
		final ReentrantReadWriteLock.ReadLock readLock = lock.readLock();
		try {
			readLock.lock();
			final V value = map.get(key);
			if (value != null) {
				return value;
			}
		} finally {
			readLock.unlock();
		}

		final ReentrantReadWriteLock.WriteLock writeLock = lock.writeLock();
		try {
			writeLock.lock();
			final V value = map.get(key); // double check
			if (value != null) {
				return value;
			}

			final V newValue = computer.apply(key);
			map.put(key, newValue);
			return newValue;
		} finally {
			writeLock.unlock();
		}
	}

	static public <K, V> V getRW(ReentrantReadWriteLock lock, Map<K, V> map, K key) {
		final ReentrantReadWriteLock.ReadLock readLock = lock.readLock();
		try {
			readLock.lock();
			return map.get(key);
		} finally {
			readLock.unlock();
		}
	}

	static public <K, V> V putRW(ReentrantReadWriteLock lock, Map<K, V> map, K key, V value) {
		final ReentrantReadWriteLock.WriteLock writeLock = lock.writeLock();
		try {
			writeLock.lock();
			return map.put(key, value);
		} finally {
			writeLock.unlock();
		}
	}
}
