package com.absmartly.sdk.internal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.absmartly.sdk.TestUtils;

class BuffersTest extends TestUtils {
	@Test
	void putUInt32() {
		final int bytesLen = 9;
		for (int i = 0; i < bytesLen - 3; ++i) {
			final byte[] bytes = new byte[bytesLen];

			final int expected = (i + 1) * 0xe6546b64;
			Buffers.putUInt32(bytes, i, expected);
			final int actual = Buffers.getUInt32(bytes, i);
			assertEquals(expected, actual);

			for (int b = 0; b < bytesLen; ++b) {
				if (b < i || b >= (i + 4)) {
					assertEquals(0, bytes[b]);
				}
			}
		}
	}

	@Test
	void getUInt32() {
		final byte[] bytes = new byte[]{97, (byte) 226, (byte) 134, (byte) 147, 98, (byte) 196, 0};
		for (int i = 0; i < bytes.length - 3; ++i) {
			assertEquals((bytes[i] & 0xff) | ((bytes[i + 1] & 0xff) << 8) | ((bytes[i + 2] & 0xff) << 16)
					| ((bytes[i + 3] & 0xff) << 24), Buffers.getUInt32(bytes, i));
		}
	}

	@Test
	void getUInt24() {
		final byte[] bytes = new byte[]{97, (byte) 226, (byte) 134, (byte) 147, 98, 0};
		for (int i = 0; i < bytes.length - 2; ++i) {
			assertEquals((bytes[i] & 0xff) | ((bytes[i + 1] & 0xff) << 8) | ((bytes[i + 2] & 0xff) << 16),
					Buffers.getUInt24(bytes, i));
		}
	}

	@Test
	void getUInt16() {
		final byte[] bytes = new byte[]{97, (byte) 226, (byte) 134, (byte) 147, 98, 0};
		for (int i = 0; i < bytes.length - 1; ++i) {
			assertEquals((bytes[i] & 0xff) | ((bytes[i + 1] & 0xff) << 8), Buffers.getUInt16(bytes, i));
		}
	}

	@Test
	void getUInt8() {
		final byte[] bytes = new byte[]{97, (byte) 226, (byte) 134, (byte) 147, 98, 0};
		for (int i = 0; i < bytes.length; ++i) {
			assertEquals(bytes[i] & 0xff, Buffers.getUInt8(bytes, i));
		}
	}

	@Test
	void encodeUTF8() {
		final List<List<Object>> testCases = listOf(
				listOf("", new byte[]{}),
				listOf(" ", new byte[]{32}),
				listOf("a", new byte[]{97}),
				listOf("ab", new byte[]{97, 98}),
				listOf("abc", new byte[]{97, 98, 99}),
				listOf("abcd", new byte[]{97, 98, 99, 100}),
				listOf("ç", new byte[]{(byte) 195, (byte) 167}),
				listOf("aç", new byte[]{97, (byte) 195, (byte) 167}),
				listOf("çb", new byte[]{(byte) 195, (byte) 167, 98}),
				listOf("açb", new byte[]{97, (byte) 195, (byte) 167, 98}),
				listOf("↓", new byte[]{(byte) 226, (byte) 134, (byte) 147}),
				listOf("a↓", new byte[]{97, (byte) 226, (byte) 134, (byte) 147}),
				listOf("↓b", new byte[]{(byte) 226, (byte) 134, (byte) 147, 98}),
				listOf("a↓b", new byte[]{97, (byte) 226, (byte) 134, (byte) 147, 98}),
				listOf("aç↓", new byte[]{97, (byte) 195, (byte) 167, (byte) 226, (byte) 134, (byte) 147}),
				listOf("aç↓b",
						new byte[]{97, (byte) 195, (byte) 167, (byte) 226, (byte) 134, (byte) 147, 98}),
				listOf("açb↓c",
						new byte[]{97, (byte) 195, (byte) 167, 98, (byte) 226, (byte) 134, (byte) 147, 99}));

		for (final List<Object> testCase : testCases) {
			final String value = (String) testCase.get(0);
			final byte[] expected = (byte[]) testCase.get(1);
			final byte[] actual = new byte[expected.length];
			final int encodeLength = Buffers.encodeUTF8(actual, 0, value);
			assertArrayEquals(expected, actual);
			assertEquals(expected.length, encodeLength);

			final byte[] actualOffset = new byte[3 + expected.length];
			final int encodeLengthOffset = Buffers.encodeUTF8(actualOffset, 3, value);
			assertArrayEquals(expected, Arrays.copyOfRange(actualOffset, 3, 3 + encodeLengthOffset));
			assertEquals(expected.length, encodeLengthOffset);
		}
	}
}
