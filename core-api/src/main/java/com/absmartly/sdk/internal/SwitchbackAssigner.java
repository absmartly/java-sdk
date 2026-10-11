package com.absmartly.sdk.internal;

import com.absmartly.sdk.internal.hashing.Murmur3_32;

// Balanced-block switchback assignment. Port of the platform's
// com.absmartly.lib.analytics.switchback.SwitchbackAssignment (Guava murmur3_32_fixed, seed 0):
//   key       = utf8(clusterUnit + "\u001f" + cycleIndex)
//   baseHash  = murmur3_32(le64(seed) ++ le32(murmur3_32(key)))
//   stepHash  = murmur3_32(le32(baseHash) ++ le32(i)),  i = armCount-1 .. 1
//   j         = min(i, floor(u32(stepHash) / 0xffffffff * (i + 1)))
//   variant   = permutation[floorMod(periodIndex, armCount)], cycle = floorDiv(periodIndex, armCount)
// Any change here breaks parity with the Collector and the analysis-side expected schedule.
public abstract class SwitchbackAssigner {
	private SwitchbackAssigner() {}

	private static final double NORMALIZER = 1.0 / 0xffffffffL;
	private static final char SEPARATOR = '\u001f';

	public static int assign(final CharSequence clusterUnit, final long periodIndex, final long seed,
			final int armCount) {
		requireValidArmCount(armCount);
		final long cycleIndex = floorDiv(periodIndex, armCount);
		// floorMod; the product may wrap near Long.MIN_VALUE but the difference is exact mod 2^64.
		final int cycleSlot = (int) (periodIndex - cycleIndex * armCount);
		return fisherYatesPermutation(armCount, baseHash(clusterUnit, cycleIndex, seed))[cycleSlot];
	}

	public static int baseHash(final CharSequence clusterUnit, final long cycleIndex, final long seed) {
		final String key = new StringBuilder(clusterUnit.length() + 21).append(clusterUnit).append(SEPARATOR)
				.append(cycleIndex).toString();
		final byte[] keyBytes = new byte[key.length() * 4];
		final int keyLen = Buffers.encodeUTF8(keyBytes, 0, key);
		final int keyHash = Murmur3_32.digest(keyBytes, 0, keyLen, 0);

		final byte[] buffer = new byte[12];
		Buffers.putUInt32(buffer, 0, (int) seed);
		Buffers.putUInt32(buffer, 4, (int) (seed >>> 32));
		Buffers.putUInt32(buffer, 8, keyHash);
		return Murmur3_32.digest(buffer, 0);
	}

	public static int[] fisherYatesPermutation(final int armCount, final int baseHash) {
		requireValidArmCount(armCount);

		final int[] permutation = new int[armCount];
		for (int i = 0; i < armCount; ++i) {
			permutation[i] = i;
		}

		final byte[] buffer = new byte[8];
		Buffers.putUInt32(buffer, 0, baseHash);
		for (int i = armCount - 1; i > 0; --i) {
			Buffers.putUInt32(buffer, 4, i);
			final int stepHash = Murmur3_32.digest(buffer, 0);
			final double prob = (stepHash & 0xffffffffL) * NORMALIZER;
			// prob == 1.0 would map to i + 1
			final int j = Math.min(i, (int) Math.floor(prob * (i + 1)));

			final int tmp = permutation[i];
			permutation[i] = permutation[j];
			permutation[j] = tmp;
		}

		return permutation;
	}

	// Math.floorDiv is Java 8+; divisor is always positive here.
	public static long floorDiv(final long dividend, final long divisor) {
		final long q = dividend / divisor;
		return ((dividend % divisor) != 0 && ((dividend ^ divisor) < 0)) ? q - 1 : q;
	}

	private static void requireValidArmCount(final int armCount) {
		if (armCount < 2) {
			throw new IllegalArgumentException("armCount must be >= 2, got " + armCount);
		}
	}
}
