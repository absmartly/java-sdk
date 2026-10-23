package com.absmartly.sdk.internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

// A switchback cluster attribute path, resolved exactly like the Collector's
// SwitchbackAttributePath: "/" separates segments, a segment wrapped in ' or " is a literal key,
// and an unquoted segment that Guava Ints.tryParse accepts (ASCII digits, optional leading '-')
// indexes a List and nothing else. Every other segment reads a Map key.
public final class SwitchbackAttributePath {
	private static final ObjectMapper JSON = new ObjectMapper();

	private final List<String> keys;
	// Parallel to keys; null where the segment is a map key.
	private final List<Integer> indices;

	private SwitchbackAttributePath(final List<String> keys, final List<Integer> indices) {
		this.keys = keys;
		this.indices = indices;
	}

	// Returns null for an unsupported path: empty, or an array index that is negative, first,
	// last, or directly after another index. Analysis cannot read those.
	public static SwitchbackAttributePath parseSupported(final String path) {
		if (path == null) {
			return null;
		}
		final SwitchbackAttributePath parsed = parse(path);
		final int n = parsed.keys.size();
		if (n == 0) {
			return null;
		}
		for (int i = 0; i < n; ++i) {
			final Integer index = parsed.indices.get(i);
			if ((index != null) && ((index < 0) || (i == 0) || (i == n - 1) || (parsed.indices.get(i - 1) != null))) {
				return null;
			}
		}
		return parsed;
	}

	// The attribute name the path starts at; a supported path always starts at a key segment.
	public String rootKey() {
		return keys.get(0);
	}

	public Object resolve(final Map<String, Object> attributes) {
		return resolveFrom(attributes, 0);
	}

	// Resolves the path below rootKey() in an SDK attribute value, read as the Collector reads it:
	// the value's JSON encoding decoded by Jackson into Map, List, String, Boolean, Integer, Long,
	// BigInteger or Double. Returns null when the value has no JSON encoding.
	public Object resolveAttribute(final Object rootValue) {
		if (rootValue == null) {
			return null;
		}
		final Object decoded;
		if ((rootValue instanceof String) || (rootValue instanceof Boolean) || (rootValue instanceof Integer)
				|| (rootValue instanceof Long)) {
			// Each of these decodes back to an equal value of the same type.
			decoded = rootValue;
		} else {
			try {
				decoded = JSON.readValue(JSON.writeValueAsBytes(rootValue), Object.class);
			} catch (Exception e) {
				return null;
			} catch (StackOverflowError e) {
				// A self-referencing Map or List.
				return null;
			}
		}
		return resolveFrom(decoded, 1);
	}

	private Object resolveFrom(final Object root, final int first) {
		Object current = root;
		for (int i = first; i < keys.size(); ++i) {
			current = step(current, keys.get(i), indices.get(i));
			if (current == null) {
				return null;
			}
		}
		return current;
	}

	private static SwitchbackAttributePath parse(final String path) {
		final List<String> keys = new ArrayList<String>();
		final List<Integer> indices = new ArrayList<Integer>();
		int i = 0;
		while (i < path.length()) {
			if (path.charAt(i) == '/') {
				++i;
				continue;
			}

			final char first = path.charAt(i);
			if ((first == '\'') || (first == '"')) {
				final int end = path.indexOf(first, i + 1);
				if (end < 0) {
					// The platform's JsonPathParser drops an unterminated quoted tail.
					break;
				}
				keys.add(path.substring(i + 1, end));
				indices.add(null);
				i = end + 1;
			} else {
				final int end = path.indexOf('/', i);
				final int stop = (end < 0) ? path.length() : end;
				final String key = path.substring(i, stop);
				keys.add(key);
				indices.add(tryParseInt(key));
				i = stop;
			}
		}
		return new SwitchbackAttributePath(Collections.unmodifiableList(keys), Collections.unmodifiableList(indices));
	}

	private static Object step(final Object current, final String key, final Integer index) {
		if (index != null) {
			if (current instanceof List) {
				final List<?> list = (List<?>) current;
				return ((index >= 0) && (index < list.size())) ? list.get(index) : null;
			}
			return null;
		}
		if (current instanceof Map) {
			return ((Map<?, ?>) current).get(key);
		}
		return null;
	}

	// Guava Ints.tryParse(String): optional '-', then one or more ASCII digits, within int range;
	// otherwise null. "+1" and non-ASCII digits are not numbers; "-0" is 0.
	static Integer tryParseInt(final String s) {
		final int length = s.length();
		final boolean negative = (length > 0) && (s.charAt(0) == '-');
		int i = negative ? 1 : 0;
		if (i == length) {
			return null;
		}
		long magnitude = 0;
		for (; i < length; ++i) {
			final char c = s.charAt(i);
			if ((c < '0') || (c > '9')) {
				return null;
			}
			magnitude = magnitude * 10 + (c - '0');
			if (magnitude > (1L << 31)) {
				return null;
			}
		}
		final long value = negative ? -magnitude : magnitude;
		return (value > Integer.MAX_VALUE) ? null : Integer.valueOf((int) value);
	}
}
