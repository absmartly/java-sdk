package com.absmartly.sdk;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java8.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import com.absmartly.sdk.java.time.Clock;
import com.absmartly.sdk.json.ContextData;
import com.absmartly.sdk.json.Experiment;
import com.absmartly.sdk.json.Exposure;
import com.absmartly.sdk.json.PublishEvent;

class ContextAssignmentRulesTest extends TestUtils {
	static final String EXP = "exp_test_abc";
	static final int NORMAL_VARIANT = 2; // exp_test_abc's randomized variant for the session below
	static final String SESSION_ID = "e791e240fcd3df7d238cfc285f475e8152fcc0ec";
	static final String USER_ID = "123456789";

	static final String US = "{\"and\":[{\"eq\":[{\"var\":\"country\"},{\"value\":\"US\"}]}]}";
	static final String GB = "{\"and\":[{\"eq\":[{\"var\":\"country\"},{\"value\":\"GB\"}]}]}";

	final Clock clock = Clock.fixed(1_620_000_000_000L);
	ContextDataProvider dataProvider;
	ContextEventHandler eventHandler;
	ScheduledExecutorService scheduler;
	String environment;

	@BeforeEach
	void setUp() {
		dataProvider = mock(ContextDataProvider.class);
		eventHandler = mock(ContextEventHandler.class);
		scheduler = mock(ScheduledExecutorService.class);
		environment = "production";
		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.<Void> completedFuture(null));
	}

	static String assign(String name, String conditions, String environments, int variant) {
		return "{\"name\":\"" + name + "\",\"type\":\"assign\",\"conditions\":" + conditions + ",\"environments\":"
				+ environments + ",\"variant\":" + variant + "}";
	}

	static String split(String conditions, String percentages) {
		return "{\"name\":\"split\",\"type\":\"split\",\"conditions\":" + conditions
				+ ",\"environments\":[],\"percentages\":\"" + percentages + "\"}";
	}

	static String rules(String... rules) {
		return "{\"rules\":[" + String.join(",", rules) + "]}";
	}

	ContextData data(String assignmentRules) {
		return data(assignmentRules, "{\"filter\":[{\"value\":true}]}", false);
	}

	ContextData data(String assignmentRules, String audience, boolean audienceStrict) {
		final byte[] bytes = getResourceBytes("context.json");
		final ContextData data = new DefaultContextDataDeserializer().deserialize(bytes, 0, bytes.length);
		for (final Experiment experiment : data.experiments) {
			if (experiment.name.equals(EXP)) {
				experiment.assignmentRules = assignmentRules;
				experiment.audience = audience;
				experiment.audienceStrict = audienceStrict;
			}
		}
		return data;
	}

	Client client() {
		final ClientConfig config = ClientConfig.create()
				.setEndpoint("https://localhost/v1")
				.setAPIKey("key")
				.setApplication("website")
				.setApplicationVersion("2.4.1");
		if (environment != null) {
			config.setEnvironment(environment);
		}
		return environment != null ? Client.create(config, mock(HTTPClient.class)) : null;
	}

	Context create(ContextConfig config, ContextData data) {
		return Context.create(clock, config, scheduler, CompletableFuture.completedFuture(data), dataProvider,
				eventHandler, null, new DefaultVariableParser(), new AudienceMatcher(new DefaultAudienceDeserializer()),
				client());
	}

	Context create(ContextData data) {
		return create(ContextConfig.create().setUnit("session_id", SESSION_ID).setUnit("user_id", USER_ID), data);
	}

	Context createWithoutSession(ContextData data) {
		return create(ContextConfig.create().setUnit("user_id", USER_ID), data);
	}

	List<Exposure> publishedExposures(Context context) {
		context.publish();
		final ArgumentCaptor<PublishEvent> captor = ArgumentCaptor.forClass(PublishEvent.class);
		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(any(), captor.capture());
		final List<Exposure> result = new ArrayList<Exposure>();
		if (captor.getValue().exposures != null) {
			for (final Exposure exposure : captor.getValue().exposures) {
				if (exposure.name.equals(EXP)) {
					result.add(exposure);
				}
			}
		}
		return result;
	}

	final ContextData rulesData = data(rules(assign("US Internal Users", US, "[]", 1)));
	final ContextData envScopedData = data(rules(assign("Production Only", US, "[\"production\"]", 1)));
	final ContextData strictData = data(rules(assign("US Users", US, "[]", 1)),
			"{\"filter\":[{\"gte\":[{\"var\":\"age\"},{\"value\":20}]}]}", true);

	// One experiment's assignmentRules sent as an object instead of a string must not fail the
	// context: that experiment's rules are skipped and every other experiment resolves normally.
	@Test
	void malformedAssignmentRulesInResponseOnlySkipThatExperiment() {
		final java.nio.charset.Charset utf8 = java.nio.charset.Charset.forName("UTF-8");
		final String json = new String(getResourceBytes("context.json"), utf8)
				.replace("\"name\":\"exp_test_abc\",", "\"name\":\"exp_test_abc\",\"assignmentRules\":{\"rules\":[]},")
				.replace("\"name\":\"exp_test_ab\",",
						"\"name\":\"exp_test_ab\",\"assignmentRules\":\"{\\\"rules\\\":[{\\\"type\\\":\\\"assign\\\",\\\"variant\\\":1}]}\",");
		assertTrue(json.contains("\"assignmentRules\":{\"rules\":[]}"));
		final byte[] bytes = json.getBytes(utf8);
		final ContextData parsed = new DefaultContextDataDeserializer().deserialize(bytes, 0, bytes.length);
		assertNotNull(parsed);

		final Context context = create(parsed);
		assertTrue(context.isReady());
		assertFalse(context.isFailed());
		assertEquals(NORMAL_VARIANT, context.getTreatment(EXP));
		assertEquals(1, context.getTreatment("exp_test_ab"));
	}

	// A custom deserializer that throws on one experiment's rules only drops those rules: the context
	// stays healthy and every other experiment resolves normally.
	@Test
	void throwingCustomDeserializerOnlySkipsThatExperimentsRules() {
		final String throwing = rules(assign("US Internal Users", US, "[]", 1));
		final DefaultAudienceDeserializer delegate = new DefaultAudienceDeserializer();
		final AudienceDeserializer deserializer = new AudienceDeserializer() {
			@Override
			public Map<String, Object> deserialize(byte[] bytes, int offset, int length) {
				if (new String(bytes, offset, length, java.nio.charset.Charset.forName("UTF-8")).equals(throwing)) {
					throw new IllegalStateException("parser failure");
				}
				return delegate.deserialize(bytes, offset, length);
			}
		};
		final Context context = Context.create(clock,
				ContextConfig.create().setUnit("session_id", SESSION_ID).setUnit("user_id", USER_ID), scheduler,
				CompletableFuture.completedFuture(data(throwing)), dataProvider, eventHandler, null,
				new DefaultVariableParser(), new AudienceMatcher(deserializer), client());

		assertTrue(context.isReady());
		assertFalse(context.isFailed());
		context.setAttribute("country", "US");
		assertEquals(NORMAL_VARIANT, context.getTreatment(EXP));
		assertEquals(1, context.getTreatment("exp_test_ab"));
	}

	// Rules are parsed once when the data is installed, not on every lookup after an attribute
	// change, so an invalid string costs (and logs) once rather than on every getTreatment.
	@Test
	void rulesAreParsedOncePerDataInstall() {
		final DefaultAudienceDeserializer deserializer = spy(new DefaultAudienceDeserializer());
		final String invalid = "{\"rules\":[";
		final Context context = Context.create(clock,
				ContextConfig.create().setUnit("session_id", SESSION_ID).setUnit("user_id", USER_ID), scheduler,
				CompletableFuture.completedFuture(data(invalid, null, false)), dataProvider, eventHandler, null,
				new DefaultVariableParser(), new AudienceMatcher(deserializer), client());

		for (int i = 0; i < 20; ++i) {
			context.setAttribute("n", i);
			assertEquals(NORMAL_VARIANT, context.getTreatment(EXP));
		}
		verify(deserializer, times(1)).deserialize(eq(invalid.getBytes(java.nio.charset.Charset.forName("UTF-8"))),
				eq(0), eq(invalid.length()));
	}

	@Test
	void ruleVariantWhenRuleMatches() {
		final Context context = create(rulesData);
		context.setAttribute("country", "US");
		assertEquals(1, context.getTreatment(EXP));
	}

	@Test
	void ruleWinsOverCustomAssignmentAndIsExposedOnce() {
		final Context context = create(rulesData);
		context.setAttribute("country", "US");
		context.setCustomAssignment(EXP, 2);
		assertEquals(1, context.getTreatment(EXP));
		assertEquals(1, context.getTreatment(EXP));
		assertEquals(1, context.getPendingCount());

		context.setAttribute("country", "GB");
		assertEquals(2, context.getTreatment(EXP));
	}

	@Test
	void normalAssignmentWhenNoRuleMatches() {
		final Context context = create(rulesData);
		context.setAttribute("country", "GB");
		assertEquals(NORMAL_VARIANT, context.getTreatment(EXP));
	}

	@Test
	void skipsRuleForOtherEnvironment() {
		environment = "staging";
		final Context context = create(envScopedData);
		context.setAttribute("country", "US");
		assertEquals(NORMAL_VARIANT, context.getTreatment(EXP));
	}

	@Test
	void skipsEnvironmentScopedRuleWithoutClient() {
		environment = null;
		final Context context = create(envScopedData);
		context.setAttribute("country", "US");
		assertEquals(NORMAL_VARIANT, context.getTreatment(EXP));
	}

	@Test
	void unscopedRuleAppliesWithoutClient() {
		environment = null;
		final Context context = create(rulesData);
		context.setAttribute("country", "US");
		assertEquals(1, context.getTreatment(EXP));
	}

	@Test
	void matchesRuleForEnvironment() {
		final Context context = create(envScopedData);
		context.setAttribute("country", "US");
		assertEquals(1, context.getTreatment(EXP));
	}

	@Test
	void overrideTakesPriorityOverRules() {
		final Context context = create(rulesData);
		context.setAttribute("country", "US");
		context.setOverride(EXP, 0);
		assertEquals(0, context.getTreatment(EXP));

		final Exposure exposure = publishedExposures(context).get(0);
		assertEquals(0, exposure.variant);
		assertTrue(exposure.overridden);
		assertFalse(exposure.ruleOverride);
		assertFalse(exposure.assigned);
	}

	@Test
	void overrideFlagsWhenOverrideMatchesRuleVariant() {
		final Context context = create(rulesData);
		context.setAttribute("country", "US");
		assertEquals(1, context.getTreatment(EXP));
		context.setOverride(EXP, 1);
		assertEquals(1, context.getTreatment(EXP));

		final List<Exposure> exposures = publishedExposures(context);
		final Exposure last = exposures.get(exposures.size() - 1);
		assertEquals(1, last.variant);
		assertTrue(last.overridden);
		assertFalse(last.ruleOverride);
	}

	@Test
	void exposureFlagsWhenRuleMatches() {
		final Context context = create(rulesData);
		context.setAttribute("country", "US");
		assertEquals(1, context.getTreatment(EXP));

		assertEquals(new Exposure(2, EXP, "session_id", 1, clock.millis(), false, true, false, false, false, false,
				true), publishedExposures(context).get(0));
	}

	@Test
	void exposureFlagsWhenNoRuleMatches() {
		final Context context = create(rulesData);
		context.setAttribute("country", "GB");
		assertEquals(NORMAL_VARIANT, context.getTreatment(EXP));

		assertEquals(new Exposure(2, EXP, "session_id", NORMAL_VARIANT, clock.millis(), true, true, false, false,
				false, false, false), publishedExposures(context).get(0));
	}

	@Test
	void ruleSkipsAudienceAndAudienceStrict() {
		final Context context = create(strictData);
		context.setAttribute("country", "US");
		assertEquals(1, context.getTreatment(EXP));

		final Exposure exposure = publishedExposures(context).get(0);
		assertFalse(exposure.audienceMismatch);
		assertTrue(exposure.ruleOverride);
		assertFalse(exposure.assigned);
	}

	@Test
	void audienceStrictWhenNoRuleMatches() {
		final Context context = create(strictData);
		context.setAttribute("country", "GB");
		assertEquals(0, context.getTreatment(EXP));

		final Exposure exposure = publishedExposures(context).get(0);
		assertTrue(exposure.audienceMismatch);
		assertFalse(exposure.ruleOverride);
	}

	@Test
	void ruleWonAssignmentIsNotInvalidatedByAudienceChanges() {
		final Context context = create(strictData);
		context.setAttribute("country", "US");
		assertEquals(1, context.getTreatment(EXP));
		context.setAttribute("age", 30);
		assertEquals(1, context.getTreatment(EXP));
		assertEquals(1, context.getPendingCount());
	}

	@Test
	void invalidatesWhenRuleStartsMatching() {
		final Context context = create(rulesData);
		assertEquals(NORMAL_VARIANT, context.getTreatment(EXP));
		context.setAttribute("country", "US");
		assertEquals(1, context.getTreatment(EXP));
	}

	@Test
	void invalidatesWhenRuleStopsMatching() {
		final Context context = create(rulesData);
		context.setAttribute("country", "US");
		assertEquals(1, context.getTreatment(EXP));
		context.setAttribute("country", "GB");
		assertEquals(NORMAL_VARIANT, context.getTreatment(EXP));
	}

	@Test
	void invalidatesWhenRuleSwitchesVariant() {
		final Context context = create(data(rules(assign("US", US, "[]", 1), assign("GB", GB, "[]", 2))));
		context.setAttribute("country", "US");
		assertEquals(1, context.getTreatment(EXP));
		context.setAttribute("country", "GB");
		assertEquals(2, context.getTreatment(EXP));
		context.setAttribute("country", "US");
		assertEquals(1, context.getTreatment(EXP));
	}

	@Test
	void keepsCacheWhenUnrelatedAttributeChanges() {
		final Context context = create(rulesData);
		context.setAttribute("country", "US");
		assertEquals(1, context.getTreatment(EXP));
		context.setAttribute("unrelated", true);
		assertEquals(1, context.getTreatment(EXP));
		assertEquals(1, context.getPendingCount());
	}

	@Test
	void variableValueFollowsRuleVariant() {
		final Context context = create(rulesData);
		context.setAttribute("country", "US");
		assertEquals(1, context.getTreatment(EXP));
		assertEquals("blue", context.getVariableValue("button.color", "default"));
		assertEquals("blue", context.peekVariableValue("button.color", "default"));
	}

	@Test
	void variableValueFromRuleWithoutTreatmentCall() {
		final Context context = create(rulesData);
		context.setAttribute("country", "US");
		assertEquals("blue", context.getVariableValue("button.color", "default"));
		assertEquals(1, context.getPendingCount());

		final List<Exposure> exposures = publishedExposures(context);
		assertEquals(1, exposures.size());
		assertEquals(1, exposures.get(0).variant);
		assertTrue(exposures.get(0).ruleOverride);
		assertFalse(exposures.get(0).assigned);
	}

	// Pins current behaviour: a refresh that adds a rule to an experiment already exposed through
	// normal assignment rebuilds it with the rule's variant and queues a second, ruleOverride
	// exposure. The rule is an explicit author decision taking effect mid-session.
	@Test
	void refreshAddingRulesToExposedAssignmentReExposesWithRuleVariant() {
		final ContextData before = data(null);
		final ContextData after = data(rules(assign("US", US, "[]", 1)));
		when(dataProvider.getContextData()).thenReturn(CompletableFuture.completedFuture(after));

		final Context context = create(before);
		context.setAttribute("country", "US");
		assertEquals(NORMAL_VARIANT, context.getTreatment(EXP));

		context.refresh();
		assertEquals(1, context.getTreatment(EXP));

		final List<Exposure> exposures = publishedExposures(context);
		assertEquals(2, exposures.size());
		assertEquals(NORMAL_VARIANT, exposures.get(0).variant);
		assertFalse(exposures.get(0).ruleOverride);
		assertEquals(1, exposures.get(1).variant);
		assertTrue(exposures.get(1).ruleOverride);
	}

	@Test
	void multipleAndConditions() {
		final String conditions = "{\"and\":[{\"eq\":[{\"var\":\"country\"},{\"value\":\"US\"}]},"
				+ "{\"eq\":[{\"var\":\"plan\"},{\"value\":\"pro\"}]}]}";
		final Context context = create(data(rules(assign("US Pro", conditions, "[]", 1))));
		context.setAttribute("country", "US");
		assertEquals(NORMAL_VARIANT, context.getTreatment(EXP));
		context.setAttribute("plan", "pro");
		assertEquals(1, context.getTreatment(EXP));
	}

	@Test
	void outOfRangeRuleVariantsFallBack() {
		assertEquals(NORMAL_VARIANT, create(data(rules(assign("OOB", "null", "[]", 99)))).getTreatment(EXP));
		assertEquals(NORMAL_VARIANT, create(data(rules(assign("Neg", "null", "[]", -1)))).getTreatment(EXP));
	}

	@Test
	void outOfRangeRuleVariantIsNotARuleOverride() {
		final Context context = create(data(rules(assign("OOB", "null", "[]", 99))));
		context.getTreatment(EXP);
		assertFalse(publishedExposures(context).get(0).ruleOverride);
	}

	@Test
	void outOfRangeChangesDoNotInvalidate() {
		final Context context = create(data(rules(assign("US", US, "[]", 99), assign("GB", GB, "[]", 100))));
		context.setAttribute("country", "US");
		final int first = context.getTreatment(EXP);
		context.setAttribute("country", "GB");
		assertEquals(first, context.getTreatment(EXP));
		assertEquals(1, context.getPendingCount());
	}

	@Test
	void ruleForcingControlSetsRuleOverride() {
		final Context context = create(data(rules(assign("Force Control", US, "[]", 0))));
		context.setAttribute("country", "US");
		assertEquals(0, context.getTreatment(EXP));

		final Exposure exposure = publishedExposures(context).get(0);
		assertEquals(0, exposure.variant);
		assertTrue(exposure.ruleOverride);
	}

	@Test
	void invalidRulesJsonFallsBack() {
		assertEquals(NORMAL_VARIANT, create(data("not json")).getTreatment(EXP));
		assertEquals(NORMAL_VARIANT, create(data("")).getTreatment(EXP));
	}

	@Test
	void systemAttributesInRuleConditions() {
		final String appRule = rules(assign("Website",
				"{\"eq\":[{\"var\":\"application\"},{\"value\":\"website\"}]}", "[]", 1));
		final String envRule = rules(assign("Production",
				"{\"eq\":[{\"var\":\"environment\"},{\"value\":\"production\"}]}", "[]", 1));
		final String versionRule = rules(assign("New app",
				"{\"semver_gte\":[{\"var\":\"app_version\"},{\"value\":\"2.4.0\"}]}", "[]", 1));

		final ContextConfig enabled = ContextConfig.create().setUnit("session_id", SESSION_ID)
				.setIncludeSystemAttributes(true);
		final ContextConfig disabled = ContextConfig.create().setUnit("session_id", SESSION_ID);
		for (final String rules : new String[]{appRule, envRule, versionRule}) {
			assertEquals(1, create(enabled, data(rules)).getTreatment(EXP));
			assertEquals(NORMAL_VARIANT, create(disabled, data(rules)).getTreatment(EXP));
		}
	}

	@Test
	void refreshWithChangedRulesInvalidates() {
		final ContextData before = data(rules(assign("US", US, "[]", 1)));
		final ContextData after = data(rules(assign("US", US, "[]", 0)));
		when(dataProvider.getContextData()).thenReturn(CompletableFuture.completedFuture(after));

		final Context context = create(before);
		context.setAttribute("country", "US");
		assertEquals(1, context.getTreatment(EXP));

		context.refresh();
		assertEquals(0, context.getTreatment(EXP));
	}

	@Test
	void refreshRemovingRulesInvalidates() {
		final ContextData after = data(null);
		when(dataProvider.getContextData()).thenReturn(CompletableFuture.completedFuture(after));

		final Context context = create(rulesData);
		context.setAttribute("country", "US");
		assertEquals(1, context.getTreatment(EXP));

		context.refresh();
		assertEquals(NORMAL_VARIANT, context.getTreatment(EXP));
	}

	@Test
	void refreshWithUnchangedRulesKeepsCache() {
		when(dataProvider.getContextData()).thenReturn(CompletableFuture.completedFuture(rulesData));

		final Context context = create(rulesData);
		context.setAttribute("country", "US");
		assertEquals(1, context.getTreatment(EXP));

		context.refresh();
		assertEquals(1, context.getTreatment(EXP));
		assertEquals(1, context.getPendingCount());
	}

	@Test
	void refreshWithEquivalentRulesTextKeepsVariant() {
		final ContextData after = data(rules(assign("US renamed", US, "[]", 1)));
		when(dataProvider.getContextData()).thenReturn(CompletableFuture.completedFuture(after));

		final Context context = create(rulesData);
		context.setAttribute("country", "US");
		assertEquals(1, context.getTreatment(EXP));

		context.refresh();
		assertEquals(1, context.getTreatment(EXP));
		assertEquals(1, context.getPendingCount());
	}


	// With these seeds the unit hashes to ~0.748 with the experiment seed, so "70/10/20" lands on variant 1,
	// while normal assignment gives variant 2.
	@Test
	void splitHashesUnitWithExperimentSeed() {
		final Context context = create(data(rules(split(US, "70/10/20"))));
		context.setAttribute("country", "US");
		assertEquals(1, context.getTreatment(EXP));
	}

	@Test
	void splitAssignsOnlyNonZeroShare() {
		final Context context = create(data(rules(split(US, "0/100/0"))));
		context.setAttribute("country", "US");
		assertEquals(1, context.getTreatment(EXP));
	}

	@Test
	void splitExposureFlags() {
		final Context context = create(data(rules(split(US, "0/100/0"))));
		context.setAttribute("country", "US");
		context.getTreatment(EXP);

		assertEquals(new Exposure(2, EXP, "session_id", 1, clock.millis(), false, true, false, false, false, false,
				true), publishedExposures(context).get(0));
	}

	@Test
	void splitWinsOverCustomAssignment() {
		final Context context = create(data(rules(split(US, "0/100/0"))));
		context.setAttribute("country", "US");
		context.setCustomAssignment(EXP, 2);
		assertEquals(1, context.getTreatment(EXP));
	}

	@Test
	void splitNotCoveringEveryVariantFallsBackWithoutLaterRules() {
		final Context context = create(data(rules(split(US, "0/100"), assign("Everyone", "null", "[]", 1))));
		context.setAttribute("country", "US");
		assertEquals(NORMAL_VARIANT, context.getTreatment(EXP));
	}

	@Test
	void splitWithoutUnitGivesFullOnVariantThenReResolves() {
		final Context context = createWithoutSession(
				data(rules(split(US, "0/100/0"), assign("Everyone", "null", "[]", 2))));
		context.setAttribute("country", "US");
		assertEquals(0, context.peekTreatment(EXP));

		context.setUnit("session_id", SESSION_ID);
		assertEquals(1, context.peekTreatment(EXP));
	}

	@Test
	void assignRuleCacheSurvivesLateUnit() {
		final Context context = createWithoutSession(rulesData);
		context.setAttribute("country", "US");
		assertEquals(1, context.peekTreatment(EXP));

		context.setUnit("session_id", SESSION_ID);
		assertEquals(1, context.getTreatment(EXP));
		assertEquals(1, context.getPendingCount());
	}

	@Test
	void reResolvesWhenSplitStartsMatchingOnSameVariantAsPreviousRule() {
		final Context context = createWithoutSession(
				data(rules(split(US, "0/100/0"), assign("Everyone", "null", "[]", 0))));
		assertEquals(0, context.peekTreatment(EXP));

		context.setAttribute("country", "US");
		assertEquals(0, context.peekTreatment(EXP));

		context.setUnit("session_id", SESSION_ID);
		assertEquals(1, context.peekTreatment(EXP));
	}

	@Test
	void exposedSplitWithoutUnitStaysPinned() {
		final Context context = createWithoutSession(data(rules(split(US, "0/100/0"))));
		context.setAttribute("country", "US");
		assertEquals(0, context.getTreatment(EXP));

		context.setUnit("session_id", SESSION_ID);
		context.setAttribute("unrelated", true);
		assertEquals(0, context.getTreatment(EXP));

		final List<Exposure> exposures = publishedExposures(context);
		assertEquals(1, exposures.size());
		assertEquals(0, exposures.get(0).variant);
	}

	// An exposed assign-rule assignment whose matching rule becomes a split resolved without its unit
	// (same variant) must be pinned, so a unit set afterwards cannot switch the variant the user saw.
	@Test
	void exposedAssignThenSplitWithoutUnitOnSameVariantStaysPinned() {
		final Context context = createWithoutSession(
				data(rules(assign("GB", GB, "[]", 0), split(US, "0/100/0"))));
		context.setAttribute("country", "GB");
		assertEquals(0, context.getTreatment(EXP));

		context.setAttribute("country", "US");
		assertEquals(0, context.getTreatment(EXP));

		context.setUnit("session_id", SESSION_ID);
		assertEquals(0, context.getTreatment(EXP));
		assertEquals(0, context.getTreatment(EXP));

		final List<Exposure> exposures = publishedExposures(context);
		assertEquals(1, exposures.size());
		assertEquals(0, exposures.get(0).variant);
		assertTrue(exposures.get(0).ruleOverride);
	}

	// Exposed branch of ruleMatches: the rule outcome changes from "split without unit" to the
	// same variant from an assign rule. The exposed assignment is kept and never re-exposed.
	@Test
	void exposedSplitWithoutUnitThenAssignOnSameVariantIsNotReExposed() {
		final Context context = createWithoutSession(
				data(rules(split(US, "0/100/0"), assign("GB", GB, "[]", 0))));
		context.setAttribute("country", "US");
		assertEquals(0, context.getTreatment(EXP));

		context.setAttribute("country", "GB");
		assertEquals(0, context.getTreatment(EXP));

		context.setUnit("session_id", SESSION_ID);
		assertEquals(0, context.getTreatment(EXP));

		final List<Exposure> exposures = publishedExposures(context);
		assertEquals(1, exposures.size());
		assertEquals(0, exposures.get(0).variant);
	}

	// A rule-won assignment is not "assigned"; once the experiment disappears it must not keep
	// serving the rule's variant.
	@Test
	void ruleVariantDroppedWhenExperimentIsRemoved() {
		final Context context = create(rulesData);
		context.setAttribute("country", "US");
		assertEquals(1, context.getTreatment(EXP));

		final ContextData without = data(rules(assign("US Internal Users", US, "[]", 1)));
		final List<Experiment> remaining = new ArrayList<Experiment>();
		for (final Experiment experiment : without.experiments) {
			if (!experiment.name.equals(EXP)) {
				remaining.add(experiment);
			}
		}
		without.experiments = remaining.toArray(new Experiment[0]);
		when(dataProvider.getContextData()).thenReturn(CompletableFuture.completedFuture(without));
		context.refresh();

		assertEquals(0, context.peekTreatment(EXP));
		assertEquals(0, context.getTreatment(EXP));
	}

	@Test
	void exposedSplitWithoutUnitStaysPinnedWhenCustomAssignmentLost() {
		final Context context = createWithoutSession(data(rules(split(US, "0/100/0"))));
		context.setAttribute("country", "US");
		context.setCustomAssignment(EXP, 2);
		assertEquals(0, context.getTreatment(EXP));

		context.setUnit("session_id", SESSION_ID);
		assertEquals(0, context.getTreatment(EXP));

		final List<Exposure> exposures = publishedExposures(context);
		assertEquals(1, exposures.size());
		assertEquals(0, exposures.get(0).variant);
	}

	@Test
	void splitWithoutUnitOnFullOnExperiment() {
		final ContextData fullOn = data(rules(split("null", "0/100/0")));
		for (final Experiment experiment : fullOn.experiments) {
			if (experiment.name.equals(EXP)) {
				experiment.fullOnVariant = 2;
			}
		}
		final Context context = createWithoutSession(fullOn);
		assertEquals(2, context.peekTreatment(EXP));

		context.setUnit("session_id", SESSION_ID);
		assertEquals(1, context.peekTreatment(EXP));
	}

	@Test
	void splitWithUnitTypeMissingFromExperiment() {
		final ContextData noUnitType = data(rules(split("null", "0/100/0")));
		for (final Experiment experiment : noUnitType.experiments) {
			if (experiment.name.equals(EXP)) {
				experiment.unitType = null;
			}
		}
		assertEquals(0, create(noUnitType).peekTreatment(EXP));
	}

	@Test
	void splitOnReadPathUsesUncachedAssigner() {
		// The first lookup happens before any assigner for session_id is cached, so the read path's
		// re-resolution after an attribute change must hash the unit itself.
		final Context context = create(data(rules(split(US, "0/100/0"))));
		context.setAttribute("country", "GB");
		assertEquals(NORMAL_VARIANT, context.peekTreatment(EXP));
		context.setAttribute("country", "US");
		assertEquals(1, context.peekTreatment(EXP));
	}

	@Test
	void splitReResolutionOnReadPathWithoutCachedHash() {
		final Context context = create(
				ContextConfig.create().setUnit("session_id", SESSION_ID).setUnit("user_id", USER_ID),
				data(rules(assign("GB", GB, "[]", 0), split(US, "0/100/0"))));
		context.setAttribute("country", "GB");
		assertEquals(0, context.peekTreatment(EXP));
		context.setAttribute("country", "US");
		assertEquals(1, context.peekTreatment(EXP));
	}

	@Test
	void ruleOnExperimentWithoutVariants() {
		final ContextData data = data(rules(assign("Any", "null", "[]", 0)));
		for (final Experiment experiment : data.experiments) {
			if (experiment.name.equals(EXP)) {
				experiment.variants = null;
			}
		}
		assertEquals(NORMAL_VARIANT, create(data).peekTreatment(EXP));
	}

	@Test
	void customAssignmentChangeKeepsRuleWonCache() {
		final Context context = create(rulesData);
		context.setAttribute("country", "US");
		assertEquals(1, context.getTreatment(EXP));
		context.setCustomAssignment(EXP, 0);
		assertEquals(1, context.getTreatment(EXP));
		assertEquals(1, context.getPendingCount());
	}

	@Test
	void customFieldAndVariableKeysUnaffected() {
		final Context context = create(rulesData);
		context.setAttribute("country", "US");
		final Map<String, List<String>> keys = context.getVariableKeys();
		assertTrue(keys.containsKey("button.color"));
	}

	// Readers race attribute writers that flip the matching rule and a refresh writer that swaps the
	// rules between two equivalent sets. Rules: US -> assign 1, GB -> split all to 0, anything else
	// -> normal assignment (NORMAL_VARIANT). Every read must be one of those outcomes, and every
	// published exposure must be consistent: ruleOverride exposures carry 0 or 1, the normal one
	// carries NORMAL_VARIANT. Read-path re-resolution must not mutate the cached assignment.
	@Test
	void concurrentAttributeChangesAndReads() throws Exception {
		final String rulesA = rules(assign("US", US, "[]", 1), split(GB, "100/0/0"));
		final String rulesB = rules(split(GB, "100/0/0"), assign("US", US, "[]", 1));
		when(dataProvider.getContextData()).thenAnswer(new org.mockito.stubbing.Answer<Object>() {
			int calls = 0;

			@Override
			public synchronized Object answer(org.mockito.invocation.InvocationOnMock invocation) {
				return CompletableFuture.completedFuture(data((++calls % 2 == 0) ? rulesA : rulesB));
			}
		});

		final Context context = create(data(rulesA));
		final java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(8);
		final java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
		final java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
		final List<java.util.concurrent.Future<?>> futures = new ArrayList<java.util.concurrent.Future<?>>();
		try {
			for (int t = 0; t < 8; ++t) {
				final int thread = t;
				futures.add(pool.submit(new Runnable() {
					@Override
					public void run() {
						try {
							start.await();
							for (int i = 0; i < 500; ++i) {
								if (thread == 0) {
									if (i % 25 == 0) {
										context.refresh();
									}
								} else if (thread % 2 == 0) {
									context.setAttribute("country", (i % 3 == 0) ? "US" : (i % 3 == 1) ? "GB" : "PT");
								} else {
									final int variant = (i % 2 == 0) ? context.peekTreatment(EXP)
											: context.getTreatment(EXP);
									if (variant != 0 && variant != 1 && variant != NORMAL_VARIANT) {
										throw new AssertionError("unexpected variant " + variant);
									}
								}
							}
						} catch (Throwable e) {
							failure.compareAndSet(null, e);
						}
					}
				}));
			}
			start.countDown();
			for (final java.util.concurrent.Future<?> future : futures) {
				future.get(30, java.util.concurrent.TimeUnit.SECONDS);
			}
		} finally {
			pool.shutdownNow();
		}
		assertNull(failure.get());

		context.setAttribute("country", "US");
		assertEquals(1, context.getTreatment(EXP));
		context.setAttribute("country", "GB");
		assertEquals(0, context.getTreatment(EXP));
		context.setAttribute("country", "PT");
		assertEquals(NORMAL_VARIANT, context.getTreatment(EXP));

		final List<Exposure> exposures = publishedExposures(context);
		assertFalse(exposures.isEmpty());
		for (final Exposure exposure : exposures) {
			if (exposure.ruleOverride) {
				assertTrue(exposure.variant == 0 || exposure.variant == 1, exposure.toString());
				assertFalse(exposure.assigned, exposure.toString());
			} else {
				assertEquals(NORMAL_VARIANT, exposure.variant, exposure.toString());
				assertTrue(exposure.assigned, exposure.toString());
			}
		}
	}

	// A split resolved without its unit races setUnit from another thread. Whatever interleaving
	// happens, the variant exposed for the experiment is never published twice with different values.
	@Test
	void concurrentLateUnitAndExposure() throws Exception {
		for (int round = 0; round < 50; ++round) {
			reset(eventHandler);
			when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.<Void> completedFuture(null));

			final Context context = createWithoutSession(data(rules(split("null", "0/100/0"))));
			final java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
			final Thread setter = new Thread(new Runnable() {
				@Override
				public void run() {
					try {
						start.await();
						context.setUnit("session_id", SESSION_ID);
					} catch (InterruptedException ignored) {}
				}
			});
			setter.start();
			start.countDown();
			final int first = context.getTreatment(EXP);
			setter.join(10000);
			final int second = context.getTreatment(EXP);

			assertEquals(first, second);
			final List<Exposure> exposures = publishedExposures(context);
			assertEquals(1, exposures.size());
			assertEquals(first, exposures.get(0).variant);
		}
	}
}
