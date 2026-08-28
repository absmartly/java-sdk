package com.absmartly.sdk.internal.hashing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.absmartly.sdk.java.nio.charset.StandardCharsets;

class HashingTest {
	@Test
	void testHashUnit() {
		assertEquals("H2jvj6o9YcAgNdhKqEbtWw",
				new String(Hashing.hashUnit("4a42766ca6313d26f49985e799ff4f3790fb86efa0fce46edb3ea8fbf1ea3408"),
						StandardCharsets.US_ASCII));
		assertEquals("DRgslOje35bZMmpaohQjkA",
				new String(Hashing.hashUnit("bleh@absmarty.com"), StandardCharsets.US_ASCII));
		assertEquals("LxcqH5VC15rXfWfA_smreg", new String(Hashing.hashUnit("açb↓c"), StandardCharsets.US_ASCII));
		assertEquals("K5I_V6RgP8c6sYKz-TVn8g", new String(Hashing.hashUnit("testy"), StandardCharsets.US_ASCII));
		assertEquals("K4uy4bTeCy34W97lmceVRg",
				new String(Hashing.hashUnit(Long.toString(123456778999L)), StandardCharsets.US_ASCII));
	}

	@Test
	void testHashUnitLarge() {
		final String chars = "4a42766ca6313d26f49985e799ff4f3790fb86efa0fce46edb3ea8fbf1ea3408";
		final StringBuilder sb = new StringBuilder();

		final int count = (2048 + chars.length() - 1) / chars.length();
		for (int i = 0; i < count; ++i) {
			sb.append(chars);
		}

		assertEquals("Rxnq-eM9eE1SEoMnkEMOIw", new String(Hashing.hashUnit(sb.toString()), StandardCharsets.US_ASCII));
	}

	@Test
	void testHashUnitAstralAndMultibyte() {
		// These vectors pin canonical UTF-8 encoding: surrogate pairs must produce 4-byte sequences.
		assertEquals("KgLqw51xanDs83V5GFkntg",
				new String(Hashing.hashUnit("😀"), StandardCharsets.US_ASCII));
		assertEquals("ZJuDalvUWRJnVtkspj-2bQ",
				new String(Hashing.hashUnit("😀😁"), StandardCharsets.US_ASCII));
		assertEquals("v2CJG7YcjjWncKOSCzF2GA",
				new String(Hashing.hashUnit("世界你好"), StandardCharsets.US_ASCII));
		assertEquals("SCgk4OzXlFMvo1UMsP88fA",
				new String(Hashing.hashUnit("user_世界_123"), StandardCharsets.US_ASCII));
	}
}
