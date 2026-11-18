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
		for (final Class<?> candidate : SUBSTITUTES) {
			if (implementsEveryInterfaceOf(candidate, type)) {
				return candidate;
			}
		}
		// No constructible class can stand in for this default (EnumSet, a custom collection...): the read can only
		// return the default.
		return type;
	}

	// Insertion-ordered shapes first; a sorted default fails them (they are not SortedSet/SortedMap) and gets the
	// sorted substitute.
	private static final Class<?>[] SUBSTITUTES = {java.util.ArrayList.class, java.util.LinkedHashSet.class,
			java.util.TreeSet.class, java.util.LinkedHashMap.class, java.util.TreeMap.class};

	// Whether a value of `candidate` can stand in for the default: it implements every interface the default's class
	// (or its superclasses) implements, and extends the nearest public superclass other than the JDK's abstract
	// collection skeletons. So Collections.emptySet() (a private class) takes a LinkedHashSet, while an EnumSet
	// default, whose public superclass is EnumSet, takes nothing: the caller's cast would fail.
	private static boolean implementsEveryInterfaceOf(final Class<?> candidate, final Class<?> type) {
		for (Class<?> c = type; c != null; c = c.getSuperclass()) {
			for (final Class<?> iface : c.getInterfaces()) {
				if (!iface.isAssignableFrom(candidate)) {
					return false;
				}
			}
		}
		Class<?> visible = type;
		while ((visible != null) && !java.lang.reflect.Modifier.isPublic(visible.getModifiers())) {
			visible = visible.getSuperclass();
		}
		return (visible == null) || SKELETONS.contains(visible) || visible.isAssignableFrom(candidate);
	}

	private static final java.util.Set<Class<?>> SKELETONS = new java.util.HashSet<Class<?>>(java.util.Arrays.asList(
			Object.class, java.util.AbstractCollection.class, java.util.AbstractList.class,
			java.util.AbstractSet.class, java.util.AbstractMap.class));

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
		if (((value instanceof Double) || (value instanceof Float)) && !Double.isFinite(((Number) value).doubleValue())
				&& isFloatingType(type)) {
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

	private static boolean isFloatingType(final Class<?> type) {
		return (type == Double.class) || (type == Float.class) || (type == BigDecimal.class);
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
		if (type == Double.class) {
			final double converted = ((Number) value).doubleValue();
			if (!Double.isFinite(converted)
					|| !losslessFloating(decimal, exactValue((Number) value), new BigDecimal(converted), 15)) {
				throw new ArithmeticException(value + " cannot be read as a Double without losing precision");
			}
			return converted;
		}
		if (type == Float.class) {
			final float converted = ((Number) value).floatValue();
			if (!Float.isFinite(converted)
					|| !losslessFloating(decimal, exactValue((Number) value), new BigDecimal(converted), 6)) {
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

	// The exact value of the source: binary for a Double or Float, decimal otherwise.
	private static BigDecimal exactValue(final Number value) {
		return ((value instanceof Double) || (value instanceof Float)) ? new BigDecimal(value.doubleValue())
				: toBigDecimal(value);
	}

	// A floating conversion is lossless when the result is exactly the source value (2^60 as a Double), or when the
	// source's shortest decimal form has at most the target's guaranteed decimal digits (15 for double, 6 for float),
	// so the result is the nearest double/float to that decimal (0.1 or 19.99 as a Float). This is pure arithmetic,
	// so every JDK and Android agree; Float.toString and BigDecimal.valueOf changed their output in JDK 19.
	private static boolean losslessFloating(final BigDecimal decimal, final BigDecimal exact,
			final BigDecimal converted, final int digits) {
		if (converted.compareTo(exact) == 0) {
			return true;
		}
		final BigDecimal shortest = decimal.stripTrailingZeros();
		return (shortest.precision() <= digits) && (shortest.round(new java.math.MathContext(digits))
				.compareTo(converted.round(new java.math.MathContext(digits))) == 0);
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
			return shortestDecimal(value.doubleValue());
		}
		return BigDecimal.valueOf(value.longValue());
	}

	// The shortest decimal that reads back as the same double: the fewest significant digits (1 to 17) whose
	// nearest double is `d`. Double.toString finds this only from JDK 19 (and differs on Android), so it is computed.
	static BigDecimal shortestDecimal(final double d) {
		if (d == 0) {
			return BigDecimal.ZERO;
		}
		final BigDecimal exact = new BigDecimal(d);
		for (int digits = 1; digits < 17; ++digits) {
			final BigDecimal rounded = exact.round(new java.math.MathContext(digits, java.math.RoundingMode.HALF_EVEN));
			if (rounded.doubleValue() == d) {
				return rounded;
			}
		}
		return exact.round(new java.math.MathContext(17, java.math.RoundingMode.HALF_EVEN));
	}
}
