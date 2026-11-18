package com.absmartly.sdk.internal;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/** Snapshots JSON containers without retaining mutable maps, lists or arrays supplied by a caller. */
public final class JsonValues {
	private JsonValues() {}

	public static Map<String, Object> snapshot(final Map<String, ?> values) {
		final Map<String, Object> result = new LinkedHashMap<String, Object>();
		for (final Map.Entry<String, ?> entry : values.entrySet()) {
			result.put(entry.getKey(), snapshotValue(entry.getValue()));
		}
		return Collections.unmodifiableMap(result);
	}

	public static @Nullable Object snapshotValue(final @Nullable Object value) {
		return snapshotValue(value, new java.util.IdentityHashMap<Object, Boolean>());
	}

	private static @Nullable Object snapshotValue(final @Nullable Object value,
			final java.util.IdentityHashMap<Object, Boolean> ancestors) {
		if (value == null) {
			return null;
		}
		// Cyclic inputs are not JSON; keep the cycle for the evaluator/serializer to reject as before.
		if (ancestors.put(value, Boolean.TRUE) != null) {
			return value;
		}
		try {
			if (value instanceof Map) {
				final Map<Object, Object> result = new LinkedHashMap<Object, Object>();
				for (final Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
					result.put(entry.getKey(), snapshotValue(entry.getValue(), ancestors));
				}
				return Collections.unmodifiableMap(result);
			}
			if ((value instanceof Iterable) || ((value != null) && value.getClass().isArray())) {
				final List<Object> result = new ArrayList<Object>();
				if (value instanceof Iterable) {
					for (final Object item : (Iterable<?>) value) {
						result.add(snapshotValue(item, ancestors));
					}
				} else {
					for (int i = 0; i < Array.getLength(value); ++i) {
						result.add(snapshotValue(Array.get(value, i), ancestors));
					}
				}
				return Collections.unmodifiableList(result);
			}
			return value;
		} finally {
			ancestors.remove(value);
		}
	}
}
