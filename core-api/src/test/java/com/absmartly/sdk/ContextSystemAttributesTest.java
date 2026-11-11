package com.absmartly.sdk;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java8.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import com.absmartly.sdk.internal.SdkVersion;
import com.absmartly.sdk.java.time.Clock;
import com.absmartly.sdk.json.Attribute;
import com.absmartly.sdk.json.ContextData;
import com.absmartly.sdk.json.Exposure;
import com.absmartly.sdk.json.PublishEvent;

class ContextSystemAttributesTest extends TestUtils {
	static final String EXP = "exp_test_ab";
	static final String SESSION_ID = "e791e240fcd3df7d238cfc285f475e8152fcc0ec";

	final Clock clock = Clock.fixed(1_620_000_000_000L);
	ContextDataProvider dataProvider;
	ContextEventHandler eventHandler;
	ScheduledExecutorService scheduler;

	@BeforeEach
	void setUp() {
		dataProvider = mock(ContextDataProvider.class);
		eventHandler = mock(ContextEventHandler.class);
		scheduler = mock(ScheduledExecutorService.class);
		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.<Void> completedFuture(null));
	}

	ContextData audienceData() {
		final byte[] bytes = getResourceBytes("audience_context.json");
		return new DefaultContextDataDeserializer().deserialize(bytes, 0, bytes.length);
	}

	// A copy of audience_context.json with exp_test_ab made a strict 0/100 split to variant 1 (B) gated by the
	// given audience filter, so the audience decides the variant.
	ContextData strictAudience(String filter) {
		final ContextData data = audienceData();
		data.experiments[0].audienceStrict = true;
		data.experiments[0].trafficSplit = new double[]{0.0, 1.0};
		data.experiments[0].split = new double[]{0.0, 1.0};
		data.experiments[0].audience = "{\"filter\":" + filter + "}";
		return data;
	}

	Client client() {
		return client("website", "production", null);
	}

	Client client(String application, String environment, String applicationVersion) {
		final ClientConfig config = ClientConfig.create()
				.setEndpoint("https://localhost/v1")
				.setAPIKey("key");
		if (application != null) {
			config.setApplication(application);
		}
		if (environment != null) {
			config.setEnvironment(environment);
		}
		if (applicationVersion != null) {
			config.setApplicationVersion(applicationVersion);
		}
		return Client.create(config, mock(HTTPClient.class));
	}

	// Client.create rejects an empty application or environment, so a context whose client has no such configuration
	// is built with a stubbed client that reports the missing value.
	Client stubbedClient(String application, String environment, String applicationVersion) {
		final Client client = mock(Client.class);
		when(client.getApplication()).thenReturn(application);
		when(client.getEnvironment()).thenReturn(environment);
		when(client.getApplicationVersion()).thenReturn(applicationVersion);
		return client;
	}

	Context createContext(ContextConfig config, Client client, ContextData data) {
		return Context.create(clock, config, scheduler, CompletableFuture.completedFuture(data), dataProvider,
				eventHandler, null, new DefaultVariableParser(), new AudienceMatcher(new DefaultAudienceDeserializer()),
				client);
	}

	ContextConfig config() {
		return ContextConfig.create().setUnit("session_id", SESSION_ID);
	}

	PublishEvent publishAndCapture(Context context) {
		context.publish();
		final ArgumentCaptor<PublishEvent> captor = ArgumentCaptor.forClass(PublishEvent.class);
		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(any(), captor.capture());
		return captor.getValue();
	}

	Exposure exposureOf(PublishEvent event, String name) {
		for (final Exposure exposure : event.exposures) {
			if (exposure.name.equals(name)) {
				return exposure;
			}
		}
		return null;
	}

	// §4.1: a strict audience on the configured environment matches and gives the full-on variant, not variant 0.
	@Test
	void strictAudienceOnEnvironmentMatches() {
		final Context context = createContext(config(), client(),
				strictAudience("[{\"eq\":[{\"var\":\"environment\"},{\"value\":\"production\"}]}]"));
		assertEquals(1, context.getTreatment(EXP));
		assertEquals("large", context.getVariableValue("banner.size", "default"));

		final Exposure exposure = exposureOf(publishAndCapture(context), EXP);
		assertEquals(1, exposure.variant);
		assertFalse(exposure.audienceMismatch);
	}

	// §4.2: a strict audience on the configured application matches.
	@Test
	void strictAudienceOnApplicationMatches() {
		final Context context = createContext(config(), client(),
				strictAudience("[{\"eq\":[{\"var\":\"application\"},{\"value\":\"website\"}]}]"));
		assertEquals(1, context.getTreatment(EXP));

		final Exposure exposure = exposureOf(publishAndCapture(context), EXP);
		assertFalse(exposure.audienceMismatch);
	}

	// §4.3: a strict audience naming another environment/application gives variant 0 and audienceMismatch.
	@Test
	void strictAudienceOnAnotherEnvironmentGivesVariantZero() {
		final Context context = createContext(config(), client(),
				strictAudience("[{\"eq\":[{\"var\":\"environment\"},{\"value\":\"staging\"}]}]"));
		assertEquals(0, context.getTreatment(EXP));

		final Exposure exposure = exposureOf(publishAndCapture(context), EXP);
		assertEquals(0, exposure.variant);
		assertTrue(exposure.audienceMismatch);
	}

	@Test
	void strictAudienceOnAnotherApplicationGivesVariantZero() {
		final Context context = createContext(config(), client(),
				strictAudience("[{\"eq\":[{\"var\":\"application\"},{\"value\":\"mobile\"}]}]"));
		assertEquals(0, context.getTreatment(EXP));
		assertTrue(exposureOf(publishAndCapture(context), EXP).audienceMismatch);
	}

	// §4.4: a user value set after creation wins; the cached assignment is re-checked.
	@Test
	void userSetEnvironmentWinsSetAfterCreation() {
		final Context staging = createContext(config(), client(),
				strictAudience("[{\"eq\":[{\"var\":\"environment\"},{\"value\":\"staging\"}]}]"));
		staging.setAttribute("environment", "staging");
		assertEquals(1, staging.getTreatment(EXP));

		final Context production = createContext(config(), client(),
				strictAudience("[{\"eq\":[{\"var\":\"environment\"},{\"value\":\"production\"}]}]"));
		production.setAttribute("environment", "staging");
		assertEquals(0, production.getTreatment(EXP));
		assertTrue(exposureOf(publishAndCapture(production), EXP).audienceMismatch);
	}

	@Test
	void invalidatesAssignmentCachedBeforeUserSetEnvironment() {
		final Context context = createContext(config(), client(),
				strictAudience("[{\"eq\":[{\"var\":\"environment\"},{\"value\":\"staging\"}]}]"));
		assertEquals(0, context.peekTreatment(EXP));

		context.setAttribute("environment", "staging");
		assertEquals(1, context.peekTreatment(EXP));
		assertEquals(1, context.getTreatment(EXP));
		assertFalse(exposureOf(publishAndCapture(context), EXP).audienceMismatch);
	}

	@Test
	void invalidatesMatchingAssignmentOnceUserOverridesEnvironment() {
		final Context context = createContext(config(), client(),
				strictAudience("[{\"eq\":[{\"var\":\"environment\"},{\"value\":\"production\"}]}]"));
		assertEquals(1, context.peekTreatment(EXP));

		context.setAttribute("environment", "staging");
		assertEquals(0, context.getTreatment(EXP));
		assertTrue(exposureOf(publishAndCapture(context), EXP).audienceMismatch);
	}

	@Test
	void userSetApplicationWins() {
		final Context context = createContext(config(), client(),
				strictAudience("[{\"eq\":[{\"var\":\"application\"},{\"value\":\"mobile\"}]}]"));
		context.setAttribute("application", "mobile");
		assertEquals(1, context.getTreatment(EXP));
	}

	// §4.6: no environment configured means no key in the map, no crash, and a filter on environment does not match
	// while a null-check on it does.
	@Test
	void missingEnvironmentLeavesKeyOutOfMap() {
		for (final String environment : new String[]{null, ""}) {
			final Context matching = createContext(config(), stubbedClient("website", environment, null),
					strictAudience("[{\"eq\":[{\"var\":\"environment\"},{\"value\":\"production\"}]}]"));
			assertEquals(0, matching.getTreatment(EXP));

			final Context absent = createContext(config(), stubbedClient("website", environment, null),
					strictAudience("[{\"null\":{\"var\":\"environment\"}}]"));
			assertEquals(1, absent.getTreatment(EXP));
			final Map<String, Object> attrs = absent.buildAttributesMapForTest();
			assertEquals(1, attrs.size());
			assertEquals("website", attrs.get("application"));
		}
	}

	@Test
	void missingApplicationLeavesKeyOutOfMap() {
		final Context context = createContext(config(), stubbedClient("", "production", null),
				strictAudience("[{\"eq\":[{\"var\":\"application\"},{\"value\":\"\"}]}]"));
		assertEquals(0, context.getTreatment(EXP));
		final Map<String, Object> attrs = context.buildAttributesMapForTest();
		assertEquals(1, attrs.size());
		assertEquals("production", attrs.get("environment"));
	}

	// §4.8: app_version is never provided to audiences, even with an application version configured, whatever the flag.
	@Test
	void appVersionIsNotAnAudienceVariable() {
		for (final String version : new String[]{"3", "1.2.3"}) {
			for (final boolean include : new boolean[]{false, true}) {
				final Context context = createContext(config().setIncludeSystemAttributes(include),
						client("website", "production", version),
						strictAudience("[{\"eq\":[{\"var\":\"app_version\"},{\"value\":\"" + version + "\"}]}]"));
				assertEquals(0, context.getTreatment(EXP));
				final Map<String, Object> attrs = context.buildAttributesMapForTest();
				assertEquals(2, attrs.size());
				assertEquals("website", attrs.get("application"));
				assertEquals("production", attrs.get("environment"));
			}
		}
	}

	@Test
	void userSetAppVersionIsStillEvaluated() {
		final Context context = createContext(config(), client("website", "production", "1.2.3"),
				strictAudience("[{\"eq\":[{\"var\":\"app_version\"},{\"value\":\"1.2.3\"}]}]"));
		context.setAttribute("app_version", "1.2.3");
		assertEquals(1, context.getTreatment(EXP));
	}

	// §4.5: system attributes are not real attributes; getAttributes/getAttribute do not return them.
	@Test
	void systemAttributesAreNotReportedAsUserAttributes() {
		final Context context = createContext(config(), client(), audienceData());
		context.setAttribute("age", 25);
		final Map<String, Object> attrs = context.getAttributes();
		assertEquals(1, attrs.size());
		assertEquals(25, attrs.get("age"));
		assertNull(context.getAttribute("environment"));
		assertNull(context.getAttribute("application"));
	}

	// Flag compatibility: evaluation uses the system attributes whether the flag is unset, false or true.
	@Test
	void evaluationUsesSystemAttributesRegardlessOfFlag() {
		assertEquals(1, createContext(config(), client(),
				strictAudience("[{\"eq\":[{\"var\":\"environment\"},{\"value\":\"production\"}]}]")).getTreatment(EXP));
		assertEquals(1, createContext(config().setIncludeSystemAttributes(false), client(),
				strictAudience("[{\"eq\":[{\"var\":\"environment\"},{\"value\":\"production\"}]}]")).getTreatment(EXP));
		assertEquals(1, createContext(config().setIncludeSystemAttributes(true), client(),
				strictAudience("[{\"eq\":[{\"var\":\"environment\"},{\"value\":\"production\"}]}]")).getTreatment(EXP));
	}

	// §4.7 (in-memory): publishing adds no application/environment/app_version, with the flag unset.
	@Test
	void publishAddsNothingByDefault() {
		final ContextConfig config = config();
		assertFalse(config.getIncludeSystemAttributes());

		final Context context = createContext(config, client("website", "production", "2.4.1"), audienceData());
		context.setAttribute("age", 21);
		context.track("goal", null);

		final PublishEvent event = publishAndCapture(context);
		assertArrayEquals(new Attribute[]{new Attribute("age", 21, clock.millis())}, event.attributes);
	}

	// §4.7 (in-memory): with the flag on, only sdk_name and sdk_version are prepended; no application/environment/
	// app_version even after they were used for evaluation.
	@Test
	void publishPrependsOnlySdkAttributesWhenEnabled() {
		final ContextConfig config = config().setIncludeSystemAttributes(true);
		final Context context = createContext(config, client("website", "production", "2.4.1"),
				strictAudience("[{\"eq\":[{\"var\":\"environment\"},{\"value\":\"production\"}]}]"));
		context.setAttribute("age", 21);
		assertEquals(1, context.getTreatment(EXP));

		final PublishEvent event = publishAndCapture(context);
		final long now = clock.millis();
		assertArrayEquals(new Attribute[]{
				new Attribute("sdk_name", "absmartly-java-sdk", now),
				new Attribute("sdk_version", SdkVersion.VERSION, now),
				new Attribute("age", 21, now),
		}, event.attributes);
	}

	@Test
	void publishWithoutClientSendsOnlySdkAttributes() {
		final ContextConfig config = config().setIncludeSystemAttributes(true);
		final Context context = createContext(config, null, audienceData());
		context.setAttribute("age", 21);
		context.track("goal", null);

		final PublishEvent event = publishAndCapture(context);
		final long now = clock.millis();
		assertArrayEquals(new Attribute[]{
				new Attribute("sdk_name", "absmartly-java-sdk", now),
				new Attribute("sdk_version", SdkVersion.VERSION, now),
				new Attribute("age", 21, now),
		}, event.attributes);
	}

	static class MovingClock extends Clock {
		long now;

		MovingClock(long now) {
			this.now = now;
		}

		@Override
		public long millis() {
			return now;
		}
	}

	// The collector only applies attributes set at or before an event, so the published system attributes take the
	// time of the earliest event in the publish, not the publish time.
	@Test
	void publishStampsSystemAttributesWithEarliestEvent() {
		final MovingClock movingClock = new MovingClock(100);
		final ContextConfig config = config().setIncludeSystemAttributes(true);
		final Context context = Context.create(movingClock, config, scheduler,
				CompletableFuture.completedFuture(audienceData()), dataProvider, eventHandler, null,
				new DefaultVariableParser(), new AudienceMatcher(new DefaultAudienceDeserializer()),
				client("website", "production", "2.4.1"));
		context.setAttribute("age", 21);
		movingClock.now = 250;
		context.track("goal", null);
		movingClock.now = 200;
		context.getTreatment(EXP);
		movingClock.now = 300;

		final PublishEvent event = publishAndCapture(context);
		assertEquals(300, event.publishedAt);
		assertArrayEquals(new Attribute[]{
				new Attribute("sdk_name", "absmartly-java-sdk", 200),
				new Attribute("sdk_version", SdkVersion.VERSION, 200),
				new Attribute("age", 21, 100),
		}, event.attributes);
	}

	// A user-set system-named attribute is published only as the user's value, whether the event is earlier than the
	// publish or happens at publish time, so the collector cannot prefer a synthetic one.
	@Test
	void publishSkipsSdkAttributesTheUserSet() {
		for (final long eventAt : new long[]{200, 300}) {
			reset(eventHandler);
			when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.<Void> completedFuture(null));

			final MovingClock movingClock = new MovingClock(100);
			final ContextConfig config = config().setIncludeSystemAttributes(true);
			final Context context = Context.create(movingClock, config, scheduler,
					CompletableFuture.completedFuture(audienceData()), dataProvider, eventHandler, null,
					new DefaultVariableParser(), new AudienceMatcher(new DefaultAudienceDeserializer()),
					client("website", "production", "2.4.1"));
			context.setAttribute("sdk_name", "custom-wrapper");
			movingClock.now = eventAt;
			context.getTreatment(EXP);
			movingClock.now = 300;

			final PublishEvent event = publishAndCapture(context);
			assertArrayEquals(new Attribute[]{
					new Attribute("sdk_version", SdkVersion.VERSION, eventAt),
					new Attribute("sdk_name", "custom-wrapper", 100),
			}, event.attributes);
		}
	}

	@Test
	void noClientWarningIsLoggedOncePerJvm() {
		final ContextConfig config = ContextConfig.create().setIncludeSystemAttributes(true);
		createContext(config, null, audienceData());
		assertTrue(Context.noClientWarningLogged());
		createContext(config, null, audienceData());
		assertTrue(Context.noClientWarningLogged());
	}
}
