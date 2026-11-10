package com.absmartly.sdk;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import com.absmartly.sdk.internal.SdkVersion;
import com.absmartly.sdk.java.time.Clock;
import com.absmartly.sdk.json.Attribute;
import com.absmartly.sdk.json.ContextData;
import com.absmartly.sdk.json.PublishEvent;

class ContextSystemAttributesTest extends TestUtils {
	final Clock clock = Clock.fixed(1_620_000_000_000L);
	ContextData audienceData;
	ContextDataProvider dataProvider;
	ContextEventHandler eventHandler;
	ScheduledExecutorService scheduler;

	@BeforeEach
	void setUp() {
		final byte[] bytes = getResourceBytes("audience_context.json");
		audienceData = new DefaultContextDataDeserializer().deserialize(bytes, 0, bytes.length);
		dataProvider = mock(ContextDataProvider.class);
		eventHandler = mock(ContextEventHandler.class);
		scheduler = mock(ScheduledExecutorService.class);
		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.<Void> completedFuture(null));
	}

	Client client(String applicationVersion) {
		final ClientConfig config = ClientConfig.create()
				.setEndpoint("https://localhost/v1")
				.setAPIKey("key")
				.setApplication("website")
				.setEnvironment("production");
		if (applicationVersion != null) {
			config.setApplicationVersion(applicationVersion);
		}
		return Client.create(config, mock(HTTPClient.class));
	}

	Context createContext(ContextConfig config, Client client, ContextData data) {
		return Context.create(clock, config, scheduler, CompletableFuture.completedFuture(data), dataProvider,
				eventHandler, null, new DefaultVariableParser(), new AudienceMatcher(new DefaultAudienceDeserializer()),
				client);
	}

	PublishEvent publishAndCapture(Context context) {
		context.publish();
		final ArgumentCaptor<PublishEvent> captor = ArgumentCaptor.forClass(PublishEvent.class);
		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(any(), captor.capture());
		return captor.getValue();
	}

	@Test
	void disabledByDefault() {
		final ContextConfig config = ContextConfig.create().setUnit("session_id", "abc");
		assertFalse(config.getIncludeSystemAttributes());

		final Context context = createContext(config, client("2.4.1"), audienceData);
		context.setAttribute("age", 21);
		context.track("goal", null);

		final PublishEvent event = publishAndCapture(context);
		assertArrayEquals(new Attribute[]{new Attribute("age", 21, clock.millis())}, event.attributes);
	}

	@Test
	void publishPrependsSystemAttributes() {
		final ContextConfig config = ContextConfig.create().setUnit("session_id", "abc")
				.setIncludeSystemAttributes(true);
		final Context context = createContext(config, client("2.4.1"), audienceData);
		context.setAttribute("age", 21);
		context.track("goal", null);

		final PublishEvent event = publishAndCapture(context);
		final long now = clock.millis();
		assertArrayEquals(new Attribute[]{
				new Attribute("sdk_name", "absmartly-java-sdk", now),
				new Attribute("sdk_version", SdkVersion.VERSION, now),
				new Attribute("application", "website", now),
				new Attribute("environment", "production", now),
				new Attribute("app_version", "2.4.1", now),
				new Attribute("age", 21, now),
		}, event.attributes);
	}

	@Test
	void publishOmitsAppVersionWhenNotSet() {
		for (final String version : new String[]{null, "", "  ", "0"}) {
			reset(eventHandler);
			when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.<Void> completedFuture(null));

			final ContextConfig config = ContextConfig.create().setUnit("session_id", "abc")
					.setIncludeSystemAttributes(true);
			final Context context = createContext(config, client(version), audienceData);
			context.track("goal", null);

			final PublishEvent event = publishAndCapture(context);
			assertEquals(4, event.attributes.length);
			for (final Attribute attr : event.attributes) {
				assertNotEquals("app_version", attr.name);
			}
		}
	}

	// Without a client only the SDK's own name and version are known; the client-supplied values are
	// left out instead of being published (or evaluated) as null.
	@Test
	void publishWithoutClientSendsOnlySdkAttributes() {
		final ContextConfig config = ContextConfig.create().setUnit("session_id", "abc")
				.setIncludeSystemAttributes(true);
		final Context context = createContext(config, null, audienceData);
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

	// The collector only applies attributes set at or before an event, so system attributes take the time of the
	// earliest event in the publish, not the publish time.
	@Test
	void publishStampsSystemAttributesWithEarliestEvent() {
		final MovingClock movingClock = new MovingClock(100);
		final ContextConfig config = ContextConfig.create().setUnit("session_id", "abc")
				.setIncludeSystemAttributes(true);
		final Context context = Context.create(movingClock, config, scheduler,
				CompletableFuture.completedFuture(audienceData), dataProvider, eventHandler, null,
				new DefaultVariableParser(), new AudienceMatcher(new DefaultAudienceDeserializer()), client("2.4.1"));
		context.setAttribute("age", 21);
		movingClock.now = 250;
		context.track("goal", null);
		movingClock.now = 200;
		context.getTreatment("exp_test_ab");
		movingClock.now = 300;

		final PublishEvent event = publishAndCapture(context);
		assertEquals(300, event.publishedAt);
		assertArrayEquals(new Attribute[]{
				new Attribute("sdk_name", "absmartly-java-sdk", 200),
				new Attribute("sdk_version", SdkVersion.VERSION, 200),
				new Attribute("application", "website", 200),
				new Attribute("environment", "production", 200),
				new Attribute("app_version", "2.4.1", 200),
				new Attribute("age", 21, 100),
		}, event.attributes);
	}

	// A system attribute the user set themselves is sent only as the user's value, whether the event is earlier
	// than the publish or happens at publish time, so the collector cannot prefer the synthetic one.
	@Test
	void publishSkipsSystemAttributesTheUserSet() {
		for (final long eventAt : new long[]{200, 300}) {
			reset(eventHandler);
			when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.<Void> completedFuture(null));

			final MovingClock movingClock = new MovingClock(100);
			final ContextConfig config = ContextConfig.create().setUnit("session_id", "abc")
					.setIncludeSystemAttributes(true);
			final Context context = Context.create(movingClock, config, scheduler,
					CompletableFuture.completedFuture(audienceData), dataProvider, eventHandler, null,
					new DefaultVariableParser(), new AudienceMatcher(new DefaultAudienceDeserializer()),
					client("2.4.1"));
			context.setAttribute("environment", "staging");
			context.setAttribute("app_version", "9.9.9");
			movingClock.now = eventAt;
			context.getTreatment("exp_test_ab");
			movingClock.now = 300;

			final PublishEvent event = publishAndCapture(context);
			assertArrayEquals(new Attribute[]{
					new Attribute("sdk_name", "absmartly-java-sdk", eventAt),
					new Attribute("sdk_version", SdkVersion.VERSION, eventAt),
					new Attribute("application", "website", eventAt),
					new Attribute("environment", "staging", 100),
					new Attribute("app_version", "9.9.9", 100),
			}, event.attributes);
		}
	}

	@Test
	void noClientWarningIsLoggedOncePerJvm() {
		final ContextConfig config = ContextConfig.create().setIncludeSystemAttributes(true);
		createContext(config, null, audienceData);
		assertTrue(Context.noClientWarningLogged());
		createContext(config, null, audienceData);
		assertTrue(Context.noClientWarningLogged());
	}

	@Test
	void systemAttributesAreAudienceVariables() {
		final ContextData data = new DefaultContextDataDeserializer().deserialize(
				getResourceBytes("audience_context.json"), 0, getResourceBytes("audience_context.json").length);
		data.experiments[0].audienceStrict = true;
		data.experiments[0].audience = "{\"filter\":[{\"and\":[{\"eq\":[{\"var\":\"application\"},{\"value\":\"website\"}]},"
				+ "{\"eq\":[{\"var\":\"environment\"},{\"value\":\"production\"}]},"
				+ "{\"semver_gte\":[{\"var\":\"app_version\"},{\"value\":\"2.0.0\"}]}]}]}";

		final ContextConfig enabled = ContextConfig.create()
				.setUnit("session_id", "e791e240fcd3df7d238cfc285f475e8152fcc0ec")
				.setIncludeSystemAttributes(true);
		assertEquals(1, createContext(enabled, client("2.4.1"), data).getTreatment("exp_test_ab"));
		assertEquals(0, createContext(enabled, client("1.9.9"), data).getTreatment("exp_test_ab"));

		final ContextConfig disabled = ContextConfig.create().setUnit("session_id",
				"e791e240fcd3df7d238cfc285f475e8152fcc0ec");
		assertEquals(0, createContext(disabled, client("2.4.1"), data).getTreatment("exp_test_ab"));
	}

	@Test
	void userAttributesOverrideSystemAttributesInEvaluation() {
		final ContextData data = new DefaultContextDataDeserializer().deserialize(
				getResourceBytes("audience_context.json"), 0, getResourceBytes("audience_context.json").length);
		data.experiments[0].audienceStrict = true;
		data.experiments[0].audience = "{\"filter\":[{\"eq\":[{\"var\":\"environment\"},{\"value\":\"staging\"}]}]}";

		final ContextConfig config = ContextConfig.create()
				.setUnit("session_id", "e791e240fcd3df7d238cfc285f475e8152fcc0ec")
				.setIncludeSystemAttributes(true);
		final Context context = createContext(config, client("2.4.1"), data);
		assertEquals(0, context.getTreatment("exp_test_ab"));

		context.setAttribute("environment", "staging");
		final Map<String, Object> attrs = context.getAttributes();
		assertEquals(1, attrs.size());
		assertEquals(1, context.getTreatment("exp_test_ab"));
	}

	@Test
	void absmartlyPassesConfiguredClientToContext() throws Exception {
		final ABSmartly absmartly = ABSmartly.create(ABSmartlyConfig.create()
				.setClient(client("3.0.0"))
				.setContextDataProvider(dataProvider)
				.setContextEventHandler(eventHandler)
				.setScheduler(scheduler));
		final Context context = absmartly.createContextWith(
				ContextConfig.create().setUnit("session_id", "abc").setIncludeSystemAttributes(true), audienceData);
		context.track("goal", null);

		final PublishEvent event = publishAndCapture(context);
		assertEquals(new Attribute("environment", "production", clock.millis()).name, event.attributes[3].name);
		assertEquals("production", event.attributes[3].value);
		assertEquals("3.0.0", event.attributes[4].value);
	}
}
