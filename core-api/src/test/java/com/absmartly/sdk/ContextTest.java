package com.absmartly.sdk;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.absmartly.sdk.json.*;

class ContextTest {
	final Map<String, String> units = TestUtils.mapOf(
			"session_id", "e791e240fcd3df7d238cfc285f475e8152fcc0ec",
			"user_id", "123456789",
			"email", "bleh@absmartly.com");

	final Map<String, Object> attributes = TestUtils.mapOf(
			"attr1", "value1",
			"attr2", "value2",
			"attr3", 5);

	final Map<String, Integer> expectedVariants = TestUtils.mapOf(
			"exp_test_ab", 1,
			"exp_test_abc", 2,
			"exp_test_not_eligible", 0,
			"exp_test_fullon", 2,
			"exp_test_new", 1);

	final Map<String, Object> expectedVariables = TestUtils.mapOf(
			"banner.border", 1,
			"banner.size", "large",
			"button.color", "red",
			"submit.color", "blue",
			"submit.shape", "rect",
			"show-modal", true);

	final Map<String, String> variableExperiments = TestUtils.mapOf(
			"banner.border", "exp_test_ab",
			"banner.size", "exp_test_ab",
			"button.color", "exp_test_abc",
			"card.width", "exp_test_not_eligible",
			"submit.color", "exp_test_fullon",
			"submit.shape", "exp_test_fullon",
			"show-modal", "exp_test_new");

	final Unit[] publishUnits = new Unit[]{
			new Unit("session_id", "pAE3a1i5Drs5mKRNq56adA"),
			new Unit("user_id", "JfnnlDI7RTiF9RgfG2JNCw"),
			new Unit("email", "IuqYkNRfEx5yClel4j3NbA")
	};

	ContextData data;
	ContextData refreshData;
	CompletableFuture<ContextData> dataFutureReady;
	CompletableFuture<ContextData> dataFutureFailed;
	CompletableFuture<ContextData> dataFuture;

	CompletableFuture<ContextData> refreshDataFutureReady;
	CompletableFuture<ContextData> refreshDataFutureFailed;
	CompletableFuture<ContextData> refreshDataFuture;

	ContextDataProvider dataProvider;
	ContextEventHandler eventHandler;
	VariableParser variableParser;
	ScheduledExecutorService scheduler;
	DefaultContextDataDeserializer deser = new DefaultContextDataDeserializer();
	Clock clock = Clock.fixed(Instant.ofEpochMilli(1_620_000_000_000L), ZoneId.of("UTC"));

	@BeforeEach
	void setUp() {
		final byte[] bytes = TestUtils.getResourceBytes("context.json");
		data = deser.deserialize(bytes, 0, bytes.length);

		final byte[] refreshBytes = TestUtils.getResourceBytes("refreshed.json");
		refreshData = deser.deserialize(refreshBytes, 0, refreshBytes.length);

		dataFutureReady = CompletableFuture.completedFuture(data);
		dataFutureFailed = TestUtils.failedFuture(new Exception("FAILED"));
		dataFuture = new CompletableFuture<>();

		refreshDataFutureReady = CompletableFuture.completedFuture(refreshData);
		refreshDataFutureFailed = TestUtils.failedFuture(new Exception("FAILED"));
		refreshDataFuture = new CompletableFuture<>();

		dataProvider = mock(ContextDataProvider.class);
		eventHandler = mock(ContextEventHandler.class);
		variableParser = new DefaultVariableParser();
		scheduler = mock(ScheduledExecutorService.class);
	}

	@Test
	void constructorSetsOverrides() {
		final Map<String, Integer> overrides = TestUtils.mapOf(
				"exp_test", 2,
				"exp_test_1", 1);

		final ContextConfig config = ContextConfig.create()
				.setUnits(units)
				.setOverrides(overrides);

		final Context context = Context.create(clock, config, scheduler, dataFutureReady, dataProvider, eventHandler,
				variableParser);
		overrides.forEach((experimentName, variant) -> assertEquals(variant, context.getOverride(experimentName)));
	}

	@Test
	void becomesReadyWithCompletedFuture() {
		final ContextConfig config = ContextConfig.create()
				.setUnits(units);

		final Context context = Context.create(clock, config, scheduler, dataFutureReady, dataProvider, eventHandler,
				variableParser);
		assertTrue(context.isReady());
		assertSame(data, context.getData());
	}

	@Test
	void becomesReadyAndFailedWithCompletedExceptionallyFuture() {
		final ContextConfig config = ContextConfig.create()
				.setUnits(units);

		final Context context = Context.create(clock, config, scheduler, dataFutureFailed, dataProvider, eventHandler,
				variableParser);
		assertTrue(context.isReady());
		assertTrue(context.isFailed());
	}

	@Test
	void becomesReadyAndFailedWithException() {
		final ContextConfig config = ContextConfig.create()
				.setUnits(units);

		final Context context = Context.create(clock, config, scheduler, dataFuture, dataProvider, eventHandler,
				variableParser);
		assertFalse(context.isReady());
		assertFalse(context.isFailed());

		dataFuture.completeExceptionally(new Exception("FAILED"));

		context.waitUntilReady();

		assertTrue(context.isReady());
		assertTrue(context.isFailed());
	}

	@Test
	void waitUntilReady() throws InterruptedException {
		final ContextConfig config = ContextConfig.create()
				.setUnits(units);

		final Context context = Context.create(clock, config, scheduler, dataFuture, dataProvider, eventHandler,
				variableParser);
		assertFalse(context.isReady());

		final Thread completer = new Thread(() -> dataFuture.complete(data));
		completer.start();

		context.waitUntilReady();
		completer.join();

		assertTrue(context.isReady());
		assertSame(data, context.getData());
	}

	@Test
	void waitUntilReadyWithCompletedFuture() {
		final ContextConfig config = ContextConfig.create()
				.setUnits(units);

		final Context context = Context.create(clock, config, scheduler, dataFutureReady, dataProvider, eventHandler,
				variableParser);
		assertTrue(context.isReady());

		context.waitUntilReady();
		assertSame(data, context.getData());
	}

	@Test
	void waitUntilReadyAsync() throws ExecutionException, InterruptedException {
		final ContextConfig config = ContextConfig.create()
				.setUnits(units);

		final Context context = Context.create(clock, config, scheduler, dataFuture, dataProvider, eventHandler,
				variableParser);
		assertFalse(context.isReady());

		final CompletableFuture<Context> readyFuture = context.waitUntilReadyAsync();
		assertFalse(context.isReady());

		final Thread completer = new Thread(() -> dataFuture.complete(data));
		completer.start();

		readyFuture.join();
		completer.join();

		assertTrue(context.isReady());
		assertSame(context, readyFuture.get());
		assertSame(data, context.getData());
	}

	@Test
	void waitUntilReadyAsyncWithCompletedFuture() throws ExecutionException, InterruptedException {
		final ContextConfig config = ContextConfig.create()
				.setUnits(units);

		final Context context = Context.create(clock, config, scheduler, dataFutureReady, dataProvider, eventHandler,
				variableParser);
		assertTrue(context.isReady());

		final CompletableFuture<Context> readyFuture = context.waitUntilReadyAsync();
		readyFuture.join();

		assertTrue(context.isReady());
		assertSame(context, readyFuture.get());
		assertSame(data, context.getData());
	}

	@Test
	void throwsWhenNotReady() {
		final ContextConfig config = ContextConfig.create()
				.setUnits(units);

		final Context context = Context.create(clock, config, scheduler, dataFuture, dataProvider, eventHandler,
				variableParser);
		assertFalse(context.isReady());
		assertFalse(context.isFailed());

		final String notReadyMessage = "ABSmartly Context is not yet ready.";
		assertEquals(notReadyMessage,
				assertThrows(IllegalStateException.class, () -> context.peekTreatment("exp_test_ab")).getMessage());
		assertEquals(notReadyMessage,
				assertThrows(IllegalStateException.class, () -> context.getTreatment("exp_test_ab")).getMessage());
		assertEquals(notReadyMessage, assertThrows(IllegalStateException.class, context::getData).getMessage());
		assertEquals(notReadyMessage, assertThrows(IllegalStateException.class, context::getExperiments).getMessage());
		assertEquals(notReadyMessage,
				assertThrows(IllegalStateException.class, () -> context.getVariableValue("banner.border", 17))
						.getMessage());
		assertEquals(notReadyMessage,
				assertThrows(IllegalStateException.class, () -> context.peekVariableValue("banner.border", 17))
						.getMessage());
		assertEquals(notReadyMessage,
				assertThrows(IllegalStateException.class, context::getVariableKeys).getMessage());
	}

	@Test
	void throwsWhenClosing() {
		final ContextConfig config = ContextConfig.create()
				.setUnits(units);

		final Context context = Context.create(clock, config, scheduler, dataFutureReady, dataProvider, eventHandler,
				variableParser);
		assertTrue(context.isReady());
		assertFalse(context.isFailed());

		context.track("goal1", TestUtils.mapOf("amount", 125, "hours", 245));

		final CompletableFuture<Void> publishFuture = new CompletableFuture<>();
		when(eventHandler.publish(any())).thenReturn(publishFuture);

		final CompletableFuture<Void> closeFuture = context.closeAsync();

		assertTrue(context.isClosing());
		assertFalse(context.isClosed());

		final String closingMessage = "ABSmartly Context is closing.";
		assertEquals(closingMessage,
				assertThrows(IllegalStateException.class, () -> context.setAttribute("attr1", "value1")).getMessage());
		assertEquals(closingMessage,
				assertThrows(IllegalStateException.class,
						() -> context.setAttributes(TestUtils.mapOf("attr1", "value1")))
								.getMessage());
		assertEquals(closingMessage,
				assertThrows(IllegalStateException.class, () -> context.setOverride("exp_test_ab", 2)).getMessage());
		assertEquals(closingMessage,
				assertThrows(IllegalStateException.class, () -> context.setOverrides(TestUtils.mapOf("exp_test_ab", 2)))
						.getMessage());
		assertEquals(closingMessage,
				assertThrows(IllegalStateException.class, () -> context.peekTreatment("exp_test_ab")).getMessage());
		assertEquals(closingMessage,
				assertThrows(IllegalStateException.class, () -> context.getTreatment("exp_test_ab")).getMessage());
		assertEquals(closingMessage,
				assertThrows(IllegalStateException.class, () -> context.track("goal1", null)).getMessage());
		assertEquals(closingMessage, assertThrows(IllegalStateException.class, context::publish).getMessage());
		assertEquals(closingMessage, assertThrows(IllegalStateException.class, context::getData).getMessage());
		assertEquals(closingMessage, assertThrows(IllegalStateException.class, context::getExperiments).getMessage());
		assertEquals(closingMessage,
				assertThrows(IllegalStateException.class, () -> context.getVariableValue("banner.border", 17))
						.getMessage());
		assertEquals(closingMessage,
				assertThrows(IllegalStateException.class, () -> context.peekVariableValue("banner.border", 17))
						.getMessage());
		assertEquals(closingMessage,
				assertThrows(IllegalStateException.class, context::getVariableKeys).getMessage());
	}

	@Test
	void throwsWhenClosed() {
		final ContextConfig config = ContextConfig.create()
				.setUnits(units);

		final Context context = Context.create(clock, config, scheduler, dataFutureReady, dataProvider, eventHandler,
				variableParser);
		assertTrue(context.isReady());
		assertFalse(context.isFailed());

		context.track("goal1", TestUtils.mapOf("amount", 125, "hours", 245));

		when(eventHandler.publish(any())).thenReturn(CompletableFuture.completedFuture(null));

		context.close();

		assertFalse(context.isClosing());
		assertTrue(context.isClosed());

		final String closedMessage = "ABSmartly Context is closed.";
		assertEquals(closedMessage,
				assertThrows(IllegalStateException.class, () -> context.setAttribute("attr1", "value1")).getMessage());
		assertEquals(closedMessage,
				assertThrows(IllegalStateException.class,
						() -> context.setAttributes(TestUtils.mapOf("attr1", "value1")))
								.getMessage());
		assertEquals(closedMessage,
				assertThrows(IllegalStateException.class, () -> context.setOverride("exp_test_ab", 2)).getMessage());
		assertEquals(closedMessage,
				assertThrows(IllegalStateException.class, () -> context.setOverrides(TestUtils.mapOf("exp_test_ab", 2)))
						.getMessage());
		assertEquals(closedMessage,
				assertThrows(IllegalStateException.class, () -> context.peekTreatment("exp_test_ab")).getMessage());
		assertEquals(closedMessage,
				assertThrows(IllegalStateException.class, () -> context.getTreatment("exp_test_ab")).getMessage());
		assertEquals(closedMessage,
				assertThrows(IllegalStateException.class, () -> context.track("goal1", null)).getMessage());
		assertEquals(closedMessage, assertThrows(IllegalStateException.class, context::publish).getMessage());
		assertEquals(closedMessage, assertThrows(IllegalStateException.class, context::getData).getMessage());
		assertEquals(closedMessage, assertThrows(IllegalStateException.class, context::getExperiments).getMessage());
		assertEquals(closedMessage,
				assertThrows(IllegalStateException.class, () -> context.getVariableValue("banner.border", 17))
						.getMessage());
		assertEquals(closedMessage,
				assertThrows(IllegalStateException.class, () -> context.peekVariableValue("banner.border", 17))
						.getMessage());
		assertEquals(closedMessage,
				assertThrows(IllegalStateException.class, context::getVariableKeys).getMessage());
	}

	@Test
	void getExperiments() {
		final ContextConfig config = ContextConfig.create()
				.setUnits(units);

		final Context context = Context.create(clock, config, scheduler, dataFutureReady, dataProvider, eventHandler,
				variableParser);
		assertTrue(context.isReady());

		final String[] experiments = Arrays.stream(data.experiments).map(x -> x.name).toArray(String[]::new);
		assertArrayEquals(experiments, context.getExperiments());
	}

	@Test
	void startsPublishTimeoutWhenReadyWithQueueNotEmpty() {
		final ContextConfig config = ContextConfig.create()
				.setUnits(units)
				.setPublishDelay(333);

		final Context context = Context.create(clock, config, scheduler, dataFuture, dataProvider, eventHandler,
				variableParser);
		assertFalse(context.isReady());
		assertFalse(context.isFailed());

		context.track("goal1", TestUtils.mapOf("amount", 125));

		final AtomicReference<Runnable> runnable = new AtomicReference<>(null);
		when(scheduler.schedule((Runnable) any(), eq(config.getPublishDelay()), eq(TimeUnit.MILLISECONDS)))
				.thenAnswer(invokation -> {
					runnable.set(invokation.getArgument(0));
					return mock(ScheduledFuture.class);
				});

		dataFuture.complete(data);
		context.waitUntilReady();

		verify(scheduler, times(1)).schedule((Runnable) any(), eq(config.getPublishDelay()), eq(TimeUnit.MILLISECONDS));
		verify(eventHandler, times(0)).publish(any());

		runnable.get().run();

		verify(eventHandler, times(1)).publish(any());
	}

	@Test
	void setAttributesBeforeReady() {
		final ContextConfig config = ContextConfig.create()
				.setUnits(units);

		final Context context = Context.create(clock, config, scheduler, dataFuture, dataProvider, eventHandler,
				variableParser);
		assertFalse(context.isReady());

		context.setAttribute("attr1", "value1");
		context.setAttributes(TestUtils.mapOf("attr2", "value2"));

		dataFuture.complete(data);

		context.waitUntilReady();
	}

	@Test
	void setOverride() {
		final ContextConfig config = ContextConfig.create()
				.setUnits(units)
				.setAttributes(attributes);

		final Context context = Context.create(clock, config, scheduler, dataFutureReady, dataProvider, eventHandler,
				variableParser);

		context.setOverride("exp_test", 2);

		assertEquals(2, context.getOverride("exp_test"));

		context.setOverride("exp_test", 3);
		assertEquals(3, context.getOverride("exp_test"));

		context.setOverride("exp_test_2", 1);
		assertEquals(1, context.getOverride("exp_test_2"));

		final Map<String, Integer> overrides = TestUtils.mapOf(
				"exp_test_new", 3,
				"exp_test_new_2", 5);

		context.setOverrides(overrides);

		assertEquals(3, context.getOverride("exp_test"));
		assertEquals(1, context.getOverride("exp_test_2"));
		overrides.forEach((experimentName, variant) -> assertEquals(variant, context.getOverride(experimentName)));

		assertNull(context.getOverride("exp_test_not_found"));
	}

	@Test
	void setOverrideClearsAssignmentCache() {
		final ContextConfig config = ContextConfig.create()
				.setUnits(units)
				.setAttributes(attributes);

		final Context context = Context.create(clock, config, scheduler, dataFutureReady, dataProvider, eventHandler,
				variableParser);

		final Map<String, Integer> overrides = TestUtils.mapOf(
				"exp_test_new", 3,
				"exp_test_new_2", 5);

		context.setOverrides(overrides);

		overrides.forEach((experimentName, variant) -> {
			assertEquals(variant, context.getTreatment(experimentName));
		});
		assertEquals(overrides.size(), context.getPendingCount());

		// overriding again with the same variant shouldn't clear assignment cache
		overrides.forEach((experimentName, variant) -> {
			context.setOverride(experimentName, variant);
			assertEquals(variant, context.getTreatment(experimentName));
		});
		assertEquals(overrides.size(), context.getPendingCount());

		// overriding with the different variant should clear assignment cache
		overrides.forEach((experimentName, variant) -> {
			context.setOverride(experimentName, variant + 11);
			assertEquals(variant + 11, context.getTreatment(experimentName));
		});

		assertEquals(overrides.size() * 2, context.getPendingCount());

		// overriding a computed assignment should clear assignment cache
		assertEquals(expectedVariants.get("exp_test_ab"), context.getTreatment("exp_test_ab"));
		assertEquals(1 + overrides.size() * 2, context.getPendingCount());

		context.setOverride("exp_test_ab", 9);
		assertEquals(9, context.getTreatment("exp_test_ab"));
		assertEquals(2 + overrides.size() * 2, context.getPendingCount());
	}

	@Test
	void setOverridesBeforeReady() {
		final ContextConfig config = ContextConfig.create()
				.setUnits(units);

		final Context context = Context.create(clock, config, scheduler, dataFuture, dataProvider, eventHandler,
				variableParser);
		assertFalse(context.isReady());

		context.setOverride("exp_test", 2);
		context.setOverrides(TestUtils.mapOf(
				"exp_test_new", 3,
				"exp_test_new_2", 5));

		dataFuture.complete(data);

		context.waitUntilReady();

		assertEquals(2, context.getOverride("exp_test"));
		assertEquals(3, context.getOverride("exp_test_new"));
		assertEquals(5, context.getOverride("exp_test_new_2"));
	}

	@Test
	void peekTreatment() {
		final ContextConfig config = ContextConfig.create()
				.setUnits(units);

		final Context context = Context.create(clock, config, scheduler, dataFutureReady, dataProvider, eventHandler,
				variableParser);
		Arrays.stream(data.experiments).forEach(experiment -> assertEquals(expectedVariants.get(experiment.name),
				context.peekTreatment(experiment.name)));
		assertEquals(0, context.peekTreatment("not_found"));

		// call again
		Arrays.stream(data.experiments).forEach(experiment -> assertEquals(expectedVariants.get(experiment.name),
				context.peekTreatment(experiment.name)));
		assertEquals(0, context.peekTreatment("not_found"));

		assertEquals(0, context.getPendingCount());
	}

	@Test
	void peekVariableValue() {
		final ContextConfig config = ContextConfig.create()
				.setUnits(units);

		final Context context = Context.create(clock, config, scheduler, dataFutureReady, dataProvider, eventHandler,
				variableParser);
		final Set<String> experiments = Arrays.stream(data.experiments).map(x -> x.name).collect(Collectors.toSet());

		variableExperiments.forEach((variable, experimentName) -> {
			final Object actual = context.peekVariableValue(variable, 17);
			final boolean eligible = !experimentName.equals("exp_test_not_eligible");

			if (eligible && experiments.contains(experimentName)) {
				assertEquals(expectedVariables.get(variable), actual);
			} else {
				assertEquals(17, actual);
			}
		});

		assertEquals(0, context.getPendingCount());
	}

	@Test
	void getVariableValue() {
		final ContextConfig config = ContextConfig.create()
				.setUnits(units);

		final Context context = Context.create(clock, config, scheduler, dataFutureReady, dataProvider, eventHandler,
				variableParser);
		final Set<String> experiments = Arrays.stream(data.experiments).map(x -> x.name).collect(Collectors.toSet());

		variableExperiments.forEach((variable, experimentName) -> {
			final Object actual = context.getVariableValue(variable, 17);
			final boolean eligible = !experimentName.equals("exp_test_not_eligible");

			if (eligible && experiments.contains(experimentName)) {
				assertEquals(expectedVariables.get(variable), actual);
			} else {
				assertEquals(17, actual);
			}
		});

		assertEquals(experiments.size(), context.getPendingCount());
	}

	@Test
	void getVariableKeys() {
		final ContextConfig config = ContextConfig.create()
				.setUnits(units);

		final Context context = Context.create(clock, config, scheduler, refreshDataFutureReady, dataProvider,
				eventHandler,
				variableParser);

		assertEquals(variableExperiments, context.getVariableKeys());
	}

	@Test
	void peekTreatmentReturnsOverrideVariant() {
		final ContextConfig config = ContextConfig.create()
				.setUnits(units);

		final Context context = Context.create(clock, config, scheduler, dataFutureReady, dataProvider, eventHandler,
				variableParser);

		Arrays.stream(data.experiments).forEach(
				experiment -> context.setOverride(experiment.name, 11 + expectedVariants.get(experiment.name)));
		context.setOverride("not_found", 3);

		Arrays.stream(data.experiments).forEach(experiment -> assertEquals(expectedVariants.get(experiment.name) + 11,
				context.peekTreatment(experiment.name)));
		assertEquals(3, context.peekTreatment("not_found"));

		// call again
		Arrays.stream(data.experiments).forEach(experiment -> assertEquals(expectedVariants.get(experiment.name) + 11,
				context.peekTreatment(experiment.name)));
		assertEquals(3, context.peekTreatment("not_found"));

		assertEquals(0, context.getPendingCount());
	}

	@Test
	void getTreatment() {
		final ContextConfig config = ContextConfig.create()
				.setUnits(units);

		final Context context = Context.create(clock, config, scheduler, dataFutureReady, dataProvider, eventHandler,
				variableParser);
		Arrays.stream(data.experiments).forEach(experiment -> assertEquals(expectedVariants.get(experiment.name),
				context.getTreatment(experiment.name)));
		assertEquals(0, context.getTreatment("not_found"));
		assertEquals(1 + data.experiments.length, context.getPendingCount());

		final PublishEvent expected = new PublishEvent();
		expected.hashed = true;
		expected.publishedAt = clock.millis();
		expected.units = publishUnits;

		expected.exposures = new Exposure[]{
				new Exposure(1, "exp_test_ab", "session_id", 1, clock.millis(), true, true, false, false),
				new Exposure(2, "exp_test_abc", "session_id", 2, clock.millis(), true, true, false, false),
				new Exposure(3, "exp_test_not_eligible", "user_id", 0, clock.millis(), true, false, false, false),
				new Exposure(4, "exp_test_fullon", "session_id", 2, clock.millis(), true, true, false, true),
				new Exposure(0, "not_found", null, 0, clock.millis(), false, true, false, false),
		};

		when(eventHandler.publish(any())).thenReturn(CompletableFuture.completedFuture(null));

		context.publish();

		verify(eventHandler, times(1)).publish(any());
		verify(eventHandler, times(1)).publish(expected);

		context.close();
	}

	@Test
	void getTreatmentStartsPublishTimeoutAfterExposure() {
		final ContextConfig config = ContextConfig.create()
				.setUnits(units)
				.setPublishDelay(333);

		final Context context = Context.create(clock, config, scheduler, dataFutureReady, dataProvider, eventHandler,
				variableParser);
		assertTrue(context.isReady());
		assertFalse(context.isFailed());

		final AtomicReference<Runnable> runnable = new AtomicReference<>(null);
		when(scheduler.schedule((Runnable) any(), eq(config.getPublishDelay()), eq(TimeUnit.MILLISECONDS)))
				.thenAnswer(invokation -> {
					runnable.set(invokation.getArgument(0));
					return mock(ScheduledFuture.class);
				});

		context.getTreatment("exp_test_ab");
		context.getTreatment("exp_test_abc");

		verify(scheduler, times(1)).schedule((Runnable) any(), eq(config.getPublishDelay()), eq(TimeUnit.MILLISECONDS));
		verify(eventHandler, times(0)).publish(any());

		runnable.get().run();

		verify(eventHandler, times(1)).publish(any());
	}

	@Test
	void getTreatmentReturnsOverrideVariant() {
		final ContextConfig config = ContextConfig.create()
				.setUnits(units);

		final Context context = Context.create(clock, config, scheduler, dataFutureReady, dataProvider, eventHandler,
				variableParser);

		Arrays.stream(data.experiments).forEach(
				experiment -> context.setOverride(experiment.name, 11 + expectedVariants.get(experiment.name)));
		context.setOverride("not_found", 3);

		Arrays.stream(data.experiments).forEach(experiment -> assertEquals(expectedVariants.get(experiment.name) + 11,
				context.getTreatment(experiment.name)));
		assertEquals(3, context.getTreatment("not_found"));
		assertEquals(1 + data.experiments.length, context.getPendingCount());

		final PublishEvent expected = new PublishEvent();
		expected.hashed = true;
		expected.publishedAt = clock.millis();
		expected.units = publishUnits;

		expected.exposures = new Exposure[]{
				new Exposure(0, "exp_test_ab", "session_id", 12, clock.millis(), true, true, true, false),
				new Exposure(0, "exp_test_abc", "session_id", 13, clock.millis(), true, true, true, false),
				new Exposure(0, "exp_test_not_eligible", "user_id", 11, clock.millis(), true, true, true, false),
				new Exposure(0, "exp_test_fullon", "session_id", 13, clock.millis(), true, true, true, false),
				new Exposure(0, "not_found", null, 3, clock.millis(), false, true, true, false),
		};

		when(eventHandler.publish(any())).thenReturn(CompletableFuture.completedFuture(null));

		context.publish();

		verify(eventHandler, times(1)).publish(any());
		verify(eventHandler, times(1)).publish(expected);

		context.close();
	}

	@Test
	void getTreatmentQueuesExposureOnce() {
		final ContextConfig config = ContextConfig.create()
				.setUnits(units);

		final Context context = Context.create(clock, config, scheduler, dataFutureReady, dataProvider, eventHandler,
				variableParser);
		Arrays.stream(data.experiments).forEach(experiment -> context.getTreatment(experiment.name));
		context.getTreatment("not_found");

		assertEquals(1 + data.experiments.length, context.getPendingCount());

		// call again
		Arrays.stream(data.experiments).forEach(experiment -> context.getTreatment(experiment.name));
		context.getTreatment("not_found");

		assertEquals(1 + data.experiments.length, context.getPendingCount());

		when(eventHandler.publish(any())).thenReturn(CompletableFuture.completedFuture(null));

		context.publish();

		verify(eventHandler, times(1)).publish(any());

		assertEquals(0, context.getPendingCount());

		Arrays.stream(data.experiments).forEach(experiment -> context.getTreatment(experiment.name));
		context.getTreatment("not_found");
		assertEquals(0, context.getPendingCount());

		context.close();
	}

	@Test
	void track() {
		final ContextConfig config = ContextConfig.create()
				.setUnits(units);

		final Context context = Context.create(clock, config, scheduler, dataFutureReady, dataProvider, eventHandler,
				variableParser);
		context.track("goal1", TestUtils.mapOf("amount", 125, "hours", 245));
		context.track("goal2", TestUtils.mapOf("tries", 7));

		assertEquals(2, context.getPendingCount());

		context.track("goal2", TestUtils.mapOf("tests", 12));
		context.track("goal3", null);

		assertEquals(4, context.getPendingCount());

		final PublishEvent expected = new PublishEvent();
		expected.hashed = true;
		expected.publishedAt = clock.millis();
		expected.units = publishUnits;

		expected.goals = new GoalAchievement[]{
				new GoalAchievement("goal1", clock.millis(),
						new TreeMap<>(TestUtils.mapOf("amount", 125, "hours", 245))),
				new GoalAchievement("goal2", clock.millis(), new TreeMap<>(TestUtils.mapOf("tries", 7))),
				new GoalAchievement("goal2", clock.millis(), new TreeMap<>(TestUtils.mapOf("tests", 12))),
				new GoalAchievement("goal3", clock.millis(), null),
		};

		when(eventHandler.publish(any())).thenReturn(CompletableFuture.completedFuture(null));

		context.publish();

		verify(eventHandler, times(1)).publish(any());
		verify(eventHandler, times(1)).publish(expected);

		context.close();
	}

	@Test
	void trackStartsPublishTimeoutAfterAchievement() {
		final ContextConfig config = ContextConfig.create()
				.setUnits(units)
				.setPublishDelay(333);

		final Context context = Context.create(clock, config, scheduler, dataFutureReady, dataProvider, eventHandler,
				variableParser);
		assertTrue(context.isReady());
		assertFalse(context.isFailed());

		final AtomicReference<Runnable> runnable = new AtomicReference<>(null);
		when(scheduler.schedule((Runnable) any(), eq(config.getPublishDelay()), eq(TimeUnit.MILLISECONDS)))
				.thenAnswer(invokation -> {
					runnable.set(invokation.getArgument(0));
					return mock(ScheduledFuture.class);
				});

		context.track("goal1", TestUtils.mapOf("amount", 125));
		context.track("goal2", TestUtils.mapOf("value", 999.0));

		verify(scheduler, times(1)).schedule((Runnable) any(), eq(config.getPublishDelay()), eq(TimeUnit.MILLISECONDS));
		verify(eventHandler, times(0)).publish(any());

		runnable.get().run();

		verify(eventHandler, times(1)).publish(any());
	}

	@Test
	void trackQueuesWhenNotReady() {
		final ContextConfig config = ContextConfig.create()
				.setUnits(units);

		final Context context = Context.create(clock, config, scheduler, dataFuture, dataProvider, eventHandler,
				variableParser);
		context.track("goal1", TestUtils.mapOf("amount", 125, "hours", 245));
		context.track("goal2", TestUtils.mapOf("tries", 7));
		context.track("goal3", null);

		assertEquals(3, context.getPendingCount());
	}

	@Test
	void publishDoesNotCallEventHandlerWhenQueueIsEmpty() {
		final ContextConfig config = ContextConfig.create()
				.setUnits(units);

		final Context context = Context.create(clock, config, scheduler, dataFutureReady, dataProvider, eventHandler,
				variableParser);
		assertEquals(0, context.getPendingCount());

		context.publish();

		verify(eventHandler, times(0)).publish(any());
	}

	@Test
	void publishResetsInternalQueuesAndKeepAttributesAndOverrides() {
		final ContextConfig config = ContextConfig.create()
				.setUnits(units)
				.setAttributes(TestUtils.mapOf(
						"attr1", "value1",
						"attr2", "value2"))
				.setOverride("exp_test_abc", 2);

		final Context context = Context.create(clock, config, scheduler, dataFutureReady, dataProvider, eventHandler,
				variableParser);
		assertEquals(0, context.getPendingCount());
		assertEquals(2, context.getOverride("exp_test_abc"));

		assertEquals(1, context.getTreatment("exp_test_ab"));
		assertEquals(2, context.getTreatment("exp_test_abc"));
		context.track("goal1", TestUtils.mapOf("amount", 125, "hours", 245));

		assertEquals(3, context.getPendingCount());

		final PublishEvent expected = new PublishEvent();
		expected.hashed = true;
		expected.publishedAt = clock.millis();
		expected.units = publishUnits;

		expected.exposures = new Exposure[]{
				new Exposure(1, "exp_test_ab", "session_id", 1, clock.millis(), true, true, false, false),
				new Exposure(0, "exp_test_abc", "session_id", 2, clock.millis(), true, true, true, false),
		};

		expected.goals = new GoalAchievement[]{
				new GoalAchievement("goal1", clock.millis(),
						new TreeMap<>(TestUtils.mapOf("amount", 125, "hours", 245))),
		};

		expected.attributes = new Attribute[]{
				new Attribute("attr2", "value2", clock.millis()),
				new Attribute("attr1", "value1", clock.millis()),
		};

		when(eventHandler.publish(expected)).thenReturn(CompletableFuture.completedFuture(null));

		final CompletableFuture<Void> future = context.publishAsync();
		assertEquals(0, context.getPendingCount());
		assertEquals(2, context.getOverride("exp_test_abc"));

		future.join();
		assertEquals(0, context.getPendingCount());
		assertEquals(2, context.getOverride("exp_test_abc"));

		verify(eventHandler, times(1)).publish(any());
		verify(eventHandler, times(1)).publish(expected);

		Mockito.clearInvocations(eventHandler);

		// repeat
		context.track("goal1", TestUtils.mapOf("amount", 125, "hours", 245));

		assertEquals(1, context.getPendingCount());

		final PublishEvent expectedNext = new PublishEvent();
		expectedNext.hashed = true;
		expectedNext.publishedAt = clock.millis();
		expectedNext.units = publishUnits;

		expectedNext.goals = new GoalAchievement[]{
				new GoalAchievement("goal1", clock.millis(),
						new TreeMap<>(TestUtils.mapOf("amount", 125, "hours", 245))),
		};

		expectedNext.attributes = new Attribute[]{
				new Attribute("attr2", "value2", clock.millis()),
				new Attribute("attr1", "value1", clock.millis()),
		};

		when(eventHandler.publish(expectedNext)).thenReturn(CompletableFuture.completedFuture(null));

		final CompletableFuture<Void> futureNext = context.publishAsync();
		assertEquals(0, context.getPendingCount());

		futureNext.join();
		assertEquals(0, context.getPendingCount());

		verify(eventHandler, times(1)).publish(any());
		verify(eventHandler, times(1)).publish(expectedNext);
	}

	@Test
	void publishDoesNotCallEventHandlerWhenFailed() {
		final ContextConfig config = ContextConfig.create()
				.setUnits(units);

		final Context context = Context.create(clock, config, scheduler, dataFutureFailed, dataProvider, eventHandler,
				variableParser);
		assertTrue(context.isReady());
		assertTrue(context.isFailed());

		context.getTreatment("exp_test_abc");
		context.track("goal1", TestUtils.mapOf("amount", 125, "hours", 245));

		assertEquals(2, context.getPendingCount());

		when(eventHandler.publish(any())).thenReturn(CompletableFuture.completedFuture(null));

		context.publish();

		verify(eventHandler, times(0)).publish(any());
	}

	@Test
	void publishExceptionally() {
		final ContextConfig config = ContextConfig.create()
				.setUnits(units);

		final Context context = Context.create(clock, config, scheduler, dataFutureReady, dataProvider, eventHandler,
				variableParser);
		assertTrue(context.isReady());
		assertFalse(context.isFailed());

		context.track("goal1", TestUtils.mapOf("amount", 125, "hours", 245));

		assertEquals(1, context.getPendingCount());

		final Exception failure = new Exception("FAILED");
		when(eventHandler.publish(any())).thenReturn(TestUtils.failedFuture(failure));

		final CompletionException actual = assertThrows(CompletionException.class, context::publish);
		assertSame(failure, actual.getCause());

		verify(eventHandler, times(1)).publish(any());
	}

	@Test
	void closeAsync() {
		final ContextConfig config = ContextConfig.create()
				.setUnits(units);

		final Context context = Context.create(clock, config, scheduler, dataFutureReady, dataProvider, eventHandler,
				variableParser);
		assertTrue(context.isReady());

		context.track("goal1", TestUtils.mapOf("amount", 125, "hours", 245));

		final CompletableFuture<Void> publishFuture = new CompletableFuture<>();
		when(eventHandler.publish(any())).thenReturn(publishFuture);

		final CompletableFuture<Void> closingFuture = context.closeAsync();
		final CompletableFuture<Void> closingFutureNext = context.closeAsync();
		assertSame(closingFuture, closingFutureNext);

		assertTrue(context.isClosing());
		assertFalse(context.isClosed());

		publishFuture.complete(null);

		closingFuture.join();

		assertFalse(context.isClosing());
		assertTrue(context.isClosed());

		verify(eventHandler, times(1)).publish(any());
	}

	@Test
	void close() throws InterruptedException {
		final ContextConfig config = ContextConfig.create()
				.setUnits(units);

		final Context context = Context.create(clock, config, scheduler, dataFutureReady, dataProvider, eventHandler,
				variableParser);
		assertTrue(context.isReady());

		context.track("goal1", TestUtils.mapOf("amount", 125, "hours", 245));

		final CompletableFuture<Void> publishFuture = new CompletableFuture<>();
		when(eventHandler.publish(any())).thenReturn(publishFuture);

		assertFalse(context.isClosing());
		assertFalse(context.isClosed());

		final Thread publisher = new Thread(() -> publishFuture.complete(null));
		publisher.start();

		context.close();
		publisher.join();

		assertFalse(context.isClosing());
		assertTrue(context.isClosed());

		verify(eventHandler, times(1)).publish(any());

		context.close();
	}

	@Test
	void closeExceptionally() throws InterruptedException {
		final ContextConfig config = ContextConfig.create()
				.setUnits(units);

		final Context context = Context.create(clock, config, scheduler, dataFutureReady, dataProvider, eventHandler,
				variableParser);
		assertTrue(context.isReady());

		context.track("goal1", TestUtils.mapOf("amount", 125, "hours", 245));

		final CompletableFuture<Void> publishFuture = new CompletableFuture<>();
		when(eventHandler.publish(any())).thenReturn(publishFuture);

		final Exception failure = new Exception("FAILED");
		final Thread publisher = new Thread(() -> publishFuture.completeExceptionally(failure));
		publisher.start();

		final CompletionException actual = assertThrows(CompletionException.class, context::close);
		assertSame(failure, actual.getCause());

		publisher.join();

		verify(eventHandler, times(1)).publish(any());
	}

	@Test
	void refresh() {
		final ContextConfig config = ContextConfig.create()
				.setUnits(units);

		final Context context = Context.create(clock, config, scheduler, dataFutureReady, dataProvider, eventHandler,
				variableParser);
		assertTrue(context.isReady());

		when(dataProvider.getContextData()).thenReturn(refreshDataFuture);
		refreshDataFuture.complete(refreshData);

		context.refresh();

		verify(dataProvider, times(1)).getContextData();

		final String[] experiments = Arrays.stream(refreshData.experiments).map(x -> x.name).toArray(String[]::new);
		assertArrayEquals(experiments, context.getExperiments());
	}

	@Test
	void refreshExceptionally() {
		final ContextConfig config = ContextConfig.create()
				.setUnits(units);

		final Context context = Context.create(clock, config, scheduler, dataFutureReady, dataProvider, eventHandler,
				variableParser);
		assertTrue(context.isReady());
		assertFalse(context.isFailed());

		context.track("goal1", TestUtils.mapOf("amount", 125, "hours", 245));

		assertEquals(1, context.getPendingCount());

		final Exception failure = new Exception("FAILED");
		when(dataProvider.getContextData()).thenReturn(TestUtils.failedFuture(failure));

		final CompletionException actual = assertThrows(CompletionException.class, context::refresh);
		assertSame(failure, actual.getCause());

		verify(dataProvider, times(1)).getContextData();
	}

	@Test
	void refreshAsync() {
		final ContextConfig config = ContextConfig.create()
				.setUnits(units);

		final Context context = Context.create(clock, config, scheduler, dataFutureReady, dataProvider, eventHandler,
				variableParser);
		assertTrue(context.isReady());

		when(dataProvider.getContextData()).thenReturn(refreshDataFuture);

		final CompletableFuture<Void> refreshFuture = context.refreshAsync();
		final CompletableFuture<Void> refreshFutureNext = context.refreshAsync();
		assertSame(refreshFuture, refreshFutureNext);

		refreshDataFuture.complete(refreshData);
		refreshFuture.join();

		verify(dataProvider, times(1)).getContextData();

		final String[] experiments = Arrays.stream(refreshData.experiments).map(x -> x.name).toArray(String[]::new);
		assertArrayEquals(experiments, context.getExperiments());
	}

	@Test
	void refreshKeepsAssignmentCacheWhenNotChanged() {
		final ContextConfig config = ContextConfig.create()
				.setUnits(units);

		final Context context = Context.create(clock, config, scheduler, dataFutureReady, dataProvider, eventHandler,
				variableParser);
		assertTrue(context.isReady());

		Arrays.stream(data.experiments).forEach(experiment -> {
			context.getTreatment(experiment.name);
		});
		context.getTreatment("not_found");

		assertEquals(data.experiments.length + 1, context.getPendingCount());

		when(dataProvider.getContextData()).thenReturn(refreshDataFuture);

		final CompletableFuture<Void> refreshFuture = context.refreshAsync();

		verify(dataProvider, times(1)).getContextData();

		refreshDataFuture.complete(refreshData);
		refreshFuture.join();

		Arrays.stream(refreshData.experiments).forEach(experiment -> {
			context.getTreatment(experiment.name);
		});
		context.getTreatment("not_found");

		assertEquals(refreshData.experiments.length + 1, context.getPendingCount());
	}

	@Test
	void refreshClearAssignmentCacheForStoppedExperiment() {
		final ContextConfig config = ContextConfig.create()
				.setUnits(units);

		final Context context = Context.create(clock, config, scheduler, dataFutureReady, dataProvider, eventHandler,
				variableParser);
		assertTrue(context.isReady());

		final String experimentName = "exp_test_abc";
		assertEquals(expectedVariants.get(experimentName), context.getTreatment(experimentName));
		assertEquals(0, context.getTreatment("not_found"));

		assertEquals(2, context.getPendingCount());

		when(dataProvider.getContextData()).thenReturn(refreshDataFuture);

		final CompletableFuture<Void> refreshFuture = context.refreshAsync();

		verify(dataProvider, times(1)).getContextData();

		refreshData.experiments = Arrays.stream(refreshData.experiments).filter(x -> !x.name.equals(experimentName))
				.toArray(Experiment[]::new);

		refreshDataFuture.complete(refreshData);
		refreshFuture.join();

		assertEquals(0, context.getTreatment(experimentName));
		assertEquals(0, context.getTreatment("not_found"));

		assertEquals(3, context.getPendingCount()); // stopped experiment triggered a new exposure
	}

	@Test
	void refreshClearAssignmentCacheForStartedExperiment() {
		final ContextConfig config = ContextConfig.create()
				.setUnits(units);

		final Context context = Context.create(clock, config, scheduler, dataFutureReady, dataProvider, eventHandler,
				variableParser);
		assertTrue(context.isReady());

		final String experimentName = "exp_test_new";
		assertEquals(0, context.getTreatment(experimentName));
		assertEquals(0, context.getTreatment("not_found"));

		assertEquals(2, context.getPendingCount());

		when(dataProvider.getContextData()).thenReturn(refreshDataFuture);

		final CompletableFuture<Void> refreshFuture = context.refreshAsync();

		verify(dataProvider, times(1)).getContextData();

		refreshDataFuture.complete(refreshData);
		refreshFuture.join();

		assertEquals(expectedVariants.get(experimentName), context.getTreatment(experimentName));
		assertEquals(0, context.getTreatment("not_found"));

		assertEquals(3, context.getPendingCount()); // stopped experiment triggered a new exposure
	}

	@Test
	void refreshClearAssignmentCacheForFullOnExperiment() {
		final ContextConfig config = ContextConfig.create()
				.setUnits(units);

		final Context context = Context.create(clock, config, scheduler, dataFutureReady, dataProvider, eventHandler,
				variableParser);
		assertTrue(context.isReady());

		final String experimentName = "exp_test_abc";
		assertEquals(expectedVariants.get(experimentName), context.getTreatment(experimentName));
		assertEquals(0, context.getTreatment("not_found"));

		assertEquals(2, context.getPendingCount());

		when(dataProvider.getContextData()).thenReturn(refreshDataFuture);

		final CompletableFuture<Void> refreshFuture = context.refreshAsync();

		verify(dataProvider, times(1)).getContextData();

		Arrays.stream(refreshData.experiments).filter(x -> x.name.equals(experimentName)).forEach(experiment -> {
			assertEquals(0, experiment.fullOnVariant);
			experiment.fullOnVariant = 1;
			assertNotEquals(expectedVariants.get(experiment.name), experiment.fullOnVariant);
		});

		refreshDataFuture.complete(refreshData);
		refreshFuture.join();

		assertEquals(1, context.getTreatment(experimentName));
		assertEquals(0, context.getTreatment("not_found"));

		assertEquals(3, context.getPendingCount()); // full-on experiment triggered a new exposure
	}

	@Test
	void refreshClearAssignmentCacheForTrafficSplitChange() {
		final ContextConfig config = ContextConfig.create()
				.setUnits(units);

		final Context context = Context.create(clock, config, scheduler, dataFutureReady, dataProvider, eventHandler,
				variableParser);
		assertTrue(context.isReady());

		final String experimentName = "exp_test_not_eligible";
		assertEquals(expectedVariants.get(experimentName), context.getTreatment(experimentName));
		assertEquals(0, context.getTreatment("not_found"));

		assertEquals(2, context.getPendingCount());

		when(dataProvider.getContextData()).thenReturn(refreshDataFuture);

		final CompletableFuture<Void> refreshFuture = context.refreshAsync();

		verify(dataProvider, times(1)).getContextData();

		Arrays.stream(refreshData.experiments).filter(x -> x.name.equals(experimentName)).forEach(experiment -> {
			experiment.trafficSplit = new double[]{0.0, 1.0};
		});

		refreshDataFuture.complete(refreshData);
		refreshFuture.join();

		assertEquals(2, context.getTreatment(experimentName));
		assertEquals(0, context.getTreatment("not_found"));

		assertEquals(3, context.getPendingCount()); // newly eligible experiment triggered a new exposure
	}

	@Test
	void refreshClearAssignmentCacheForIterationChange() {
		final ContextConfig config = ContextConfig.create()
				.setUnits(units);

		final Context context = Context.create(clock, config, scheduler, dataFutureReady, dataProvider, eventHandler,
				variableParser);
		assertTrue(context.isReady());

		final String experimentName = "exp_test_abc";
		assertEquals(expectedVariants.get(experimentName), context.getTreatment(experimentName));
		assertEquals(0, context.getTreatment("not_found"));

		assertEquals(2, context.getPendingCount());

		when(dataProvider.getContextData()).thenReturn(refreshDataFuture);

		final CompletableFuture<Void> refreshFuture = context.refreshAsync();

		verify(dataProvider, times(1)).getContextData();

		Arrays.stream(refreshData.experiments).filter(x -> x.name.equals(experimentName)).forEach(experiment -> {
			experiment.iteration = 2;
			experiment.trafficSeedHi = 54870830;
			experiment.trafficSeedHi = 398724581;
			experiment.seedHi = 77498863;
			experiment.seedHi = 34737352;
		});

		refreshDataFuture.complete(refreshData);
		refreshFuture.join();

		assertEquals(1, context.getTreatment(experimentName));
		assertEquals(0, context.getTreatment("not_found"));

		assertEquals(3, context.getPendingCount()); // full-on experiment triggered a new exposure
	}

	@Test
	void refreshClearAssignmentCacheForExperimentIdChange() {
		final ContextConfig config = ContextConfig.create()
				.setUnits(units);

		final Context context = Context.create(clock, config, scheduler, dataFutureReady, dataProvider, eventHandler,
				variableParser);
		assertTrue(context.isReady());

		final String experimentName = "exp_test_abc";
		assertEquals(expectedVariants.get(experimentName), context.getTreatment(experimentName));
		assertEquals(0, context.getTreatment("not_found"));

		assertEquals(2, context.getPendingCount());

		when(dataProvider.getContextData()).thenReturn(refreshDataFuture);

		final CompletableFuture<Void> refreshFuture = context.refreshAsync();

		verify(dataProvider, times(1)).getContextData();

		Arrays.stream(refreshData.experiments).filter(x -> x.name.equals(experimentName)).forEach(experiment -> {
			experiment.id = 11;
			experiment.trafficSeedHi = 54870830;
			experiment.trafficSeedHi = 398724581;
			experiment.seedHi = 77498863;
			experiment.seedHi = 34737352;
		});

		refreshDataFuture.complete(refreshData);
		refreshFuture.join();

		assertEquals(1, context.getTreatment(experimentName));
		assertEquals(0, context.getTreatment("not_found"));

		assertEquals(3, context.getPendingCount()); // full-on experiment triggered a new exposure
	}
}
