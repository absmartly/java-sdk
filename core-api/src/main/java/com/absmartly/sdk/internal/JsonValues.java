package com.absmartly.sdk.internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * Snapshots the JSON containers a caller passes as attribute or goal values, so later changes to them cannot reach
 * a queued publish or an event. Only Map and List are copied (into unmodifiable copies, recursively); every other
 * value, including sets, arrays and other objects, is kept as it was given, so audiences and serialization see the
 * same types as in 1.x.
 */
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
		if (!(value instanceof Map) && !(value instanceof List)) {
			return value;
		}
		return snapshotContainer(value, new IdentityHashMap<Object, Boolean>());
	}

	private static @Nullable Object snapshotContainer(final @Nullable Object value,
			final IdentityHashMap<Object, Boolean> ancestors) {
		if (!(value instanceof Map) && !(value instanceof List)) {
			return value;
		}
		// Cyclic inputs are not JSON; keep the cycle for the evaluator/serializer to reject as before.
		if (ancestors.put(value, Boolean.TRUE) != null) {
			return value;
		}
		try {
			if (value instanceof Map) {
				final Map<Object, @Nullable Object> result = new LinkedHashMap<Object, @Nullable Object>();
				for (final Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
					result.put(entry.getKey(), snapshotContainer(entry.getValue(), ancestors));
				}
				return Collections.unmodifiableMap(result);
			}
			final List<@Nullable Object> result = new ArrayList<@Nullable Object>(((List<?>) value).size());
			for (final Object item : (List<?>) value) {
				result.add(snapshotContainer(item, ancestors));
			}
			return Collections.unmodifiableList(result);
		} finally {
			ancestors.remove(value);
		}
	}
}
