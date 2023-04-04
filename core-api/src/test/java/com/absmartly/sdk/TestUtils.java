package com.absmartly.sdk;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;
import java8.util.concurrent.CompletableFuture;

public abstract class TestUtils {
	public static byte[] getResourceBytes(String resourceName) {
		final ClassLoader classLoader = ContextDataSerializer.class.getClassLoader();
		final File resource = new File(Objects.requireNonNull(classLoader.getResource(resourceName)).getFile());
		try (final FileInputStream inputStream = new FileInputStream(resource.getAbsolutePath())) {
			final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

			final byte[] data = new byte[16384];

			int read;
			while ((read = inputStream.read(data, 0, data.length)) != -1) {
				bytes.write(data, 0, read);
			}

			return bytes.toByteArray();
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	public static <K, V> Map<K, V> mapOf() {
		final HashMap<K, V> hashMap = new HashMap<>(0);
		return hashMap;
	}

	public static <K, V> Map<K, V> mapOf(K k1, V v1) {
		final var hashMap = new HashMap<K, V>(1);
		hashMap.put(k1, v1);
		return hashMap;
	}

	public static <K, V> Map<K, V> mapOf(K k1, V v1, K k2, V v2) {
		final HashMap<K, V> hashMap = new HashMap<>(2);
		hashMap.put(k1, v1);
		hashMap.put(k2, v2);
		return hashMap;
	}

	public static <K, V> Map<K, V> mapOf(K k1, V v1, K k2, V v2, K k3, V v3) {
		final HashMap<K, V> hashMap = new HashMap<>(3);
		hashMap.put(k1, v1);
		hashMap.put(k2, v2);
		hashMap.put(k3, v3);
		return hashMap;
	}

	public static <K, V> Map<K, V> mapOf(K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4) {
		final HashMap<K, V> hashMap = new HashMap<>(4);
		hashMap.put(k1, v1);
		hashMap.put(k2, v2);
		hashMap.put(k3, v3);
		hashMap.put(k4, v4);
		return hashMap;
	}

	public static <K, V> Map<K, V> mapOf(K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4, K k5, V v5) {
		final HashMap<K, V> hashMap = new HashMap<>(5);
		hashMap.put(k1, v1);
		hashMap.put(k2, v2);
		hashMap.put(k3, v3);
		hashMap.put(k4, v4);
		hashMap.put(k5, v5);
		return hashMap;
	}

	public static <K, V> Map<K, V> mapOf(K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4, K k5, V v5, K k6, V v6) {
		final HashMap<K, V> hashMap = new HashMap<>(6);
		hashMap.put(k1, v1);
		hashMap.put(k2, v2);
		hashMap.put(k3, v3);
		hashMap.put(k4, v4);
		hashMap.put(k5, v5);
		hashMap.put(k6, v6);
		return hashMap;
	}

	public static <K, V> Map<K, V> mapOf(K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4, K k5, V v5, K k6, V v6, K k7,
			V v7) {
		final HashMap<K, V> hashMap = new HashMap<>(7);
		hashMap.put(k1, v1);
		hashMap.put(k2, v2);
		hashMap.put(k3, v3);
		hashMap.put(k4, v4);
		hashMap.put(k5, v5);
		hashMap.put(k6, v6);
		hashMap.put(k7, v7);
		return hashMap;
	}

	@SafeVarargs
	@SuppressWarnings("varargs")
	public static <T> List<T> listOf(T... elements) {
		if (elements.length > 0) {
			return Arrays.asList(elements);
		}
		return Collections.emptyList();
	}

	@SafeVarargs
	@SuppressWarnings("varargs")
	public static <T> Set<T> setOf(T... elements) {
		if (elements.length > 0) {
			return Arrays.stream(elements).collect(Collectors.toSet());
		}
		return Collections.emptySet();
	}

	public static <T> CompletableFuture<T> failedFuture(Throwable e) {
		final CompletableFuture<T> future = new CompletableFuture<>();
		future.completeExceptionally(e);
		return future;
	}
}
