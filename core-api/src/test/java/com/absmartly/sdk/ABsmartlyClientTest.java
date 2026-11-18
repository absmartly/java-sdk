package com.absmartly.sdk;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.absmartly.sdk.event.CloseEvent;
import com.absmartly.sdk.event.ContextEvent;
import com.absmartly.sdk.event.ErrorEvent;
import com.absmartly.sdk.event.ExposureEvent;
import com.absmartly.sdk.event.GoalEvent;
import com.absmartly.sdk.event.PublishEvent;
import com.absmartly.sdk.event.ReadyEvent;
import com.absmartly.sdk.event.RefreshEvent;
import com.absmartly.sdk.event.Subscription;
import com.absmartly.sdk.internal.Clock;

/** The 2.0 public surface: client, state, typed variables, typed errors and events, over a fake transport. */
@Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class ABsmartlyClientTest extends TestUtils {
	static final String CONTEXT_JSON = new String(getResourceBytes("context.json"), StandardCharsets.UTF_8);
	static final ContextOptions SESSION = ContextOptions.builder()
			.unit("session_id", "e791e240fcd3df7d238cfc285f475e8152fcc0ec")
			.publishDelay(Duration.ofMillis(-1))
			.build();

	final List<AutoCloseable> closeables = new ArrayList<>();

	@AfterEach
	void tearDown() throws Exception {
		for (final AutoCloseable closeable : closeables) {
			closeable.close();
		}
	}

	ABsmartlyClient client(final Transport transport) {
		final ABsmartlyClient client = ABsmartlyClient.builder()
				.endpoint(URI.create("https://acme.absmartly.io/v1"))
				.apiKey("key")
				.application("website")
				.environment("production")
				.transport(transport)
				.clock(Clock.fixed(1_620_000_000_000L))
				.build();
		closeables.add(client);
		return client;
	}

	@Test
	void builderRequiresEveryConnectionSetting() {
		final FakeTransport transport = FakeTransport.ok("{}");
		assertEquals("Missing Endpoint configuration", assertThrows(IllegalArgumentException.class,
				() -> ABsmartlyClient.builder().apiKey("k").application("a").environment("e").transport(transport)
						.build())
				.getMessage());
		assertEquals("Missing APIKey configuration", assertThrows(IllegalArgumentException.class,
				() -> ABsmartlyClient.builder().endpoint(URI.create("https://x")).application("a").environment("e")
						.transport(transport).build())
				.getMessage());
		assertEquals("Missing Application configuration", assertThrows(IllegalArgumentException.class,
				() -> ABsmartlyClient.builder().endpoint(URI.create("https://x")).apiKey("k").environment("e")
						.transport(transport).build())
				.getMessage());
		assertEquals("Missing Environment configuration", assertThrows(IllegalArgumentException.class,
				() -> ABsmartlyClient.builder().endpoint(URI.create("https://x")).apiKey("k").application("a")
						.transport(transport).build())
				.getMessage());
		assertEquals("Missing Application configuration", assertThrows(IllegalArgumentException.class,
				() -> ABsmartlyClient.builder().endpoint(URI.create("https://x")).apiKey("k").application(" ")
						.environment("e").transport(transport).build())
				.getMessage());
		assertFalse(transport.closed, "a caller-supplied transport stays open");
	}

	@Test
	void defaultTransportIsTheJdkOneAndIsClosedWithTheClient() {
		final ABsmartlyClient client = ABsmartlyClient.builder()
				.endpoint(URI.create("https://acme.absmartly.io/v1"))
				.apiKey("key")
				.application("website")
				.environment("production")
				.applicationVersion(1004)
				.build();
		assertEquals("website", client.application());
		assertEquals("production", client.environment());
		client.close();
		client.close();
	}

	@Test
	void contextBlocksUntilReady() {
		final FakeTransport transport = FakeTransport.ok(CONTEXT_JSON);
		final ABsmartlyClient client = client(transport);
		try (Context context = client.context(SESSION)) {
			assertEquals(ContextState.READY, context.state());
			assertNull(context.failure());
			assertEquals(1, context.treatment("exp_test_ab"));
			assertEquals(1, context.pendingEventCount());
		}
		assertEquals(2, transport.requests.size(), "close publishes the pending exposure");
		assertEquals("PUT", transport.requests.get(1).method());
	}

	@Test
	void contextAsyncCompletesReady() {
		final ABsmartlyClient client = client(FakeTransport.ok(CONTEXT_JSON));
		final Context context = client.contextAsync(SESSION).join();
		assertEquals(ContextState.READY, context.state());
		assertSame(context, context.ready());
		assertSame(context, context.readyAsync().join());
	}

	@Test
	void failedLoadThrowsContextLoadException() {
		final ABsmartlyClient client = client(new FakeTransport(r -> FakeTransport.status(500,
				"Internal Server Error")));
		final ContextLoadException error = assertThrows(ContextLoadException.class, () -> client.context(SESSION));
		final HttpStatusException cause = (HttpStatusException) error.getCause();
		assertEquals(500, cause.statusCode());
		assertEquals("ABsmartly Context failed to load: Internal Server Error", error.getMessage());

		final CompletionException async = assertThrows(CompletionException.class,
				() -> client.contextAsync(SESSION).join());
		assertTrue(async.getCause() instanceof ContextLoadException);
	}

	@Test
	void newContextLoadsInTheBackgroundAndStaysUsableWhenFailed() {
		final CompletableFuture<Transport.Response> response = new CompletableFuture<>();
		final ABsmartlyClient client = client(new FakeTransport(r -> response));
		final Context context = client.newContext(SESSION);
		assertEquals(ContextState.LOADING, context.state());
		assertThrows(ContextNotReadyException.class, () -> context.treatment("exp_test_ab"));

		response.completeExceptionally(new java.io.IOException("connection reset"));
		assertThrows(ContextLoadException.class, context::ready);
		assertEquals(ContextState.FAILED, context.state());
		assertTrue(context.failure() instanceof TransportException);
		assertEquals(0, context.treatment("exp_test_ab"));
		assertEquals("blue", context.variable("button.color", "blue"));
		context.close();
		assertEquals(ContextState.CLOSED, context.state());
	}

	@Test
	void prefetchedDataMakesNoRequest() {
		final FakeTransport transport = FakeTransport.ok(CONTEXT_JSON);
		final ABsmartlyClient client = client(transport);
		final ContextData data = client.contextData();
		assertEquals(1, transport.requests.size());
		assertEquals(data, client.contextDataAsync().join());

		final Context context = client.context(SESSION, data);
		assertEquals(ContextState.READY, context.state());
		assertEquals(data, context.data());
		assertEquals(2, transport.requests.size());

		final Context fromJson = client.context(SESSION, ContextData.fromJson(CONTEXT_JSON));
		assertEquals(context.experiments(), fromJson.experiments());
	}

	@Test
	void closedClientRejectsNewContexts() {
		final ABsmartlyClient client = client(FakeTransport.ok(CONTEXT_JSON));
		final ContextData data = client.contextData();
		client.close();
		assertThrows(ContextClosedException.class, () -> client.context(SESSION));
		assertThrows(ContextClosedException.class, () -> client.newContext(SESSION));
		assertThrows(ContextClosedException.class, () -> client.context(SESSION, data));
		assertThrows(ContextClosedException.class, client::contextData);
	}

	@Test
	void closeLeavesSuppliedSchedulerAndTransportRunning() {
		final ScheduledExecutorService scheduler = new ScheduledThreadPoolExecutor(1);
		final FakeTransport transport = FakeTransport.ok("{}");
		try {
			ABsmartlyClient.builder().endpoint(URI.create("https://x")).apiKey("k").application("a").environment("e")
					.transport(transport).scheduler(scheduler).executor(Runnable::run).build().close();
			assertFalse(scheduler.isShutdown());
			assertFalse(transport.closed);
		} finally {
			scheduler.shutdownNow();
		}
	}

	@Test
	void stateFollowsTheLifecycle() {
		final CompletableFuture<Transport.Response> publish = new CompletableFuture<>();
		final ABsmartlyClient client = client(new FakeTransport(r -> "GET".equals(r.method())
				? CompletableFuture.completedFuture(new Transport.Response(200, "OK",
						CONTEXT_JSON.getBytes(StandardCharsets.UTF_8)))
				: publish));
		final Context context = client.context(SESSION);
		context.treatment("exp_test_ab");
		final CompletableFuture<Void> closing = context.closeAsync();
		assertEquals(ContextState.CLOSING, context.state());
		assertThrows(ContextClosedException.class, () -> context.track("goal"));
		publish.complete(new Transport.Response(200, "OK", null));
		closing.join();
		assertEquals(ContextState.CLOSED, context.state());
		assertEquals("ABsmartly Context is closed",
				assertThrows(ContextClosedException.class, () -> context.setAttribute("a", 1)).getMessage());
	}

	@Test
	void publishFailureKeepsEventsAndThrowsTheCause() {
		final ABsmartlyClient client = client(new FakeTransport(r -> "GET".equals(r.method())
				? CompletableFuture.completedFuture(new Transport.Response(200, "OK",
						CONTEXT_JSON.getBytes(StandardCharsets.UTF_8)))
				: FakeTransport.status(503, "Service Unavailable")));
		final Context context = client.context(SESSION);
		context.track("purchase", Map.of("total", 19.99));
		final HttpStatusException error = assertThrows(HttpStatusException.class, context::publish);
		assertEquals(503, error.statusCode());
		assertEquals(1, context.pendingEventCount());
		assertThrows(HttpStatusException.class, context::close);
		assertEquals(ContextState.READY, context.state(), "a failed close leaves the context open to retry");
	}

	@Test
	void typedVariables() {
		final ABsmartlyClient client = client(FakeTransport.ok(CONTEXT_JSON));
		final Context context = client.context(SESSION);

		final String size = context.variable("banner.size", "small");
		assertEquals("large", size);
		final int border = context.variable("banner.border", 0);
		assertEquals(1, border);
		assertEquals(1L, context.variable("banner.border", 0L));
		assertEquals(1.0, context.variable("banner.border", 0.0));
		assertEquals(BigDecimal.ONE, context.variable("banner.border", BigDecimal.ZERO));
		assertEquals(BigInteger.ONE, context.variable("banner.border", BigInteger.TEN));
		assertEquals((short) 1, context.variable("banner.border", (short) 0));
		assertEquals((byte) 1, context.variable("banner.border", (byte) 0));
		assertEquals(1.0f, context.variable("banner.border", 0.0f));

		assertEquals(7, context.variable("banner.size", 7), "a string is not an int: the default");
		assertEquals("dflt", context.variable("banner.border", "dflt"), "no number-to-string coercion");
		assertEquals("dflt", context.variable("missing", "dflt"));
		assertNull(context.variable("missing", String.class, null));
		assertEquals("large", context.variable("banner.size", String.class, null));
		assertEquals("large", context.variableValue("banner.size", null));
		assertNull(context.variableValue("missing", null));
	}

	@Test
	void collectionDefaultsInferTheirInterface() {
		final String json = "{\"experiments\":[{\"id\":1,\"name\":\"exp_list\",\"unitType\":\"session_id\","
				+ "\"iteration\":1,\"seedHi\":0,\"seedLo\":0,\"split\":[0,1],\"trafficSeedHi\":0,"
				+ "\"trafficSeedLo\":0,\"trafficSplit\":[0,1],\"fullOnVariant\":1,"
				+ "\"applications\":[{\"name\":\"website\"}],\"variants\":[{\"name\":\"A\",\"config\":null},"
				+ "{\"name\":\"B\",\"config\":\"{\\\"items\\\":[\\\"actual\\\"],"
				+ "\\\"limits\\\":{\\\"cart\\\":3}}\"}]}]}";
		final ABsmartlyClient client = client(FakeTransport.ok(json));
		final Context context = client.context(SESSION);

		final List<String> peekedEmpty = context.peekVariable("items", Collections.emptyList());
		assertEquals(List.of("actual"), peekedEmpty);
		assertEquals(List.of("actual"), context.peekVariable("items", List.of("fallback")));
		assertEquals(Map.of("cart", 3), context.peekVariable("limits", Collections.emptyMap()));
		assertEquals(0, context.pendingEventCount());

		final List<String> items = context.variable("items", Collections.emptyList());
		assertEquals(List.of("actual"), items);
		assertEquals(Map.of("cart", 3), context.variable("limits", Map.of("cart", 1)));
		assertEquals(List.of("fallback"), context.variable("missing", List.of("fallback")));
		assertEquals(Collections.emptyMap(), context.variable("items", Collections.emptyMap()),
				"an array is not a map: the default");

		// A constructible collection default keeps its class.
		final java.util.TreeMap<String, Object> limits = context.peekVariable("limits", new java.util.TreeMap<>());
		assertEquals(new java.util.TreeMap<>(Map.of("cart", 3)), limits);
		final java.util.LinkedList<String> linked = context.peekVariable("items", new java.util.LinkedList<>());
		assertEquals(new java.util.LinkedList<>(List.of("actual")), linked);
		final java.util.TreeMap<String, Object> fallback = new java.util.TreeMap<>(Map.of("x", 1));
		assertSame(fallback, context.peekVariable("items", fallback), "an array is not a map: the default");
		final java.util.LinkedList<String> listFallback = new java.util.LinkedList<>();
		assertSame(listFallback, context.peekVariable("limits", listFallback), "an object is not a list");

		// Non-constructible sorted and set defaults convert into a class of the same shape.
		final java.util.SortedMap<String, Object> sorted = context.peekVariable("limits",
				Collections.emptySortedMap());
		assertEquals(new java.util.TreeMap<>(Map.of("cart", 3)), sorted);
		final java.util.NavigableMap<String, Object> navigable = context.peekVariable("limits",
				Collections.unmodifiableNavigableMap(new java.util.TreeMap<>()));
		assertEquals(Map.of("cart", 3), navigable);
		final java.util.Set<String> set = context.peekVariable("items", Collections.emptySet());
		assertEquals(java.util.Set.of("actual"), set);
		final java.util.SortedSet<String> sortedSet = context.peekVariable("items", Collections.emptySortedSet());
		assertEquals(new java.util.TreeSet<>(List.of("actual")), sortedSet);
		assertEquals(1, context.pendingEventCount());
	}

	@Test
	void longVariablesKeepTheirFullValue() {
		assertEquals(5_000_000_000L, VariableConverter.convert("big", 5_000_000_000L, Long.class, 0L));
		assertEquals(5_000_000_000L, VariableConverter.convert("big", new BigInteger("5000000000"), Long.class, 0L));
		assertEquals(0, VariableConverter.convert("big", 5_000_000_000L, Integer.class, 0));
		assertEquals(0L, VariableConverter.convert("big", new BigInteger("99999999999999999999"), Long.class, 0L));
		assertEquals(-5_000_000_000L, VariableConverter.convert("big", -5_000_000_000.0, Long.class, 0L));
	}

	@Test
	void floatingConversionsRejectOverflowAndPrecisionLoss() {
		assertEquals(-1.0, VariableConverter.convert("n", new BigInteger("9007199254740993"), Double.class, -1.0));
		assertEquals(-1.0f, VariableConverter.convert("n", 16777217, Float.class, -1.0f));
		assertEquals(-1.0, VariableConverter.convert("n", new BigDecimal("1e1000"), Double.class, -1.0));
		assertEquals(-1.0f, VariableConverter.convert("n", Double.MAX_VALUE, Float.class, -1.0f));
		assertEquals(0.5f, VariableConverter.convert("n", 0.5, Float.class, -1.0f));
		assertEquals(-1.0f, VariableConverter.convert("n", Double.POSITIVE_INFINITY, Float.class, -1.0f));
		assertEquals(-1.0, VariableConverter.convert("n", Double.NaN, Double.class, -1.0));
	}

	@Test
	void floatReadsDoNotDependOnTheJdk() {
		// JDK 11's Double.toString gave non-shortest forms for these, which made them fall back to the default.
		assertEquals(9.0e9f, VariableConverter.convert("n", 9.0e9, Float.class, -1.0f));
		assertEquals(2.285692e9f, VariableConverter.convert("n", (double) 2.285692e9f, Float.class, -1.0f));
		assertEquals(3.0e10f, VariableConverter.convert("n", 3.0e10, Float.class, -1.0f));
		assertEquals(1.0e23, VariableConverter.convert("n", new BigDecimal("1e23"), Double.class, -1.0));
		assertEquals(new BigDecimal("2E-3"), VariableConverter.shortestDecimal(2.0e-3));
		assertEquals(new BigDecimal("1E+23"), VariableConverter.shortestDecimal(1.0e23));
		assertEquals(new BigDecimal("0.1"), VariableConverter.shortestDecimal(0.1));
		final java.util.Random random = new java.util.Random(42);
		for (int i = 0; i < 20000; ++i) {
			final float f = Float.intBitsToFloat(random.nextInt());
			if (Float.isFinite(f)) {
				assertEquals(f, VariableConverter.convert("n", (double) f, Float.class, Float.NaN), "float " + f);
			}
			final double d = Double.longBitsToDouble(random.nextLong());
			if (Double.isFinite(d)) {
				assertEquals(0, Double.compare(d, VariableConverter.shortestDecimal(d).doubleValue()), "double " + d);
			}
		}
	}

	@Test
	@SuppressWarnings("unchecked")
	void defaultsWithNoConstructibleSubstituteFallBack() {
		final java.util.EnumSet<TimeUnit> none = java.util.EnumSet.noneOf(TimeUnit.class);
		assertTrue(java.util.EnumSet.class.isAssignableFrom(VariableConverter.inferType(none)),
				"no substitute implements every interface of an EnumSet's class");
		final java.util.EnumSet<TimeUnit> read = VariableConverter.convert("set", List.of("SECONDS"),
				(Class<java.util.EnumSet<TimeUnit>>) VariableConverter.inferType(none), none);
		assertSame(none, read, "an EnumSet default is returned rather than a LinkedHashSet");
		assertEquals(java.util.TreeSet.class, VariableConverter.inferType(Collections.emptySortedSet()));
		assertEquals(java.util.LinkedHashSet.class, VariableConverter.inferType(Collections.emptySet()));
		assertEquals(java.util.ArrayList.class, VariableConverter.inferType(List.of()));
		assertEquals(java.util.TreeMap.class, VariableConverter.inferType(Collections.emptySortedMap()));
		assertEquals(java.util.LinkedHashMap.class, VariableConverter.inferType(Map.of()));
	}

	@Test
	void infinityIsKeptForUntypedReads() {
		assertEquals(Double.POSITIVE_INFINITY, VariableConverter.convert("n", Double.POSITIVE_INFINITY, Object.class,
				null));
		assertEquals(Double.POSITIVE_INFINITY, VariableConverter.convert("n", Double.POSITIVE_INFINITY, Number.class,
				null));
		assertEquals(-1.0, VariableConverter.convert("n", Double.POSITIVE_INFINITY, Double.class, -1.0));
	}

	@Test
	void floatingConversionsKeepExactValues() {
		assertEquals(0x1p60, VariableConverter.convert("n", 1L << 60, Double.class, -1.0));
		assertEquals(0x1p62, VariableConverter.convert("n", 1L << 62, Double.class, -1.0));
		assertEquals(0x1p40f, VariableConverter.convert("n", 1L << 40, Float.class, -1.0f));
		assertEquals(0.3f, VariableConverter.convert("n", (double) 0.3f, Float.class, -1.0f));
		assertEquals(0.1f, VariableConverter.convert("n", 0.1, Float.class, -1.0f));
		assertEquals(19.99, VariableConverter.convert("n", 19.99, Double.class, -1.0));
		assertEquals(1.0e300, VariableConverter.convert("n", new BigDecimal("1e300"), Double.class, -1.0));
	}

	@Test
	void numericConversionNeverLosesPrecision() {
		assertEquals(5, VariableConverter.convert("k", 5.0, Integer.class, 0));
		assertEquals(0, VariableConverter.convert("k", 5.5, Integer.class, 0));
		assertEquals(0, VariableConverter.convert("k", 3_000_000_000L, Integer.class, 0));
		assertEquals((byte) 9, VariableConverter.convert("k", 300, Byte.class, (byte) 9));
		assertEquals(3_000_000_000L, VariableConverter.convert("k", 3_000_000_000L, Long.class, 0L));
		assertEquals(new BigDecimal("2.5"), VariableConverter.convert("k", 2.5, BigDecimal.class, null));
		assertEquals(Boolean.TRUE, VariableConverter.convert("k", true, Boolean.class, false));
		assertEquals(Boolean.FALSE, VariableConverter.convert("k", "true", Boolean.class, false));
		assertNull(VariableConverter.convert("k", List.of(1), Integer.class, null));
	}

	public static final class Banner {
		public String color;
		public int size;
	}

	@Test
	void objectVariablesMapToClasses() {
		final Map<String, Object> json = Map.of("color", "red", "size", 3);
		final Banner banner = VariableConverter.convert("banner", json, Banner.class, null);
		assertEquals("red", banner.color);
		assertEquals(3, banner.size);

		final Banner fallback = new Banner();
		assertSame(fallback, VariableConverter.convert("banner", Map.of("size", "huge"), Banner.class, fallback));
		assertSame(json, VariableConverter.convert("banner", json, Map.class, null));
		assertNull(VariableConverter.convert("banner", json, String.class, null));
	}

	@Test
	void peekVariablesDoNotExpose() {
		final ABsmartlyClient client = client(FakeTransport.ok(CONTEXT_JSON));
		final Context context = client.context(SESSION);
		assertEquals("large", context.peekVariable("banner.size", "small"));
		assertEquals("large", context.peekVariable("banner.size", String.class, "small"));
		assertEquals(1, context.peekTreatment("exp_test_ab"));
		assertEquals(0, context.pendingEventCount());
		assertEquals("large", context.variable("banner.size", "small"));
		assertEquals(1, context.pendingEventCount());
	}

	@Test
	void accessorsReturnUnmodifiableSnapshots() {
		final ABsmartlyClient client = client(FakeTransport.ok(CONTEXT_JSON));
		final Context context = client.context(ContextOptions.builder()
				.unit("session_id", "e791e240fcd3df7d238cfc285f475e8152fcc0ec")
				.unit("user_id", 123)
				.attribute("plan", "pro")
				.override("exp_test_ab", 0)
				.customAssignment("exp_test_abc", 2)
				.build());

		assertEquals("123", context.unit("user_id"));
		assertEquals(123, context.unitValue("user_id"));
		assertEquals(Map.of("session_id", "e791e240fcd3df7d238cfc285f475e8152fcc0ec", "user_id", "123"),
				context.units());
		assertThrows(UnsupportedOperationException.class, () -> context.units().put("x", "y"));
		assertThrows(UnsupportedOperationException.class, () -> context.unitValues().clear());
		assertEquals("pro", context.attribute("plan"));
		assertEquals(Map.of("plan", "pro"), context.attributes());
		assertThrows(UnsupportedOperationException.class, () -> context.attributes().clear());

		assertEquals(Map.of("exp_test_ab", 0), context.overrides());
		assertEquals(0, context.override("exp_test_ab"));
		assertEquals(0, context.treatment("exp_test_ab"));
		context.clearOverride("exp_test_ab");
		assertNull(context.override("exp_test_ab"));
		assertEquals(Collections.emptyMap(), context.overrides());
		assertEquals(1, context.treatment("exp_test_ab"), "the override is gone: assigned normally again");

		assertEquals(Map.of("exp_test_abc", 2), context.customAssignments());
		assertEquals(2, context.customAssignment("exp_test_abc"));
		context.clearCustomAssignment("exp_test_abc");
		assertNull(context.customAssignment("exp_test_abc"));
		assertThrows(UnsupportedOperationException.class, () -> context.customAssignments().put("x", 1));

		assertEquals(List.of("exp_test_ab", "exp_test_abc", "exp_test_not_eligible", "exp_test_fullon"),
				context.experiments());
		assertThrows(UnsupportedOperationException.class, () -> context.experiments().add("x"));
		assertEquals(Collections.emptyList(), context.customFieldKeys());
		assertNull(context.customFieldType("exp_test_ab", "missing"));
		assertEquals(List.of("exp_test_ab"), context.variableKeys().get("banner.size"));
	}

	@Test
	void eventsAreTypedAndReachContextAndClientListeners() {
		final ABsmartlyClient client = client(FakeTransport.ok(CONTEXT_JSON));
		final List<ContextEvent> clientEvents = Collections.synchronizedList(new ArrayList<>());
		final List<ContextEvent> contextEvents = Collections.synchronizedList(new ArrayList<>());
		client.onEvent(clientEvents::add);

		final Context context = client.context(ContextOptions.builder()
				.unit("session_id", "e791e240fcd3df7d238cfc285f475e8152fcc0ec")
				.publishDelay(Duration.ofMillis(-1))
				.eventListener(contextEvents::add)
				.build());
		// As in 1.x, context() can return just before READY is dispatched on the loading thread.
		awaitSize(contextEvents, 1);
		awaitSize(clientEvents, 1);
		context.treatment("exp_test_ab");
		context.track("purchase", Map.of("total", 19.99, "items", 2));
		context.publish();
		awaitSize(contextEvents, 4);
		context.refresh();
		// As in 1.x, a future completes before its event is logged, so wait for each event before the next step.
		awaitSize(contextEvents, 5);
		context.close();
		awaitSize(contextEvents, 6);
		awaitSize(clientEvents, 6);

		final List<ContextEvent.Type> expected = List.of(ContextEvent.Type.READY, ContextEvent.Type.EXPOSURE,
				ContextEvent.Type.GOAL, ContextEvent.Type.PUBLISH, ContextEvent.Type.REFRESH, ContextEvent.Type.CLOSE);
		assertEquals(expected, types(contextEvents));
		assertEquals(expected, types(clientEvents));

		final ReadyEvent ready = (ReadyEvent) contextEvents.get(0);
		assertSame(context, ready.context());
		assertEquals(1_620_000_000_000L, ready.timestamp());
		assertEquals(4, ready.data().experimentNames().size());

		final Exposure exposure = ((ExposureEvent) contextEvents.get(1)).exposure();
		assertEquals(1, exposure.id());
		assertEquals("exp_test_ab", exposure.name());
		assertEquals("session_id", exposure.unit());
		assertEquals(1, exposure.variant());
		assertTrue(exposure.assigned());
		assertTrue(exposure.eligible());
		assertFalse(exposure.overridden() || exposure.fullOn() || exposure.custom() || exposure.audienceMismatch()
				|| exposure.ruleOverride());
		assertEquals(1_620_000_000_000L, exposure.exposedAt());

		final Goal goal = ((GoalEvent) contextEvents.get(2)).goal();
		assertEquals("purchase", goal.name());
		assertEquals(Map.of("items", 2, "total", 19.99), goal.properties());
		assertThrows(UnsupportedOperationException.class, () -> goal.properties().clear());

		final PublishEvent publish = (PublishEvent) contextEvents.get(3);
		assertEquals(List.of(exposure), publish.exposures());
		assertEquals(List.of(goal), publish.goals());
		assertTrue(publish.toJson().contains("\"hashed\":true"));

		assertTrue(contextEvents.get(4) instanceof RefreshEvent);
		assertTrue(contextEvents.get(5) instanceof CloseEvent);
		for (final ContextEvent event : contextEvents) {
			assertNotNull(event.toString());
		}
	}

	@Test
	void errorEventCarriesTheUnwrappedCause() {
		final ABsmartlyClient client = client(new FakeTransport(r -> FakeTransport.status(401, "")));
		final List<ContextEvent> events = Collections.synchronizedList(new ArrayList<>());
		client.onEvent(events::add);
		final Context context = client.newContext(SESSION);
		assertThrows(ContextLoadException.class, context::ready);

		// The ready future completes before the event is logged, as in 1.x, so wait for the event.
		awaitSize(events, 1);
		assertEquals(List.of(ContextEvent.Type.ERROR), types(events));
		final Throwable cause = ((ErrorEvent) events.get(0)).cause();
		assertEquals(401, ((HttpStatusException) cause).statusCode());
		assertEquals("Unauthorized", cause.getMessage());
		assertSame(cause, context.failure());
	}

	@Test
	void throwingListenerDoesNotBreakTheSdkOrOtherListeners() {
		final ABsmartlyClient client = client(FakeTransport.ok(CONTEXT_JSON));
		final AtomicInteger seen = new AtomicInteger();
		client.onEvent(event -> {
			throw new IllegalStateException("listener bug");
		});
		client.onEvent(event -> {
			if (event.type() == ContextEvent.Type.EXPOSURE) {
				seen.incrementAndGet();
			}
		});
		final Context context = client.context(SESSION);
		assertEquals(1, context.treatment("exp_test_ab"));
		assertEquals(1, seen.get());
	}

	@Test
	void subscriptionRemovesTheListener() {
		final ABsmartlyClient client = client(FakeTransport.ok(CONTEXT_JSON));
		final Context context = client.context(SESSION);
		final AtomicInteger seen = new AtomicInteger();
		final Subscription subscription = context.onEvent(event -> {
			if (event.type() == ContextEvent.Type.EXPOSURE) {
				seen.incrementAndGet();
			}
		});
		context.treatment("exp_test_ab");
		subscription.close();
		subscription.close();
		context.treatment("exp_test_abc");
		assertEquals(1, seen.get());
	}

	@Test
	void flowPublisherStreamsEventsAndCompletesOnClose() throws InterruptedException {
		final ABsmartlyClient client = client(FakeTransport.ok(CONTEXT_JSON));
		final Context context = client.context(SESSION);
		final List<ContextEvent.Type> received = Collections.synchronizedList(new ArrayList<>());
		final CountDownLatch completed = new CountDownLatch(1);
		context.events().subscribe(new Flow.Subscriber<ContextEvent>() {
			@Override
			public void onSubscribe(final Flow.Subscription subscription) {
				subscription.request(Long.MAX_VALUE);
			}

			@Override
			public void onNext(final ContextEvent item) {
				// A late READY from the load can reach a subscriber attached right after context() returned.
				if (item.type() != ContextEvent.Type.READY) {
					received.add(item.type());
				}
			}

			@Override
			public void onError(final Throwable throwable) {}

			@Override
			public void onComplete() {
				completed.countDown();
			}
		});
		assertSame(context.events(), context.events());
		context.treatment("exp_test_ab");
		context.close();

		assertTrue(completed.await(5, TimeUnit.SECONDS));
		assertEquals(List.of(ContextEvent.Type.EXPOSURE, ContextEvent.Type.PUBLISH, ContextEvent.Type.CLOSE),
				received);
	}

	@Test
	void clientStreamCompletesOnClientClose() throws InterruptedException {
		final ABsmartlyClient client = client(FakeTransport.ok(CONTEXT_JSON));
		final CountDownLatch completed = new CountDownLatch(1);
		final List<ContextEvent.Type> received = Collections.synchronizedList(new ArrayList<>());
		client.events().subscribe(new Flow.Subscriber<ContextEvent>() {
			@Override
			public void onSubscribe(final Flow.Subscription subscription) {
				subscription.request(Long.MAX_VALUE);
			}

			@Override
			public void onNext(final ContextEvent item) {
				received.add(item.type());
			}

			@Override
			public void onError(final Throwable throwable) {}

			@Override
			public void onComplete() {
				completed.countDown();
			}
		});
		final Context context = client.newContext(SESSION);
		// The ready future can complete before READY is dispatched, so wait for the event before exposing.
		awaitStreamed(received, 1);
		context.treatment("exp_test_ab");
		awaitStreamed(received, 2);
		client.close();
		assertTrue(completed.await(5, TimeUnit.SECONDS));
		assertEquals(List.of(ContextEvent.Type.READY, ContextEvent.Type.EXPOSURE), received);
	}

	@Test
	void contextStreamsUseTheClientExecutor() throws InterruptedException {
		final java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors
				.newSingleThreadExecutor(runnable -> new Thread(runnable, "client-executor"));
		try {
			final ABsmartlyClient client = ABsmartlyClient.builder().endpoint(URI.create("https://x")).apiKey("k")
					.application("a").environment("e").transport(FakeTransport.ok(CONTEXT_JSON)).executor(pool)
					.build();
			closeables.add(client);
			final Context context = client.context(SESSION);
			final CountDownLatch received = new CountDownLatch(1);
			final List<String> threads = Collections.synchronizedList(new ArrayList<>());
			context.events().subscribe(new Flow.Subscriber<ContextEvent>() {
				@Override
				public void onSubscribe(final Flow.Subscription subscription) {
					subscription.request(Long.MAX_VALUE);
				}

				@Override
				public void onNext(final ContextEvent item) {
					if (item.type() == ContextEvent.Type.EXPOSURE) {
						threads.add(Thread.currentThread().getName());
						received.countDown();
					}
				}

				@Override
				public void onError(final Throwable throwable) {}

				@Override
				public void onComplete() {}
			});
			context.treatment("exp_test_ab");
			assertTrue(received.await(5, TimeUnit.SECONDS));
			assertEquals(List.of("client-executor"), threads, "the context's stream delivers on the client executor");
		} finally {
			pool.shutdownNow();
		}
	}

	@Test
	void slowSubscriberNeverBlocksTheProducer() throws Exception {
		final java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor();
		final EventStream stream = new EventStream(pool);
		final CountDownLatch subscribed = new CountDownLatch(1);
		stream.publisher().subscribe(new Flow.Subscriber<ContextEvent>() {
			@Override
			public void onSubscribe(final Flow.Subscription subscription) {
				subscribed.countDown();
			}

			@Override
			public void onNext(final ContextEvent item) {}

			@Override
			public void onError(final Throwable throwable) {}

			@Override
			public void onComplete() {}
		});
		assertTrue(subscribed.await(5, TimeUnit.SECONDS));
		final ABsmartlyClient client = client(FakeTransport.ok(CONTEXT_JSON));
		final Context context = client.context(SESSION);
		final CompletableFuture<Void> producer = CompletableFuture.runAsync(() -> {
			for (int i = 0; i < Flow.defaultBufferSize() * 4; ++i) {
				stream.submit(new CloseEvent(context, i));
			}
		});
		producer.get(5, TimeUnit.SECONDS);
		// Nothing was requested, so every event past the buffer is lost and the producer is never blocked.
		final int total = Flow.defaultBufferSize() * 4;
		assertEquals(total - Flow.defaultBufferSize(), stream.droppedCount());
		stream.close();
		pool.shutdownNow();
	}

	@Test
	void transportIsClosedWhenTheClientCannotBeBuilt() {
		final FakeTransport created = FakeTransport.ok("{}");
		assertThrows(IllegalArgumentException.class, () -> ABsmartlyClient.builder().endpoint(URI.create("ftp://x"))
				.apiKey("k").application("a").environment("e").defaultTransport(() -> created).build());
		assertTrue(created.closed, "a transport the client created is closed when construction fails");

		final FakeTransport supplied = FakeTransport.ok("{}");
		assertThrows(IllegalArgumentException.class, () -> ABsmartlyClient.builder().endpoint(URI.create("ftp://x"))
				.apiKey("k").application("a").environment("e").transport(supplied).build());
		assertFalse(supplied.closed, "a caller's transport stays open");
	}

	@Test
	void clientClosesTheTransportItCreated() throws Exception {
		final ABsmartlyClient client = ABsmartlyClient.builder().endpoint(URI.create("https://x")).apiKey("k")
				.application("a").environment("e").build();
		final java.lang.reflect.Field field = ABsmartlyClient.class.getDeclaredField("client_");
		field.setAccessible(true);
		final Client inner = (Client) field.get(client);
		final java.lang.reflect.Field transportField = Client.class.getDeclaredField("transport_");
		transportField.setAccessible(true);
		final JdkHttpTransport transport = (JdkHttpTransport) transportField.get(inner);
		final java.lang.reflect.Field httpField = JdkHttpTransport.class.getDeclaredField("closed_");
		httpField.setAccessible(true);
		assertFalse((Boolean) httpField.get(transport));
		client.close();
		assertTrue((Boolean) httpField.get(transport));
	}

	@Test
	void slowStreamSubscriberLosesOverflowWithoutBlocking() throws InterruptedException {
		final EventStream stream = new EventStream(Runnable::run);
		final List<ContextEvent> received = Collections.synchronizedList(new ArrayList<>());
		final Flow.Subscription[] holder = new Flow.Subscription[1];
		stream.publisher().subscribe(new Flow.Subscriber<ContextEvent>() {
			@Override
			public void onSubscribe(final Flow.Subscription subscription) {
				holder[0] = subscription;
			}

			@Override
			public void onNext(final ContextEvent item) {
				received.add(item);
			}

			@Override
			public void onError(final Throwable throwable) {}

			@Override
			public void onComplete() {}
		});
		final ABsmartlyClient client = client(FakeTransport.ok(CONTEXT_JSON));
		final Context context = client.context(SESSION);
		final int total = Flow.defaultBufferSize() * 3;
		for (int i = 0; i < total; ++i) {
			stream.submit(new com.absmartly.sdk.event.CloseEvent(context, i));
		}
		holder[0].request(Long.MAX_VALUE);
		awaitSize(received, Flow.defaultBufferSize());
		Thread.sleep(50);
		assertTrue(received.size() < total, "events beyond the buffer are dropped, not queued");
		assertTrue(received.size() >= Flow.defaultBufferSize() - 1, "the buffered events are delivered");
		stream.close();
	}

	@Test
	void streamFirstRequestedAfterCloseIsAlreadyComplete() throws InterruptedException {
		final ABsmartlyClient client = client(FakeTransport.ok(CONTEXT_JSON));
		final Context context = client.context(SESSION);
		context.close();
		assertTrue(awaitCompletion(context.events()), "context stream completes after close");
		client.close();
		assertTrue(awaitCompletion(client.events()), "client stream completes after close");
	}

	private static boolean awaitCompletion(final Flow.Publisher<ContextEvent> publisher)
			throws InterruptedException {
		final CountDownLatch completed = new CountDownLatch(1);
		publisher.subscribe(new Flow.Subscriber<ContextEvent>() {
			@Override
			public void onSubscribe(final Flow.Subscription subscription) {
				subscription.request(Long.MAX_VALUE);
			}

			@Override
			public void onNext(final ContextEvent item) {}

			@Override
			public void onError(final Throwable throwable) {}

			@Override
			public void onComplete() {
				completed.countDown();
			}
		});
		return completed.await(5, TimeUnit.SECONDS);
	}

	@Test
	void slowStreamSubscriberDropsEventsInsteadOfBlocking() {
		final ABsmartlyClient client = client(FakeTransport.ok(CONTEXT_JSON));
		final Context context = client.context(SESSION);
		context.events().subscribe(new Flow.Subscriber<ContextEvent>() {
			@Override
			public void onSubscribe(final Flow.Subscription subscription) {}

			@Override
			public void onNext(final ContextEvent item) {}

			@Override
			public void onError(final Throwable throwable) {}

			@Override
			public void onComplete() {}
		});
		for (int i = 0; i < Flow.defaultBufferSize() + 10; ++i) {
			context.track("goal" + i);
		}
		assertEquals(Flow.defaultBufferSize() + 10, context.pendingEventCount());
	}

	@Test
	void blockingCallsRethrowUncheckedCausesAndWrapOthers() {
		final IllegalStateException unchecked = new IllegalStateException("x");
		assertSame(unchecked, Context.unwrap(new CompletionException(unchecked)));
		final Exception checked = new Exception("checked");
		final RuntimeException wrapped = Context.unwrap(new CompletionException(checked));
		assertTrue(wrapped instanceof ABsmartlyException);
		assertSame(checked, wrapped.getCause());
		assertThrows(AssertionError.class, () -> Context.unwrap(new CompletionException(new AssertionError())));

		final CompletableFuture<Void> cancelled = new CompletableFuture<>();
		cancelled.cancel(false);
		assertTrue(assertThrows(ABsmartlyException.class, () -> Context.join(cancelled))
				.getCause() instanceof java.util.concurrent.CancellationException);
	}

	@Test
	void exceptionAccessors() {
		assertEquals("HTTP 418", new HttpStatusException(418, "").getMessage());
		assertEquals(418, new HttpStatusException(418, "").statusCode());
		assertEquals("", HttpStatusException.reasonPhrase(418));
		assertEquals("Not Found", HttpStatusException.reasonPhrase(404));
		assertEquals("ABsmartly Context failed to load: NullPointerException",
				new ContextLoadException(new NullPointerException()).getMessage());
		assertEquals("ABsmartly Context is not yet ready", new ContextNotReadyException().getMessage());
		assertTrue(new RequestTimeoutException("t", null) instanceof TransportException);
		assertTrue(new DecodingException("d", null) instanceof ABsmartlyException);
	}

	@Test
	void valueTypesHaveValueSemantics() {
		final Exposure a = new Exposure(1, "e", "u", 1, 2, true, true, false, false, false, false, false);
		final Exposure b = new Exposure(1, "e", "u", 1, 2, true, true, false, false, false, false, false);
		final Exposure c = new Exposure(1, "e", "u", 2, 2, true, true, false, false, false, false, false);
		assertEquals(a, b);
		assertEquals(a.hashCode(), b.hashCode());
		assertNotEquals(a, c);
		assertNotEquals(a, "e");
		assertEquals(a, a);
		final Goal g1 = new Goal("g", 1, null);
		final Goal g2 = new Goal("g", 1, null);
		assertEquals(g1, g2);
		assertEquals(g1.hashCode(), g2.hashCode());
		assertNotEquals(g1, new Goal("g", 2, null));
		assertNotEquals(g1, "g");
		assertEquals(g1, g1);
		assertTrue(g1.toString().contains("g"));
		assertNull(g1.properties());

		final Transport.Request request = new Transport.Request("PUT", URI.create("https://x/context"),
				Map.of("h", "v"), new byte[]{1});
		assertEquals("PUT https://x/context", request.toString());
		assertArrayEquals(new byte[]{1}, request.body());
		assertNotSame(request.body(), request.body());
		final Transport lambda = r -> FakeTransport.status(200, "OK");
		lambda.close();
		final Transport.Response response = new Transport.Response(200, "OK", null);
		assertNull(response.body());
		assertEquals("Response{200 OK}", response.toString());
		assertEquals(new ContextData(new com.absmartly.sdk.internal.json.ContextPayload()),
				new ContextData(new com.absmartly.sdk.internal.json.ContextPayload()));
	}

	private static void awaitSize(final List<?> list, final int size) {
		final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while ((list.size() < size) && (System.nanoTime() < deadline)) {
			Thread.onSpinWait();
		}
	}

	private static void awaitStreamed(final List<?> list, final int size) {
		awaitSize(list, size);
	}

	private static List<ContextEvent.Type> types(final List<ContextEvent> events) {
		final List<ContextEvent.Type> types = new ArrayList<>();
		synchronized (events) {
			for (final ContextEvent event : events) {
				types.add(event.type());
			}
		}
		return types;
	}
}
