package com.absmartly.sdk;

import java.math.BigDecimal;
import java.math.BigInteger;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.absmartly.sdk.internal.Json;

/** Converts a variable's parsed JSON value to the type the caller asked for, falling back to the default. */
final class VariableConverter {
	private static final Logger log = LoggerFactory.getLogger(VariableConverter.class);

	private VariableConverter() {}

	// The type a default value asks for: its own class, except that a collection default whose class cannot be built
	// (Collections.emptyList(), List.of(...), unmodifiable views: no public no-argument constructor) asks for a
	// constructible class of the same shape, so the value is converted into something the default's type accepts. A
	// constructible collection class (TreeMap, LinkedList...) is kept as is.
	static Class<?> inferType(final Object defaultValue) {
		final Class<?> type = defaultValue.getClass();
		if (!(defaultValue instanceof java.util.Collection) && !(defaultValue instanceof java.util.Map)) {
			return type;
		}
		if (isConstructible(type)) {
			return type;
		}
		if (defaultValue instanceof java.util.SortedMap) {
			return java.util.TreeMap.class;
		}
		if (defaultValue instanceof java.util.Map) {
			return java.util.LinkedHashMap.class;
		}
		if (defaultValue instanceof java.util.SortedSet) {
			return java.util.TreeSet.class;
		}
		if (defaultValue instanceof java.util.Set) {
			return java.util.LinkedHashSet.class;
		}
		if (defaultValue instanceof java.util.List) {
			return java.util.ArrayList.class;
		}
		return type;
	}

	private static boolean isConstructible(final Class<?> type) {
		if (!java.lang.reflect.Modifier.isPublic(type.getModifiers())
				|| java.lang.reflect.Modifier.isAbstract(type.getModifiers())) {
			return false;
		}
		try {
			return java.lang.reflect.Modifier.isPublic(type.getConstructor().getModifiers());
		} catch (final NoSuchMethodException e) {
			return false;
		}
	}

	static <T> @Nullable T convert(final String key, final @Nullable Object value, final Class<T> type,
			final @Nullable T defaultValue) {
		if (value == null) {
			return defaultValue;
		}
		if (((value instanceof Double) || (value instanceof Float))
				&& !Double.isFinite(((Number) value).doubleValue())) {
			log.warn("ABsmartly variable '{}' is {}, not a finite number", key, value);
			return defaultValue;
		}
		if (type.isInstance(value)) {
			return type.cast(value);
		}
		try {
			final Object converted = convertKnown(value, type);
			if (converted != null) {
				return type.cast(converted);
			}
			if ((value instanceof java.util.Map) && !isScalarType(type)
					&& !java.util.Collection.class.isAssignableFrom(type)) {
				return Json.convert(value, type);
			}
			if ((value instanceof java.util.List) && java.util.Collection.class.isAssignableFrom(type)) {
				return Json.convert(value, type);
			}
		} catch (final RuntimeException e) {
			log.warn("ABsmartly variable '{}' cannot be read as {}: {}", key, type.getSimpleName(), e.getMessage());
			return defaultValue;
		}
		log.warn("ABsmartly variable '{}' is a {}, not a {}", key, value.getClass().getSimpleName(),
				type.getSimpleName());
		return defaultValue;
	}

	private static boolean isScalarType(final Class<?> type) {
		return (type == String.class) || (type == Boolean.class) || Number.class.isAssignableFrom(type);
	}

	// Numbers convert between numeric types when no precision is lost; nothing else is coerced.
	private static @Nullable Object convertKnown(final Object value, final Class<?> type) {
		if (!(value instanceof Number) || !Number.class.isAssignableFrom(type)) {
			return null;
		}
		final BigDecimal decimal = toBigDecimal((Number) value);
		// The exact value of the source: binary for a Double or Float, decimal otherwise.
		final BigDecimal exact = ((value instanceof Double) || (value instanceof Float))
				? new BigDecimal(((Number) value).doubleValue())
				: decimal;
		// A conversion is lossless when it keeps the exact value, or the same shortest decimal form (0.1 as a Float).
		if (type == Double.class) {
			final double converted = ((Number) value).doubleValue();
			if (!Double.isFinite(converted) || ((new BigDecimal(converted).compareTo(exact) != 0)
					&& (BigDecimal.valueOf(converted).compareTo(decimal) != 0))) {
				throw new ArithmeticException(value + " cannot be read as a Double without losing precision");
			}
			return converted;
		}
		if (type == Float.class) {
			final float converted = ((Number) value).floatValue();
			if (!Float.isFinite(converted) || ((new BigDecimal(converted).compareTo(exact) != 0)
					&& (new BigDecimal(Float.toString(converted)).compareTo(decimal) != 0))) {
				throw new ArithmeticException(value + " cannot be read as a Float without losing precision");
			}
			return converted;
		}
		if (type == BigDecimal.class) {
			return decimal;
		}
		final BigInteger integer;
		try {
			integer = decimal.toBigIntegerExact();
		} catch (final ArithmeticException e) {
			throw new IllegalArgumentException(value + " is not a whole number");
		}
		if (type == BigInteger.class) {
			return integer;
		}
		if (type == Long.class) {
			return exact(integer, Long.MIN_VALUE, Long.MAX_VALUE);
		}
		if (type == Integer.class) {
			return (int) exact(integer, Integer.MIN_VALUE, Integer.MAX_VALUE);
		}
		if (type == Short.class) {
			return (short) exact(integer, Short.MIN_VALUE, Short.MAX_VALUE);
		}
		if (type == Byte.class) {
			return (byte) exact(integer, Byte.MIN_VALUE, Byte.MAX_VALUE);
		}
		return null;
	}

	// BigInteger.longValueExact() and friends need Android API 31, so the range is checked by hand.
	private static long exact(final BigInteger value, final long min, final long max) {
		if ((value.compareTo(BigInteger.valueOf(min)) < 0) || (value.compareTo(BigInteger.valueOf(max)) > 0)) {
			throw new ArithmeticException(value + " is out of range");
		}
		return value.longValue();
	}

	private static BigDecimal toBigDecimal(final Number value) {
		if (value instanceof BigDecimal) {
			return (BigDecimal) value;
		}
		if (value instanceof BigInteger) {
			return new BigDecimal((BigInteger) value);
		}
		if ((value instanceof Double) || (value instanceof Float)) {
			return BigDecimal.valueOf(value.doubleValue());
		}
		return BigDecimal.valueOf(value.longValue());
	}
}
