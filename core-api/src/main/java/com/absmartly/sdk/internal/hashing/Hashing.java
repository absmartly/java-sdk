package com.absmartly.sdk.internal.hashing;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

import com.absmartly.sdk.internal.Buffers;

public abstract class Hashing {
	private Hashing() {}

	private static ThreadLocal<byte[]> threadBuffer = new ThreadLocal<byte[]>() {
		@Override
		public byte[] initialValue() {
			return new byte[512];
		}
	};

	public static byte[] hashUnit(Object unit) {
		return hashUnit(unitToString(unit));
	}

	// Numeric unit UIDs hash like the JavaScript SDK's value.toFixed(0): rounded to an integer, no
	// decimal point.
	public static String unitToString(final Object uid) {
		if (uid instanceof String) {
			return (String) uid;
		}
		if ((uid instanceof Integer) || (uid instanceof Long) || (uid instanceof Short) || (uid instanceof Byte)
				|| (uid instanceof BigInteger)) {
			return uid.toString();
		}
		if (uid instanceof BigDecimal) {
			return ((BigDecimal) uid).setScale(0, RoundingMode.HALF_UP).toPlainString();
		}
		final double value = ((Number) uid).doubleValue();
		if (Double.isNaN(value) || Double.isInfinite(value)) {
			return value > 0 ? "Infinity" : (value < 0 ? "-Infinity" : "NaN");
		}
		return new BigDecimal(value).setScale(0, RoundingMode.HALF_UP).toPlainString();
	}

	public static byte[] hashUnit(CharSequence unit) {
		final int n = unit.length();
		// Up to 4 UTF-8 bytes per UTF-16 code unit (3-byte BMP chars, and 4-byte
		// astral chars span two code units). n << 1 underflowed for 3-byte chars.
		final int bufferLen = n * 4;

		byte[] buffer = threadBuffer.get();
		if (buffer.length < bufferLen) {
			final int bit = 32 - Integer.numberOfLeadingZeros(bufferLen - 1);
			buffer = new byte[1 << bit];
			threadBuffer.set(buffer);
		}

		final int encoded = Buffers.encodeUTF8(buffer, 0, unit);
		return MD5.digestBase64UrlNoPadding(buffer, 0, encoded);
	}
}
