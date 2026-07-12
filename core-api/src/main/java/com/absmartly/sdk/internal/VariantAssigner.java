package com.absmartly.sdk.internal;

import com.absmartly.sdk.internal.hashing.Murmur3_32;

public class VariantAssigner {
	private static final ThreadLocal<byte[]> threadBuffer = new ThreadLocal<byte[]>() {
		@Override
		public byte[] initialValue() {
			return new byte[12];
		}
	};
	// Convert an unsigned 32-bit hash to [0, 1). Dividing by 0xffffffff would
	// produce exactly 1.0 for one hash value, incorrectly missing a 100% split.
	private static final double normalizer = 1.0 / 0x100000000L;

	public VariantAssigner(byte[] unitHash) {
		unitHash_ = Murmur3_32.digest(unitHash, 0);
	}

	public int assign(double[] split, int seedHi, int seedLo) {
		final double prob = probability(seedHi, seedLo);
		return chooseVariant(split, prob);
	}

	public static int chooseVariant(double[] split, double prob) {
		double cumSum = 0.0;
		for (int i = 0; i < split.length; ++i) {
			cumSum += split[i];
			if (prob < cumSum) {
				return i;
			}
		}

		return split.length - 1;
	}

	private double probability(int seedHi, int seedLo) {
		final byte[] buffer = threadBuffer.get();

		Buffers.putUInt32(buffer, 0, seedLo);
		Buffers.putUInt32(buffer, 4, seedHi);
		Buffers.putUInt32(buffer, 8, unitHash_);

		final int hash = Murmur3_32.digest(buffer, 0);
		return normalizeHash(hash);
	}

	static double normalizeHash(int hash) {
		return (hash & 0xffffffffL) * normalizer;
	}

	private final int unitHash_;
}
