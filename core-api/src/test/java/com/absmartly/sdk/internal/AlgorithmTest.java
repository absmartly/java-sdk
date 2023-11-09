package com.absmartly.sdk.internal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.mockito.Mockito.*;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java8.util.function.Function;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import com.absmartly.sdk.TestUtils;

class AlgorithmTest extends TestUtils {
	@Test
	void mapSetToArray() {
		final Function<Integer, Integer> square = mock(Function.class, withSettings().defaultAnswer(new Answer() {
			@Override
			public Object answer(InvocationOnMock invocation) throws Throwable {
				final Integer arg = (Integer) invocation.getArgument(0);
				return arg * arg;
			}
		}));

		final Set<Integer> ints = new HashSet<Integer>(Arrays.asList(1, 2, 3, 4, 5));
		final Integer[] expected = new Integer[]{1, 4, 9, 16, 25};
		final Integer[] actual = Algorithm.mapSetToArray(ints, new Integer[0], square);

		assertArrayEquals(expected, actual);
		verify(square, Mockito.timeout(5000).times(5)).apply(anyInt());
	}

	@Test
	void mapSetToArraySameSizeArray() {
		final Function<Integer, Integer> square = mock(Function.class, withSettings().defaultAnswer(new Answer() {
			@Override
			public Object answer(InvocationOnMock invocation) throws Throwable {
				final Integer arg = (Integer) invocation.getArgument(0);
				return arg * arg;
			}
		}));

		final Set<Integer> ints = new HashSet<Integer>(Arrays.asList(1, 2, 3, 4, 5));
		final Integer[] expected = new Integer[]{1, 4, 9, 16, 25};
		final Integer[] actual = Algorithm.mapSetToArray(ints, new Integer[]{0, 0, 0, 0, 0}, square);

		assertArrayEquals(expected, actual);
		verify(square, Mockito.timeout(5000).times(5)).apply(anyInt());
	}

	@Test
	void mapSetToArrayLargerArray() {
		final Function<Integer, Integer> square = mock(Function.class, withSettings().defaultAnswer(new Answer() {
			@Override
			public Object answer(InvocationOnMock invocation) throws Throwable {
				final Integer arg = (Integer) invocation.getArgument(0);
				return arg * arg;
			}
		}));

		final Set<Integer> ints = new HashSet<Integer>(Arrays.asList(1, 2, 3, 4, 5));
		final Integer[] expected = new Integer[]{1, 4, 9, 16, 25, null, 0};
		final Integer[] actual = Algorithm.mapSetToArray(ints, new Integer[]{0, 0, 0, 0, 0, 0, 0}, square);

		assertArrayEquals(expected, actual);
		verify(square, Mockito.timeout(5000).times(5)).apply(anyInt());
	}
}
