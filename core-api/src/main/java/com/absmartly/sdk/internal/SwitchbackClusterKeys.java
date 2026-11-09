package com.absmartly.sdk.internal;

import java.math.BigInteger;

// Port of the Collector's SwitchbackClusterKeys.normalize: the cluster key an attribute value
// yields, or null when it yields none. A non-empty String is the key as-is; a Byte, Short,
// Integer, Long or BigInteger is its base-10 text. Null, empty strings, booleans, floating-point
// numbers, objects and arrays yield no key. The key is passed to SwitchbackAssigner unhashed.
public abstract class SwitchbackClusterKeys {
	private SwitchbackClusterKeys() {}

	public static String normalize(final Object value) {
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
