package com.absmartly.sdk.jsonexpr;

import java.util.ArrayList;
import java.util.List;

/**
 * Semantic version comparison used by the semver_* operators. Mirrors the JavaScript SDK: a leading "v"/"V" and build
 * metadata are ignored, missing core parts count as 0, numeric identifiers compare numerically (leading zeros
 * ignored) and rank below alphanumeric ones, and a release ranks above any of its pre-releases.
 */
public final class SemverComparator {
	private SemverComparator() {}

	/**
	 * @return -1, 0 or 1, or null when either side cannot be converted to a non-empty version string.
	 */
	public static Integer compare(final Evaluator evaluator, final Object lhs, final Object rhs) {
		final String lhsStr = evaluator.stringConvert(lhs);
		final String rhsStr = evaluator.stringConvert(rhs);
		if (lhsStr == null || rhsStr == null || lhsStr.isEmpty() || rhsStr.isEmpty()) {
			return null;
		}
		return compare(lhsStr, rhsStr);
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

		final List<String> lPre = split(l.preRelease, '.');
		final List<String> rPre = split(r.preRelease, '.');
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

		return new Version(split(core, '.'), preRelease);
	}

	private static List<String> split(final String s, final char sep) {
		final List<String> parts = new ArrayList<String>();
		int start = 0;
		for (int i = 0; i < s.length(); ++i) {
			if (s.charAt(i) == sep) {
				parts.add(s.substring(start, i));
				start = i + 1;
			}
		}
		parts.add(s.substring(start));
		return parts;
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
