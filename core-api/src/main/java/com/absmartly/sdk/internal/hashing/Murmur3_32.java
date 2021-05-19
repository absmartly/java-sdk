package com.absmartly.sdk.internal.hashing;

import com.absmartly.sdk.internal.Buffers;

public abstract class Murmur3_32 {
	private Murmur3_32() {}

	public static int digest(byte[] key, int seed) {
		return digest(key, 0, key.length, seed);
	}

	public static int digest(byte[] key, int offset, int len, int seed) {
		final int n = offset + (len & ~3);

		int hash = seed;
		int i = offset;
		for (; i < n; i += 4) {
			final int chunk = Buffers.getUInt32(key, i);
			hash ^= scramble32(chunk);
			hash = Integer.rotateLeft(hash, 13);
			hash = (hash * 5) + 0xe6546b64;
		}

		switch (len & 3) {
		case 3:
			hash ^= scramble32(Buffers.getUInt24(key, i));
			break;
		case 2:
			hash ^= scramble32(Buffers.getUInt16(key, i));
			break;
		case 1:
			hash ^= scramble32(Buffers.getUInt8(key, i));
		default:
			break;
		}

		hash ^= len;
		hash = fmix32(hash);
		return hash;
	}

	private static int fmix32(int h) {
		h ^= h >>> 16;
		h = h * 0x85ebca6b;
		h ^= h >>> 13;
		h = h * 0xc2b2ae35;
		h ^= h >>> 16;

		return h;
	}

	private static int scramble32(int block) {
		return Integer.rotateLeft(block * 0xcc9e2d51, 15) * 0x1b873593;
	}
}
