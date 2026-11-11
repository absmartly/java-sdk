package com.absmartly.sdk;

import static com.absmartly.sdk.SwitchbackTestSupport.attrs;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java8.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.absmartly.sdk.SwitchbackTestSupport.ExposureCapture;
import com.absmartly.sdk.SwitchbackTestSupport.MutableClock;
import com.absmartly.sdk.internal.SwitchbackAssigner;
import com.absmartly.sdk.internal.VariantAssigner;
import com.absmartly.sdk.internal.hashing.Hashing;
import com.absmartly.sdk.json.ContextData;
import com.absmartly.sdk.json.Experiment;
import com.absmartly.sdk.json.ExperimentApplication;
import com.absmartly.sdk.json.ExperimentVariant;
import com.absmartly.sdk.json.Exposure;

// Port of the Collector's ContextAPISwitchbackTest ClusterRule section (absmartly/abs 9bd2b5ca35)
// through the public Context API. Each @Test keeps the Collector's name and fixture: switchback
// "sbc" (id 900, "34/33/33", 1h) linked to user_id (unit type id 2) at "profile/store" and to
// session_id (unit type id 1) at "session_store", declared in unit type id order, so session_id
// comes first although the user_id link was inserted first.
//
// The SDK has no request order: a context holds one uid per unit type (setUnit rejects a second)
// and keeps its units in a map, so the declared source order alone decides which unit serves.
// SwitchbackServingTest pins that source-major iteration directly.
//
// An ineligible Collector response (eligible=false, variant 0, config 0, under the first linked
// request unit in declared order, no exposure) is checked through Context's
// switchbackResolutionForTest seam: its unit and eligibility are not otherwise observable when
// nothing is published.
class ContextSwitchbackClusterRuleTest {
	static final long HOUR = 3_600_000L;
	// 2026-01-01T00:00:00Z, hour aligned.
	static final long ANCHOR = 1_767_225_600_000L;
	static final long PERIOD = 5;
	static final long NOW = ANCHOR + PERIOD * HOUR + 17 * 60_000L;
	static final int SEED_HI = 0x1234;
	static final int SEED_LO = 0x5678abcd;
	static final long SEED = ((long) SEED_HI << 32) | (SEED_LO & 0xffffffffL);
	static final int TRAFFIC_SEED_HI = 7;
	static final int TRAFFIC_SEED_LO = 11;

	static final String SESSION = "session_id";
	static final String USER = "user_id";

	MutableClock clock;
	ContextEventHandler eventHandler;
	Experiment experiment;

	ExposureCapture capture;

	@BeforeEach
	void addClusteredSwitchback() {
		clock = new MutableClock(NOW);
		eventHandler = mock(ContextEventHandler.class);
		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.<Void> completedFuture(null));
		experiment = switchbackExperiment("sbc", 900, USER, "34/33/33");
		unlinkAll();
		link(USER, "profile/store");
		link(SESSION, "session_store");
	}

	static Experiment switchbackExperiment(String name, int id, String unitType, String split) {
		final String[] parts = split.split("/");
		final Experiment experiment = new Experiment();
		experiment.id = id;
		experiment.name = name;
		experiment.unitType = unitType;
		experiment.iteration = 1;
		experiment.seedHi = SEED_HI;
		experiment.seedLo = SEED_LO;
		experiment.split = new double[parts.length];
		experiment.variants = new ExperimentVariant[parts.length];
		for (int i = 0; i < parts.length; ++i) {
			experiment.split[i] = Integer.parseInt(parts[i]) / 100.0;
			experiment.variants[i] = new ExperimentVariant(String.valueOf(i), "{\"sbc.config\":\"test config " + i
					+ "\"}");
		}
		experiment.trafficSeedHi = TRAFFIC_SEED_HI;
		experiment.trafficSeedLo = TRAFFIC_SEED_LO;
		experiment.trafficSplit = new double[]{0.0, 1.0};
		experiment.applications = new ExperimentApplication[]{new ExperimentApplication("website")};
		experiment.randomizationType = "switchback";
		experiment.switchbackStartAt = ANCHOR;
		experiment.switchbackPeriodMs = HOUR;
		experiment.switchbackDesignType = "balanced_block";
		return experiment;
	}

	static Experiment unitExperiment(String name, int id, String unitType) {
		final Experiment experiment = switchbackExperiment(name, id, unitType, "50/50");
		experiment.randomizationType = null;
		experiment.switchbackStartAt = null;
		experiment.switchbackPeriodMs = null;
		experiment.switchbackDesignType = null;
		experiment.variants = new ExperimentVariant[]{new ExperimentVariant("0", null),
				new ExperimentVariant("1", null)};
		return experiment;
	}

	final List<String[]> links = new ArrayList<String[]>();

	void unlinkAll() {
		links.clear();
		refreshLinks();
	}

	void link(String unitType, String path) {
		links.add(new String[]{unitType, path});
		refreshLinks();
	}

	// switchbackClusters in declared (unit type id ascending) order: session_id (1) before user_id (2).
	void refreshLinks() {
		final Map<String, String> clusters = new LinkedHashMap<String, String>();
		for (final String type : new String[]{SESSION, USER, "device_id"}) {
			for (final String[] link : links) {
				if (link[0].equals(type)) {
					clusters.put(link[0], link[1]);
				}
			}
		}
		experiment.switchbackClusters = links.isEmpty() ? null : clusters;
	}

	int scheduled(String clusterKey) {
		return SwitchbackAssigner.assign(clusterKey, PERIOD, SEED, 3);
	}

	static String[] unit(String type, String uid) {
		return new String[]{type, uid};
	}

	Context createContext(Map<String, Object> attributes, Experiment[] experiments, String[]... units) {
		capture = new ExposureCapture();
		final ContextConfig config = ContextConfig.create();
		final Map<String, Object> unitMap = new LinkedHashMap<String, Object>();
		for (final String[] unit : units) {
			unitMap.put(unit[0], unit[1]);
		}
		config.setUnits(unitMap);
		config.setEventLogger(capture);
		final ContextData data = new ContextData();
		data.experiments = experiments;
		final Context context = Context.create(clock, config, mock(ScheduledExecutorService.class),
				CompletableFuture.completedFuture(data), mock(ContextDataProvider.class), eventHandler, null,
				new DefaultVariableParser(), new AudienceMatcher(new DefaultAudienceDeserializer()));
		if (attributes != null) {
			context.setAttributes(attributes);
		}
		return context;
	}

	// The Collector's ExperimentAssignment for "sbc", or null when it lists none (not applicable).
	static final class Assigned {
		String unit;
		boolean eligible;
		int variant;
		boolean fullOn;
		boolean ruleOverride;
		boolean silent;
		String config;
		List<Exposure> exposures;
	}

	Assigned sbc(Map<String, Object> attributes, String[]... units) {
		return sbcWith(attributes, new Experiment[]{experiment}, units);
	}

	// Every other experiment is resolved (and exposed) before "sbc", so the unit hash and assigner
	// caches it fills are in place when "sbc" resolves. Only "sbc" exposures are returned.
	Assigned sbcWith(Map<String, Object> attributes, Experiment[] experiments, String[]... units) {
		final Context context = createContext(attributes, experiments, units);
		for (final Experiment other : experiments) {
			if (!other.name.equals("sbc")) {
				context.getTreatment(other.name);
			}
		}
		final int variant = context.getTreatment("sbc");
		final Context.SwitchbackResolution resolution = context.switchbackResolutionForTest("sbc");
		assertNotNull(resolution);
		final String config = (String) context.getVariableValue("sbc.config", null);
		final List<Exposure> exposures = sbcExposures();
		// Keyed on the serving status, not on assigned: a strict audience mismatch is not assigned yet is
		// still an assignment the Collector lists.
		if (!resolution.applies) {
			assertEquals(0, variant);
			assertNull(config);
			assertTrue(exposures.isEmpty());
			return null;
		}
		final Assigned assigned = new Assigned();
		assigned.unit = resolution.unitType;
		assigned.eligible = resolution.eligible;
		assigned.silent = resolution.silent;
		assigned.variant = variant;
		assigned.fullOn = resolution.fullOn;
		assigned.ruleOverride = resolution.ruleOverride;
		assigned.config = config;
		assigned.exposures = exposures;
		return assigned;
	}

	List<Exposure> sbcExposures() {
		final List<Exposure> exposures = new ArrayList<Exposure>();
		for (final Exposure exposure : capture.exposures) {
			if (exposure.name.equals("sbc")) {
				exposures.add(exposure);
			}
		}
		return exposures;
	}

	// R33-5: an ineligible cluster resolution is still an assignment, as control, under the first
	// linked-type request unit in declared order, and no exposure is recorded for it.
	void assertIneligible(Map<String, Object> attributes, String expectedUnit, String[]... units) {
		final Assigned assignment = sbc(attributes, units);
		assertNotNull(assignment, String.valueOf(attributes));
		assertEquals(expectedUnit, assignment.unit);
		assertFalse(assignment.eligible);
		assertTrue(assignment.silent);
		assertEquals(0, assignment.variant);
		assertFalse(assignment.fullOn);
		assertFalse(assignment.ruleOverride);
		assertEquals("test config 0", assignment.config);
		assertTrue(assignment.exposures.isEmpty(), String.valueOf(attributes));
	}

	static Map<String, Object> store(Object value) {
		return attrs("store", value);
	}

	@Test
	void missingOrUnreadableClusterConfigurationReturnsOnlyLegacyIneligibleControl() {
		for (final String broken : new String[]{"null-cluster", "missing-links", "unreadable-path", "geo.city",
				"'geo.city'", "\"geo.city\"", "unknown-unit"}) {
			unlinkAll();
			if (broken.equals("unreadable-path")) {
				link(SESSION, "'geo");
			} else if (broken.equals("unknown-unit")) {
				// A readable map whose only linked type (device_id) is absent from the request.
				link("device_id", "session_store");
				assertNull(sbc(attrs("session_store", "s-1"), unit(SESSION, "x1"), unit(USER, "u1")), broken);
				continue;
			} else if (!broken.equals("null-cluster") && !broken.equals("missing-links")) {
				link(SESSION, broken);
			} else if (broken.equals("missing-links")) {
				experiment.switchbackClusters = new LinkedHashMap<String, String>();
			}
			final Map<String, Object> attributes = attrs("geo", attrs("city", "Porto"), "geo.city", "Faro",
					"session_store", "s-1");
			// The declared unit (user_id) only identifies an ineligible control response.
			assertIneligible(attributes, USER, unit(SESSION, "x1"), unit(USER, "u1"));
			// Without a unit of the declared type, there is no response at all.
			assertNull(sbc(attributes, unit(SESSION, "x1")), broken);
		}
	}

	// The Collector's name is kept; the SDK has no request order (see the class comment), so one
	// order is exercised.
	@Test
	void trafficUsesServingUidNotExperimentUidInBothRequestOrders() {
		// percentageOfTraffic = 40.
		experiment.trafficSplit = new double[]{0.6, 0.4};
		final Experiment ordinary = unitExperiment("ordinary", 901, USER);
		final double[] split = {0.6, 0.4};
		final String[] witnesses = new String[2];
		for (int i = 0; (i < 1000) && ((witnesses[0] == null) || (witnesses[1] == null)); ++i) {
			final String uid = "traffic-" + i;
			witnesses[new VariantAssigner(Hashing.hashUnit(uid)).assign(split, TRAFFIC_SEED_HI,
					TRAFFIC_SEED_LO) == 0 ? 0 : 1] = uid;
		}
		assertNotNull(witnesses[0]);
		assertNotNull(witnesses[1]);
		final String key = "s-18";
		// The eligible draw must be distinguishable from control.
		assertNotEquals(0, scheduled(key));
		for (final boolean servingEligible : new boolean[]{false, true}) {
			final Assigned assignment = sbcWith(attrs("session_store", key, "profile", store(key)),
					new Experiment[]{experiment, ordinary}, unit(SESSION, witnesses[servingEligible ? 1 : 0]),
					unit(USER, witnesses[servingEligible ? 0 : 1]));
			assertEquals(SESSION, assignment.unit);
			assertEquals(servingEligible, assignment.eligible);
			assertEquals(servingEligible ? scheduled(key) : 0, assignment.variant);
			// A traffic miss on an eligible cluster is still recorded, under the serving unit.
			assertEquals(1, assignment.exposures.size());
			assertEquals(SESSION, assignment.exposures.get(0).unit);
			assertEquals(servingEligible, assignment.exposures.get(0).eligible);
		}
	}

	@Test
	void oneMatchingUnitServesItsValue() {
		final Assigned assignment = sbc(attrs("profile", store("s-17")), unit(USER, "u1"));

		assertTrue(assignment.eligible);
		assertEquals(USER, assignment.unit);
		assertEquals(scheduled("s-17"), assignment.variant);
	}

	@Test
	void twoAgreeingUnitsServeTheValue() {
		final Assigned assignment = sbc(attrs("profile", store("s-17"), "session_store", "s-17"), unit(USER, "u1"),
				unit(SESSION, "x1"));

		assertTrue(assignment.eligible);
		assertEquals(scheduled("s-17"), assignment.variant);
	}

	@Test
	void twoDisagreeingUnitsAreIneligible() {
		// session_id (type id 1) is first in declared order whatever the request order.
		assertIneligible(attrs("profile", store("s-17"), "session_store", "s-18"), SESSION, unit(USER, "u1"),
				unit(SESSION, "x1"));
	}

	// The Collector serves two session_id units agreeing on one value. A context holds one uid per
	// unit type, so the second is rejected and only the single-unit path is reproducible.
	@Test
	void twoUnitsOfOneTypeWithOnePathAgreeOnTheSameValue() {
		final String key = "s-17";
		assertNotEquals(0, scheduled(key));
		final Context context = createContext(attrs("session_store", key), new Experiment[]{experiment},
				unit(SESSION, "x1"));
		assertThrows(IllegalArgumentException.class, () -> context.setUnit(SESSION, "x2"));

		final Assigned assignment = sbc(attrs("session_store", key), unit(SESSION, "x1"));
		assertTrue(assignment.eligible);
		assertEquals(scheduled(key), assignment.variant);
	}

	@Test
	void noMatchingUnitIsIneligible() {
		// A value at the path, but no unit of a linked type: not applicable to this context.
		assertNull(sbc(attrs("session_store", "s-1"), unit("device_id", "d1")));
		// No units at all.
		assertNull(sbc(attrs("session_store", "s-1")));
	}

	@Test
	void matchingUnitWithoutAValueIsIneligible() {
		assertIneligible(attrs("other", "s-1"), USER, unit(USER, "u1"));
		assertIneligible(attrs("other", "s-1"), SESSION, unit(USER, "u1"), unit(SESSION, "x1"));
	}

	@Test
	void nonStringNonIntegerValuesAreIneligible() {
		for (final Object value : new Object[]{1.5, 3.0, true, attrs("a", 1), TestUtils.listOf("a"), ""}) {
			assertIneligible(attrs("session_store", value), SESSION, unit(SESSION, "x1"));
		}
	}

	@Test
	void wholeNumbersAreKeyedByTheirDecimalText() {
		assertNotEquals(0, scheduled("17"));
		final Assigned assignment = sbc(attrs("session_store", 17), unit(SESSION, "x1"));

		assertTrue(assignment.eligible);
		assertEquals(scheduled("17"), assignment.variant);
		// Equal to the string "17" from another unit type, so the two agree.
		assertTrue(sbc(attrs("session_store", 17, "profile", store("17")), unit(SESSION, "x1"),
				unit(USER, "u1")).eligible);
	}

	@Test
	void nestedAndArrayPathsAreRead() {
		unlinkAll();
		link(SESSION, "items/1/store");

		final Assigned assignment = sbc(attrs("items", TestUtils.listOf(store("a"), store("b"))),
				unit(SESSION, "x1"));
		assertEquals(scheduled("b"), assignment.variant);
		assertNotEquals(scheduled("a"), scheduled("b"));
	}

	@Test
	void exposureUnitIsTheFirstMatchingUnitInDeclaredOrderWhateverTheRequestOrder() {
		final String key = "s-18";
		assertNotEquals(0, scheduled(key));
		// Declared order is unit type id ascending: session_id (1) before user_id (2), although the
		// user_id link was inserted first.
		final Assigned assignment = sbc(attrs("profile", store(key), "session_store", key), unit(USER, "u1"),
				unit(SESSION, "x1"));

		assertEquals(SESSION, assignment.unit);
		assertEquals(SESSION, assignment.exposures.get(0).unit);
		assertEquals(scheduled(key), assignment.variant);
	}

	@Test
	void ineligibleClusterNeverPublishesAnExposure() {
		final Context context = createContext(attrs("session_store", "a", "profile", store("b")),
				new Experiment[]{experiment}, unit(USER, "u1"), unit(SESSION, "x1"));
		assertEquals(0, context.getTreatment("sbc"));
		assertEquals(0, context.getPendingCount());
		context.publish();
		verify(eventHandler, never()).publish(any(), any());
		assertTrue(capture.exposures.isEmpty());
	}

	@Test
	void aUnitWithoutAValueDoesNotServeEvenIfFirstInDeclaredOrder() {
		final String key = "s-17";
		assertNotEquals(0, scheduled(key));
		final Assigned assignment = sbc(attrs("profile", store(key)), unit(SESSION, "x1"), unit(USER, "u1"));

		assertEquals(USER, assignment.unit);
		assertEquals(USER, assignment.exposures.get(0).unit);
		assertEquals(scheduled(key), assignment.variant);
	}

	@Test
	void valueNotInTheFilterIsIneligible() {
		experiment.switchbackClusterValues = new String[]{"s-1", "s-2"};

		assertIneligible(attrs("session_store", "s-3"), SESSION, unit(SESSION, "x1"));
		final Assigned allowed = sbc(attrs("session_store", "s-2"), unit(SESSION, "x1"));
		assertTrue(allowed.eligible);
		assertEquals(scheduled("s-2"), allowed.variant);
	}

	@Test
	void nullFilterAssignsEveryValue() {
		experiment.switchbackClusterValues = null;

		for (int i = 0; i < 20; ++i) {
			final Assigned assignment = sbc(attrs("session_store", "s-" + i), unit(SESSION, "x1"));
			assertTrue(assignment.eligible);
			assertEquals(scheduled("s-" + i), assignment.variant);
		}
	}

	// A valid cluster map whose linked types are all absent from the request does not fall back to
	// the experiment's declared unitType: no assignment, no exposure.
	@Test
	void validClusterWithoutLinkedRequestUnitDoesNotFallBackToExperimentUnit() {
		unlinkAll();
		link(SESSION, "session_store");
		final String key = "s-17";
		assertNotEquals(0, scheduled(key));

		assertNull(sbc(attrs("session_store", key, "profile", store(key)), unit(USER, "u1")));
		final Assigned linked = sbc(attrs("session_store", key), unit(USER, "u1"), unit(SESSION, "x1"));
		assertEquals(SESSION, linked.unit);
		assertEquals(scheduled(key), linked.variant);
	}

	// The Collector's wire-rule oracle SdkSwitchbackRule (ContextAPISwitchbackTest support class),
	// transcribed: period index from the anchor re-ceiled to the hour, traffic on the serving uid's
	// hash, then the schedule draw over the cluster key. A null cluster key (no value, disagreement,
	// filtered value) has no draw. It shares SwitchbackAssigner, VariantAssigner and Hashing with the
	// SDK, whose parity is pinned by the golden vectors, so it is a wire-rule parity check, not an
	// independent oracle.
	static final class SdkSwitchbackRule {
		static final long NO_PERIOD = Long.MIN_VALUE;

		static long periodIndex(Experiment experiment, long nowMs) {
			final Long startAt = experiment.switchbackStartAt;
			final Long periodMs = experiment.switchbackPeriodMs;
			if ((startAt == null) || (periodMs == null) || (periodMs <= 0)) {
				return NO_PERIOD;
			}
			if ((experiment.split == null) || (experiment.split.length < 2)) {
				return NO_PERIOD;
			}
			final long anchor = -Math.floorDiv(-startAt, HOUR) * HOUR;
			if (nowMs < anchor) {
				return NO_PERIOD;
			}
			return Math.floorDiv(nowMs - anchor, periodMs);
		}

		// {variant, eligible ? 1 : 0}
		static int[] evaluate(Experiment experiment, String uid, String clusterKey, long nowMs) {
			final long periodIndex = periodIndex(experiment, nowMs);
			if ((clusterKey == null) || (periodIndex == NO_PERIOD)) {
				return new int[]{0, 0};
			}
			final boolean eligible = new VariantAssigner(Hashing.hashUnit(uid)).assign(experiment.trafficSplit,
					experiment.trafficSeedHi, experiment.trafficSeedLo) == 1;
			if (!eligible) {
				return new int[]{0, 0};
			}
			return new int[]{SwitchbackAssigner.assign(clusterKey, periodIndex,
					((long) experiment.seedHi << 32) | (experiment.seedLo & 0xffffffffL), experiment.split.length),
					1};
		}
	}

	// Cluster axis of the grid: one source with the key, or a cluster that resolves no key.
	enum ClusterCase {
		SINGLE, DISAGREEING_SECOND_SOURCE, MISSING_VALUE, FILTERED_VALUE
	}

	Context gridContext(ClusterCase cluster, String uid, String key) {
		unlinkAll();
		link(SESSION, "session_store");
		experiment.switchbackClusterValues = null;
		switch (cluster) {
		case DISAGREEING_SECOND_SOURCE:
			link(USER, "profile/store");
			return createContext(attrs("session_store", key, "profile", store(key + "-other")),
					new Experiment[]{experiment}, unit(SESSION, uid), unit(USER, "u-" + uid));
		case MISSING_VALUE:
			return createContext(attrs("other", key), new Experiment[]{experiment}, unit(SESSION, uid));
		case FILTERED_VALUE:
			experiment.switchbackClusterValues = new String[]{key + "-allowed"};
			return createContext(attrs("session_store", key), new Experiment[]{experiment}, unit(SESSION, uid));
		default:
			return createContext(attrs("session_store", key), new Experiment[]{experiment}, unit(SESSION, uid));
		}
	}

	@Test
	void contextMatchesTheSdkSwitchbackWireRuleParityGrid() {
		int eligible = 0;
		int ineligible = 0;
		int trafficMisses = 0;
		final long[] anchors = {ANCHOR, ANCHOR + 7 * HOUR};
		final long[] periods = {HOUR, 2 * HOUR, 24 * HOUR};
		for (final ClusterCase cluster : ClusterCase.values()) {
			for (final long anchor : anchors) {
				for (final long periodMs : periods) {
					experiment.trafficSplit = new double[]{0.3, 0.7};
					experiment.switchbackStartAt = anchor;
					experiment.switchbackPeriodMs = periodMs;
					for (int u = 0; u < 6; ++u) {
						final String uid = "uid-" + u;
						for (final String key : new String[]{"s-1", "s-2", "42", "Lisboa"}) {
							for (final long now : new long[]{anchor - HOUR, anchor - 1, anchor, anchor + periodMs - 1,
									anchor + periodMs, anchor + 3 * periodMs + 5, anchor + 11 * periodMs}) {
								clock.now = now;
								final String oracleKey = (cluster == ClusterCase.SINGLE) ? key : null;
								final int[] oracle = SdkSwitchbackRule.evaluate(experiment, uid, oracleKey, now);
								final Context context = gridContext(cluster, uid, key);
								final String message = cluster + "/" + anchor + "/" + periodMs + "/" + uid + "/" + key
										+ "/" + now;
								assertEquals(oracle[0], context.getTreatment("sbc"), message);
								assertEquals(oracle[1] == 1, context.switchbackResolutionForTest("sbc").eligible,
										message);
								// Before the anchor nothing is recorded; from it, a keyed cluster records
								// one exposure whose eligibility is the oracle's (a traffic miss included).
								final int expectedExposures = ((oracleKey != null) && (now >= anchor)) ? 1 : 0;
								assertEquals(expectedExposures, capture.exposures.size(), message);
								if (expectedExposures == 1) {
									assertEquals(oracle[1] == 1, capture.exposures.get(0).eligible, message);
								}
								// Counted on the keyed cluster only, where both outcomes occur, so each
								// guard below checks that case rather than the keyless cases' misses.
								if (cluster == ClusterCase.SINGLE) {
									if (oracle[1] == 1) {
										++eligible;
									} else {
										++ineligible;
										// From the anchor on, an ineligible keyed cluster is a traffic miss.
										if (now >= anchor) {
											++trafficMisses;
										}
									}
								}
							}
						}
					}
				}
			}
		}
		assertTrue(eligible > 100);
		assertTrue(ineligible > 100);
		// Pre-anchor cases alone meet the guard above; this one needs the recorded traffic-miss branch.
		assertTrue(trafficMisses > 0);

		// The deliberate divergence: an anchor off the hour is unservable in the SDK (as in the
		// Collector's Registry), while the transcribed oracle would re-ceil it and draw.
		final long unaligned = ANCHOR + 7 * HOUR + 1;
		experiment.trafficSplit = new double[]{0.0, 1.0};
		experiment.switchbackStartAt = unaligned;
		experiment.switchbackPeriodMs = HOUR;
		clock.now = ANCHOR + 8 * HOUR + 5;
		assertNotEquals(SdkSwitchbackRule.NO_PERIOD, SdkSwitchbackRule.periodIndex(experiment, clock.now));
		assertEquals(1, SdkSwitchbackRule.evaluate(experiment, "uid-0", "s-1", clock.now)[1]);
		final Context context = gridContext(ClusterCase.SINGLE, "uid-0", "s-1");
		assertEquals(0, context.getTreatment("sbc"));
		assertFalse(context.switchbackResolutionForTest("sbc").eligible);
		assertTrue(capture.exposures.isEmpty());
	}
}
