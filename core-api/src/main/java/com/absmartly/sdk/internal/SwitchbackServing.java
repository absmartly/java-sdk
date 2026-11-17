package com.absmartly.sdk.internal;

import java.util.List;
import java.util.Map;

// Which unit serves a switchback experiment and under which cluster key. Port of the Collector's
// ContextAPI.switchbackServing (R33, cluster-defined switchbacks):
//
// - The experiment's switchbackClusters map, in its declared order (unit_type_id ascending), lists
//   the linked unit types and, for each, the attribute path holding its cluster value.
// - No context unit of a linked type: the experiment does not apply (ABSENT).
// - Otherwise each linked unit with a uid, in source order and then unit order, reads the attribute
//   at its source's path, normalised by SwitchbackClusterKeys. The first unit yielding a value is
//   the serving unit and the value is the cluster key; units yielding no value are skipped.
// - INELIGIBLE, served under the first linked unit: no unit yields a value, two units yield
//   different values, or an allow-list is set and lacks the key.
// - ELIGIBLE otherwise.
// - A missing, empty or unreadable cluster map takes the Collector's legacy path instead (legacy).
public final class SwitchbackServing {
	public static final int ABSENT = 0;
	public static final int INELIGIBLE = 1;
	public static final int ELIGIBLE = 2;

	public interface Unit {
		String type();

		Object uid();
	}

	// Reads the cluster value of one source: the attribute at the path, or null when there is none.
	public interface AttributeReader {
		Object read(SwitchbackAttributePath path);
	}

	public final int status;
	// The serving unit when ELIGIBLE, the first linked unit when INELIGIBLE, null when ABSENT.
	public final Unit unit;
	// The raw normalised cluster key when ELIGIBLE; never hashed.
	public final String clusterKey;

	private SwitchbackServing(final int status, final Unit unit, final String clusterKey) {
		this.status = status;
		this.unit = unit;
		this.clusterKey = clusterKey;
	}

	// sourceTypes and sourcePaths are the non-null results of sourceTypes() and sourcePaths() for a
	// readable cluster map; an unreadable map takes legacy() instead. The declared source order alone
	// decides which unit serves: units is in no meaningful order (the context keeps its units in a
	// map), and the source loop is the outer one.
	public static SwitchbackServing resolve(final String[] sourceTypes, final SwitchbackAttributePath[] sourcePaths,
			final String[] allowedValues, final List<? extends Unit> units, final AttributeReader reader) {
		Unit first = null;
		Unit serving = null;
		String key = null;
		boolean disagreement = false;
		for (int s = 0; s < sourceTypes.length; ++s) {
			final String type = sourceTypes[s];
			for (final Unit unit : units) {
				if ((unit.uid() == null) || !type.equals(unit.type())) {
					continue;
				}
				if (first == null) {
					first = unit;
				}
				final String value = SwitchbackClusterKeys.normalize(reader.read(sourcePaths[s]));
				if (value == null) {
					continue;
				}
				if (key == null) {
					key = value;
					serving = unit;
				} else if (!key.equals(value)) {
					disagreement = true;
				}
			}
		}

		if (first == null) {
			return new SwitchbackServing(ABSENT, null, null);
		}
		if ((key == null) || disagreement || !allowed(allowedValues, key)) {
			return new SwitchbackServing(INELIGIBLE, first, null);
		}
		return new SwitchbackServing(ELIGIBLE, serving, key);
	}

	// The Collector's compatibility path for a missing, empty or unreadable cluster map: the
	// experiment's declared unitType only identifies an ineligible control response (never an
	// assignment or a cluster); without a unit of that type the experiment does not apply.
	public static SwitchbackServing legacy(final String unitType, final List<? extends Unit> units) {
		if (unitType != null) {
			for (final Unit unit : units) {
				if ((unit.uid() != null) && unitType.equals(unit.type())) {
					return new SwitchbackServing(INELIGIBLE, unit, null);
				}
			}
		}
		return new SwitchbackServing(ABSENT, null, null);
	}

	// A null or empty allow-list admits every value: the Collector serves an experiment that has no
	// allow-list rows with this field absent, and builds its filter set only when the list is non-empty.
	static boolean allowed(final String[] allowedValues, final String key) {
		if ((allowedValues == null) || (allowedValues.length == 0)) {
			return true;
		}
		for (final String allowed : allowedValues) {
			if (key.equals(allowed)) {
				return true;
			}
		}
		return false;
	}

	// The linked unit types of a switchbackClusters map, in declared order; null for a missing or
	// empty map.
	public static String[] sourceTypes(final Map<String, String> clusters) {
		if ((clusters == null) || clusters.isEmpty()) {
			return null;
		}
		final String[] types = new String[clusters.size()];
		int i = 0;
		for (final Map.Entry<String, String> entry : clusters.entrySet()) {
			types[i++] = entry.getKey();
		}
		return types;
	}

	// The parsed paths of a switchbackClusters map, parallel to sourceTypes(); null for a missing or
	// empty map, a null unit type, or any path that is not a supported attribute path.
	public static SwitchbackAttributePath[] sourcePaths(final Map<String, String> clusters) {
		if ((clusters == null) || clusters.isEmpty()) {
			return null;
		}
		final SwitchbackAttributePath[] paths = new SwitchbackAttributePath[clusters.size()];
		int i = 0;
		for (final Map.Entry<String, String> entry : clusters.entrySet()) {
			if (entry.getKey() == null) {
				return null;
			}
			final SwitchbackAttributePath path = SwitchbackAttributePath.parseSupported(entry.getValue());
			if (path == null) {
				return null;
			}
			paths[i++] = path;
		}
		return paths;
	}
}
