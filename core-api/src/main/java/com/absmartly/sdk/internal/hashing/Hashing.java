package com.absmartly.sdk.internal.hashing;

import java.math.BigDecimal;
import java.math.MathContext;
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

	// Numeric unit UIDs hash like the JavaScript SDK's value.toFixed(0). JavaScript numbers are doubles,
	// so every numeric type is converted to double first (a long beyond 2^53 rounds as it would in
	// JavaScript), negative values that round to zero keep their sign ("-0"), and magnitudes of 1e21
	// or more use JavaScript's exponent notation ("1e+21").
	public static String unitToString(final Object uid) {
		if (uid instanceof String) {
			return (String) uid;
		}
		final double value = ((Number) uid).doubleValue();
		if (Double.isNaN(value)) {
			return "NaN";
		}
		if (Double.isInfinite(value)) {
			return value > 0 ? "Infinity" : "-Infinity";
		}
		final double magnitude = Math.abs(value);
		final String sign = value < 0 ? "-" : "";
		if (magnitude >= 1e21) {
			final BigDecimal shortest = shortestRoundTrip(magnitude).stripTrailingZeros();
			final String digits = shortest.unscaledValue().toString();
			final int exponent = digits.length() - 1 - shortest.scale();
			final String mantissa = digits.length() == 1 ? digits : digits.charAt(0) + "." + digits.substring(1);
			return sign + mantissa + "e+" + exponent;
		}
		return sign + new BigDecimal(magnitude).setScale(0, RoundingMode.HALF_UP).toPlainString();
	}

	// The fewest significant digits that read back as the same double, nearest to the exact value, as
	// JavaScript's Number-to-String conversion picks them. Double.toString is not shortest on every JDK
	// (JDK 11 gives 9.999999999999999E22 for 1e23).
	private static BigDecimal shortestRoundTrip(final double value) {
		final BigDecimal exact = new BigDecimal(value);
		for (int precision = 1; precision < 17; ++precision) {
			final BigDecimal candidate = exact.round(new MathContext(precision, RoundingMode.HALF_EVEN));
			if (candidate.doubleValue() == value) {
				return candidate;
			}
		}
		return exact.round(new MathContext(17, RoundingMode.HALF_EVEN));
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
