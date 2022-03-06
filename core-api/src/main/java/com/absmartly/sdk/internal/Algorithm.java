package com.absmartly.sdk.internal;

import java.util.Set;
import java8.util.function.Function;

public class Algorithm {
	public static <T, R> R[] mapSetToArray(Set<T> set, R[] array, Function<T, R> mapper) {
		final int size = set.size();
		if (array.length < size) {
			array = (R[]) java.lang.reflect.Array.newInstance(array.getClass().getComponentType(), size);
		}

		if (array.length > size) {
			array[size] = null;
		}

		int index = 0;
		for (final T value : set) {
			array[index++] = mapper.apply(value);
		}
		return array;
	}
}
