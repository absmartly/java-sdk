package com.absmartly.sdk.internal.hashing;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.absmartly.sdk.TestUtils;

class MD5Test {
	@Test
	void digestBase64UrlNoPadding() {
		final List<List<String>> testCases = TestUtils.listOf(
				TestUtils.listOf("", "1B2M2Y8AsgTpgAmY7PhCfg"),
				TestUtils.listOf(" ", "chXunH2dwinSkhpA6JnsXw"),
				TestUtils.listOf("t", "41jvpIn1gGLxDdcxa2Vkng"),
				TestUtils.listOf("te", "Vp73JkK-D63XEdakaNaO4Q"),
				TestUtils.listOf("tes", "KLZi2IO212_Zbk3cXpungA"),
				TestUtils.listOf("test", "CY9rzUYh03PK3k6DJie09g"),
				TestUtils.listOf("testy", "K5I_V6RgP8c6sYKz-TVn8g"),
				TestUtils.listOf("testy1", "8fT8xGipOhPkZ2DncKU-1A"),
				TestUtils.listOf("testy12", "YqRAtOz000gIu61ErEH18A"),
				TestUtils.listOf("testy123", "pfV2H07L6WvdqlY0zHuYIw"),
				TestUtils.listOf("special characters açb↓c", "4PIrO7lKtTxOcj2eMYlG7A"),
				TestUtils.listOf("The quick brown fox jumps over the lazy dog", "nhB9nTcrtoJr2B01QqQZ1g"),
				TestUtils.listOf("The quick brown fox jumps over the lazy dog and eats a pie",
						"iM-8ECRrLUQzixl436y96A"),
				TestUtils.listOf(
						"Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt ut labore et dolore magna aliqua. Ut enim ad minim veniam, quis nostrud exercitation ullamco laboris nisi ut aliquip ex ea commodo consequat. Duis aute irure dolor in reprehenderit in voluptate velit esse cillum dolore eu fugiat nulla pariatur. Excepteur sint occaecat cupidatat non proident, sunt in culpa qui officia deserunt mollit anim id est laborum.",
						"24m7XOq4f5wPzCqzbBicLA"));

		for (List<String> testCase : testCases) {
			final byte[] key = testCase.get(0).getBytes(StandardCharsets.UTF_8);
			final byte[] actual = MD5.digestBase64UrlNoPadding(key);
			final byte[] expected = testCase.get(1).getBytes(StandardCharsets.US_ASCII);
			assertArrayEquals(expected, actual);

			final byte[] keyOffset = ("123" + testCase.get(0) + "321").getBytes(StandardCharsets.UTF_8);
			final byte[] actualOffset = MD5.digestBase64UrlNoPadding(keyOffset, 3, keyOffset.length - 6);
			assertArrayEquals(expected, actualOffset);

		}
	}
}
