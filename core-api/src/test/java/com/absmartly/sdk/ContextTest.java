package com.absmartly.sdk;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.*;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java8.util.concurrent.CompletableFuture;
import java8.util.concurrent.CompletionException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import com.absmartly.sdk.java.time.Clock;
import com.absmartly.sdk.json.Attribute;
import com.absmartly.sdk.json.ContextData;
import com.absmartly.sdk.json.Experiment;
import com.absmartly.sdk.json.Exposure;
import com.absmartly.sdk.json.GoalAchievement;
import com.absmartly.sdk.json.PublishEvent;
import com.absmartly.sdk.json.Unit;

class ContextTest extends TestUtils {
	final Map<String, String> units = mapOf(
			"session_id", "e791e240fcd3df7d238cfc285f475e8152fcc0ec",
			"user_id", "123456789",
			"email", "bleh@absmartly.com");

	final Map<String, Object> attributes = mapOf(
			"attr1", "value1",
			"attr2", "value2",
			"attr3", 5);

	final Map<String, Integer> expectedVariants = mapOf(
			"exp_test_ab", 1,
			"exp_test_abc", 2,
			"exp_test_not_eligible", 0,
			"exp_test_fullon", 2,
			"exp_test_new", 1);

	final Map<String, Object> expectedVariables = mapOf(
			"banner.border", 1,
			"banner.size", "large",
			"button.color", "red",
			"submit.color", "blue",
			"submit.shape", "rect",
			"show-modal", true);

	final Map<String, List<String>> variableExperiments = mapOf(
			"banner.border", listOf("exp_test_ab"),
			"banner.size", listOf("exp_test_ab"),
			"button.color", listOf("exp_test_abc"),
			"card.width", listOf("exp_test_not_eligible"),
			"submit.color", listOf("exp_test_fullon"),
			"submit.shape", listOf("exp_test_fullon"),
			"show-modal", listOf("exp_test_new"));

	final Unit[] publishUnits = new Unit[]{
			new Unit("user_id", "JfnnlDI7RTiF9RgfG2JNCw"),
			new Unit("session_id", "pAE3a1i5Drs5mKRNq56adA"),
			new Unit("email", "IuqYkNRfEx5yClel4j3NbA")
	};

	ContextData data;
	ContextData refreshData;

	ContextData audienceData;

	ContextData audienceStrictData;

	CompletableFuture<ContextData> dataFutureReady;
	CompletableFuture<ContextData> dataFutureFailed;
	CompletableFuture<ContextData> dataFuture;

	CompletableFuture<ContextData> refreshDataFutureReady;
	CompletableFuture<ContextData> refreshDataFuture;

	CompletableFuture<ContextData> audienceDataFutureReady;
	CompletableFuture<ContextData> audienceStrictDataFutureReady;

	ContextDataProvider dataProvider;
	ContextEventLogger eventLogger;
	ContextEventHandler eventHandler;
	VariableParser variableParser;
	AudienceMatcher audienceMatcher;
	ScheduledExecutorService scheduler;
	DefaultContextDataDeserializer deser = new DefaultContextDataDeserializer();
	Clock clock = Clock.fixed(1_620_000_000_000L);

	@BeforeEach
	void setUp() {
		final byte[] bytes = getResourceBytes("context.json");
		data = deser.deserialize(bytes, 0, bytes.length);

		final byte[] refreshBytes = getResourceBytes("refreshed.json");
		refreshData = deser.deserialize(refreshBytes, 0, refreshBytes.length);

		final byte[] audienceBytes = getResourceBytes("audience_context.json");
		audienceData = deser.deserialize(audienceBytes, 0, audienceBytes.length);

		final byte[] audienceStrictBytes = getResourceBytes("audience_strict_context.json");
		audienceStrictData = deser.deserialize(audienceStrictBytes, 0, audienceStrictBytes.length);

		dataFutureReady = CompletableFuture.completedFuture(data);
		dataFutureFailed = failedFuture(new Exception("FAILED"));
		dataFuture = new CompletableFuture<>();

		refreshDataFutureReady = CompletableFuture.completedFuture(refreshData);
		refreshDataFuture = new CompletableFuture<>();

		audienceDataFutureReady = CompletableFuture.completedFuture(audienceData);
		audienceStrictDataFutureReady = CompletableFuture.completedFuture(audienceStrictData);

		dataProvider = mock(ContextDataProvider.class);
		eventHandler = mock(ContextEventHandler.class);
		eventLogger = mock(ContextEventLogger.class);
		variableParser = new DefaultVariableParser();
		audienceMatcher = new AudienceMatcher(new DefaultAudienceDeserializer());
		scheduler = mock(ScheduledExecutorService.class);
	}

	Context createContext(ContextConfig config, CompletableFuture<ContextData> dataFuture) {
		return Context.create(clock, config, scheduler, dataFuture, dataProvider, eventHandler,
				eventLogger, variableParser, audienceMatcher);
	}

	Context createContext(CompletableFuture<ContextData> dataFuture) {
		final ContextConfig config = ContextConfig.create()
				.setUnits(units);

		return Context.create(clock, config, scheduler, dataFuture, dataProvider, eventHandler,
				eventLogger, variableParser, audienceMatcher);
	}

	Context createReadyContext() {
		final ContextConfig config = ContextConfig.create()
				.setUnits(units);

		return Context.create(clock, config, scheduler, dataFutureReady, dataProvider, eventHandler,
				eventLogger, variableParser, audienceMatcher);
	}

	Context createReadyContext(ContextData data) {
		final ContextConfig config = ContextConfig.create()
				.setUnits(units);

		return Context.create(clock, config, scheduler, CompletableFuture.completedFuture(data), dataProvider,
				eventHandler,
				eventLogger, variableParser, audienceMatcher);
	}

	@Test
	void constructorSetsOverrides() {
		final Map<String, Integer> overrides = mapOf(
				"exp_test", 2,
				"exp_test_1", 1);

		final ContextConfig config = ContextConfig.create()
				.setUnits(units)
				.setOverrides(overrides);

		final Context context = createContext(config, dataFutureReady);
		overrides.forEach((experimentName, variant) -> assertEquals(variant, context.getOverride(experimentName)));
	}

	@Test
	void constructorSetsCustomAssignments() {
		final Map<String, Integer> cassignments = mapOf(
				"exp_test", 2,
				"exp_test_1", 1);

		final ContextConfig config = ContextConfig.create()
				.setUnits(units)
				.setCustomAssignments(cassignments);

		final Context context = createContext(config, dataFutureReady);
		cassignments.forEach(
				(experimentName, variant) -> assertEquals(variant, context.getCustomAssignment(experimentName)));
	}

	@Test
	void becomesReadyWithCompletedFuture() {
		final Context context = createReadyContext();
		assertTrue(context.isReady());
		assertSame(data, context.getData());
	}

	@Test
	void becomesReadyAndFailedWithCompletedExceptionallyFuture() {
		final Context context = createContext(dataFutureFailed);
		assertTrue(context.isReady());
		assertTrue(context.isFailed());
	}

	@Test
	void becomesReadyAndFailedWithException() {
		final Context context = createContext(dataFuture);
		assertFalse(context.isReady());
		assertFalse(context.isFailed());

		dataFuture.completeExceptionally(new Exception("FAILED"));

		context.waitUntilReady();

		assertTrue(context.isReady());
		assertTrue(context.isFailed());
	}

	@Test
	void readyErrorReturnsNullOnSuccess() {
		final Context context = createReadyContext();
		assertNull(context.readyError());
	}

	@Test
	void readyErrorReturnsExceptionOnFailedFuture() {
		final Context context = createContext(dataFutureFailed);
		assertTrue(context.isFailed());
		assertNotNull(context.readyError());
	}

	@Test
	void readyErrorReturnsExceptionOnAsyncFailure() {
		final Context context = createContext(dataFuture);
		final Exception error = new Exception("FAILED");
		dataFuture.completeExceptionally(error);
		context.waitUntilReady();
		assertTrue(context.isFailed());
		assertNotNull(context.readyError());
	}

	@Test
	void callsEventLoggerWhenReady() {
		final Context context = createContext(dataFuture);

		dataFuture.complete(data);

		context.waitUntilReady();
		verify(eventLogger, Mockito.timeout(5000).times(1)).handleEvent(context, ContextEventLogger.EventType.Ready,
				data);
	}

	@Test
	void callsEventLoggerWithCompletedFuture() {
		final Context context = createReadyContext();
		verify(eventLogger, Mockito.timeout(5000).times(1)).handleEvent(context, ContextEventLogger.EventType.Ready,
				data);
	}

	@Test
	void callsEventLoggerWithException() {
		final Context context = createContext(dataFuture);

		final Exception error = new Exception("FAILED");
		dataFuture.completeExceptionally(error);

		context.waitUntilReady();
		verify(eventLogger, Mockito.timeout(5000).times(1)).handleEvent(context, ContextEventLogger.EventType.Error,
				error);
	}

	@Test
	void waitUntilReady() throws InterruptedException {
		final Context context = createContext(dataFuture);
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
		final Context context = createReadyContext();
		assertTrue(context.isReady());

		context.waitUntilReady();
		assertSame(data, context.getData());
	}

	@Test
	void waitUntilReadyAsync() throws ExecutionException, InterruptedException {
		final Context context = createContext(dataFuture);
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
		final Context context = createReadyContext();
		assertTrue(context.isReady());

		final CompletableFuture<Context> readyFuture = context.waitUntilReadyAsync();
		readyFuture.join();

		assertTrue(context.isReady());
		assertSame(context, readyFuture.get());
		assertSame(data, context.getData());
	}

	@Test
	void throwsWhenNotReady() {
		final Context context = createContext(dataFuture);
		assertFalse(context.isReady());
		assertFalse(context.isFailed());

		final String notReadyMessage = "ABsmartly Context is not yet ready.";
		assertEquals(notReadyMessage, assertThrows(IllegalStateException.class, context::getData).getMessage());

		assertEquals(0, context.peekTreatment("exp_test_ab"));
		assertEquals(0, context.getTreatment("exp_test_ab"));
		assertArrayEquals(new String[0], context.getExperiments());
		assertEquals(17, context.getVariableValue("banner.border", 17));
		assertEquals(17, context.peekVariableValue("banner.border", 17));
		assertEquals(new HashMap<>(), context.getVariableKeys());
		assertArrayEquals(new String[0], context.getCustomFieldKeys());
		assertNull(context.getCustomFieldValue("exp_test_ab", "key"));
		assertNull(context.getCustomFieldValueType("exp_test_ab", "key"));
	}

	@Test
	void returnsDefaultsWhenNotReady() {
		final Context context = createContext(dataFuture);
		assertFalse(context.isReady());

		assertEquals(0, context.peekTreatment("exp_test_ab"));
		assertEquals(0, context.getTreatment("exp_test_ab"));
		assertArrayEquals(new String[0], context.getExperiments());
		assertEquals("default", context.getVariableValue("banner.border", "default"));
		assertEquals("default", context.peekVariableValue("banner.border", "default"));
		assertTrue(context.getVariableKeys().isEmpty());
		assertArrayEquals(new String[0], context.getCustomFieldKeys());
		assertNull(context.getCustomFieldValue("exp_test_ab", "key"));
		assertNull(context.getCustomFieldValueType("exp_test_ab", "key"));
	}

	@Test
	void throwsWhenClosing() {
		final Context context = createReadyContext();
		assertTrue(context.isReady());
		assertFalse(context.isFailed());

		context.track("goal1", mapOf("amount", 125, "hours", 245));

		final CompletableFuture<Void> publishFuture = new CompletableFuture<>();
		when(eventHandler.publish(any(), any())).thenReturn(publishFuture);

		context.closeAsync();

		assertTrue(context.isClosing());
		assertFalse(context.isClosed());

		final String closingMessage = "ABsmartly Context is closing.";
		assertEquals(closingMessage,
				assertThrows(IllegalStateException.class, () -> context.setAttribute("attr1", "value1")).getMessage());
		assertEquals(closingMessage,
				assertThrows(IllegalStateException.class,
						() -> context.setAttributes(mapOf("attr1", "value1")))
						.getMessage());
		assertEquals(closingMessage,
				assertThrows(IllegalStateException.class, () -> context.setUnit("test", "test"))
						.getMessage());
		assertEquals(closingMessage,
				assertThrows(IllegalStateException.class, () -> context.setCustomAssignment("exp_test_ab", 2))
						.getMessage());
		assertEquals(closingMessage,
				assertThrows(IllegalStateException.class,
						() -> context.setCustomAssignments(mapOf("exp_test_ab", 2)))
						.getMessage());
		assertEquals(closingMessage,
				assertThrows(IllegalStateException.class, () -> context.track("goal1", null)).getMessage());
		assertEquals(closingMessage, assertThrows(IllegalStateException.class, context::publish).getMessage());
		assertEquals(closingMessage, assertThrows(IllegalStateException.class, context::getData).getMessage());

		assertEquals(0, context.peekTreatment("exp_test_ab"));
		assertEquals(0, context.getTreatment("exp_test_ab"));
		assertArrayEquals(new String[0], context.getExperiments());
		assertEquals(17, context.getVariableValue("banner.border", 17));
		assertEquals(17, context.peekVariableValue("banner.border", 17));
		assertEquals(new HashMap<>(), context.getVariableKeys());
		assertArrayEquals(new String[0], context.getCustomFieldKeys());
		assertNull(context.getCustomFieldValue("exp_test_ab", "key"));
		assertNull(context.getCustomFieldValueType("exp_test_ab", "key"));
	}

	@Test
	void throwsWhenClosed() {
		final Context context = createReadyContext();
		assertTrue(context.isReady());
		assertFalse(context.isFailed());

		context.track("goal1", mapOf("amount", 125, "hours", 245));

		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));

		context.close();

		assertFalse(context.isClosing());
		assertTrue(context.isClosed());

		final String closedMessage = "ABsmartly Context is finalized.";
		assertEquals(closedMessage,
				assertThrows(IllegalStateException.class, () -> context.setAttribute("attr1", "value1")).getMessage());
		assertEquals(closedMessage,
				assertThrows(IllegalStateException.class,
						() -> context.setAttributes(mapOf("attr1", "value1")))
						.getMessage());
		assertEquals(closedMessage,
				assertThrows(IllegalStateException.class, () -> context.setUnit("test", "test"))
						.getMessage());
		assertEquals(closedMessage,
				assertThrows(IllegalStateException.class, () -> context.setCustomAssignment("exp_test_ab", 2))
						.getMessage());
		assertEquals(closedMessage,
				assertThrows(IllegalStateException.class,
						() -> context.setCustomAssignments(mapOf("exp_test_ab", 2)))
						.getMessage());
		assertEquals(closedMessage,
				assertThrows(IllegalStateException.class, () -> context.track("goal1", null)).getMessage());
		assertEquals(closedMessage, assertThrows(IllegalStateException.class, context::publish).getMessage());
		assertEquals(closedMessage, assertThrows(IllegalStateException.class, context::getData).getMessage());

		assertEquals(0, context.peekTreatment("exp_test_ab"));
		assertEquals(0, context.getTreatment("exp_test_ab"));
		assertArrayEquals(new String[0], context.getExperiments());
		assertEquals(17, context.getVariableValue("banner.border", 17));
		assertEquals(17, context.peekVariableValue("banner.border", 17));
		assertEquals(new HashMap<>(), context.getVariableKeys());
		assertArrayEquals(new String[0], context.getCustomFieldKeys());
		assertNull(context.getCustomFieldValue("exp_test_ab", "key"));
		assertNull(context.getCustomFieldValueType("exp_test_ab", "key"));
	}

	@Test
	void isFinalizedAliasesIsClosedAfterClose() {
		final Context context = createReadyContext();
		assertFalse(context.isFinalized());
		assertFalse(context.isFinalizing());

		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));

		context.close();

		assertTrue(context.isFinalized());
		assertTrue(context.isClosed());
	}

	@Test
	void finalizeAsyncIsAliasForCloseAsync() {
		final Context context = createReadyContext();
		assertFalse(context.isFinalized());

		context.track("goal1", mapOf("amount", 125, "hours", 245));

		final CompletableFuture<Void> publishFuture = new CompletableFuture<>();
		when(eventHandler.publish(any(), any())).thenReturn(publishFuture);

		final CompletableFuture<Void> finalizeFuture = context.finalizeAsync();
		assertTrue(context.isFinalizing());
		assertFalse(context.isFinalized());

		publishFuture.complete(null);
		finalizeFuture.join();

		assertTrue(context.isFinalized());
		assertFalse(context.isFinalizing());
	}

	@Test
	void getExperiments() {
		final Context context = createReadyContext();
		assertTrue(context.isReady());

		final String[] experiments = Arrays.stream(data.experiments).map(x -> x.name).toArray(String[]::new);
		assertArrayEquals(experiments, context.getExperiments());
	}

	@Test
	void startsRefreshTimerWhenReady() {
		final ContextConfig config = ContextConfig.create()
				.setUnits(units)
				.setRefreshInterval(5_000);

		final Context context = createContext(config, dataFuture);
		assertFalse(context.isReady());
		assertFalse(context.isFailed());

		final AtomicReference<Runnable> runnable = new AtomicReference<>(null);
		when(scheduler.scheduleWithFixedDelay(any(), eq(config.getRefreshInterval()),
				eq(config.getRefreshInterval()), eq(TimeUnit.MILLISECONDS)))
				.thenAnswer(invokation -> {
					runnable.set(invokation.getArgument(0));
					return mock(ScheduledFuture.class);
				});

		dataFuture.complete(data);
		context.waitUntilReady();

		verify(scheduler, Mockito.timeout(5000).times(1)).scheduleWithFixedDelay(any(), eq(config.getRefreshInterval()),
				eq(config.getRefreshInterval()), eq(TimeUnit.MILLISECONDS));

		verify(dataProvider, Mockito.timeout(5000).times(0)).getContextData();
		when(dataProvider.getContextData()).thenReturn(refreshDataFutureReady);

		runnable.get().run();

		verify(dataProvider, Mockito.timeout(5000).times(1)).getContextData();
	}

	@Test
	void doestNotStartRefreshTimerWhenFailed() {
		final ContextConfig config = ContextConfig.create()
				.setUnits(units)
				.setRefreshInterval(5_000);

		final Context context = createContext(config, dataFuture);
		assertFalse(context.isReady());
		assertFalse(context.isFailed());

		dataFuture.completeExceptionally(new Exception("test"));

		context.waitUntilReady();

		assertTrue(context.isFailed());

		verify(scheduler, Mockito.timeout(5000).times(0)).scheduleWithFixedDelay(any(), anyLong(), anyLong(), any());
	}

	@Test
	void startsPublishTimeoutWhenReadyWithQueueNotEmpty() {
		final ContextConfig config = ContextConfig.create()
				.setUnits(units)
				.setPublishDelay(333);

		final Context context = createContext(config, dataFuture);
		assertFalse(context.isReady());
		assertFalse(context.isFailed());

		context.track("goal1", mapOf("amount", 125));

		final AtomicReference<Runnable> runnable = new AtomicReference<>(null);
		when(scheduler.schedule((Runnable) any(), eq(config.getPublishDelay()), eq(TimeUnit.MILLISECONDS)))
				.thenAnswer(invokation -> {
					runnable.set(invokation.getArgument(0));
					return mock(ScheduledFuture.class);
				});

		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));

		dataFuture.complete(data);
		context.waitUntilReady();

		verify(scheduler, Mockito.timeout(5000).times(1)).schedule((Runnable) any(), eq(config.getPublishDelay()),
				eq(TimeUnit.MILLISECONDS));
		verify(eventHandler, Mockito.timeout(5000).times(0)).publish(any(), any());

		runnable.get().run();

		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(any(), any());
	}

	@Test
	void setUnits() {
		final Context context = createContext(ContextConfig.create(), dataFuture);
		context.setUnits(units);

		for (final Map.Entry<String, String> entry : units.entrySet()) {
			assertEquals(entry.getValue(), context.getUnit(entry.getKey()));
		}
		assertEquals(units, context.getUnits());
	}

	@Test
	void setUnitsBeforeReady() {
		final Context context = createContext(ContextConfig.create(), dataFuture);
		assertFalse(context.isReady());

		context.setUnits(units);

		dataFuture.complete(data);

		context.waitUntilReady();

		context.getTreatment("exp_test_ab");

		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));

		context.publish();

		final PublishEvent expected = new PublishEvent();
		expected.hashed = true;
		expected.publishedAt = clock.millis();
		expected.units = publishUnits;
		expected.exposures = new Exposure[]{
				new Exposure(1, "exp_test_ab", "session_id", 1, clock.millis(), true, true, false, false, false, false),
		};

		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));

		context.publish();

		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(any(), any());
		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(context, expected);
	}

	@Test
	void setUnitEmpty() {
		final Context context = createContext(dataFutureReady);
		assertThrows(IllegalArgumentException.class, () -> {
			context.setUnit("db_user_id", "");
		}, "Unit 'session_id' UID must not be blank.");
	}

	@Test
	void setUnitThrowsOnAlreadySet() {
		final Context context = createContext(dataFutureReady);

		assertThrows(IllegalArgumentException.class, () -> {
			context.setUnit("session_id", "new_uid");
		}, "Unit 'session_id' UID already set.");
	}

	@Test
	void setAttributes() {
		final Context context = createContext(dataFuture);

		context.setAttribute("attr1", "value1");
		context.setAttributes(mapOf("attr2", "value2", "attr3", 15));

		assertEquals("value1", context.getAttribute("attr1"));
		assertEquals(mapOf("attr1", "value1", "attr2", "value2", "attr3", 15), context.getAttributes());
	}

	@Test
	void setAttributesBeforeReady() {
		final Context context = createContext(dataFuture);
		assertFalse(context.isReady());

		context.setAttribute("attr1", "value1");
		context.setAttributes(mapOf("attr2", "value2"));

		assertEquals("value1", context.getAttribute("attr1"));
		assertEquals(mapOf("attr1", "value1", "attr2", "value2"), context.getAttributes());

		dataFuture.complete(data);

		context.waitUntilReady();
	}

	@Test
	void setOverride() {
		final Context context = createReadyContext();

		context.setOverride("exp_test", 2);

		assertEquals(2, context.getOverride("exp_test"));

		context.setOverride("exp_test", 3);
		assertEquals(3, context.getOverride("exp_test"));

		context.setOverride("exp_test_2", 1);
		assertEquals(1, context.getOverride("exp_test_2"));

		final Map<String, Integer> overrides = mapOf(
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
		final Context context = createReadyContext();

		final Map<String, Integer> overrides = mapOf(
				"exp_test_new", 3,
				"exp_test_new_2", 5);

		context.setOverrides(overrides);

		overrides.forEach((experimentName, variant) -> assertEquals(variant, context.getTreatment(experimentName)));
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
		final Context context = createContext(dataFuture);
		assertFalse(context.isReady());

		context.setOverride("exp_test", 2);
		context.setOverrides(mapOf(
				"exp_test_new", 3,
				"exp_test_new_2", 5));

		dataFuture.complete(data);

		context.waitUntilReady();

		assertEquals(2, context.getOverride("exp_test"));
		assertEquals(3, context.getOverride("exp_test_new"));
		assertEquals(5, context.getOverride("exp_test_new_2"));
	}

	@Test
	void setCustomAssignment() {
		final Context context = createReadyContext();
		context.setCustomAssignment("exp_test", 2);

		assertEquals(2, context.getCustomAssignment("exp_test"));

		context.setCustomAssignment("exp_test", 3);
		assertEquals(3, context.getCustomAssignment("exp_test"));

		context.setCustomAssignment("exp_test_2", 1);
		assertEquals(1, context.getCustomAssignment("exp_test_2"));

		final Map<String, Integer> cassignments = mapOf(
				"exp_test_new", 3,
				"exp_test_new_2", 5);

		context.setCustomAssignments(cassignments);

		assertEquals(3, context.getCustomAssignment("exp_test"));
		assertEquals(1, context.getCustomAssignment("exp_test_2"));
		cassignments.forEach(
				(experimentName, variant) -> assertEquals(variant, context.getCustomAssignment(experimentName)));

		assertNull(context.getCustomAssignment("exp_test_not_found"));
	}

	@Test
	void setCustomAssignmentDoesNotOverrideFullOnOrNotEligibleAssignments() {
		final Context context = createReadyContext();

		context.setCustomAssignment("exp_test_not_eligible", 3);
		context.setCustomAssignment("exp_test_fullon", 3);

		assertEquals(0, context.getTreatment("exp_test_not_eligible"));
		assertEquals(2, context.getTreatment("exp_test_fullon"));
	}

	@Test
	void setCustomAssignmentClearsAssignmentCache() {
		final Context context = createReadyContext();

		final Map<String, Integer> cassignments = mapOf(
				"exp_test_ab", 2,
				"exp_test_abc", 3);

		cassignments.forEach((experimentName, variant) -> assertEquals(expectedVariants.get(experimentName),
				context.getTreatment(experimentName)));
		assertEquals(cassignments.size(), context.getPendingCount());

		context.setCustomAssignments(cassignments);

		cassignments.forEach((experimentName, variant) -> assertEquals(variant, context.getTreatment(experimentName)));
		assertEquals(2 * cassignments.size(), context.getPendingCount());

		// overriding again with the same variant shouldn't clear assignment cache
		cassignments.forEach((experimentName, variant) -> {
			context.setCustomAssignment(experimentName, variant);
			assertEquals(variant, context.getTreatment(experimentName));
		});
		assertEquals(2 * cassignments.size(), context.getPendingCount());

		// overriding with the different variant should clear assignment cache
		cassignments.forEach((experimentName, variant) -> {
			context.setCustomAssignment(experimentName, variant + 11);
			assertEquals(variant + 11, context.getTreatment(experimentName));
		});

		assertEquals(cassignments.size() * 3, context.getPendingCount());
	}

	@Test
	void setCustomAssignmentsBeforeReady() {
		final Context context = createContext(dataFuture);
		assertFalse(context.isReady());

		context.setCustomAssignment("exp_test", 2);
		context.setCustomAssignments(mapOf(
				"exp_test_new", 3,
				"exp_test_new_2", 5));

		dataFuture.complete(data);

		context.waitUntilReady();

		assertEquals(2, context.getCustomAssignment("exp_test"));
		assertEquals(3, context.getCustomAssignment("exp_test_new"));
		assertEquals(5, context.getCustomAssignment("exp_test_new_2"));
	}

	@Test
	void peekTreatment() {
		final Context context = createReadyContext();

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
		final Context context = createReadyContext();

		final Set<String> experiments = Arrays.stream(data.experiments).map(x -> x.name).collect(Collectors.toSet());

		variableExperiments.forEach((variable, experimentNames) -> {
			final String experimentName = experimentNames.get(0);
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
	void peekVariableValueConflictingKeyDisjointAudiences() {
		for (final Experiment experiment : data.experiments) {
			switch (experiment.name) {
			case "exp_test_ab":
				assert (expectedVariants.get(experiment.name) != 0);
				experiment.audienceStrict = true;
				experiment.audience = "{\"filter\":[{\"gte\":[{\"var\":\"age\"},{\"value\":20}]}]}";
				experiment.variants[expectedVariants.get(experiment.name)].config = "{\"icon\":\"arrow\"}";
				break;
			case "exp_test_abc":
				assert (expectedVariants.get(experiment.name) != 0);
				experiment.audienceStrict = true;
				experiment.audience = "{\"filter\":[{\"lt\":[{\"var\":\"age\"},{\"value\":20}]}]}";
				experiment.variants[expectedVariants.get(experiment.name)].config = "{\"icon\":\"circle\"}";
				break;
			default:
				break;
			}
		}

		{
			final Context context = createReadyContext(data);
			context.setAttribute("age", 20);
			assertEquals("arrow", context.peekVariableValue("icon", "square"));
		}

		{
			final Context context = createReadyContext(data);
			context.setAttribute("age", 19);
			assertEquals("circle", context.peekVariableValue("icon", "square"));
		}
	}

	@Test
	void peekVariableValuePicksLowestExperimentIdOnConflictingKey() {
		for (final Experiment experiment : data.experiments) {
			switch (experiment.name) {
			case "exp_test_ab":
				assert (expectedVariants.get(experiment.name) != 0);
				experiment.id = 99;
				experiment.variants[expectedVariants.get(experiment.name)].config = "{\"icon\":\"arrow\"}";
				break;
			case "exp_test_abc":
				assert (expectedVariants.get(experiment.name) != 0);
				experiment.id = 1;
				experiment.variants[expectedVariants.get(experiment.name)].config = "{\"icon\":\"circle\"}";
				break;
			default:
				break;
			}
		}

		final Context context = createReadyContext(data);
		assertEquals("circle", context.peekVariableValue("icon", "square"));
	}

	@Test
	void peekVariableValueReturnsAssignedVariantOnAudienceMismatchNonStrictMode() {
		final Context context = createContext(audienceDataFutureReady);

		assertEquals("large", context.peekVariableValue("banner.size", "small"));
	}

	@Test
	void peekVariableValueReturnsControlVariantOnAudienceMismatchStrictMode() {
		final Context context = createContext(audienceStrictDataFutureReady);

		assertEquals("small", context.peekVariableValue("banner.size", "small"));
	}

	@Test
	void getVariableValue() {
		final Context context = createReadyContext();

		final Set<String> experiments = Arrays.stream(data.experiments).map(x -> x.name).collect(Collectors.toSet());

		variableExperiments.forEach((variable, experimentNames) -> {
			final String experimentName = experimentNames.get(0);
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
	void getVariableValueConflictingKeyDisjointAudiences() {
		for (final Experiment experiment : data.experiments) {
			switch (experiment.name) {
			case "exp_test_ab":
				assert (expectedVariants.get(experiment.name) != 0);
				experiment.audienceStrict = true;
				experiment.audience = "{\"filter\":[{\"gte\":[{\"var\":\"age\"},{\"value\":20}]}]}";
				experiment.variants[expectedVariants.get(experiment.name)].config = "{\"icon\":\"arrow\"}";
				break;
			case "exp_test_abc":
				assert (expectedVariants.get(experiment.name) != 0);
				experiment.audienceStrict = true;
				experiment.audience = "{\"filter\":[{\"lt\":[{\"var\":\"age\"},{\"value\":20}]}]}";
				experiment.variants[expectedVariants.get(experiment.name)].config = "{\"icon\":\"circle\"}";
				break;
			default:
				break;
			}
		}

		{
			final Context context = createReadyContext(data);
			context.setAttribute("age", 20);
			assertEquals("arrow", context.getVariableValue("icon", "square"));

			assertEquals(1, context.getPendingCount());
		}

		{
			final Context context = createReadyContext(data);
			context.setAttribute("age", 19);
			assertEquals("circle", context.getVariableValue("icon", "square"));

			assertEquals(1, context.getPendingCount());
		}
	}

	@Test
	void getVariableValueQueuesExposureWithAudienceMismatchFalseOnAudienceMatch() {
		final Context context = createContext(audienceDataFutureReady);
		context.setAttribute("age", 21);

		assertEquals("large", context.getVariableValue("banner.size", "small"));
		assertEquals(1, context.getPendingCount());

		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));

		context.publish();

		final PublishEvent expected = new PublishEvent();
		expected.hashed = true;
		expected.publishedAt = clock.millis();
		expected.units = publishUnits;
		expected.attributes = new Attribute[]{
				new Attribute("age", 21, clock.millis()),
		};

		expected.exposures = new Exposure[]{
				new Exposure(1, "exp_test_ab", "session_id", 1, clock.millis(), true, true, false, false, false, false),
		};

		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));

		context.publish();

		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(any(), any());
		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(context, expected);
	}

	@Test
	void getVariableValueQueuesExposureWithAudienceMismatchTrueOnAudienceMismatch() {
		final Context context = createContext(audienceDataFutureReady);

		assertEquals("large", context.getVariableValue("banner.size", "small"));
		assertEquals(1, context.getPendingCount());

		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));

		context.publish();

		final PublishEvent expected = new PublishEvent();
		expected.hashed = true;
		expected.publishedAt = clock.millis();
		expected.units = publishUnits;

		expected.exposures = new Exposure[]{
				new Exposure(1, "exp_test_ab", "session_id", 1, clock.millis(), true, true, false, false, false, true),
		};

		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));

		context.publish();

		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(any(), any());
		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(context, expected);
	}

	@Test
	void getVariableValueDoesNotQueuesExposureWithAudienceMismatchFalseAndControlVariantOnAudienceMismatchInStrictMode() {
		final Context context = createContext(audienceStrictDataFutureReady);

		assertEquals("small", context.getVariableValue("banner.size", "small"));
		assertEquals(0, context.getPendingCount());
	}

	@Test
	void getVariableValueCallsEventLogger() {
		final Context context = createReadyContext();

		Mockito.clearInvocations(eventLogger);

		context.getVariableValue("banner.border", null);
		context.getVariableValue("banner.size", null);

		final Exposure[] exposures = new Exposure[]{
				new Exposure(1, "exp_test_ab", "session_id", 1, clock.millis(), true, true, false, false, false, false),
		};

		verify(eventLogger, Mockito.timeout(5000).times(exposures.length)).handleEvent(any(), any(), any());

		for (Exposure expected : exposures) {
			verify(eventLogger, Mockito.timeout(5000).times(1)).handleEvent(ArgumentMatchers.same(context),
					ArgumentMatchers.eq(ContextEventLogger.EventType.Exposure), ArgumentMatchers.eq(expected));
		}

		// verify not called again with the same exposure
		Mockito.clearInvocations(eventLogger);
		context.getVariableValue("banner.border", null);
		context.getVariableValue("banner.size", null);

		verify(eventLogger, Mockito.timeout(5000).times(0)).handleEvent(any(), any(), any());
	}

	@Test
	void getVariableKeys() {
		final Context context = createContext(refreshDataFutureReady);

		assertEquals(variableExperiments, context.getVariableKeys());
	}

	@Test
	void peekTreatmentReturnsOverrideVariant() {
		final Context context = createReadyContext();

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
	void peekTreatmentReturnsAssignedVariantOnAudienceMismatchNonStrictMode() {
		final Context context = createContext(audienceDataFutureReady);

		assertEquals(1, context.peekTreatment("exp_test_ab"));
	}

	@Test
	void peekTreatmentReturnsControlVariantOnAudienceMismatchStrictMode() {
		final Context context = createContext(audienceStrictDataFutureReady);

		assertEquals(0, context.peekTreatment("exp_test_ab"));
	}

	@Test
	void getTreatment() {
		final Context context = createReadyContext();

		Arrays.stream(data.experiments).forEach(experiment -> assertEquals(expectedVariants.get(experiment.name),
				context.getTreatment(experiment.name)));
		assertEquals(0, context.getTreatment("not_found"));
		assertEquals(1 + data.experiments.length, context.getPendingCount());

		final PublishEvent expected = new PublishEvent();
		expected.hashed = true;
		expected.publishedAt = clock.millis();
		expected.units = publishUnits;

		expected.exposures = new Exposure[]{
				new Exposure(1, "exp_test_ab", "session_id", 1, clock.millis(), true, true, false, false, false, false),
				new Exposure(2, "exp_test_abc", "session_id", 2, clock.millis(), true, true, false, false, false,
						false),
				new Exposure(3, "exp_test_not_eligible", "user_id", 0, clock.millis(), true, false, false, false,
						false, false),
				new Exposure(4, "exp_test_fullon", "session_id", 2, clock.millis(), true, true, false, true, false,
						false),
				new Exposure(0, "not_found", null, 0, clock.millis(), false, true, false, false, false, false),
		};

		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));

		context.publish();

		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(any(), any());
		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(context, expected);

		context.close();
	}

	@Test
	void getTreatmentStartsPublishTimeoutAfterExposure() {
		final ContextConfig config = ContextConfig.create()
				.setUnits(units)
				.setPublishDelay(333);

		final Context context = createContext(config, dataFutureReady);
		assertTrue(context.isReady());
		assertFalse(context.isFailed());

		final AtomicReference<Runnable> runnable = new AtomicReference<>(null);
		when(scheduler.schedule((Runnable) any(), eq(config.getPublishDelay()), eq(TimeUnit.MILLISECONDS)))
				.thenAnswer(invokation -> {
					runnable.set(invokation.getArgument(0));
					return mock(ScheduledFuture.class);
				});

		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));

		context.getTreatment("exp_test_ab");
		context.getTreatment("exp_test_abc");

		verify(scheduler, Mockito.timeout(5000).times(1)).schedule((Runnable) any(), eq(config.getPublishDelay()),
				eq(TimeUnit.MILLISECONDS));
		verify(eventHandler, Mockito.timeout(5000).times(0)).publish(any(), any());

		runnable.get().run();

		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(any(), any());
	}

	@Test
	void getTreatmentReturnsOverrideVariant() {
		final Context context = createReadyContext();

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
				new Exposure(1, "exp_test_ab", "session_id", 12, clock.millis(), false, true, true, false, false,
						false),
				new Exposure(2, "exp_test_abc", "session_id", 13, clock.millis(), false, true, true, false, false,
						false),
				new Exposure(3, "exp_test_not_eligible", "user_id", 11, clock.millis(), false, true, true, false, false,
						false),
				new Exposure(4, "exp_test_fullon", "session_id", 13, clock.millis(), false, true, true, false, false,
						false),
				new Exposure(0, "not_found", null, 3, clock.millis(), false, true, true, false, false, false),
		};

		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));

		context.publish();

		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(any(), any());
		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(context, expected);

		context.close();
	}

	@Test
	void getTreatmentQueuesExposureOnce() {
		final Context context = createReadyContext();

		Arrays.stream(data.experiments).forEach(experiment -> context.getTreatment(experiment.name));
		context.getTreatment("not_found");

		assertEquals(1 + data.experiments.length, context.getPendingCount());

		// call again
		Arrays.stream(data.experiments).forEach(experiment -> context.getTreatment(experiment.name));
		context.getTreatment("not_found");

		assertEquals(1 + data.experiments.length, context.getPendingCount());

		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));

		context.publish();

		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(any(), any());

		assertEquals(0, context.getPendingCount());

		Arrays.stream(data.experiments).forEach(experiment -> context.getTreatment(experiment.name));
		context.getTreatment("not_found");
		assertEquals(0, context.getPendingCount());

		context.close();
	}

	@Test
	void getTreatmentQueuesExposureWithAudienceMismatchFalseOnAudienceMatch() {
		final Context context = createContext(audienceDataFutureReady);
		context.setAttribute("age", 21);

		assertEquals(1, context.getTreatment("exp_test_ab"));
		assertEquals(1, context.getPendingCount());

		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));

		context.publish();

		final PublishEvent expected = new PublishEvent();
		expected.hashed = true;
		expected.publishedAt = clock.millis();
		expected.units = publishUnits;
		expected.attributes = new Attribute[]{
				new Attribute("age", 21, clock.millis()),
		};

		expected.exposures = new Exposure[]{
				new Exposure(1, "exp_test_ab", "session_id", 1, clock.millis(), true, true, false, false, false, false),
		};

		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));

		context.publish();

		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(any(), any());
		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(context, expected);
	}

	@Test
	void getTreatmentQueuesExposureWithAudienceMismatchTrueOnAudienceMismatch() {
		final Context context = createContext(audienceDataFutureReady);

		assertEquals(1, context.getTreatment("exp_test_ab"));
		assertEquals(1, context.getPendingCount());

		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));

		context.publish();

		final PublishEvent expected = new PublishEvent();
		expected.hashed = true;
		expected.publishedAt = clock.millis();
		expected.units = publishUnits;

		expected.exposures = new Exposure[]{
				new Exposure(1, "exp_test_ab", "session_id", 1, clock.millis(), true, true, false, false, false, true),
		};

		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));

		context.publish();

		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(any(), any());
		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(context, expected);
	}

	@Test
	void getTreatmentQueuesExposureWithAudienceMismatchTrueAndControlVariantOnAudienceMismatchInStrictMode() {
		final Context context = createContext(audienceStrictDataFutureReady);

		assertEquals(0, context.getTreatment("exp_test_ab"));
		assertEquals(1, context.getPendingCount());

		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));

		context.publish();

		final PublishEvent expected = new PublishEvent();
		expected.hashed = true;
		expected.publishedAt = clock.millis();
		expected.units = publishUnits;

		expected.exposures = new Exposure[]{
				new Exposure(1, "exp_test_ab", "session_id", 0, clock.millis(), false, true, false, false, false,
						true),
		};

		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));

		context.publish();

		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(any(), any());
		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(context, expected);
	}

	@Test
	void getTreatmentCallsEventLogger() {
		final Context context = createReadyContext();

		Mockito.clearInvocations(eventLogger);

		context.getTreatment("exp_test_ab");
		context.getTreatment("not_found");

		final Exposure[] exposures = new Exposure[]{
				new Exposure(1, "exp_test_ab", "session_id", 1, clock.millis(), true, true, false, false, false, false),
				new Exposure(0, "not_found", null, 0, clock.millis(), false, true, false, false, false, false),
		};

		verify(eventLogger, Mockito.timeout(5000).times(exposures.length)).handleEvent(any(), any(), any());

		for (Exposure expected : exposures) {
			verify(eventLogger, Mockito.timeout(5000).times(1)).handleEvent(ArgumentMatchers.same(context),
					ArgumentMatchers.eq(ContextEventLogger.EventType.Exposure), ArgumentMatchers.eq(expected));
		}

		// verify not called again with the same exposure
		Mockito.clearInvocations(eventLogger);
		context.getTreatment("exp_test_ab");
		context.getTreatment("not_found");

		verify(eventLogger, Mockito.timeout(5000).times(0)).handleEvent(any(), any(), any());
	}

	@Test
	void track() {
		final Context context = createReadyContext();
		context.track("goal1", mapOf("amount", 125, "hours", 245));
		context.track("goal2", mapOf("tries", 7));

		assertEquals(2, context.getPendingCount());

		context.track("goal2", mapOf("tests", 12));
		context.track("goal3", null);

		assertEquals(4, context.getPendingCount());

		final PublishEvent expected = new PublishEvent();
		expected.hashed = true;
		expected.publishedAt = clock.millis();
		expected.units = publishUnits;

		expected.goals = new GoalAchievement[]{
				new GoalAchievement("goal1", clock.millis(),
						new TreeMap<>(mapOf("amount", 125, "hours", 245))),
				new GoalAchievement("goal2", clock.millis(), new TreeMap<>(mapOf("tries", 7))),
				new GoalAchievement("goal2", clock.millis(), new TreeMap<>(mapOf("tests", 12))),
				new GoalAchievement("goal3", clock.millis(), null),
		};

		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));

		context.publish();

		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(any(), any());
		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(context, expected);

		context.close();
	}

	@Test
	void trackCallsEventLogger() {
		final Context context = createReadyContext();
		Mockito.clearInvocations(eventLogger);

		final Map<String, Object> properties = mapOf("amount", 125, "hours", 245);
		context.track("goal1", properties);

		final GoalAchievement[] achievements = new GoalAchievement[]{
				new GoalAchievement("goal1", clock.millis(), properties)
		};

		verify(eventLogger, Mockito.timeout(5000).times(achievements.length)).handleEvent(any(), any(), any());

		for (GoalAchievement goal : achievements) {
			verify(eventLogger, Mockito.timeout(5000).times(1)).handleEvent(ArgumentMatchers.same(context),
					ArgumentMatchers.eq(ContextEventLogger.EventType.Goal), ArgumentMatchers.eq(goal));
		}

		// verify called again with the same goal
		Mockito.clearInvocations(eventLogger);
		context.track("goal1", properties);

		for (GoalAchievement goal : achievements) {
			verify(eventLogger, Mockito.timeout(5000).times(1)).handleEvent(ArgumentMatchers.same(context),
					ArgumentMatchers.eq(ContextEventLogger.EventType.Goal), ArgumentMatchers.eq(goal));
		}
	}

	@Test
	void trackStartsPublishTimeoutAfterAchievement() {
		final ContextConfig config = ContextConfig.create()
				.setUnits(units)
				.setPublishDelay(333);

		final Context context = createContext(config, dataFutureReady);
		assertTrue(context.isReady());
		assertFalse(context.isFailed());

		final AtomicReference<Runnable> runnable = new AtomicReference<>(null);
		when(scheduler.schedule((Runnable) any(), eq(config.getPublishDelay()), eq(TimeUnit.MILLISECONDS)))
				.thenAnswer(invokation -> {
					runnable.set(invokation.getArgument(0));
					return mock(ScheduledFuture.class);
				});

		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));

		context.track("goal1", mapOf("amount", 125));
		context.track("goal2", mapOf("value", 999.0));

		verify(scheduler, Mockito.timeout(5000).times(1)).schedule((Runnable) any(), eq(config.getPublishDelay()),
				eq(TimeUnit.MILLISECONDS));
		verify(eventHandler, Mockito.timeout(5000).times(0)).publish(any(), any());

		runnable.get().run();

		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(any(), any());
	}

	@Test
	void trackQueuesWhenNotReady() {
		final Context context = createContext(dataFuture);

		context.track("goal1", mapOf("amount", 125, "hours", 245));
		context.track("goal2", mapOf("tries", 7));
		context.track("goal3", null);

		assertEquals(3, context.getPendingCount());
	}

	@Test
	void publishDoesNotCallEventHandlerWhenQueueIsEmpty() {
		final Context context = createReadyContext();
		assertEquals(0, context.getPendingCount());

		context.publish();

		verify(eventHandler, Mockito.timeout(5000).times(0)).publish(any(), any());
	}

	@Test
	void publishCallsEventLogger() {
		final Context context = createReadyContext();

		context.track("goal1", mapOf("amount", 125, "hours", 245));

		Mockito.clearInvocations(eventLogger);

		final PublishEvent expected = new PublishEvent();
		expected.hashed = true;
		expected.publishedAt = clock.millis();
		expected.units = publishUnits;

		expected.goals = new GoalAchievement[]{
				new GoalAchievement("goal1", clock.millis(),
						new TreeMap<>(mapOf("amount", 125, "hours", 245))),
		};

		when(eventHandler.publish(context, expected)).thenReturn(CompletableFuture.completedFuture(null));

		context.publish();

		verify(eventLogger, Mockito.timeout(5000).times(1)).handleEvent(any(), any(), any());
		verify(eventLogger, Mockito.timeout(5000).times(1)).handleEvent(context, ContextEventLogger.EventType.Publish,
				expected);
	}

	@Test
	void publishCallsEventLoggerOnError() {
		final Context context = createReadyContext();

		context.track("goal1", mapOf("amount", 125, "hours", 245));

		Mockito.clearInvocations(eventLogger);

		final Exception failure = new Exception("ERROR");
		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.failedFuture(failure));

		final CompletionException actual = assertThrows(CompletionException.class, context::publish);
		assertSame(failure, actual.getCause());

		verify(eventLogger, Mockito.timeout(5000).times(1)).handleEvent(any(), any(), any());
		verify(eventLogger, Mockito.timeout(5000).times(1)).handleEvent(context, ContextEventLogger.EventType.Error,
				failure);
	}

	@Test
	void publishResetsInternalQueuesAndKeepsAttributesOverridesAndCustomAssignments() {
		final ContextConfig config = ContextConfig.create()
				.setUnits(units)
				.setAttributes(mapOf(
						"attr1", "value1",
						"attr2", "value2"))
				.setCustomAssignment("exp_test_abc", 3)
				.setOverride("not_found", 3);

		final Context context = createContext(config, dataFutureReady);

		assertEquals(0, context.getPendingCount());

		assertEquals(1, context.getTreatment("exp_test_ab"));
		assertEquals(3, context.getTreatment("exp_test_abc"));
		assertEquals(3, context.getTreatment("not_found"));
		context.track("goal1", mapOf("amount", 125, "hours", 245));

		assertEquals(4, context.getPendingCount());

		final PublishEvent expected = new PublishEvent();
		expected.hashed = true;
		expected.publishedAt = clock.millis();
		expected.units = publishUnits;

		expected.exposures = new Exposure[]{
				new Exposure(1, "exp_test_ab", "session_id", 1, clock.millis(), true, true, false, false, false, false),
				new Exposure(2, "exp_test_abc", "session_id", 3, clock.millis(), true, true, false, false, true, false),
				new Exposure(0, "not_found", null, 3, clock.millis(), false, true, true, false, false, false),
		};

		expected.goals = new GoalAchievement[]{
				new GoalAchievement("goal1", clock.millis(),
						new TreeMap<>(mapOf("amount", 125, "hours", 245))),
		};

		expected.attributes = new Attribute[]{
				new Attribute("attr2", "value2", clock.millis()),
				new Attribute("attr1", "value1", clock.millis()),
		};

		when(eventHandler.publish(context, expected)).thenReturn(CompletableFuture.completedFuture(null));

		final CompletableFuture<Void> future = context.publishAsync();
		assertEquals(0, context.getPendingCount());
		assertEquals(3, context.getCustomAssignment("exp_test_abc"));
		assertEquals(3, context.getOverride("not_found"));

		future.join();
		assertEquals(0, context.getPendingCount());
		assertEquals(3, context.getCustomAssignment("exp_test_abc"));
		assertEquals(3, context.getOverride("not_found"));

		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(any(), any());
		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(context, expected);

		Mockito.clearInvocations(eventHandler);

		// repeat
		assertEquals(1, context.getTreatment("exp_test_ab"));
		assertEquals(3, context.getTreatment("exp_test_abc"));
		assertEquals(3, context.getTreatment("not_found"));
		context.track("goal1", mapOf("amount", 125, "hours", 245));

		assertEquals(1, context.getPendingCount());

		final PublishEvent expectedNext = new PublishEvent();
		expectedNext.hashed = true;
		expectedNext.publishedAt = clock.millis();
		expectedNext.units = publishUnits;

		expectedNext.goals = new GoalAchievement[]{
				new GoalAchievement("goal1", clock.millis(),
						new TreeMap<>(mapOf("amount", 125, "hours", 245))),
		};

		expectedNext.attributes = new Attribute[]{
				new Attribute("attr2", "value2", clock.millis()),
				new Attribute("attr1", "value1", clock.millis()),
		};

		when(eventHandler.publish(context, expectedNext)).thenReturn(CompletableFuture.completedFuture(null));

		final CompletableFuture<Void> futureNext = context.publishAsync();
		assertEquals(0, context.getPendingCount());

		futureNext.join();
		assertEquals(0, context.getPendingCount());

		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(any(), any());
		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(context, expectedNext);
	}

	@Test
	void publishDoesNotCallEventHandlerWhenFailed() {
		final Context context = createContext(dataFutureFailed);
		assertTrue(context.isReady());
		assertTrue(context.isFailed());

		context.getTreatment("exp_test_abc");
		context.track("goal1", mapOf("amount", 125, "hours", 245));

		assertEquals(2, context.getPendingCount());

		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));

		context.publish();

		verify(eventHandler, Mockito.timeout(5000).times(0)).publish(any(), any());
	}

	@Test
	void publishExceptionally() {
		final Context context = createReadyContext();
		assertTrue(context.isReady());
		assertFalse(context.isFailed());

		context.track("goal1", mapOf("amount", 125, "hours", 245));

		assertEquals(1, context.getPendingCount());

		final Exception failure = new Exception("FAILED");
		when(eventHandler.publish(any(), any())).thenReturn(failedFuture(failure));

		final CompletionException actual = assertThrows(CompletionException.class, context::publish);
		assertSame(failure, actual.getCause());

		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(any(), any());
	}

	@Test
	void closeAsync() {
		final Context context = createReadyContext();
		assertTrue(context.isReady());

		context.track("goal1", mapOf("amount", 125, "hours", 245));

		final CompletableFuture<Void> publishFuture = new CompletableFuture<>();
		when(eventHandler.publish(any(), any())).thenReturn(publishFuture);

		final CompletableFuture<Void> closingFuture = context.closeAsync();
		final CompletableFuture<Void> closingFutureNext = context.closeAsync();
		assertSame(closingFuture, closingFutureNext);

		assertTrue(context.isClosing());
		assertFalse(context.isClosed());

		publishFuture.complete(null);

		closingFuture.join();

		assertFalse(context.isClosing());
		assertTrue(context.isClosed());

		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(any(), any());
	}

	@Test
	void close() throws InterruptedException {
		final Context context = createReadyContext();
		assertTrue(context.isReady());

		context.track("goal1", mapOf("amount", 125, "hours", 245));

		final CompletableFuture<Void> publishFuture = new CompletableFuture<>();
		when(eventHandler.publish(any(), any())).thenReturn(publishFuture);

		assertFalse(context.isClosing());
		assertFalse(context.isClosed());

		final Thread publisher = new Thread(() -> publishFuture.complete(null));
		publisher.start();

		context.close();
		publisher.join();

		assertFalse(context.isClosing());
		assertTrue(context.isClosed());

		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(any(), any());

		context.close();
	}

	@Test
	void closeCallsEventLogger() throws InterruptedException {
		final Context context = createReadyContext();

		Mockito.clearInvocations(eventLogger);

		context.close();

		verify(eventLogger, Mockito.timeout(5000).times(1)).handleEvent(any(), any(), any());
		verify(eventLogger, Mockito.timeout(5000).times(1)).handleEvent(context, ContextEventLogger.EventType.Close,
				null);
	}

	@Test
	void closeCallsEventLoggerWithPendingEvents() throws InterruptedException {
		final Context context = createReadyContext();

		context.track("goal1", mapOf("amount", 125, "hours", 245));

		Mockito.clearInvocations(eventLogger);

		final PublishEvent expected = new PublishEvent();
		expected.hashed = true;
		expected.publishedAt = clock.millis();
		expected.units = publishUnits;

		expected.goals = new GoalAchievement[]{
				new GoalAchievement("goal1", clock.millis(),
						new TreeMap<>(mapOf("amount", 125, "hours", 245))),
		};

		final CompletableFuture<Void> publishFuture = new CompletableFuture<>();
		when(eventHandler.publish(any(), any())).thenReturn(publishFuture);

		final Thread publisher = new Thread(() -> publishFuture.complete(null));
		publisher.start();

		context.close();
		publisher.join();

		verify(eventLogger, Mockito.timeout(5000).times(2)).handleEvent(any(), any(), any());
		verify(eventLogger, Mockito.timeout(5000).times(1)).handleEvent(context, ContextEventLogger.EventType.Publish,
				expected);
		verify(eventLogger, Mockito.timeout(5000).times(1)).handleEvent(context, ContextEventLogger.EventType.Close,
				null);
	}

	@Test
	void closeCallsEventLoggerOnError() throws InterruptedException {
		final Context context = createReadyContext();

		context.track("goal1", mapOf("amount", 125, "hours", 245));

		Mockito.clearInvocations(eventLogger);

		final CompletableFuture<Void> publishFuture = new CompletableFuture<>();
		when(eventHandler.publish(any(), any())).thenReturn(publishFuture);

		final Exception failure = new Exception("FAILED");
		final Thread publisher = new Thread(() -> publishFuture.completeExceptionally(failure));
		publisher.start();

		final CompletionException actual = assertThrows(CompletionException.class, context::close);
		assertSame(failure, actual.getCause());

		publisher.join();

		verify(eventLogger, Mockito.timeout(5000).times(1)).handleEvent(any(), any(), any());
		verify(eventLogger, Mockito.timeout(5000).times(1)).handleEvent(context, ContextEventLogger.EventType.Error,
				failure);
	}

	@Test
	void closeExceptionally() throws InterruptedException {
		final Context context = createReadyContext();
		assertTrue(context.isReady());

		context.track("goal1", mapOf("amount", 125, "hours", 245));

		final CompletableFuture<Void> publishFuture = new CompletableFuture<>();
		when(eventHandler.publish(any(), any())).thenReturn(publishFuture);

		final Exception failure = new Exception("FAILED");
		final Thread publisher = new Thread(() -> publishFuture.completeExceptionally(failure));
		publisher.start();

		final CompletionException actual = assertThrows(CompletionException.class, context::close);
		assertSame(failure, actual.getCause());

		publisher.join();

		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(any(), any());
	}

	@Test
	void closeStopsRefreshTimer() {
		final ContextConfig config = ContextConfig.create()
				.setUnits(units)
				.setRefreshInterval(5_000);

		final ScheduledFuture refreshTimer = mock(ScheduledFuture.class);
		when(scheduler.scheduleWithFixedDelay(any(), eq(config.getRefreshInterval()),
				eq(config.getRefreshInterval()), eq(TimeUnit.MILLISECONDS)))
				.thenReturn(refreshTimer);

		final Context context = createContext(config, dataFutureReady);
		assertTrue(context.isReady());

		context.close();

		verify(refreshTimer, Mockito.timeout(5000).times(1)).cancel(false);
	}

	@Test
	void refresh() {
		final Context context = createReadyContext();
		assertTrue(context.isReady());

		when(dataProvider.getContextData()).thenReturn(refreshDataFuture);
		refreshDataFuture.complete(refreshData);

		context.refresh();

		verify(dataProvider, Mockito.timeout(5000).times(1)).getContextData();

		final String[] experiments = Arrays.stream(refreshData.experiments).map(x -> x.name).toArray(String[]::new);
		assertArrayEquals(experiments, context.getExperiments());
	}

	@Test
	void refreshCallsEventLogger() {
		final Context context = createReadyContext();
		Mockito.clearInvocations(eventLogger);

		when(dataProvider.getContextData()).thenReturn(refreshDataFuture);
		refreshDataFuture.complete(refreshData);

		context.refresh();

		verify(eventLogger, Mockito.timeout(5000).times(1)).handleEvent(context, ContextEventLogger.EventType.Refresh,
				refreshData);
	}

	@Test
	void refreshCallsEventLoggerOnError() {
		final Context context = createReadyContext();
		Mockito.clearInvocations(eventLogger);

		final Exception failure = new Exception("ERROR");
		when(dataProvider.getContextData()).thenReturn(CompletableFuture.failedFuture(failure));
		refreshDataFuture.complete(refreshData);

		final CompletionException actual = assertThrows(CompletionException.class, context::refresh);
		assertSame(failure, actual.getCause());

		verify(eventLogger, Mockito.timeout(5000).times(1)).handleEvent(context, ContextEventLogger.EventType.Error,
				failure);
	}

	@Test
	void refreshExceptionally() {
		final Context context = createReadyContext();
		assertTrue(context.isReady());
		assertFalse(context.isFailed());

		context.track("goal1", mapOf("amount", 125, "hours", 245));

		assertEquals(1, context.getPendingCount());

		final Exception failure = new Exception("FAILED");
		when(dataProvider.getContextData()).thenReturn(failedFuture(failure));

		final CompletionException actual = assertThrows(CompletionException.class, context::refresh);
		assertSame(failure, actual.getCause());

		verify(dataProvider, Mockito.timeout(5000).times(1)).getContextData();
	}

	@Test
	void refreshAsync() {
		final Context context = createReadyContext();
		assertTrue(context.isReady());

		when(dataProvider.getContextData()).thenReturn(refreshDataFuture);

		final CompletableFuture<Void> refreshFuture = context.refreshAsync();
		final CompletableFuture<Void> refreshFutureNext = context.refreshAsync();
		assertSame(refreshFuture, refreshFutureNext);

		refreshDataFuture.complete(refreshData);
		refreshFuture.join();

		verify(dataProvider, Mockito.timeout(5000).times(1)).getContextData();

		final String[] experiments = Arrays.stream(refreshData.experiments).map(x -> x.name).toArray(String[]::new);
		assertArrayEquals(experiments, context.getExperiments());
	}

	@Test
	void refreshKeepsAssignmentCacheWhenNotChanged() {
		final Context context = createReadyContext();
		assertTrue(context.isReady());

		Arrays.stream(data.experiments).forEach(experiment -> context.getTreatment(experiment.name));
		context.getTreatment("not_found");

		assertEquals(data.experiments.length + 1, context.getPendingCount());

		when(dataProvider.getContextData()).thenReturn(refreshDataFuture);

		final CompletableFuture<Void> refreshFuture = context.refreshAsync();

		verify(dataProvider, Mockito.timeout(5000).times(1)).getContextData();

		refreshDataFuture.complete(refreshData);
		refreshFuture.join();

		Arrays.stream(refreshData.experiments).forEach(experiment -> context.getTreatment(experiment.name));
		context.getTreatment("not_found");

		assertEquals(refreshData.experiments.length + 1, context.getPendingCount());
	}

	@Test
	void refreshKeepsAssignmentCacheWhenNotChangedOnAudienceMismatch() {
		final Context context = createContext(audienceStrictDataFutureReady);

		assertEquals(0, context.getTreatment("exp_test_ab"));

		assertEquals(1, context.getPendingCount());

		when(dataProvider.getContextData()).thenReturn(audienceStrictDataFutureReady);

		final CompletableFuture<Void> refreshFuture = context.refreshAsync();

		verify(dataProvider, Mockito.timeout(5000).times(1)).getContextData();

		refreshFuture.join();

		assertEquals(0, context.getTreatment("exp_test_ab"));

		assertEquals(1, context.getPendingCount()); // no new exposure
	}

	@Test
	void refreshKeepsAssignmentCacheWhenNotChangedWithOverride() {
		final Context context = createReadyContext();

		context.setOverride("exp_test_ab", 3);
		assertEquals(3, context.getTreatment("exp_test_ab"));

		assertEquals(1, context.getPendingCount());

		when(dataProvider.getContextData()).thenReturn(dataFutureReady);

		final CompletableFuture<Void> refreshFuture = context.refreshAsync();

		verify(dataProvider, Mockito.timeout(5000).times(1)).getContextData();

		refreshFuture.join();

		assertEquals(3, context.getTreatment("exp_test_ab"));

		assertEquals(1, context.getPendingCount()); // no new exposure
	}

	@Test
	void refreshClearAssignmentCacheForStoppedExperiment() {
		final Context context = createReadyContext();
		assertTrue(context.isReady());

		final String experimentName = "exp_test_abc";
		assertEquals(expectedVariants.get(experimentName), context.getTreatment(experimentName));
		assertEquals(0, context.getTreatment("not_found"));

		assertEquals(2, context.getPendingCount());

		when(dataProvider.getContextData()).thenReturn(refreshDataFuture);

		final CompletableFuture<Void> refreshFuture = context.refreshAsync();

		verify(dataProvider, Mockito.timeout(5000).times(1)).getContextData();

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
		final Context context = createReadyContext();
		assertTrue(context.isReady());

		final String experimentName = "exp_test_new";
		assertEquals(0, context.getTreatment(experimentName));
		assertEquals(0, context.getTreatment("not_found"));

		assertEquals(2, context.getPendingCount());

		when(dataProvider.getContextData()).thenReturn(refreshDataFuture);

		final CompletableFuture<Void> refreshFuture = context.refreshAsync();

		verify(dataProvider, Mockito.timeout(5000).times(1)).getContextData();

		refreshDataFuture.complete(refreshData);
		refreshFuture.join();

		assertEquals(expectedVariants.get(experimentName), context.getTreatment(experimentName));
		assertEquals(0, context.getTreatment("not_found"));

		assertEquals(3, context.getPendingCount()); // stopped experiment triggered a new exposure
	}

	@Test
	void refreshClearAssignmentCacheForFullOnExperiment() {
		final Context context = createReadyContext();
		assertTrue(context.isReady());

		final String experimentName = "exp_test_abc";
		assertEquals(expectedVariants.get(experimentName), context.getTreatment(experimentName));
		assertEquals(0, context.getTreatment("not_found"));

		assertEquals(2, context.getPendingCount());

		when(dataProvider.getContextData()).thenReturn(refreshDataFuture);

		final CompletableFuture<Void> refreshFuture = context.refreshAsync();

		verify(dataProvider, Mockito.timeout(5000).times(1)).getContextData();

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
		final Context context = createReadyContext();
		assertTrue(context.isReady());

		final String experimentName = "exp_test_not_eligible";
		assertEquals(expectedVariants.get(experimentName), context.getTreatment(experimentName));
		assertEquals(0, context.getTreatment("not_found"));

		assertEquals(2, context.getPendingCount());

		when(dataProvider.getContextData()).thenReturn(refreshDataFuture);

		final CompletableFuture<Void> refreshFuture = context.refreshAsync();

		verify(dataProvider, Mockito.timeout(5000).times(1)).getContextData();

		Arrays.stream(refreshData.experiments).filter(x -> x.name.equals(experimentName))
				.forEach(experiment -> experiment.trafficSplit = new double[]{0.0, 1.0});

		refreshDataFuture.complete(refreshData);
		refreshFuture.join();

		assertEquals(2, context.getTreatment(experimentName));
		assertEquals(0, context.getTreatment("not_found"));

		assertEquals(3, context.getPendingCount()); // newly eligible experiment triggered a new exposure
	}

	@Test
	void refreshClearAssignmentCacheForIterationChange() {
		final Context context = createReadyContext();
		assertTrue(context.isReady());

		final String experimentName = "exp_test_abc";
		assertEquals(expectedVariants.get(experimentName), context.getTreatment(experimentName));
		assertEquals(0, context.getTreatment("not_found"));

		assertEquals(2, context.getPendingCount());

		when(dataProvider.getContextData()).thenReturn(refreshDataFuture);

		final CompletableFuture<Void> refreshFuture = context.refreshAsync();

		verify(dataProvider, Mockito.timeout(5000).times(1)).getContextData();

		Arrays.stream(refreshData.experiments).filter(x -> x.name.equals(experimentName)).forEach(experiment -> {
			experiment.iteration = 2;
			experiment.trafficSeedHi = 54870830;
			experiment.trafficSeedLo = 398724581;
			experiment.seedHi = 77498863;
			experiment.seedLo = 34737352;
		});

		refreshDataFuture.complete(refreshData);
		refreshFuture.join();

		assertEquals(2, context.getTreatment(experimentName));
		assertEquals(0, context.getTreatment("not_found"));

		assertEquals(3, context.getPendingCount()); // full-on experiment triggered a new exposure
	}

	@Test
	void refreshClearAssignmentCacheForExperimentIdChange() {
		final Context context = createReadyContext();
		assertTrue(context.isReady());

		final String experimentName = "exp_test_abc";
		assertEquals(expectedVariants.get(experimentName), context.getTreatment(experimentName));
		assertEquals(0, context.getTreatment("not_found"));

		assertEquals(2, context.getPendingCount());

		when(dataProvider.getContextData()).thenReturn(refreshDataFuture);

		final CompletableFuture<Void> refreshFuture = context.refreshAsync();

		verify(dataProvider, Mockito.timeout(5000).times(1)).getContextData();

		Arrays.stream(refreshData.experiments).filter(x -> x.name.equals(experimentName)).forEach(experiment -> {
			experiment.id = 11;
			experiment.trafficSeedHi = 54870830;
			experiment.trafficSeedLo = 398724581;
			experiment.seedHi = 77498863;
			experiment.seedLo = 34737352;
		});

		refreshDataFuture.complete(refreshData);
		refreshFuture.join();

		assertEquals(2, context.getTreatment(experimentName));
		assertEquals(0, context.getTreatment("not_found"));

		assertEquals(3, context.getPendingCount()); // full-on experiment triggered a new exposure
	}

	@Test
	void getCustomFieldKeys() {
		final byte[] customFieldsContextBytes = getResourceBytes("custom_fields_context.json");
		final ContextData customFieldsContextData = deser.deserialize(customFieldsContextBytes, 0,
				customFieldsContextBytes.length);
		final Context context = createReadyContext(customFieldsContextData);
		assertTrue(context.isReady());

		assertArrayEquals(new String[]{"country", "languages", "overrides"}, context.getCustomFieldKeys());
	}

	@Test
	void getCustomFieldValue() {
		final byte[] customFieldsContextBytes = getResourceBytes("custom_fields_context.json");
		final ContextData customFieldsContextData = deser.deserialize(customFieldsContextBytes, 0,
				customFieldsContextBytes.length);
		final Context context = createReadyContext(customFieldsContextData);
		assertTrue(context.isReady());

		assertNull(context.getCustomFieldValue("not_found", "not_found"));
		assertNull(context.getCustomFieldValue("exp_test_ab", "not_found"));
		assertEquals("US,PT,ES,DE,FR", context.getCustomFieldValue("exp_test_ab", "country"));
		assertEquals("string", context.getCustomFieldValueType("exp_test_ab", "country"));
		assertEquals(mapOf("123", 1, "456", 0), context.getCustomFieldValue("exp_test_ab", "overrides"));
		assertEquals("json", context.getCustomFieldValueType("exp_test_ab", "overrides"));
		assertNull(context.getCustomFieldValue("exp_test_ab", "languages"));
		assertNull(context.getCustomFieldValueType("exp_test_ab", "languages"));

		assertEquals("US,PT,ES", context.getCustomFieldValue("exp_test_abc", "country"));
		assertEquals("string", context.getCustomFieldValueType("exp_test_abc", "country"));
		assertNull(context.getCustomFieldValue("exp_test_abc", "overrides"));
		assertNull(context.getCustomFieldValueType("exp_test_abc", "overrides"));
		assertEquals("en-US,en-GB,pt-PT,pt-BR,es-ES,es-MX", context.getCustomFieldValue("exp_test_abc", "languages"));
		assertEquals("string", context.getCustomFieldValueType("exp_test_abc", "languages"));

		assertNull(context.getCustomFieldValue("exp_test_no_custom_fields", "country"));
		assertNull(context.getCustomFieldValueType("exp_test_no_custom_fields", "country"));
		assertNull(context.getCustomFieldValue("exp_test_no_custom_fields", "overrides"));
		assertNull(context.getCustomFieldValueType("exp_test_no_custom_fields", "overrides"));
		assertNull(context.getCustomFieldValue("exp_test_no_custom_fields", "languages"));
		assertNull(context.getCustomFieldValueType("exp_test_no_custom_fields", "languages"));
	}

	@Test
	void getTreatmentQueuesExposureAfterPeek() {
		final Context context = createReadyContext();

		Arrays.stream(data.experiments).forEach(experiment -> context.peekTreatment(experiment.name));
		context.peekTreatment("not_found");

		assertEquals(0, context.getPendingCount());

		Arrays.stream(data.experiments).forEach(experiment -> context.getTreatment(experiment.name));
		context.getTreatment("not_found");

		assertEquals(1 + data.experiments.length, context.getPendingCount());
	}

	@Test
	void getTreatmentQueuesExposureWithBaseVariantOnUnknownExperiment() {
		final Context context = createReadyContext();

		assertEquals(0, context.getTreatment("not_found"));
		assertEquals(1, context.getPendingCount());

		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));

		context.publish();

		final PublishEvent expected = new PublishEvent();
		expected.hashed = true;
		expected.publishedAt = clock.millis();
		expected.units = publishUnits;

		expected.exposures = new Exposure[]{
				new Exposure(0, "not_found", null, 0, clock.millis(), false, true, false, false, false, false),
		};

		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));

		context.publish();

		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(any(), any());
		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(context, expected);
	}

	@Test
	void getTreatmentDoesNotReQueueExposureOnUnknownExperiment() {
		final Context context = createReadyContext();

		assertEquals(0, context.getTreatment("not_found"));
		assertEquals(1, context.getPendingCount());

		assertEquals(0, context.getTreatment("not_found"));
		assertEquals(1, context.getPendingCount());

		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));

		context.publish();

		assertEquals(0, context.getTreatment("not_found"));
		assertEquals(0, context.getPendingCount());
	}

	@Test
	void getTreatmentQueuesExposureWithCustomAssignmentVariant() {
		final Context context = createReadyContext();

		context.setCustomAssignment("exp_test_ab", 2);

		assertEquals(2, context.getTreatment("exp_test_ab"));
		assertEquals(1, context.getPendingCount());

		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));

		context.publish();

		final PublishEvent expected = new PublishEvent();
		expected.hashed = true;
		expected.publishedAt = clock.millis();
		expected.units = publishUnits;

		expected.exposures = new Exposure[]{
				new Exposure(1, "exp_test_ab", "session_id", 2, clock.millis(), true, true, false, false, true, false),
		};

		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));

		context.publish();

		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(any(), any());
		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(context, expected);
	}

	@Test
	void getVariableValueReturnsDefaultValueWhenUnassigned() {
		final Context context = createReadyContext();

		assertEquals(17, context.getVariableValue("card.width", 17));
	}

	@Test
	void getVariableValueReturnsDefaultValueOnUnknownVariable() {
		final Context context = createReadyContext();

		assertEquals("default", context.getVariableValue("unknown_variable", "default"));
		assertEquals(0, context.getPendingCount());
	}

	@Test
	void getVariableValueReturnsVariableValuesWhenOverridden() {
		final Context context = createReadyContext();

		context.setOverride("exp_test_ab", 0);

		assertEquals(17, context.getVariableValue("banner.border", 17));
	}

	@Test
	void getVariableValueQueuesExposureAfterPeekVariableValue() {
		final Context context = createReadyContext();

		context.peekVariableValue("banner.border", 17);
		context.peekVariableValue("banner.size", 17);

		assertEquals(0, context.getPendingCount());

		context.getVariableValue("banner.border", 17);
		context.getVariableValue("banner.size", 17);

		assertEquals(1, context.getPendingCount());
	}

	@Test
	void getVariableValueQueuesExposureOnce() {
		final Context context = createReadyContext();

		context.getVariableValue("banner.border", 17);
		context.getVariableValue("banner.size", 17);

		assertEquals(1, context.getPendingCount());

		context.getVariableValue("banner.border", 17);
		context.getVariableValue("banner.size", 17);

		assertEquals(1, context.getPendingCount());
	}

	@Test
	void peekVariableValueReturnsDefaultValueWhenUnassigned() {
		final Context context = createReadyContext();

		assertEquals(17, context.peekVariableValue("card.width", 17));
	}

	@Test
	void peekVariableValueReturnsVariableValuesWhenOverridden() {
		final Context context = createReadyContext();

		context.setOverride("exp_test_ab", 0);

		assertEquals(17, context.peekVariableValue("banner.border", 17));
	}

	@Test
	void peekVariableValueReturnsDefaultValueOnUnknownOverrideVariant() {
		final Context context = createReadyContext();

		context.setOverride("exp_test_ab", 15);

		assertEquals(17, context.peekVariableValue("banner.border", 17));
	}

	@Test
	void refreshKeepsOverrides() {
		final Context context = createReadyContext();

		context.setOverride("exp_test_ab", 5);
		assertEquals(5, context.getOverride("exp_test_ab"));

		when(dataProvider.getContextData()).thenReturn(refreshDataFutureReady);

		context.refresh();

		assertEquals(5, context.getOverride("exp_test_ab"));
		assertEquals(5, context.getTreatment("exp_test_ab"));
	}

	@Test
	void refreshKeepsCustomAssignments() {
		final Context context = createReadyContext();

		context.setCustomAssignment("exp_test_ab", 2);
		assertEquals(2, context.getCustomAssignment("exp_test_ab"));

		when(dataProvider.getContextData()).thenReturn(refreshDataFutureReady);

		context.refresh();

		assertEquals(2, context.getCustomAssignment("exp_test_ab"));
		assertEquals(2, context.getTreatment("exp_test_ab"));
	}

	@Test
	void refreshDoesNotCallPublishWhenFailed() {
		final Context context = createContext(dataFutureFailed);
		assertTrue(context.isReady());
		assertTrue(context.isFailed());

		when(dataProvider.getContextData()).thenReturn(refreshDataFutureReady);

		context.refresh();

		verify(dataProvider, Mockito.timeout(5000).times(1)).getContextData();
	}

	@Test
	void publishIncludesExposureData() {
		final Context context = createReadyContext();

		context.getTreatment("exp_test_ab");

		assertEquals(1, context.getPendingCount());

		final PublishEvent expected = new PublishEvent();
		expected.hashed = true;
		expected.publishedAt = clock.millis();
		expected.units = publishUnits;
		expected.exposures = new Exposure[]{
				new Exposure(1, "exp_test_ab", "session_id", 1, clock.millis(), true, true, false, false, false, false),
		};

		when(eventHandler.publish(context, expected)).thenReturn(CompletableFuture.completedFuture(null));

		context.publish();

		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(any(), any());
		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(context, expected);
	}

	@Test
	void publishIncludesGoalData() {
		final Context context = createReadyContext();

		context.track("goal1", mapOf("amount", 125, "hours", 245));

		assertEquals(1, context.getPendingCount());

		final PublishEvent expected = new PublishEvent();
		expected.hashed = true;
		expected.publishedAt = clock.millis();
		expected.units = publishUnits;
		expected.goals = new GoalAchievement[]{
				new GoalAchievement("goal1", clock.millis(), new TreeMap<>(mapOf("amount", 125, "hours", 245))),
		};

		when(eventHandler.publish(context, expected)).thenReturn(CompletableFuture.completedFuture(null));

		context.publish();

		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(any(), any());
		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(context, expected);
	}

	@Test
	void publishIncludesAttributeData() {
		final ContextConfig config = ContextConfig.create()
				.setUnits(units)
				.setAttributes(mapOf("attr1", "value1"));

		final Context context = createContext(config, dataFutureReady);

		context.track("goal1", null);

		final PublishEvent expected = new PublishEvent();
		expected.hashed = true;
		expected.publishedAt = clock.millis();
		expected.units = publishUnits;
		expected.goals = new GoalAchievement[]{
				new GoalAchievement("goal1", clock.millis(), null),
		};
		expected.attributes = new Attribute[]{
				new Attribute("attr1", "value1", clock.millis()),
		};

		when(eventHandler.publish(context, expected)).thenReturn(CompletableFuture.completedFuture(null));

		context.publish();

		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(any(), any());
		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(context, expected);
	}

	@Test
	void publishClearsQueueOnSuccess() {
		final Context context = createReadyContext();

		context.track("goal1", mapOf("amount", 125));
		assertEquals(1, context.getPendingCount());

		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));

		context.publish();

		assertEquals(0, context.getPendingCount());
	}

	@Test
	void publishPropagatesClientErrorOnFailure() {
		final Context context = createReadyContext();

		context.track("goal1", mapOf("amount", 125));
		assertEquals(1, context.getPendingCount());

		final Exception failure = new Exception("publish error");
		when(eventHandler.publish(any(), any())).thenReturn(failedFuture(failure));

		final CompletionException actual = assertThrows(CompletionException.class, context::publish);
		assertSame(failure, actual.getCause());
	}

	@Test
	void closeDoesNotCallEventHandlerWhenQueueIsEmpty() {
		final Context context = createReadyContext();
		assertEquals(0, context.getPendingCount());

		context.close();

		assertTrue(context.isClosed());
		verify(eventHandler, Mockito.timeout(5000).times(0)).publish(any(), any());
	}

	@Test
	void closeCallsEventHandlerWithPendingData() {
		final Context context = createReadyContext();

		context.track("goal1", mapOf("amount", 125));

		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));

		context.close();

		assertTrue(context.isClosed());
		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(any(), any());
	}

	@Test
	void closeDoesNotCallEventHandlerWhenFailed() {
		final Context context = createContext(dataFutureFailed);
		assertTrue(context.isReady());
		assertTrue(context.isFailed());

		context.getTreatment("exp_test_abc");
		context.track("goal1", mapOf("amount", 125));

		context.close();

		assertTrue(context.isClosed());
		verify(eventHandler, Mockito.timeout(5000).times(0)).publish(any(), any());
	}

	@Test
	void closeAsyncReturnsSameFutureWhenCalledTwice() {
		final Context context = createReadyContext();

		context.track("goal1", mapOf("amount", 125));

		final CompletableFuture<Void> publishFuture = new CompletableFuture<>();
		when(eventHandler.publish(any(), any())).thenReturn(publishFuture);

		final CompletableFuture<Void> closeFuture1 = context.closeAsync();
		final CompletableFuture<Void> closeFuture2 = context.closeAsync();

		assertSame(closeFuture1, closeFuture2);

		publishFuture.complete(null);
		closeFuture1.join();

		assertTrue(context.isClosed());
	}

	@Test
	void closeAsyncReturnsCompletedFutureWhenAlreadyClosed() {
		final Context context = createReadyContext();

		context.close();
		assertTrue(context.isClosed());

		final CompletableFuture<Void> closeFuture = context.closeAsync();
		assertTrue(closeFuture.isDone());
	}

	@Test
	void trackQueuesGoalWithProperties() {
		final Context context = createReadyContext();

		final Map<String, Object> properties = mapOf("amount", 125, "hours", 245);
		context.track("goal1", properties);

		assertEquals(1, context.getPendingCount());
	}

	@Test
	void trackQueuesGoalWithNullProperties() {
		final Context context = createReadyContext();

		context.track("goal1", null);

		assertEquals(1, context.getPendingCount());
	}

	@Test
	void getVariableValueConflictingKeyOverlappingAudiences() {
		for (final Experiment experiment : data.experiments) {
			switch (experiment.name) {
			case "exp_test_ab":
				assert (expectedVariants.get(experiment.name) != 0);
				experiment.audienceStrict = true;
				experiment.audience = "{\"filter\":[{\"gte\":[{\"var\":\"age\"},{\"value\":20}]}]}";
				experiment.variants[expectedVariants.get(experiment.name)].config = "{\"icon\":\"arrow\"}";
				break;
			case "exp_test_abc":
				assert (expectedVariants.get(experiment.name) != 0);
				experiment.audienceStrict = true;
				experiment.audience = "{\"filter\":[{\"gte\":[{\"var\":\"age\"},{\"value\":20}]}]}";
				experiment.variants[expectedVariants.get(experiment.name)].config = "{\"icon\":\"circle\"}";
				break;
			default:
				break;
			}
		}

		final Context context = createReadyContext(data);
		context.setAttribute("age", 25);
		assertEquals("arrow", context.getVariableValue("icon", "square"));
		assertEquals(1, context.getPendingCount());
	}

	@Test
	void publishKeepsEventsPendingOnFailure() {
		final Context context = createReadyContext();

		context.track("goal1", mapOf("amount", 125));
		assertEquals(1, context.getPendingCount());

		final Exception failure = new Exception("PUBLISH_FAILED");
		when(eventHandler.publish(any(), any())).thenReturn(failedFuture(failure));

		assertThrows(CompletionException.class, context::publish);

		assertEquals(1, context.getPendingCount());
	}

	@Test
	void setOverrideSucceedsAfterClose() {
		final Context context = createReadyContext();

		context.close();
		assertTrue(context.isClosed());

		context.setOverride("exp_test_ab", 2);
		assertEquals(2, context.getOverride("exp_test_ab"));
	}

	@Test
	void setOverridesSucceedsAfterClose() {
		final Context context = createReadyContext();

		context.close();
		assertTrue(context.isClosed());

		context.setOverrides(mapOf("exp_test_ab", 2, "exp_test_abc", 1));
		assertEquals(2, context.getOverride("exp_test_ab"));
		assertEquals(1, context.getOverride("exp_test_abc"));
	}

	@Test
	void peekVariableValueConflictingKeyOverlappingAudiences() {
		for (final Experiment experiment : data.experiments) {
			switch (experiment.name) {
			case "exp_test_ab":
				assert (expectedVariants.get(experiment.name) != 0);
				experiment.audienceStrict = true;
				experiment.audience = "{\"filter\":[{\"gte\":[{\"var\":\"age\"},{\"value\":20}]}]}";
				experiment.variants[expectedVariants.get(experiment.name)].config = "{\"icon\":\"arrow\"}";
				break;
			case "exp_test_abc":
				assert (expectedVariants.get(experiment.name) != 0);
				experiment.audienceStrict = true;
				experiment.audience = "{\"filter\":[{\"gte\":[{\"var\":\"age\"},{\"value\":20}]}]}";
				experiment.variants[expectedVariants.get(experiment.name)].config = "{\"icon\":\"circle\"}";
				break;
			default:
				break;
			}
		}

		final Context context = createReadyContext(data);
		context.setAttribute("age", 25);
		assertEquals("arrow", context.peekVariableValue("icon", "square"));
		assertEquals(0, context.getPendingCount());
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	void concurrentProducerDuringFailedPublishRestoresBothEvents() {
		final Context context = createReadyContext();

		context.track("goal_a", mapOf("amount", 1));
		assertEquals(1, context.getPendingCount());

		final CompletableFuture<Void> publishFuture1 = new CompletableFuture<>();
		when(eventHandler.publish(any(), any())).thenReturn(publishFuture1);

		final CompletableFuture<Void> asyncResult1 = context.publishAsync();
		assertEquals(0, context.getPendingCount());

		context.track("goal_b", mapOf("amount", 2));
		assertEquals(1, context.getPendingCount());

		final Exception failure = new Exception("publish failed");
		publishFuture1.completeExceptionally(failure);

		assertThrows(CompletionException.class, asyncResult1::join);
		assertEquals(2, context.getPendingCount());

		final CompletableFuture<Void> publishFuture2 = new CompletableFuture<>();
		when(eventHandler.publish(any(), any())).thenReturn(publishFuture2);

		final CompletableFuture<Void> asyncResult2 = context.publishAsync();
		assertEquals(0, context.getPendingCount());

		publishFuture2.complete(null);
		asyncResult2.join();

		verify(eventHandler, Mockito.times(2)).publish(any(), any());
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	void closeAsyncRetryAfterFailedPublishSucceeds() {
		final Context context = createReadyContext();

		context.track("goal_a", mapOf("amount", 1));
		assertEquals(1, context.getPendingCount());

		final CompletableFuture<Void> publishFuture1 = new CompletableFuture<>();
		when(eventHandler.publish(any(), any())).thenReturn(publishFuture1);

		final CompletableFuture<Void> closeFuture1 = context.closeAsync();
		assertFalse(context.isClosed());

		final Exception failure = new Exception("publish failed");
		publishFuture1.completeExceptionally(failure);

		final CompletionException actual = assertThrows(CompletionException.class, closeFuture1::join);
		assertSame(failure, actual.getCause());

		assertFalse(context.isClosed());
		assertTrue(context.getPendingCount() > 0);

		final CompletableFuture<Void> publishFuture2 = new CompletableFuture<>();
		when(eventHandler.publish(any(), any())).thenReturn(publishFuture2);

		final CompletableFuture<Void> closeFuture2 = context.closeAsync();
		assertFalse(closeFuture2 == closeFuture1, "second closeAsync must return a new future");

		publishFuture2.complete(null);
		closeFuture2.join();

		assertTrue(context.isClosed());
		assertEquals(0, context.getPendingCount());

		verify(eventHandler, Mockito.times(2)).publish(any(), any());
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	void closeAsyncAwaitsPublishStartedBeforeCloseAndRestoresEventsOnFailure() {
		final Context context = createReadyContext();

		context.track("goal_a", mapOf("amount", 1));
		assertEquals(1, context.getPendingCount());

		final CompletableFuture<Void> publishFuture1 = new CompletableFuture<>();
		when(eventHandler.publish(any(), any())).thenReturn(publishFuture1);

		// publishAsync() flushes the queue immediately, so pendingCount_ is already 0
		// by the time closeAsync() runs below, with the publisher future still open.
		final CompletableFuture<Void> publishResult = context.publishAsync();
		assertEquals(0, context.getPendingCount());

		final CompletableFuture<Void> closeFuture = context.closeAsync();
		assertFalse(closeFuture.isDone());
		assertFalse(context.isClosed());

		final Exception failure = new Exception("publish failed");
		publishFuture1.completeExceptionally(failure);

		assertThrows(CompletionException.class, publishResult::join);
		assertThrows(CompletionException.class, closeFuture::join);

		assertFalse(context.isClosed());
		assertTrue(context.getPendingCount() > 0);

		final CompletableFuture<Void> publishFuture2 = new CompletableFuture<>();
		when(eventHandler.publish(any(), any())).thenReturn(publishFuture2);

		final CompletableFuture<Void> retryPublish = assertDoesNotThrow(context::publishAsync);
		assertEquals(0, context.getPendingCount());

		publishFuture2.complete(null);
		retryPublish.join();

		final CompletableFuture<Void> closeFuture2 = context.closeAsync();
		closeFuture2.join();

		assertTrue(context.isClosed());
		assertEquals(0, context.getPendingCount());

		verify(eventHandler, Mockito.times(2)).publish(any(), any());
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	void closeAsyncCompletesWhenAwaitedPublishSucceeds() {
		final Context context = createReadyContext();

		context.track("goal_a", mapOf("amount", 1));
		assertEquals(1, context.getPendingCount());

		final CompletableFuture<Void> publishFuture = new CompletableFuture<>();
		when(eventHandler.publish(any(), any())).thenReturn(publishFuture);

		final CompletableFuture<Void> publishResult = context.publishAsync();
		assertEquals(0, context.getPendingCount());

		final CompletableFuture<Void> closeFuture = context.closeAsync();
		assertFalse(closeFuture.isDone());
		assertFalse(context.isClosed());

		publishFuture.complete(null);
		publishResult.join();
		closeFuture.join();

		assertTrue(context.isClosed());
		assertEquals(0, context.getPendingCount());

		verify(eventHandler, Mockito.times(1)).publish(any(), any());
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	void publishSucceedsWhenLoggerThrowsOnPublishEvent() {
		final Context context = createReadyContext();

		context.track("goal1", mapOf("amount", 125));
		assertEquals(1, context.getPendingCount());

		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
		Mockito.doThrow(new RuntimeException("logger failure"))
				.when(eventLogger).handleEvent(any(), eq(ContextEventLogger.EventType.Publish), any());

		assertDoesNotThrow(context::publish);
		assertEquals(0, context.getPendingCount());

		// no new events were queued, so a retry must not re-deliver the same batch
		context.publishAsync().join();
		assertEquals(0, context.getPendingCount());

		verify(eventHandler, Mockito.times(1)).publish(any(), any());
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	void closeNeverFinalizesWithEventsRestoredByConcurrentPublishFailure() throws Exception {
		for (int i = 0; i < 100; i++) {
			final Context context = createReadyContext();
			context.track("goal", mapOf("attempt", i));

			final CompletableFuture<Void> publisherFuture = new CompletableFuture<>();
			when(eventHandler.publish(any(), any())).thenReturn(publisherFuture);
			final CompletableFuture<Void> publishResult = context.publishAsync();

			final java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
			final AtomicReference<CompletableFuture<Void>> closeResult = new AtomicReference<>();
			final Thread closeThread = new Thread(() -> {
				try {
					start.await();
					closeResult.set(context.closeAsync());
				} catch (final InterruptedException exception) {
					Thread.currentThread().interrupt();
				}
			});
			final Thread failureThread = new Thread(() -> {
				try {
					start.await();
					publisherFuture.completeExceptionally(new Exception("publish failed"));
				} catch (final InterruptedException exception) {
					Thread.currentThread().interrupt();
				}
			});

			closeThread.start();
			failureThread.start();
			start.countDown();
			closeThread.join();
			failureThread.join();

			assertThrows(CompletionException.class, publishResult::join);
			final CompletableFuture<Void> closeFuture = closeResult.get();
			assertNotNull(closeFuture);
			assertThrows(CompletionException.class, closeFuture::join);
			assertFalse(context.isClosed() && context.getPendingCount() > 0);
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	void closeAwaitsEveryOverlappingPublishBeforeFinalizing() {
		final Context context = createReadyContext();
		final CompletableFuture<Void> publisherFutureA = new CompletableFuture<>();
		final CompletableFuture<Void> publisherFutureB = new CompletableFuture<>();
		when(eventHandler.publish(any(), any())).thenReturn(publisherFutureA, publisherFutureB);

		context.track("goal_a", mapOf("amount", 1));
		final CompletableFuture<Void> publishResultA = context.publishAsync();
		context.track("goal_b", mapOf("amount", 2));
		final CompletableFuture<Void> publishResultB = context.publishAsync();

		publisherFutureB.complete(null);
		publishResultB.join();

		final CompletableFuture<Void> closeFuture = context.closeAsync();
		assertFalse(closeFuture.isDone());
		assertFalse(context.isClosed());

		publisherFutureA.completeExceptionally(new Exception("publish A failed"));
		assertThrows(CompletionException.class, publishResultA::join);
		assertThrows(CompletionException.class, closeFuture::join);
		assertFalse(context.isClosed());
		assertTrue(context.getPendingCount() > 0);

		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
		assertDoesNotThrow(context::publishAsync).join();
		assertEquals(0, context.getPendingCount());
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	void synchronousPublisherFailureRestoresEventsAndDoesNotHangClose() {
		final Context context = createReadyContext();
		context.track("goal", mapOf("amount", 1));

		final RuntimeException failure = new RuntimeException("publisher threw");
		when(eventHandler.publish(any(), any())).thenThrow(failure);

		final CompletableFuture<Void> publishResult = assertDoesNotThrow(context::publishAsync);
		final CompletionException actual = assertThrows(CompletionException.class, publishResult::join);
		assertSame(failure, actual.getCause());
		assertTrue(context.getPendingCount() > 0);

		final CompletableFuture<Void> closeResult = assertDoesNotThrow(context::closeAsync);
		assertTrue(closeResult.isDone());
		assertThrows(CompletionException.class, closeResult::join);
		assertFalse(context.isClosed());
		assertTrue(context.getPendingCount() > 0);
	}
}
