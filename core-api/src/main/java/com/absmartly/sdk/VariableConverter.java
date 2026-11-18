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

	// Whether a value of `candidate` can stand in for the default: it implements every interface that declares methods
	// on the default's class (or its superclasses), and extends the nearest public superclass of the default. Marker
	// interfaces with no methods (Kotlin's KMappedMarker, RandomAccess, Serializable...) are skipped. Kotlin's empty
	// collections are the one exception to the superclass rule: they are public final classes, but Kotlin code only
	// ever types them as List/Set/Map. Every other default whose class a JDK collection does not extend (EnumSet, a
	// caller's own final collection, ConcurrentHashMap's key set) takes nothing, and the read returns the default.
	private static boolean implementsEveryInterfaceOf(final Class<?> candidate, final Class<?> type) {
		for (Class<?> c = type; c != null; c = c.getSuperclass()) {
			for (final Class<?> iface : c.getInterfaces()) {
				if ((iface.getMethods().length > 0) && !iface.isAssignableFrom(candidate)) {
					return false;
				}
			}
		}
		Class<?> visible = type;
		if (KOTLIN_EMPTY_COLLECTIONS.contains(type.getName())) {
			return true;
		}
		while ((visible != null) && !java.lang.reflect.Modifier.isPublic(visible.getModifiers())) {
			visible = visible.getSuperclass();
		}
		return (visible == null) || visible.isAssignableFrom(candidate);
	}

	private static final java.util.Set<String> KOTLIN_EMPTY_COLLECTIONS = new java.util.HashSet<String>(
			java.util.Arrays.asList("kotlin.collections.EmptyList", "kotlin.collections.EmptySet",
					"kotlin.collections.EmptyMap"));

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

	// Double and Float accept Infinity and NaN as values, so these reads must turn them away explicitly; every other
	// numeric conversion (BigDecimal, the integer types) already rejects a non-finite number.
	private static boolean isFloatingType(final Class<?> type) {
		return (type == Double.class) || (type == Float.class);
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
		if ((type == Double.class) || (type == Float.class)) {
			final boolean isDouble = type == Double.class;
			final double converted = isDouble ? ((Number) value).doubleValue() : ((Number) value).floatValue();
			if (!Double.isFinite(converted) || ((converted == 0) && (decimal.signum() != 0))) {
				throw new ArithmeticException(value + " is out of range for a " + type.getSimpleName());
			}
			// A whole-number source (an ID, a count) must survive exactly: 2^53 + 1 is not a double. A fractional or
			// floating source rounds to the nearest value, as Java's own conversions do (0.1 is not exact either).
			if (isWholeNumberType(value) && (new BigDecimal(converted).compareTo(decimal) != 0)) {
				throw new ArithmeticException(value + " cannot be read as a " + type.getSimpleName() + " exactly");
			}
			return isDouble ? (Object) converted : (Object) (float) converted;
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

	private static boolean isWholeNumberType(final Object value) {
		return (value instanceof Long) || (value instanceof Integer) || (value instanceof Short)
				|| (value instanceof Byte) || (value instanceof BigInteger);
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

	// The shortest decimal that reads back as the same double. Double.toString finds this only from JDK 19 (and
	// differs on Android), so it is computed.
	static BigDecimal shortestDecimal(final double d) {
		return (d == 0) ? BigDecimal.ZERO : shortest(new BigDecimal(d), 17, candidate -> candidate.doubleValue() == d);
	}

	// The fewest significant digits that read back as the same value. At each length the exact value's rounding and its
	// two neighbours at that length are tried, the closest that reads back winning: at a power of two the gap below
	// is half the gap above, so the shortest decimal is not always the rounding (1.2621775E-29 for 2^-96 as a float).
	private static BigDecimal shortest(final BigDecimal exact, final int maxDigits,
			final java.util.function.Predicate<BigDecimal> readsBack) {
		for (int digits = 1; digits < maxDigits; ++digits) {
			final BigDecimal rounded = exact.round(new java.math.MathContext(digits, java.math.RoundingMode.HALF_EVEN));
			if (readsBack.test(rounded)) {
				return rounded.stripTrailingZeros();
			}
			final BigDecimal unit = BigDecimal.ONE.scaleByPowerOfTen(-rounded.scale());
			BigDecimal best = null;
			for (final BigDecimal neighbour : new BigDecimal[]{rounded.subtract(unit), rounded.add(unit)}) {
				if ((neighbour.precision() <= digits) && readsBack.test(neighbour) && ((best == null)
						|| (neighbour.subtract(exact).abs().compareTo(best.subtract(exact).abs()) < 0))) {
					best = neighbour;
				}
			}
			if (best != null) {
				return best.stripTrailingZeros();
			}
		}
		return exact.round(new java.math.MathContext(maxDigits, java.math.RoundingMode.HALF_EVEN)).stripTrailingZeros();
	}
}
