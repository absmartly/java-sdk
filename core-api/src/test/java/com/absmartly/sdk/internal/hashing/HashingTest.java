package com.absmartly.sdk.internal.hashing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class HashingTest {
	@Test
	void hashUnit() {
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
	void hashUnitLarge() {
		final String chars = "4a42766ca6313d26f49985e799ff4f3790fb86efa0fce46edb3ea8fbf1ea3408";
		final StringBuilder sb = new StringBuilder();

		final int count = (2048 + chars.length() - 1) / chars.length();
		for (int i = 0; i < count; ++i) {
			sb.append(chars);
		}

		assertEquals("Rxnq-eM9eE1SEoMnkEMOIw", new String(Hashing.hashUnit(sb.toString()), StandardCharsets.US_ASCII));
	}
}
