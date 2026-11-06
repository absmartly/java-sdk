package com.absmartly.sdk.jsonexpr;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Semantic version comparison used by the semver_* operators. Mirrors the JavaScript SDK: a leading "v"/"V" and build
 * metadata are ignored, missing core parts count as 0, numeric identifiers compare numerically (leading zeros
 * ignored) and rank below alphanumeric ones, and a release ranks above any of its pre-releases.
 *
 * Parsing is deliberately as permissive as the JavaScript SDK's, so both SDKs order the same inputs the same way: a
 * non-numeric core part (as in "1.x" or "abc") is not rejected but compared as an alphanumeric identifier, and an
 * empty pre-release ("1.0.0-") is treated as a release.
 */
public final class SemverComparator {
	private SemverComparator() {}

	// Same format as ExprEvaluator.stringConvert, but with fixed symbols: under a default locale with a comma decimal
	// separator, 2.5 would otherwise become "2,5", a single alphanumeric core part instead of "2" and "5".
	private static final ThreadLocal<DecimalFormat> formatter = new ThreadLocal<DecimalFormat>() {
		@Override
		public DecimalFormat initialValue() {
			final DecimalFormat formatter = new DecimalFormat("#", new DecimalFormatSymbols(Locale.ROOT));
			formatter.setMaximumFractionDigits(15);
			formatter.setMinimumFractionDigits(0);
			formatter.setMinimumIntegerDigits(1);
			formatter.setGroupingUsed(false);
			return formatter;
		}
	};

	/**
	 * @return -1, 0 or 1, or null when either side is not a string, number or boolean, or has an empty version core
	 *         after removing a leading v/V and build metadata (for example "", "v", "+build" or "-beta").
	 */
	public static Integer compare(final Evaluator evaluator, final Object lhs, final Object rhs) {
		final String lhsStr = stringConvert(evaluator, lhs);
		final String rhsStr = stringConvert(evaluator, rhs);
		if (lhsStr == null || rhsStr == null) {
			return null;
		}
		return compare(lhsStr, rhsStr);
	}

	private static String stringConvert(final Evaluator evaluator, final Object x) {
		return x instanceof Number ? formatter.get().format(x) : evaluator.stringConvert(x);
	}

	public static Integer compare(final String lhs, final String rhs) {
		final Version l = parse(lhs);
		final Version r = parse(rhs);
		if (l == null || r == null) {
			return null;
		}

		final int maxLen = Math.max(l.parts.size(), r.parts.size());
		for (int i = 0; i < maxLen; ++i) {
			final String lPart = i < l.parts.size() ? l.parts.get(i) : "0";
			final String rPart = i < r.parts.size() ? r.parts.get(i) : "0";
			final int result = compareIdentifiers(lPart, rPart);
			if (result != 0) {
				return result;
			}
		}

		if (l.preRelease.isEmpty() && r.preRelease.isEmpty()) {
			return 0;
		} else if (l.preRelease.isEmpty()) {
			return 1;
		} else if (r.preRelease.isEmpty()) {
			return -1;
		}

		final List<String> lPre = split(l.preRelease);
		final List<String> rPre = split(r.preRelease);
		final int preLen = Math.max(lPre.size(), rPre.size());
		for (int i = 0; i < preLen; ++i) {
			if (i >= lPre.size()) {
				return -1;
			}
			if (i >= rPre.size()) {
				return 1;
			}
			final int result = compareIdentifiers(lPre.get(i), rPre.get(i));
			if (result != 0) {
				return result;
			}
		}
		return 0;
	}

	private static final class Version {
		Version(List<String> parts, String preRelease) {
			this.parts = parts;
			this.preRelease = preRelease;
		}

		final List<String> parts;
		final String preRelease;
	}

	private static Version parse(final String version) {
		String v = version;
		if (v.startsWith("v") || v.startsWith("V")) {
			v = v.substring(1);
		}

		final int plusIndex = v.indexOf('+');
		if (plusIndex >= 0) {
			v = v.substring(0, plusIndex);
		}

		if (v.isEmpty()) {
			return null;
		}

		final int dashIndex = v.indexOf('-');
		final String core = dashIndex >= 0 ? v.substring(0, dashIndex) : v;
		final String preRelease = dashIndex >= 0 ? v.substring(dashIndex + 1) : "";
		if (core.isEmpty()) {
			return null;
		}

		return new Version(split(core), preRelease);
	}

	// A negative limit keeps empty components, so "1..0" has three parts.
	private static List<String> split(final String s) {
		return Arrays.asList(s.split("\\.", -1));
	}

	private static boolean isNumeric(final String s) {
		if (s.isEmpty()) {
			return false;
		}
		for (int i = 0; i < s.length(); ++i) {
			final char c = s.charAt(i);
			if (c < '0' || c > '9') {
				return false;
			}
		}
		return true;
	}

	private static String stripLeadingZeros(final String s) {
		int i = 0;
		while (i < s.length() - 1 && s.charAt(i) == '0') {
			++i;
		}
		return s.substring(i);
	}

	private static int compareIdentifiers(final String a, final String b) {
		final boolean aIsNum = isNumeric(a);
		final boolean bIsNum = isNumeric(b);

		if (aIsNum && bIsNum) {
			final String aNorm = stripLeadingZeros(a);
			final String bNorm = stripLeadingZeros(b);
			if (aNorm.length() != bNorm.length()) {
				return aNorm.length() > bNorm.length() ? 1 : -1;
			}
			return Integer.signum(aNorm.compareTo(bNorm));
		}
		if (aIsNum) {
			return -1;
		}
		if (bIsNum) {
			return 1;
		}
		return Integer.signum(a.compareTo(b));
	}
}
