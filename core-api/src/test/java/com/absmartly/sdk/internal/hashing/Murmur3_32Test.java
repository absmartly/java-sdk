package com.absmartly.sdk.internal.hashing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.absmartly.sdk.TestUtils;
import com.absmartly.sdk.java.nio.charset.StandardCharsets;

class Murmur3_32Test extends TestUtils {
	@Test
	void testDigest() {
		final List<List<Object>> testCases = listOf(
				listOf("", 0x00000000, 0x00000000),
				listOf(" ", 0x00000000, 0x7ef49b98),
				listOf("t", 0x00000000, 0xca87df4d),
				listOf("te", 0x00000000, 0xedb8ee1b),
				listOf("tes", 0x00000000, 0x0bb90e5a),
				listOf("test", 0x00000000, 0xba6bd213),
				listOf("testy", 0x00000000, 0x44af8342),
				listOf("testy1", 0x00000000, 0x8a1a243a),
				listOf("testy12", 0x00000000, 0x845461b9),
				listOf("testy123", 0x00000000, 0x47628ac4),
				listOf("special characters açb↓c", 0x00000000, 0xbe83b140),
				listOf("The quick brown fox jumps over the lazy dog", 0x00000000, 0x2e4ff723),
				listOf("", 0xdeadbeef, 0x0de5c6a9),
				listOf(" ", 0xdeadbeef, 0x25acce43),
				listOf("t", 0xdeadbeef, 0x3b15dcf8),
				listOf("te", 0xdeadbeef, 0xac981332),
				listOf("tes", 0xdeadbeef, 0xc1c78dda),
				listOf("test", 0xdeadbeef, 0xaa22d41a),
				listOf("testy", 0xdeadbeef, 0x84f5f623),
				listOf("testy1", 0xdeadbeef, 0x09ed28e9),
				listOf("testy12", 0xdeadbeef, 0x22467835),
				listOf("testy123", 0xdeadbeef, 0xd633060d),
				listOf("special characters açb↓c", 0xdeadbeef, 0xf7fdd8a2),
				listOf("The quick brown fox jumps over the lazy dog", 0xdeadbeef, 0x3a7b3f4d),
				listOf("", 0x00000001, 0x514e28b7),
				listOf(" ", 0x00000001, 0x4f0f7132),
				listOf("t", 0x00000001, 0x5db1831e),
				listOf("te", 0x00000001, 0xd248bb2e),
				listOf("tes", 0x00000001, 0xd432eb74),
				listOf("test", 0x00000001, 0x99c02ae2),
				listOf("testy", 0x00000001, 0xc5b2dc1e),
				listOf("testy1", 0x00000001, 0x33925ceb),
				listOf("testy12", 0x00000001, 0xd92c9f23),
				listOf("testy123", 0x00000001, 0x3bc1712d),
				listOf("special characters açb↓c", 0x00000001, 0x293327b5),
				listOf("The quick brown fox jumps over the lazy dog", 0x00000001, 0x78e69e27));

		for (final List<Object> testCase : testCases) {
			final byte[] key = ((String) testCase.get(0)).getBytes(StandardCharsets.UTF_8);

			final int actual = Murmur3_32.digest(key, (int) testCase.get(1));
			final int expected = (int) testCase.get(2);
			assertEquals(expected, actual);

			final byte[] keyOffset = ("123" + testCase.get(0) + "321").getBytes(StandardCharsets.UTF_8);
			final int actualOffset = Murmur3_32.digest(keyOffset, 3, keyOffset.length - 6, (int) testCase.get(1));
			assertEquals(expected, actualOffset);
		}
	}
}
