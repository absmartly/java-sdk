package com.absmartly.sdk.internal;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import com.absmartly.sdk.java.nio.charset.StandardCharsets;

// Hashed switchback cluster buckets. Port of the platform's
// com.absmartly.lib.utils.switchback.SwitchbackBuckets, which the Collector and the warehouse SQL
// also implement:
//   h      = MD5(utf8("sbcluster" + U+001F + seedHi + ":" + seedLo + U+001F + key))[0..3], big-endian, unsigned
//   bucket = h mod count
// The bucket, written in base 10, is the cluster passed to SwitchbackAssigner.assign. Any change
// here breaks parity with switchback/bucket-golden-vectors.json and with analysis.
public abstract class SwitchbackBuckets {
	private SwitchbackBuckets() {}

	private static final char SEPARATOR = '\u001f';

	private static final ThreadLocal<MessageDigest> MD5 = new ThreadLocal<MessageDigest>() {
		@Override
		protected MessageDigest initialValue() {
			try {
				return MessageDigest.getInstance("MD5");
			} catch (NoSuchAlgorithmException e) {
				// Every Java platform is required to provide MD5.
				throw new IllegalStateException(e);
			}
		}
	};

	public static boolean isAllowedCount(final Integer count) {
		return (count != null) && ((count == 10) || (count == 50) || (count == 100));
	}

	public static int bucketOf(final int seedHi, final int seedLo, final String key, final int count) {
		if (!isAllowedCount(count)) {
			throw new IllegalArgumentException("Switchback cluster count must be one of [10, 50, 100], got " + count);
		}
		final String input = new StringBuilder(key.length() + 34).append("sbcluster").append(SEPARATOR)
				.append(seedHi).append(':').append(seedLo).append(SEPARATOR).append(key).toString();
		// String.getBytes, not Buffers.encodeUTF8: the platform replaces an unpaired surrogate with
		// '?' and the hashed bytes must match it.
		final byte[] digest = MD5.get().digest(input.getBytes(StandardCharsets.UTF_8));
		final long hash = (((digest[0] & 0xffL) << 24) | ((digest[1] & 0xffL) << 16) | ((digest[2] & 0xffL) << 8)
				| (digest[3] & 0xffL));
		return (int) (hash % count);
	}

	// Bucket key of an attribute value, or null when the unit is ineligible: a non-empty String
	// as-is, an integral Byte/Short/Integer/Long/BigInteger in base 10, anything else null.
	public static String normalizeAttribute(final Object value) {
		if (value instanceof String) {
			final String s = (String) value;
			return s.isEmpty() ? null : s;
		} else if ((value instanceof Long) || (value instanceof Integer) || (value instanceof Short)
				|| (value instanceof Byte)) {
			return Long.toString(((Number) value).longValue());
		} else if (value instanceof BigInteger) {
			return value.toString();
		}
		return null;
	}
}
