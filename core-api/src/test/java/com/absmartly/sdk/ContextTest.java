package com.absmartly.sdk;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import com.absmartly.sdk.internal.hashing.Hashing;
import com.absmartly.sdk.internal.Clock;
import com.absmartly.sdk.internal.json.AttributePayload;
import com.absmartly.sdk.internal.json.ContextPayload;
import com.absmartly.sdk.internal.json.Experiment;
import com.absmartly.sdk.internal.json.ExposurePayload;
import com.absmartly.sdk.internal.json.GoalAchievement;
import com.absmartly.sdk.internal.json.PublishPayload;
import com.absmartly.sdk.internal.json.Unit;

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

	ContextPayload data;
	ContextPayload refreshData;

	ContextPayload audienceData;

	ContextPayload audienceStrictData;

	CompletableFuture<ContextPayload> dataFutureReady;
	CompletableFuture<ContextPayload> dataFutureFailed;
	CompletableFuture<ContextPayload> dataFuture;

	CompletableFuture<ContextPayload> refreshDataFutureReady;
	CompletableFuture<ContextPayload> refreshDataFuture;

	CompletableFuture<ContextPayload> audienceDataFutureReady;
	CompletableFuture<ContextPayload> audienceStrictDataFutureReady;

	ContextDataProvider dataProvider;
	ContextEventLogger eventLogger;
	ContextEventHandler eventHandler;
	VariableParser variableParser;
	AudienceMatcher audienceMatcher;
	ScheduledExecutorService scheduler;
	TestJson.Deserializer deser = new TestJson.Deserializer();
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

	Context createContext(TestContextConfig config, CompletableFuture<ContextPayload> dataFuture) {
		return TestContexts.create(clock, config, scheduler, dataFuture, dataProvider, eventHandler,
				eventLogger, variableParser, audienceMatcher);
	}

	Context createContext(CompletableFuture<ContextPayload> dataFuture) {
		final TestContextConfig config = TestContextConfig.create()
				.setUnits(units);

		return TestContexts.create(clock, config, scheduler, dataFuture, dataProvider, eventHandler,
				eventLogger, variableParser, audienceMatcher);
	}

	Context createReadyContext() {
		final TestContextConfig config = TestContextConfig.create()
				.setUnits(units);

		return TestContexts.create(clock, config, scheduler, dataFutureReady, dataProvider, eventHandler,
				eventLogger, variableParser, audienceMatcher);
	}

	Context createReadyContext(ContextPayload data) {
		final TestContextConfig config = TestContextConfig.create()
				.setUnits(units);

		return TestContexts.create(clock, config, scheduler, CompletableFuture.completedFuture(data), dataProvider,
				eventHandler,
				eventLogger, variableParser, audienceMatcher);
	}

	@Test
	void constructorSetsOverrides() {
		final Map<String, Integer> overrides = mapOf(
				"exp_test", 2,
				"exp_test_1", 1);

		final TestContextConfig config = TestContextConfig.create()
				.setUnits(units)
				.setOverrides(overrides);

		final Context context = createContext(config, dataFutureReady);
		overrides.forEach((experimentName, variant) -> assertEquals(variant, context.override(experimentName)));
	}

	@Test
	void constructorSetsCustomAssignments() {
		final Map<String, Integer> cassignments = mapOf(
				"exp_test", 2,
				"exp_test_1", 1);

		final TestContextConfig config = TestContextConfig.create()
				.setUnits(units)
				.setCustomAssignments(cassignments);

		final Context context = createContext(config, dataFutureReady);
		cassignments.forEach(
				(experimentName, variant) -> assertEquals(variant, context.customAssignment(experimentName)));
	}

	@Test
	void becomesReadyWithCompletedFuture() {
		final Context context = createReadyContext();
		assertTrue(context.isReady());
		assertSame(data, context.data().payload());
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

		context.awaitLoad();

		assertTrue(context.isReady());
		assertTrue(context.isFailed());
	}

	@Test
	void readsReturnDefaultsWhenLoadFailed() {
		final Context context = createContext(dataFutureFailed);
		assertTrue(context.isReady());
		assertTrue(context.isFailed());

		assertEquals(0, context.treatment("exp_test_ab"));
		assertEquals(0, context.peekTreatment("exp_test_ab"));
		assertEquals(17, context.variableValue("banner.border", 17));
		assertEquals(17, context.peekVariableValue("banner.border", 17));
		assertTrue(context.variableKeys().isEmpty());
		assertEquals(java.util.Arrays.asList(new String[0]), context.experiments());
		assertEquals(java.util.Arrays.asList(new String[0]), context.customFieldKeys());
		assertNull(context.customFieldValue("exp_test_ab", "key"));
		assertNull(context.customFieldType("exp_test_ab", "key"));
	}

	@Test
	void readyErrorReturnsNullOnSuccess() {
		final Context context = createReadyContext();
		assertNull(context.failure());
	}

	@Test
	void readyErrorReturnsExceptionOnFailedFuture() {
		final Context context = createContext(dataFutureFailed);
		assertTrue(context.isFailed());
		assertNotNull(context.failure());
	}

	@Test
	void readyErrorReturnsExceptionOnAsyncFailure() {
		final Context context = createContext(dataFuture);
		final Exception error = new Exception("FAILED");
		dataFuture.completeExceptionally(error);
		context.awaitLoad();
		assertTrue(context.isFailed());
		assertNotNull(context.failure());
	}

	@Test
	void callsEventLoggerWhenReady() {
		final Context context = createContext(dataFuture);

		dataFuture.complete(data);

		context.awaitLoad();
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

		context.awaitLoad();
		verify(eventLogger, Mockito.timeout(5000).times(1)).handleEvent(context, ContextEventLogger.EventType.Error,
				error);
	}

	@Test
	void waitUntilReady() throws InterruptedException {
		final Context context = createContext(dataFuture);
		assertFalse(context.isReady());

		final Thread completer = new Thread(() -> dataFuture.complete(data));
		completer.start();

		context.awaitLoad();
		completer.join();

		assertTrue(context.isReady());
		assertSame(data, context.data().payload());
	}

	@Test
	void waitUntilReadyWithCompletedFuture() {
		final Context context = createReadyContext();
		assertTrue(context.isReady());

		context.awaitLoad();
		assertSame(data, context.data().payload());
	}

	@Test
	void readyAsync() throws ExecutionException, InterruptedException {
		final Context context = createContext(dataFuture);
		assertFalse(context.isReady());

		final CompletableFuture<Context> readyFuture = context.readyAsync();
		assertFalse(context.isReady());

		final Thread completer = new Thread(() -> dataFuture.complete(data));
		completer.start();

		readyFuture.join();
		completer.join();

		assertTrue(context.isReady());
		assertSame(context, readyFuture.get());
		assertSame(data, context.data().payload());
	}

	@Test
	void waitUntilReadyAsyncWithCompletedFuture() throws ExecutionException, InterruptedException {
		final Context context = createReadyContext();
		assertTrue(context.isReady());

		final CompletableFuture<Context> readyFuture = context.readyAsync();
		readyFuture.join();

		assertTrue(context.isReady());
		assertSame(context, readyFuture.get());
		assertSame(data, context.data().payload());
	}

	@Test
	void throwsWhenNotReady() {
		final Context context = createContext(dataFuture);
		assertFalse(context.isReady());
		assertFalse(context.isFailed());

		final String notReadyMessage = "ABsmartly Context is not yet ready";
		assertEquals(notReadyMessage, assertThrows(ABsmartlyException.class, context::data).getMessage());

		assertEquals(notReadyMessage,
				assertThrows(ABsmartlyException.class, () -> context.peekTreatment("exp_test_ab")).getMessage());
		assertEquals(notReadyMessage,
				assertThrows(ABsmartlyException.class, () -> context.treatment("exp_test_ab")).getMessage());
		assertEquals(notReadyMessage, assertThrows(ABsmartlyException.class, context::experiments).getMessage());
		assertEquals(notReadyMessage,
				assertThrows(ABsmartlyException.class, () -> context.variableValue("banner.border", 17))
						.getMessage());
		assertEquals(notReadyMessage,
				assertThrows(ABsmartlyException.class, () -> context.peekVariableValue("banner.border", 17))
						.getMessage());
		assertEquals(notReadyMessage, assertThrows(ABsmartlyException.class, context::variableKeys).getMessage());
		assertEquals(notReadyMessage,
				assertThrows(ABsmartlyException.class, context::customFieldKeys).getMessage());
		assertEquals(notReadyMessage,
				assertThrows(ABsmartlyException.class, () -> context.customFieldValue("exp_test_ab", "key"))
						.getMessage());
		assertEquals(notReadyMessage,
				assertThrows(ABsmartlyException.class, () -> context.customFieldType("exp_test_ab", "key"))
						.getMessage());
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

		final String closingMessage = "ABsmartly Context is closing";
		assertEquals(closingMessage,
				assertThrows(ABsmartlyException.class, () -> context.setAttribute("attr1", "value1")).getMessage());
		assertEquals(closingMessage,
				assertThrows(ABsmartlyException.class,
						() -> context.setAttributes(mapOf("attr1", "value1")))
						.getMessage());
		assertEquals(closingMessage,
				assertThrows(ABsmartlyException.class, () -> context.setUnit("test", "test"))
						.getMessage());
		assertEquals(closingMessage,
				assertThrows(ABsmartlyException.class, () -> context.setCustomAssignment("exp_test_ab", 2))
						.getMessage());
		assertEquals(closingMessage,
				assertThrows(ABsmartlyException.class,
						() -> context.setCustomAssignments(mapOf("exp_test_ab", 2)))
						.getMessage());
		assertEquals(closingMessage,
				assertThrows(ABsmartlyException.class, () -> context.track("goal1", null)).getMessage());
		assertEquals(closingMessage, assertThrows(ABsmartlyException.class, context::publish).getMessage());
		assertEquals(closingMessage, assertThrows(ABsmartlyException.class, context::data).getMessage());

		assertEquals(closingMessage,
				assertThrows(ABsmartlyException.class, () -> context.peekTreatment("exp_test_ab")).getMessage());
		assertEquals(closingMessage,
				assertThrows(ABsmartlyException.class, () -> context.treatment("exp_test_ab")).getMessage());
		assertEquals(closingMessage,
				assertThrows(ABsmartlyException.class, () -> context.variableValue("banner.border", 17))
						.getMessage());
		assertEquals(closingMessage,
				assertThrows(ABsmartlyException.class, () -> context.peekVariableValue("banner.border", 17))
						.getMessage());
		assertEquals(closingMessage, assertThrows(ABsmartlyException.class, context::variableKeys).getMessage());
		// custom field reads have no lifecycle guard after ready, as in 1.6.3
		assertDoesNotThrow(context::customFieldKeys);
		assertDoesNotThrow(() -> context.customFieldValue("exp_test_ab", "key"));
		assertDoesNotThrow(() -> context.customFieldType("exp_test_ab", "key"));
		assertEquals(data.experiments.length, context.experiments().size());
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

		final String closedMessage = "ABsmartly Context is closed";
		assertEquals(closedMessage,
				assertThrows(ABsmartlyException.class, () -> context.setAttribute("attr1", "value1")).getMessage());
		assertEquals(closedMessage,
				assertThrows(ABsmartlyException.class,
						() -> context.setAttributes(mapOf("attr1", "value1")))
						.getMessage());
		assertEquals(closedMessage,
				assertThrows(ABsmartlyException.class, () -> context.setUnit("test", "test"))
						.getMessage());
		assertEquals(closedMessage,
				assertThrows(ABsmartlyException.class, () -> context.setCustomAssignment("exp_test_ab", 2))
						.getMessage());
		assertEquals(closedMessage,
				assertThrows(ABsmartlyException.class,
						() -> context.setCustomAssignments(mapOf("exp_test_ab", 2)))
						.getMessage());
		assertEquals(closedMessage,
				assertThrows(ABsmartlyException.class, () -> context.track("goal1", null)).getMessage());
		assertEquals(closedMessage, assertThrows(ABsmartlyException.class, context::publish).getMessage());
		assertEquals(closedMessage, assertThrows(ABsmartlyException.class, context::data).getMessage());

		assertEquals(closedMessage,
				assertThrows(ABsmartlyException.class, () -> context.peekTreatment("exp_test_ab")).getMessage());
		assertEquals(closedMessage,
				assertThrows(ABsmartlyException.class, () -> context.treatment("exp_test_ab")).getMessage());
		assertEquals(closedMessage,
				assertThrows(ABsmartlyException.class, () -> context.variableValue("banner.border", 17))
						.getMessage());
		assertEquals(closedMessage,
				assertThrows(ABsmartlyException.class, () -> context.peekVariableValue("banner.border", 17))
						.getMessage());
		assertEquals(closedMessage, assertThrows(ABsmartlyException.class, context::variableKeys).getMessage());
		// custom field reads have no lifecycle guard after ready, as in 1.6.3
		assertDoesNotThrow(context::customFieldKeys);
		assertDoesNotThrow(() -> context.customFieldValue("exp_test_ab", "key"));
		assertDoesNotThrow(() -> context.customFieldType("exp_test_ab", "key"));
		assertEquals(data.experiments.length, context.experiments().size());
	}

	@Test
	void isFinalizedAliasesIsClosedAfterClose() {
		final Context context = createReadyContext();
		assertFalse(context.isClosed());
		assertFalse(context.isClosing());

		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));

		context.close();

		assertTrue(context.isClosed());
		assertTrue(context.isClosed());
	}

	@Test
	void finalizeAsyncIsAliasForCloseAsync() {
		final Context context = createReadyContext();
		assertFalse(context.isClosed());

		context.track("goal1", mapOf("amount", 125, "hours", 245));

		final CompletableFuture<Void> publishFuture = new CompletableFuture<>();
		when(eventHandler.publish(any(), any())).thenReturn(publishFuture);

		final CompletableFuture<Void> finalizeFuture = context.closeAsync();
		assertTrue(context.isClosing());
		assertFalse(context.isClosed());

		publishFuture.complete(null);
		finalizeFuture.join();

		assertTrue(context.isClosed());
		assertFalse(context.isClosing());
	}

	@Test
	void getExperiments() {
		final Context context = createReadyContext();
		assertTrue(context.isReady());

		final String[] experiments = Arrays.stream(data.experiments).map(x -> x.name).toArray(String[]::new);
		assertEquals(java.util.Arrays.asList(experiments), context.experiments());
	}

	@Test
	void startsRefreshTimerWhenReady() {
		final TestContextConfig config = TestContextConfig.create()
				.setUnits(units)
				.setRefreshInterval(5_000);

		final Context context = createContext(config, dataFuture);
		assertFalse(context.isReady());
		assertFalse(context.isFailed());

		final AtomicReference<Runnable> runnable = new AtomicReference<>(null);
		when(scheduler.scheduleWithFixedDelay(any(), eq(config.toOptions().refreshIntervalMillis()),
				eq(config.toOptions().refreshIntervalMillis()), eq(TimeUnit.MILLISECONDS)))
				.thenAnswer(invokation -> {
					runnable.set(invokation.getArgument(0));
					return mock(ScheduledFuture.class);
				});

		dataFuture.complete(data);
		context.awaitLoad();

		verify(scheduler, Mockito.timeout(5000).times(1)).scheduleWithFixedDelay(any(), eq(config.toOptions().refreshIntervalMillis()),
				eq(config.toOptions().refreshIntervalMillis()), eq(TimeUnit.MILLISECONDS));

		verify(dataProvider, Mockito.timeout(5000).times(0)).getContextData();
		when(dataProvider.getContextData()).thenReturn(refreshDataFutureReady);

		runnable.get().run();

		verify(dataProvider, Mockito.timeout(5000).times(1)).getContextData();
	}

	@Test
	void doestNotStartRefreshTimerWhenFailed() {
		final TestContextConfig config = TestContextConfig.create()
				.setUnits(units)
				.setRefreshInterval(5_000);

		final Context context = createContext(config, dataFuture);
		assertFalse(context.isReady());
		assertFalse(context.isFailed());

		dataFuture.completeExceptionally(new Exception("test"));

		context.awaitLoad();

		assertTrue(context.isFailed());

		verify(scheduler, Mockito.timeout(5000).times(0)).scheduleWithFixedDelay(any(), anyLong(), anyLong(), any());
	}

	@Test
	void startsPublishTimeoutWhenReadyWithQueueNotEmpty() {
		final TestContextConfig config = TestContextConfig.create()
				.setUnits(units)
				.setPublishDelay(333);

		final Context context = createContext(config, dataFuture);
		assertFalse(context.isReady());
		assertFalse(context.isFailed());

		context.track("goal1", mapOf("amount", 125));

		final AtomicReference<Runnable> runnable = new AtomicReference<>(null);
		when(scheduler.schedule((Runnable) any(), eq(config.toOptions().publishDelayMillis()), eq(TimeUnit.MILLISECONDS)))
				.thenAnswer(invokation -> {
					runnable.set(invokation.getArgument(0));
					return mock(ScheduledFuture.class);
				});

		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));

		dataFuture.complete(data);
		context.awaitLoad();

		verify(scheduler, Mockito.timeout(5000).times(1)).schedule((Runnable) any(), eq(config.toOptions().publishDelayMillis()),
				eq(TimeUnit.MILLISECONDS));
		verify(eventHandler, Mockito.timeout(5000).times(0)).publish(any(), any());

		runnable.get().run();

		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(any(), any());
	}

	@Test
	void setUnits() {
		final Context context = createContext(TestContextConfig.create(), dataFuture);
		context.setUnits(units);

		for (final Map.Entry<String, String> entry : units.entrySet()) {
			assertEquals(entry.getValue(), context.unit(entry.getKey()));
		}
		assertEquals(units, context.units());
	}

	@Test
	void setUnitsBeforeReady() {
		final Context context = createContext(TestContextConfig.create(), dataFuture);
		assertFalse(context.isReady());

		context.setUnits(units);

		dataFuture.complete(data);

		context.awaitLoad();

		context.treatment("exp_test_ab");

		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));

		context.publish();

		final PublishPayload expected = new PublishPayload();
		expected.hashed = true;
		expected.publishedAt = clock.millis();
		expected.units = publishUnits;
		expected.exposures = new ExposurePayload[]{
				new ExposurePayload(1, "exp_test_ab", "session_id", 1, clock.millis(), true, true, false, false, false, false),
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

		final UnitAlreadySetException error = assertThrows(UnitAlreadySetException.class, () -> {
			context.setUnit("session_id", "new_uid");
		});
		assertEquals("Unit 'session_id' UID already set.", error.getMessage());
		assertEquals("session_id", error.unitType());
	}

	@Test
	void setAttributes() {
		final Context context = createContext(dataFuture);

		context.setAttribute("attr1", "value1");
		context.setAttributes(mapOf("attr2", "value2", "attr3", 15));

		assertEquals("value1", context.attribute("attr1"));
		assertEquals(mapOf("attr1", "value1", "attr2", "value2", "attr3", 15), context.attributes());
	}

	@Test
	void setAttributesBeforeReady() {
		final Context context = createContext(dataFuture);
		assertFalse(context.isReady());

		context.setAttribute("attr1", "value1");
		context.setAttributes(mapOf("attr2", "value2"));

		assertEquals("value1", context.attribute("attr1"));
		assertEquals(mapOf("attr1", "value1", "attr2", "value2"), context.attributes());

		dataFuture.complete(data);

		context.awaitLoad();
	}

	@Test
	void setOverride() {
		final Context context = createReadyContext();

		context.setOverride("exp_test", 2);

		assertEquals(2, context.override("exp_test"));

		context.setOverride("exp_test", 3);
		assertEquals(3, context.override("exp_test"));

		context.setOverride("exp_test_2", 1);
		assertEquals(1, context.override("exp_test_2"));

		final Map<String, Integer> overrides = mapOf(
				"exp_test_new", 3,
				"exp_test_new_2", 5);

		context.setOverrides(overrides);

		assertEquals(3, context.override("exp_test"));
		assertEquals(1, context.override("exp_test_2"));
		overrides.forEach((experimentName, variant) -> assertEquals(variant, context.override(experimentName)));

		assertNull(context.override("exp_test_not_found"));
	}

	@Test
	void setOverrideClearsAssignmentCache() {
		final Context context = createReadyContext();

		final Map<String, Integer> overrides = mapOf(
				"exp_test_new", 3,
				"exp_test_new_2", 5);

		context.setOverrides(overrides);

		overrides.forEach((experimentName, variant) -> assertEquals(variant, context.treatment(experimentName)));
		assertEquals(overrides.size(), context.pendingEventCount());

		// overriding again with the same variant shouldn't clear assignment cache
		overrides.forEach((experimentName, variant) -> {
			context.setOverride(experimentName, variant);
			assertEquals(variant, context.treatment(experimentName));
		});
		assertEquals(overrides.size(), context.pendingEventCount());

		// overriding with the different variant should clear assignment cache
		overrides.forEach((experimentName, variant) -> {
			context.setOverride(experimentName, variant + 11);
			assertEquals(variant + 11, context.treatment(experimentName));
		});

		assertEquals(overrides.size() * 2, context.pendingEventCount());

		// overriding a computed assignment should clear assignment cache
		assertEquals(expectedVariants.get("exp_test_ab"), context.treatment("exp_test_ab"));
		assertEquals(1 + overrides.size() * 2, context.pendingEventCount());

		context.setOverride("exp_test_ab", 9);
		assertEquals(9, context.treatment("exp_test_ab"));
		assertEquals(2 + overrides.size() * 2, context.pendingEventCount());
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

		context.awaitLoad();

		assertEquals(2, context.override("exp_test"));
		assertEquals(3, context.override("exp_test_new"));
		assertEquals(5, context.override("exp_test_new_2"));
	}

	@Test
	void setCustomAssignment() {
		final Context context = createReadyContext();
		context.setCustomAssignment("exp_test", 2);

		assertEquals(2, context.customAssignment("exp_test"));

		context.setCustomAssignment("exp_test", 3);
		assertEquals(3, context.customAssignment("exp_test"));

		context.setCustomAssignment("exp_test_2", 1);
		assertEquals(1, context.customAssignment("exp_test_2"));

		final Map<String, Integer> cassignments = mapOf(
				"exp_test_new", 3,
				"exp_test_new_2", 5);

		context.setCustomAssignments(cassignments);

		assertEquals(3, context.customAssignment("exp_test"));
		assertEquals(1, context.customAssignment("exp_test_2"));
		cassignments.forEach(
				(experimentName, variant) -> assertEquals(variant, context.customAssignment(experimentName)));

		assertNull(context.customAssignment("exp_test_not_found"));
	}

	@Test
	void setCustomAssignmentDoesNotOverrideFullOnOrNotEligibleAssignments() {
		final Context context = createReadyContext();

		context.setCustomAssignment("exp_test_not_eligible", 3);
		context.setCustomAssignment("exp_test_fullon", 3);

		assertEquals(0, context.treatment("exp_test_not_eligible"));
		assertEquals(2, context.treatment("exp_test_fullon"));
	}

	@Test
	void setCustomAssignmentClearsAssignmentCache() {
		final Context context = createReadyContext();

		final Map<String, Integer> cassignments = mapOf(
				"exp_test_ab", 2,
				"exp_test_abc", 3);

		cassignments.forEach((experimentName, variant) -> assertEquals(expectedVariants.get(experimentName),
				context.treatment(experimentName)));
		assertEquals(cassignments.size(), context.pendingEventCount());

		context.setCustomAssignments(cassignments);

		cassignments.forEach((experimentName, variant) -> assertEquals(variant, context.treatment(experimentName)));
		assertEquals(2 * cassignments.size(), context.pendingEventCount());

		// overriding again with the same variant shouldn't clear assignment cache
		cassignments.forEach((experimentName, variant) -> {
			context.setCustomAssignment(experimentName, variant);
			assertEquals(variant, context.treatment(experimentName));
		});
		assertEquals(2 * cassignments.size(), context.pendingEventCount());

		// overriding with the different variant should clear assignment cache
		cassignments.forEach((experimentName, variant) -> {
			context.setCustomAssignment(experimentName, variant + 11);
			assertEquals(variant + 11, context.treatment(experimentName));
		});

		assertEquals(cassignments.size() * 3, context.pendingEventCount());
	}

	// Regression test for the java-sdk#12 cache bug: a full-on variant can never equal the custom
	// value on file, so the cache-validity check must not compare them directly (that would
	// invalidate on every call once any custom assignment is set - see
	// setCustomAssignmentDoesNotOverrideFullOnOrNotEligibleAssignments's sibling history) nor
	// ignore the custom assignment outright (that would mask a legitimate change and never
	// re-expose, which is the regression: exposure count must go 1 -> 2 -> 2, not 1 -> 1 -> 1).
	@Test
	void setCustomAssignmentAfterExposureReExposesFullOnAssignment() {
		final Context context = createReadyContext();

		assertEquals(2, context.treatment("exp_test_fullon"));
		assertEquals(1, context.pendingEventCount());

		// the custom value can never win against a full-on variant, but setting one is still a
		// change the cache must react to: it owes exactly one fresh exposure for it
		context.setCustomAssignment("exp_test_fullon", 0);
		assertEquals(2, context.treatment("exp_test_fullon"));
		assertEquals(2, context.pendingEventCount());

		// repeating the same custom assignment must not re-invalidate the cache
		context.setCustomAssignment("exp_test_fullon", 0);
		assertEquals(2, context.treatment("exp_test_fullon"));
		assertEquals(2, context.pendingEventCount());
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

		context.awaitLoad();

		assertEquals(2, context.customAssignment("exp_test"));
		assertEquals(3, context.customAssignment("exp_test_new"));
		assertEquals(5, context.customAssignment("exp_test_new_2"));
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

		assertEquals(0, context.pendingEventCount());
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

		assertEquals(0, context.pendingEventCount());
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
			final Object actual = context.variableValue(variable, 17);
			final boolean eligible = !experimentName.equals("exp_test_not_eligible");

			if (eligible && experiments.contains(experimentName)) {
				assertEquals(expectedVariables.get(variable), actual);
			} else {
				assertEquals(17, actual);
			}
		});

		assertEquals(experiments.size(), context.pendingEventCount());
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
			assertEquals("arrow", context.variableValue("icon", "square"));

			assertEquals(1, context.pendingEventCount());
		}

		{
			final Context context = createReadyContext(data);
			context.setAttribute("age", 19);
			assertEquals("circle", context.variableValue("icon", "square"));

			assertEquals(1, context.pendingEventCount());
		}
	}

	@Test
	void getVariableValueQueuesExposureWithAudienceMismatchFalseOnAudienceMatch() {
		final Context context = createContext(audienceDataFutureReady);
		context.setAttribute("age", 21);

		assertEquals("large", context.variableValue("banner.size", "small"));
		assertEquals(1, context.pendingEventCount());

		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));

		context.publish();

		final PublishPayload expected = new PublishPayload();
		expected.hashed = true;
		expected.publishedAt = clock.millis();
		expected.units = publishUnits;
		expected.attributes = new AttributePayload[]{
				new AttributePayload("age", 21, clock.millis()),
		};

		expected.exposures = new ExposurePayload[]{
				new ExposurePayload(1, "exp_test_ab", "session_id", 1, clock.millis(), true, true, false, false, false, false),
		};

		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));

		context.publish();

		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(any(), any());
		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(context, expected);
	}

	@Test
	void getVariableValueQueuesExposureWithAudienceMismatchTrueOnAudienceMismatch() {
		final Context context = createContext(audienceDataFutureReady);

		assertEquals("large", context.variableValue("banner.size", "small"));
		assertEquals(1, context.pendingEventCount());

		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));

		context.publish();

		final PublishPayload expected = new PublishPayload();
		expected.hashed = true;
		expected.publishedAt = clock.millis();
		expected.units = publishUnits;

		expected.exposures = new ExposurePayload[]{
				new ExposurePayload(1, "exp_test_ab", "session_id", 1, clock.millis(), true, true, false, false, false, true),
		};

		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));

		context.publish();

		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(any(), any());
		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(context, expected);
	}

	@Test
	void getVariableValueDoesNotQueuesExposureWithAudienceMismatchFalseAndControlVariantOnAudienceMismatchInStrictMode() {
		final Context context = createContext(audienceStrictDataFutureReady);

		assertEquals("small", context.variableValue("banner.size", "small"));
		assertEquals(0, context.pendingEventCount());
	}

	@Test
	void getVariableValueCallsEventLogger() {
		final Context context = createReadyContext();

		Mockito.clearInvocations(eventLogger);

		context.variableValue("banner.border", null);
		context.variableValue("banner.size", null);

		final ExposurePayload[] exposures = new ExposurePayload[]{
				new ExposurePayload(1, "exp_test_ab", "session_id", 1, clock.millis(), true, true, false, false, false, false),
		};

		verify(eventLogger, Mockito.timeout(5000).times(exposures.length)).handleEvent(any(), any(), any());

		for (ExposurePayload expected : exposures) {
			verify(eventLogger, Mockito.timeout(5000).times(1)).handleEvent(ArgumentMatchers.same(context),
					ArgumentMatchers.eq(ContextEventLogger.EventType.Exposure), ArgumentMatchers.eq(expected));
		}

		// verify not called again with the same exposure
		Mockito.clearInvocations(eventLogger);
		context.variableValue("banner.border", null);
		context.variableValue("banner.size", null);

		verify(eventLogger, Mockito.timeout(5000).times(0)).handleEvent(any(), any(), any());
	}

	@Test
	void getVariableKeys() {
		final Context context = createContext(refreshDataFutureReady);

		assertEquals(variableExperiments, context.variableKeys());
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

		assertEquals(0, context.pendingEventCount());
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
				context.treatment(experiment.name)));
		assertEquals(0, context.treatment("not_found"));
		assertEquals(1 + data.experiments.length, context.pendingEventCount());

		final PublishPayload expected = new PublishPayload();
		expected.hashed = true;
		expected.publishedAt = clock.millis();
		expected.units = publishUnits;

		expected.exposures = new ExposurePayload[]{
				new ExposurePayload(1, "exp_test_ab", "session_id", 1, clock.millis(), true, true, false, false, false, false),
				new ExposurePayload(2, "exp_test_abc", "session_id", 2, clock.millis(), true, true, false, false, false,
						false),
				new ExposurePayload(3, "exp_test_not_eligible", "user_id", 0, clock.millis(), true, false, false, false,
						false, false),
				new ExposurePayload(4, "exp_test_fullon", "session_id", 2, clock.millis(), true, true, false, true, false,
						false),
				new ExposurePayload(0, "not_found", null, 0, clock.millis(), false, true, false, false, false, false),
		};

		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));

		context.publish();

		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(any(), any());
		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(context, expected);

		context.close();
	}

	@Test
	void getTreatmentStartsPublishTimeoutAfterExposure() {
		final TestContextConfig config = TestContextConfig.create()
				.setUnits(units)
				.setPublishDelay(333);

		final Context context = createContext(config, dataFutureReady);
		assertTrue(context.isReady());
		assertFalse(context.isFailed());

		final AtomicReference<Runnable> runnable = new AtomicReference<>(null);
		when(scheduler.schedule((Runnable) any(), eq(config.toOptions().publishDelayMillis()), eq(TimeUnit.MILLISECONDS)))
				.thenAnswer(invokation -> {
					runnable.set(invokation.getArgument(0));
					return mock(ScheduledFuture.class);
				});

		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));

		context.treatment("exp_test_ab");
		context.treatment("exp_test_abc");

		verify(scheduler, Mockito.timeout(5000).times(1)).schedule((Runnable) any(), eq(config.toOptions().publishDelayMillis()),
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
				context.treatment(experiment.name)));
		assertEquals(3, context.treatment("not_found"));
		assertEquals(1 + data.experiments.length, context.pendingEventCount());

		final PublishPayload expected = new PublishPayload();
		expected.hashed = true;
		expected.publishedAt = clock.millis();
		expected.units = publishUnits;

		expected.exposures = new ExposurePayload[]{
				new ExposurePayload(1, "exp_test_ab", "session_id", 12, clock.millis(), false, true, true, false, false,
						false),
				new ExposurePayload(2, "exp_test_abc", "session_id", 13, clock.millis(), false, true, true, false, false,
						false),
				new ExposurePayload(3, "exp_test_not_eligible", "user_id", 11, clock.millis(), false, true, true, false, false,
						false),
				new ExposurePayload(4, "exp_test_fullon", "session_id", 13, clock.millis(), false, true, true, false, false,
						false),
				new ExposurePayload(0, "not_found", null, 3, clock.millis(), false, true, true, false, false, false),
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

		Arrays.stream(data.experiments).forEach(experiment -> context.treatment(experiment.name));
		context.treatment("not_found");

		assertEquals(1 + data.experiments.length, context.pendingEventCount());

		// call again
		Arrays.stream(data.experiments).forEach(experiment -> context.treatment(experiment.name));
		context.treatment("not_found");

		assertEquals(1 + data.experiments.length, context.pendingEventCount());

		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));

		context.publish();

		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(any(), any());

		assertEquals(0, context.pendingEventCount());

		Arrays.stream(data.experiments).forEach(experiment -> context.treatment(experiment.name));
		context.treatment("not_found");
		assertEquals(0, context.pendingEventCount());

		context.close();
	}

	@Test
	void getTreatmentQueuesExposureWithAudienceMismatchFalseOnAudienceMatch() {
		final Context context = createContext(audienceDataFutureReady);
		context.setAttribute("age", 21);

		assertEquals(1, context.treatment("exp_test_ab"));
		assertEquals(1, context.pendingEventCount());

		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));

		context.publish();

		final PublishPayload expected = new PublishPayload();
		expected.hashed = true;
		expected.publishedAt = clock.millis();
		expected.units = publishUnits;
		expected.attributes = new AttributePayload[]{
				new AttributePayload("age", 21, clock.millis()),
		};

		expected.exposures = new ExposurePayload[]{
				new ExposurePayload(1, "exp_test_ab", "session_id", 1, clock.millis(), true, true, false, false, false, false),
		};

		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));

		context.publish();

		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(any(), any());
		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(context, expected);
	}

	@Test
	void getTreatmentQueuesExposureWithAudienceMismatchTrueOnAudienceMismatch() {
		final Context context = createContext(audienceDataFutureReady);

		assertEquals(1, context.treatment("exp_test_ab"));
		assertEquals(1, context.pendingEventCount());

		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));

		context.publish();

		final PublishPayload expected = new PublishPayload();
		expected.hashed = true;
		expected.publishedAt = clock.millis();
		expected.units = publishUnits;

		expected.exposures = new ExposurePayload[]{
				new ExposurePayload(1, "exp_test_ab", "session_id", 1, clock.millis(), true, true, false, false, false, true),
		};

		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));

		context.publish();

		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(any(), any());
		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(context, expected);
	}

	@Test
	void getTreatmentQueuesExposureWithAudienceMismatchTrueAndControlVariantOnAudienceMismatchInStrictMode() {
		final Context context = createContext(audienceStrictDataFutureReady);

		assertEquals(0, context.treatment("exp_test_ab"));
		assertEquals(1, context.pendingEventCount());

		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));

		context.publish();

		final PublishPayload expected = new PublishPayload();
		expected.hashed = true;
		expected.publishedAt = clock.millis();
		expected.units = publishUnits;

		expected.exposures = new ExposurePayload[]{
				new ExposurePayload(1, "exp_test_ab", "session_id", 0, clock.millis(), false, true, false, false, false,
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

		context.treatment("exp_test_ab");
		context.treatment("not_found");

		final ExposurePayload[] exposures = new ExposurePayload[]{
				new ExposurePayload(1, "exp_test_ab", "session_id", 1, clock.millis(), true, true, false, false, false, false),
				new ExposurePayload(0, "not_found", null, 0, clock.millis(), false, true, false, false, false, false),
		};

		verify(eventLogger, Mockito.timeout(5000).times(exposures.length)).handleEvent(any(), any(), any());

		for (ExposurePayload expected : exposures) {
			verify(eventLogger, Mockito.timeout(5000).times(1)).handleEvent(ArgumentMatchers.same(context),
					ArgumentMatchers.eq(ContextEventLogger.EventType.Exposure), ArgumentMatchers.eq(expected));
		}

		// verify not called again with the same exposure
		Mockito.clearInvocations(eventLogger);
		context.treatment("exp_test_ab");
		context.treatment("not_found");

		verify(eventLogger, Mockito.timeout(5000).times(0)).handleEvent(any(), any(), any());
	}

	@Test
	void track() {
		final Context context = createReadyContext();
		context.track("goal1", mapOf("amount", 125, "hours", 245));
		context.track("goal2", mapOf("tries", 7));

		assertEquals(2, context.pendingEventCount());

		context.track("goal2", mapOf("tests", 12));
		context.track("goal3", null);

		assertEquals(4, context.pendingEventCount());

		final PublishPayload expected = new PublishPayload();
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
		final TestContextConfig config = TestContextConfig.create()
				.setUnits(units)
				.setPublishDelay(333);

		final Context context = createContext(config, dataFutureReady);
		assertTrue(context.isReady());
		assertFalse(context.isFailed());

		final AtomicReference<Runnable> runnable = new AtomicReference<>(null);
		when(scheduler.schedule((Runnable) any(), eq(config.toOptions().publishDelayMillis()), eq(TimeUnit.MILLISECONDS)))
				.thenAnswer(invokation -> {
					runnable.set(invokation.getArgument(0));
					return mock(ScheduledFuture.class);
				});

		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));

		context.track("goal1", mapOf("amount", 125));
		context.track("goal2", mapOf("value", 999.0));

		verify(scheduler, Mockito.timeout(5000).times(1)).schedule((Runnable) any(), eq(config.toOptions().publishDelayMillis()),
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

		assertEquals(3, context.pendingEventCount());
	}

	@Test
	void publishDoesNotCallEventHandlerWhenQueueIsEmpty() {
		final Context context = createReadyContext();
		assertEquals(0, context.pendingEventCount());

		context.publish();

		verify(eventHandler, Mockito.timeout(5000).times(0)).publish(any(), any());
	}

	@Test
	void publishCallsEventLogger() {
		final Context context = createReadyContext();

		context.track("goal1", mapOf("amount", 125, "hours", 245));

		Mockito.clearInvocations(eventLogger);

		final PublishPayload expected = new PublishPayload();
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

		final ABsmartlyException actual = assertThrows(ABsmartlyException.class, context::publish);
		assertSame(failure, actual.getCause());

		verify(eventLogger, Mockito.timeout(5000).times(1)).handleEvent(any(), any(), any());
		verify(eventLogger, Mockito.timeout(5000).times(1)).handleEvent(context, ContextEventLogger.EventType.Error,
				failure);
	}

	@Test
	void publishResetsInternalQueuesAndKeepsAttributesOverridesAndCustomAssignments() {
		final TestContextConfig config = TestContextConfig.create()
				.setUnits(units)
				.setAttributes(mapOf(
						"attr1", "value1",
						"attr2", "value2"))
				.setCustomAssignment("exp_test_abc", 3)
				.setOverride("not_found", 3);

		final Context context = createContext(config, dataFutureReady);

		assertEquals(0, context.pendingEventCount());

		assertEquals(1, context.treatment("exp_test_ab"));
		assertEquals(3, context.treatment("exp_test_abc"));
		assertEquals(3, context.treatment("not_found"));
		context.track("goal1", mapOf("amount", 125, "hours", 245));

		assertEquals(4, context.pendingEventCount());

		final PublishPayload expected = new PublishPayload();
		expected.hashed = true;
		expected.publishedAt = clock.millis();
		expected.units = publishUnits;

		expected.exposures = new ExposurePayload[]{
				new ExposurePayload(1, "exp_test_ab", "session_id", 1, clock.millis(), true, true, false, false, false, false),
				new ExposurePayload(2, "exp_test_abc", "session_id", 3, clock.millis(), true, true, false, false, true, false),
				new ExposurePayload(0, "not_found", null, 3, clock.millis(), false, true, true, false, false, false),
		};

		expected.goals = new GoalAchievement[]{
				new GoalAchievement("goal1", clock.millis(),
						new TreeMap<>(mapOf("amount", 125, "hours", 245))),
		};

		expected.attributes = new AttributePayload[]{
				new AttributePayload("attr2", "value2", clock.millis()),
				new AttributePayload("attr1", "value1", clock.millis()),
		};

		when(eventHandler.publish(context, expected)).thenReturn(CompletableFuture.completedFuture(null));

		final CompletableFuture<Void> future = context.publishAsync();
		assertEquals(0, context.pendingEventCount());
		assertEquals(3, context.customAssignment("exp_test_abc"));
		assertEquals(3, context.override("not_found"));

		future.join();
		assertEquals(0, context.pendingEventCount());
		assertEquals(3, context.customAssignment("exp_test_abc"));
		assertEquals(3, context.override("not_found"));

		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(any(), any());
		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(context, expected);

		Mockito.clearInvocations(eventHandler);

		// repeat
		assertEquals(1, context.treatment("exp_test_ab"));
		assertEquals(3, context.treatment("exp_test_abc"));
		assertEquals(3, context.treatment("not_found"));
		context.track("goal1", mapOf("amount", 125, "hours", 245));

		assertEquals(1, context.pendingEventCount());

		final PublishPayload expectedNext = new PublishPayload();
		expectedNext.hashed = true;
		expectedNext.publishedAt = clock.millis();
		expectedNext.units = publishUnits;

		expectedNext.goals = new GoalAchievement[]{
				new GoalAchievement("goal1", clock.millis(),
						new TreeMap<>(mapOf("amount", 125, "hours", 245))),
		};

		expectedNext.attributes = new AttributePayload[]{
				new AttributePayload("attr2", "value2", clock.millis()),
				new AttributePayload("attr1", "value1", clock.millis()),
		};

		when(eventHandler.publish(context, expectedNext)).thenReturn(CompletableFuture.completedFuture(null));

		final CompletableFuture<Void> futureNext = context.publishAsync();
		assertEquals(0, context.pendingEventCount());

		futureNext.join();
		assertEquals(0, context.pendingEventCount());

		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(any(), any());
		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(context, expectedNext);
	}

	@Test
	void publishDoesNotCallEventHandlerWhenFailed() {
		final Context context = createContext(dataFutureFailed);
		assertTrue(context.isReady());
		assertTrue(context.isFailed());

		context.treatment("exp_test_abc");
		context.track("goal1", mapOf("amount", 125, "hours", 245));

		assertEquals(2, context.pendingEventCount());

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

		assertEquals(1, context.pendingEventCount());

		final Exception failure = new Exception("FAILED");
		when(eventHandler.publish(any(), any())).thenReturn(failedFuture(failure));

		final ABsmartlyException actual = assertThrows(ABsmartlyException.class, context::publish);
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
		assertNotSame(closingFuture, closingFutureNext);

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

		final PublishPayload expected = new PublishPayload();
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

		final ABsmartlyException actual = assertThrows(ABsmartlyException.class, context::close);
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

		final ABsmartlyException actual = assertThrows(ABsmartlyException.class, context::close);
		assertSame(failure, actual.getCause());

		publisher.join();

		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(any(), any());
	}

	@Test
	void closeStopsRefreshTimer() {
		final TestContextConfig config = TestContextConfig.create()
				.setUnits(units)
				.setRefreshInterval(5_000);

		final ScheduledFuture refreshTimer = mock(ScheduledFuture.class);
		when(scheduler.scheduleWithFixedDelay(any(), eq(config.toOptions().refreshIntervalMillis()),
				eq(config.toOptions().refreshIntervalMillis()), eq(TimeUnit.MILLISECONDS)))
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
		assertEquals(java.util.Arrays.asList(experiments), context.experiments());
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

		final ABsmartlyException actual = assertThrows(ABsmartlyException.class, context::refresh);
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

		assertEquals(1, context.pendingEventCount());

		final Exception failure = new Exception("FAILED");
		when(dataProvider.getContextData()).thenReturn(failedFuture(failure));

		final ABsmartlyException actual = assertThrows(ABsmartlyException.class, context::refresh);
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
		assertEquals(java.util.Arrays.asList(experiments), context.experiments());
	}

	@Test
	void refreshKeepsAssignmentCacheWhenNotChanged() {
		final Context context = createReadyContext();
		assertTrue(context.isReady());

		Arrays.stream(data.experiments).forEach(experiment -> context.treatment(experiment.name));
		context.treatment("not_found");

		assertEquals(data.experiments.length + 1, context.pendingEventCount());

		when(dataProvider.getContextData()).thenReturn(refreshDataFuture);

		final CompletableFuture<Void> refreshFuture = context.refreshAsync();

		verify(dataProvider, Mockito.timeout(5000).times(1)).getContextData();

		refreshDataFuture.complete(refreshData);
		refreshFuture.join();

		Arrays.stream(refreshData.experiments).forEach(experiment -> context.treatment(experiment.name));
		context.treatment("not_found");

		assertEquals(refreshData.experiments.length + 1, context.pendingEventCount());
	}

	@Test
	void refreshKeepsAssignmentCacheWhenNotChangedOnAudienceMismatch() {
		final Context context = createContext(audienceStrictDataFutureReady);

		assertEquals(0, context.treatment("exp_test_ab"));

		assertEquals(1, context.pendingEventCount());

		when(dataProvider.getContextData()).thenReturn(audienceStrictDataFutureReady);

		final CompletableFuture<Void> refreshFuture = context.refreshAsync();

		verify(dataProvider, Mockito.timeout(5000).times(1)).getContextData();

		refreshFuture.join();

		assertEquals(0, context.treatment("exp_test_ab"));

		assertEquals(1, context.pendingEventCount()); // no new exposure
	}

	@Test
	void refreshKeepsAssignmentCacheWhenNotChangedWithOverride() {
		final Context context = createReadyContext();

		context.setOverride("exp_test_ab", 3);
		assertEquals(3, context.treatment("exp_test_ab"));

		assertEquals(1, context.pendingEventCount());

		when(dataProvider.getContextData()).thenReturn(dataFutureReady);

		final CompletableFuture<Void> refreshFuture = context.refreshAsync();

		verify(dataProvider, Mockito.timeout(5000).times(1)).getContextData();

		refreshFuture.join();

		assertEquals(3, context.treatment("exp_test_ab"));

		assertEquals(1, context.pendingEventCount()); // no new exposure
	}

	@Test
	void refreshClearAssignmentCacheForStoppedExperiment() {
		final Context context = createReadyContext();
		assertTrue(context.isReady());

		final String experimentName = "exp_test_abc";
		assertEquals(expectedVariants.get(experimentName), context.treatment(experimentName));
		assertEquals(0, context.treatment("not_found"));

		assertEquals(2, context.pendingEventCount());

		when(dataProvider.getContextData()).thenReturn(refreshDataFuture);

		final CompletableFuture<Void> refreshFuture = context.refreshAsync();

		verify(dataProvider, Mockito.timeout(5000).times(1)).getContextData();

		refreshData.experiments = Arrays.stream(refreshData.experiments).filter(x -> !x.name.equals(experimentName))
				.toArray(Experiment[]::new);

		refreshDataFuture.complete(refreshData);
		refreshFuture.join();

		assertEquals(0, context.treatment(experimentName));
		assertEquals(0, context.treatment("not_found"));

		assertEquals(3, context.pendingEventCount()); // stopped experiment triggered a new exposure
	}

	@Test
	void refreshClearAssignmentCacheForStartedExperiment() {
		final Context context = createReadyContext();
		assertTrue(context.isReady());

		final String experimentName = "exp_test_new";
		assertEquals(0, context.treatment(experimentName));
		assertEquals(0, context.treatment("not_found"));

		assertEquals(2, context.pendingEventCount());

		when(dataProvider.getContextData()).thenReturn(refreshDataFuture);

		final CompletableFuture<Void> refreshFuture = context.refreshAsync();

		verify(dataProvider, Mockito.timeout(5000).times(1)).getContextData();

		refreshDataFuture.complete(refreshData);
		refreshFuture.join();

		assertEquals(expectedVariants.get(experimentName), context.treatment(experimentName));
		assertEquals(0, context.treatment("not_found"));

		assertEquals(3, context.pendingEventCount()); // stopped experiment triggered a new exposure
	}

	@Test
	void refreshClearAssignmentCacheForFullOnExperiment() {
		final Context context = createReadyContext();
		assertTrue(context.isReady());

		final String experimentName = "exp_test_abc";
		assertEquals(expectedVariants.get(experimentName), context.treatment(experimentName));
		assertEquals(0, context.treatment("not_found"));

		assertEquals(2, context.pendingEventCount());

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

		assertEquals(1, context.treatment(experimentName));
		assertEquals(0, context.treatment("not_found"));

		assertEquals(3, context.pendingEventCount()); // full-on experiment triggered a new exposure
	}

	@Test
	void refreshClearAssignmentCacheForTrafficSplitChange() {
		final Context context = createReadyContext();
		assertTrue(context.isReady());

		final String experimentName = "exp_test_not_eligible";
		assertEquals(expectedVariants.get(experimentName), context.treatment(experimentName));
		assertEquals(0, context.treatment("not_found"));

		assertEquals(2, context.pendingEventCount());

		when(dataProvider.getContextData()).thenReturn(refreshDataFuture);

		final CompletableFuture<Void> refreshFuture = context.refreshAsync();

		verify(dataProvider, Mockito.timeout(5000).times(1)).getContextData();

		Arrays.stream(refreshData.experiments).filter(x -> x.name.equals(experimentName))
				.forEach(experiment -> experiment.trafficSplit = new double[]{0.0, 1.0});

		refreshDataFuture.complete(refreshData);
		refreshFuture.join();

		assertEquals(2, context.treatment(experimentName));
		assertEquals(0, context.treatment("not_found"));

		assertEquals(3, context.pendingEventCount()); // newly eligible experiment triggered a new exposure
	}

	@Test
	void refreshClearAssignmentCacheForIterationChange() {
		final Context context = createReadyContext();
		assertTrue(context.isReady());

		final String experimentName = "exp_test_abc";
		assertEquals(expectedVariants.get(experimentName), context.treatment(experimentName));
		assertEquals(0, context.treatment("not_found"));

		assertEquals(2, context.pendingEventCount());

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

		assertEquals(2, context.treatment(experimentName));
		assertEquals(0, context.treatment("not_found"));

		assertEquals(3, context.pendingEventCount()); // full-on experiment triggered a new exposure
	}

	@Test
	void refreshClearAssignmentCacheForExperimentIdChange() {
		final Context context = createReadyContext();
		assertTrue(context.isReady());

		final String experimentName = "exp_test_abc";
		assertEquals(expectedVariants.get(experimentName), context.treatment(experimentName));
		assertEquals(0, context.treatment("not_found"));

		assertEquals(2, context.pendingEventCount());

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

		assertEquals(2, context.treatment(experimentName));
		assertEquals(0, context.treatment("not_found"));

		assertEquals(3, context.pendingEventCount()); // full-on experiment triggered a new exposure
	}

	@Test
	void getCustomFieldKeys() {
		final byte[] customFieldsContextBytes = getResourceBytes("custom_fields_context.json");
		final ContextPayload customFieldsContextData = deser.deserialize(customFieldsContextBytes, 0,
				customFieldsContextBytes.length);
		final Context context = createReadyContext(customFieldsContextData);
		assertTrue(context.isReady());

		assertEquals(java.util.Arrays.asList(new String[]{"country", "languages", "overrides"}), context.customFieldKeys());
	}

	@Test
	void getCustomFieldValue() {
		final byte[] customFieldsContextBytes = getResourceBytes("custom_fields_context.json");
		final ContextPayload customFieldsContextData = deser.deserialize(customFieldsContextBytes, 0,
				customFieldsContextBytes.length);
		final Context context = createReadyContext(customFieldsContextData);
		assertTrue(context.isReady());

		assertNull(context.customFieldValue("not_found", "not_found"));
		assertNull(context.customFieldValue("exp_test_ab", "not_found"));
		assertEquals("US,PT,ES,DE,FR", context.customFieldValue("exp_test_ab", "country"));
		assertEquals("string", context.customFieldType("exp_test_ab", "country"));
		assertEquals(mapOf("123", 1, "456", 0), context.customFieldValue("exp_test_ab", "overrides"));
		assertEquals("json", context.customFieldType("exp_test_ab", "overrides"));
		assertNull(context.customFieldValue("exp_test_ab", "languages"));
		assertNull(context.customFieldType("exp_test_ab", "languages"));

		assertEquals("US,PT,ES", context.customFieldValue("exp_test_abc", "country"));
		assertEquals("string", context.customFieldType("exp_test_abc", "country"));
		assertNull(context.customFieldValue("exp_test_abc", "overrides"));
		assertNull(context.customFieldType("exp_test_abc", "overrides"));
		assertEquals("en-US,en-GB,pt-PT,pt-BR,es-ES,es-MX", context.customFieldValue("exp_test_abc", "languages"));
		assertEquals("string", context.customFieldType("exp_test_abc", "languages"));

		assertNull(context.customFieldValue("exp_test_no_custom_fields", "country"));
		assertNull(context.customFieldType("exp_test_no_custom_fields", "country"));
		assertNull(context.customFieldValue("exp_test_no_custom_fields", "overrides"));
		assertNull(context.customFieldType("exp_test_no_custom_fields", "overrides"));
		assertNull(context.customFieldValue("exp_test_no_custom_fields", "languages"));
		assertNull(context.customFieldType("exp_test_no_custom_fields", "languages"));
	}

	@Test
	void getTreatmentQueuesExposureAfterPeek() {
		final Context context = createReadyContext();

		Arrays.stream(data.experiments).forEach(experiment -> context.peekTreatment(experiment.name));
		context.peekTreatment("not_found");

		assertEquals(0, context.pendingEventCount());

		Arrays.stream(data.experiments).forEach(experiment -> context.treatment(experiment.name));
		context.treatment("not_found");

		assertEquals(1 + data.experiments.length, context.pendingEventCount());
	}

	@Test
	void getTreatmentQueuesExposureWithBaseVariantOnUnknownExperiment() {
		final Context context = createReadyContext();

		assertEquals(0, context.treatment("not_found"));
		assertEquals(1, context.pendingEventCount());

		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));

		context.publish();

		final PublishPayload expected = new PublishPayload();
		expected.hashed = true;
		expected.publishedAt = clock.millis();
		expected.units = publishUnits;

		expected.exposures = new ExposurePayload[]{
				new ExposurePayload(0, "not_found", null, 0, clock.millis(), false, true, false, false, false, false),
		};

		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));

		context.publish();

		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(any(), any());
		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(context, expected);
	}

	@Test
	void getTreatmentDoesNotReQueueExposureOnUnknownExperiment() {
		final Context context = createReadyContext();

		assertEquals(0, context.treatment("not_found"));
		assertEquals(1, context.pendingEventCount());

		assertEquals(0, context.treatment("not_found"));
		assertEquals(1, context.pendingEventCount());

		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));

		context.publish();

		assertEquals(0, context.treatment("not_found"));
		assertEquals(0, context.pendingEventCount());
	}

	@Test
	void getTreatmentQueuesExposureWithCustomAssignmentVariant() {
		final Context context = createReadyContext();

		context.setCustomAssignment("exp_test_ab", 2);

		assertEquals(2, context.treatment("exp_test_ab"));
		assertEquals(1, context.pendingEventCount());

		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));

		context.publish();

		final PublishPayload expected = new PublishPayload();
		expected.hashed = true;
		expected.publishedAt = clock.millis();
		expected.units = publishUnits;

		expected.exposures = new ExposurePayload[]{
				new ExposurePayload(1, "exp_test_ab", "session_id", 2, clock.millis(), true, true, false, false, true, false),
		};

		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));

		context.publish();

		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(any(), any());
		verify(eventHandler, Mockito.timeout(5000).times(1)).publish(context, expected);
	}

	@Test
	void getVariableValueReturnsDefaultValueWhenUnassigned() {
		final Context context = createReadyContext();

		assertEquals(17, context.variableValue("card.width", 17));
	}

	@Test
	void getVariableValueReturnsDefaultValueOnUnknownVariable() {
		final Context context = createReadyContext();

		assertEquals("default", context.variableValue("unknown_variable", "default"));
		assertEquals(0, context.pendingEventCount());
	}

	@Test
	void getVariableValueReturnsVariableValuesWhenOverridden() {
		final Context context = createReadyContext();

		context.setOverride("exp_test_ab", 0);

		assertEquals(17, context.variableValue("banner.border", 17));
	}

	@Test
	void getVariableValueQueuesExposureAfterPeekVariableValue() {
		final Context context = createReadyContext();

		context.peekVariableValue("banner.border", 17);
		context.peekVariableValue("banner.size", 17);

		assertEquals(0, context.pendingEventCount());

		context.variableValue("banner.border", 17);
		context.variableValue("banner.size", 17);

		assertEquals(1, context.pendingEventCount());
	}

	@Test
	void getVariableValueQueuesExposureOnce() {
		final Context context = createReadyContext();

		context.variableValue("banner.border", 17);
		context.variableValue("banner.size", 17);

		assertEquals(1, context.pendingEventCount());

		context.variableValue("banner.border", 17);
		context.variableValue("banner.size", 17);

		assertEquals(1, context.pendingEventCount());
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
		assertEquals(5, context.override("exp_test_ab"));

		when(dataProvider.getContextData()).thenReturn(refreshDataFutureReady);

		context.refresh();

		assertEquals(5, context.override("exp_test_ab"));
		assertEquals(5, context.treatment("exp_test_ab"));
	}

	@Test
	void refreshKeepsCustomAssignments() {
		final Context context = createReadyContext();

		context.setCustomAssignment("exp_test_ab", 2);
		assertEquals(2, context.customAssignment("exp_test_ab"));

		when(dataProvider.getContextData()).thenReturn(refreshDataFutureReady);

		context.refresh();

		assertEquals(2, context.customAssignment("exp_test_ab"));
		assertEquals(2, context.treatment("exp_test_ab"));
	}

	@Test
	void publishIncludesExposureData() {
		final Context context = createReadyContext();

		context.treatment("exp_test_ab");

		assertEquals(1, context.pendingEventCount());

		final PublishPayload expected = new PublishPayload();
		expected.hashed = true;
		expected.publishedAt = clock.millis();
		expected.units = publishUnits;
		expected.exposures = new ExposurePayload[]{
				new ExposurePayload(1, "exp_test_ab", "session_id", 1, clock.millis(), true, true, false, false, false, false),
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

		assertEquals(1, context.pendingEventCount());

		final PublishPayload expected = new PublishPayload();
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
		final TestContextConfig config = TestContextConfig.create()
				.setUnits(units)
				.setAttributes(mapOf("attr1", "value1"));

		final Context context = createContext(config, dataFutureReady);

		context.track("goal1", null);

		final PublishPayload expected = new PublishPayload();
		expected.hashed = true;
		expected.publishedAt = clock.millis();
		expected.units = publishUnits;
		expected.goals = new GoalAchievement[]{
				new GoalAchievement("goal1", clock.millis(), null),
		};
		expected.attributes = new AttributePayload[]{
				new AttributePayload("attr1", "value1", clock.millis()),
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
		assertEquals(1, context.pendingEventCount());

		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));

		context.publish();

		assertEquals(0, context.pendingEventCount());
	}

	@Test
	void publishPropagatesClientErrorOnFailure() {
		final Context context = createReadyContext();

		context.track("goal1", mapOf("amount", 125));
		assertEquals(1, context.pendingEventCount());

		final Exception failure = new Exception("publish error");
		when(eventHandler.publish(any(), any())).thenReturn(failedFuture(failure));

		final ABsmartlyException actual = assertThrows(ABsmartlyException.class, context::publish);
		assertSame(failure, actual.getCause());
	}

	@Test
	void closeDoesNotCallEventHandlerWhenQueueIsEmpty() {
		final Context context = createReadyContext();
		assertEquals(0, context.pendingEventCount());

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

		context.treatment("exp_test_abc");
		context.track("goal1", mapOf("amount", 125));

		context.close();

		assertTrue(context.isClosed());
		verify(eventHandler, Mockito.timeout(5000).times(0)).publish(any(), any());
	}

	@Test
	void closeAsyncReturnsIndependentFuturesForTheSameAttempt() {
		final Context context = createReadyContext();

		context.track("goal1", mapOf("amount", 125));

		final CompletableFuture<Void> publishFuture = new CompletableFuture<>();
		when(eventHandler.publish(any(), any())).thenReturn(publishFuture);

		final CompletableFuture<Void> closeFuture1 = context.closeAsync();
		final CompletableFuture<Void> closeFuture2 = context.closeAsync();

		assertNotSame(closeFuture1, closeFuture2);

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

		assertEquals(1, context.pendingEventCount());
	}

	@Test
	void trackQueuesGoalWithNullProperties() {
		final Context context = createReadyContext();

		context.track("goal1", null);

		assertEquals(1, context.pendingEventCount());
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
		assertEquals("arrow", context.variableValue("icon", "square"));
		assertEquals(1, context.pendingEventCount());
	}

	@Test
	void publishKeepsEventsPendingOnFailure() {
		final Context context = createReadyContext();

		context.track("goal1", mapOf("amount", 125));
		assertEquals(1, context.pendingEventCount());

		final Exception failure = new Exception("PUBLISH_FAILED");
		when(eventHandler.publish(any(), any())).thenReturn(failedFuture(failure));

		assertThrows(ABsmartlyException.class, context::publish);

		assertEquals(1, context.pendingEventCount());
	}

	@Test
	void setOverrideSucceedsAfterClose() {
		final Context context = createReadyContext();

		context.close();
		assertTrue(context.isClosed());

		context.setOverride("exp_test_ab", 2);
		assertEquals(2, context.override("exp_test_ab"));
	}

	@Test
	void setOverridesSucceedsAfterClose() {
		final Context context = createReadyContext();

		context.close();
		assertTrue(context.isClosed());

		context.setOverrides(mapOf("exp_test_ab", 2, "exp_test_abc", 1));
		assertEquals(2, context.override("exp_test_ab"));
		assertEquals(1, context.override("exp_test_abc"));
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
		assertEquals(0, context.pendingEventCount());
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	void concurrentProducerDuringFailedPublishRestoresBothEvents() {
		final Context context = createReadyContext();

		context.track("goal_a", mapOf("amount", 1));
		assertEquals(1, context.pendingEventCount());

		final CompletableFuture<Void> publishFuture1 = new CompletableFuture<>();
		when(eventHandler.publish(any(), any())).thenReturn(publishFuture1);

		final CompletableFuture<Void> asyncResult1 = context.publishAsync();
		assertEquals(0, context.pendingEventCount());

		context.track("goal_b", mapOf("amount", 2));
		assertEquals(1, context.pendingEventCount());

		final Exception failure = new Exception("publish failed");
		publishFuture1.completeExceptionally(failure);

		assertThrows(CompletionException.class, asyncResult1::join);
		assertEquals(2, context.pendingEventCount());

		final CompletableFuture<Void> publishFuture2 = new CompletableFuture<>();
		when(eventHandler.publish(any(), any())).thenReturn(publishFuture2);

		final CompletableFuture<Void> asyncResult2 = context.publishAsync();
		assertEquals(0, context.pendingEventCount());

		publishFuture2.complete(null);
		asyncResult2.join();

		verify(eventHandler, Mockito.times(2)).publish(any(), any());
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	void closeAsyncRetryAfterFailedPublishSucceeds() {
		final Context context = createReadyContext();

		context.track("goal_a", mapOf("amount", 1));
		assertEquals(1, context.pendingEventCount());

		final CompletableFuture<Void> publishFuture1 = new CompletableFuture<>();
		when(eventHandler.publish(any(), any())).thenReturn(publishFuture1);

		final CompletableFuture<Void> closeFuture1 = context.closeAsync();
		assertFalse(context.isClosed());

		final Exception failure = new Exception("publish failed");
		publishFuture1.completeExceptionally(failure);

		final CompletionException actual = assertThrows(CompletionException.class, closeFuture1::join);
		assertSame(failure, actual.getCause());

		assertFalse(context.isClosed());
		assertTrue(context.pendingEventCount() > 0);

		final CompletableFuture<Void> publishFuture2 = new CompletableFuture<>();
		when(eventHandler.publish(any(), any())).thenReturn(publishFuture2);

		final CompletableFuture<Void> closeFuture2 = context.closeAsync();
		assertFalse(closeFuture2 == closeFuture1, "second closeAsync must return a new future");

		publishFuture2.complete(null);
		closeFuture2.join();

		assertTrue(context.isClosed());
		assertEquals(0, context.pendingEventCount());

		verify(eventHandler, Mockito.times(2)).publish(any(), any());
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	void publisherCanCloseContextInline() throws Exception {
		final Context context = createReadyContext();
		final AtomicReference<CompletableFuture<Void>> closeResult = new AtomicReference<>();
		when(eventHandler.publish(any(), any())).thenAnswer(invocation -> {
			final CompletableFuture<Void> result = context.closeAsync();
			closeResult.set(result);
			return result;
		});
		context.track("goal", mapOf("amount", 1));

		final CompletableFuture<Void> publishResult = context.publishAsync();
		publishResult.get(2, TimeUnit.SECONDS);
		closeResult.get().get(2, TimeUnit.SECONDS);

		assertTrue(context.isClosed());
		assertEquals(0, context.pendingEventCount());
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	void publisherCanCloseContextFromWorkerThread() throws Exception {
		final Context context = createReadyContext();
		final AtomicReference<CompletableFuture<Void>> closeResult = new AtomicReference<>();
		when(eventHandler.publish(any(), any())).thenAnswer(invocation -> {
			final CompletableFuture<Void> transportResult = new CompletableFuture<>();
			final Thread worker = new Thread(() -> {
				try {
					final CompletableFuture<Void> result = context.closeAsync();
					closeResult.set(result);
					result.get(2, TimeUnit.SECONDS);
					transportResult.complete(null);
				} catch (Exception exception) {
					transportResult.completeExceptionally(exception);
				}
			});
			worker.setDaemon(true);
			worker.start();
			return transportResult;
		});
		context.track("goal", mapOf("amount", 1));

		final CompletableFuture<Void> publishResult = context.publishAsync();
		publishResult.get(2, TimeUnit.SECONDS);
		closeResult.get().get(2, TimeUnit.SECONDS);

		assertTrue(context.isClosed());
		assertEquals(0, context.pendingEventCount());
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	void publisherCanCloseContextFromExecutorCallback() throws Exception {
		final Context context = createReadyContext();
		final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
			final Thread thread = new Thread(runnable);
			thread.setDaemon(true);
			return thread;
		});
		final AtomicReference<CompletableFuture<Void>> closeResult = new AtomicReference<>();
		try {
			when(eventHandler.publish(any(), any())).thenAnswer(invocation -> {
				final CompletableFuture<Void> transportResult = new CompletableFuture<>();
				executor.execute(() -> {
					try {
						final CompletableFuture<Void> result = context.closeAsync();
						closeResult.set(result);
						result.get(2, TimeUnit.SECONDS);
						transportResult.complete(null);
					} catch (Exception exception) {
						transportResult.completeExceptionally(exception);
					}
				});
				return transportResult;
			});
			context.track("goal", mapOf("amount", 1));

			final CompletableFuture<Void> publishResult = context.publishAsync();
			publishResult.get(2, TimeUnit.SECONDS);
			closeResult.get().get(2, TimeUnit.SECONDS);

			assertTrue(context.isClosed());
			assertEquals(0, context.pendingEventCount());
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	void publishSucceedsWhenLoggerThrowsOnPublishEvent() {
		final Context context = createReadyContext();

		context.track("goal1", mapOf("amount", 125));
		assertEquals(1, context.pendingEventCount());

		when(eventHandler.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
		Mockito.doThrow(new RuntimeException("logger failure"))
				.when(eventLogger).handleEvent(any(), eq(ContextEventLogger.EventType.Publish), any());

		assertDoesNotThrow(context::publish);
		assertEquals(0, context.pendingEventCount());

		// no new events were queued, so a retry must not re-deliver the same batch
		context.publishAsync().join();
		assertEquals(0, context.pendingEventCount());

		verify(eventHandler, Mockito.times(1)).publish(any(), any());
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	void synchronousPublisherFailureSettlesWhenErrorLoggerThrows() {
		final Context context = createReadyContext();
		context.track("goal", mapOf("amount", 1));

		final RuntimeException publisherFailure = new RuntimeException("publisher threw");
		when(eventHandler.publish(any(), any())).thenThrow(publisherFailure);
		Mockito.doThrow(new RuntimeException("logger threw"))
				.when(eventLogger).handleEvent(any(), eq(ContextEventLogger.EventType.Error), any());

		final CompletableFuture<Void> publishResult = assertDoesNotThrow(context::publishAsync);
		final CompletionException actual = assertThrows(CompletionException.class, publishResult::join);
		assertSame(publisherFailure, actual.getCause());
		assertTrue(context.pendingEventCount() > 0);

		final CompletableFuture<Void> closeResult = assertDoesNotThrow(context::closeAsync);
		assertTrue(closeResult.isDone());
		assertThrows(CompletionException.class, closeResult::join);
		assertFalse(context.isClosed());
		assertTrue(context.pendingEventCount() > 0);
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
		assertTrue(context.pendingEventCount() > 0);

		final CompletableFuture<Void> closeResult = assertDoesNotThrow(context::closeAsync);
		assertTrue(closeResult.isDone());
		assertThrows(CompletionException.class, closeResult::join);
		assertFalse(context.isClosed());
		assertTrue(context.pendingEventCount() > 0);
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	void cancellingPublishResultDoesNotDisturbPublishAccounting() {
		final Context context = createReadyContext();
		context.track("goal", mapOf("amount", 1));

		final CompletableFuture<Void> publisherFuture = new CompletableFuture<>();
		when(eventHandler.publish(any(), any())).thenReturn(publisherFuture)
				.thenReturn(CompletableFuture.completedFuture(null));

		final CompletableFuture<Void> publishResult = context.publishAsync();
		assertTrue(publishResult.cancel(false));

		final RuntimeException failure = new RuntimeException("publish failed");
		publisherFuture.completeExceptionally(failure);

		assertTrue(context.pendingEventCount() > 0);

		final CompletableFuture<Void> retryPublish = assertDoesNotThrow(context::publishAsync);
		retryPublish.join();
		assertEquals(0, context.pendingEventCount());
	}

	// With pending events the Close event is logged from the publish stage, after closeAsync() settles.
	private static <T> T awaitValue(final AtomicReference<T> ref) throws InterruptedException {
		final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (ref.get() == null && System.nanoTime() < deadline) {
			Thread.sleep(5);
		}
		return ref.get();
	}

	private void recordFutureStateOnEvent(final ContextEventLogger.EventType type,
			final AtomicReference<CompletableFuture<?>> future, final AtomicReference<Boolean> doneWhenLogged) {
		Mockito.doAnswer(invocation -> {
			if (invocation.getArgument(1) == type && future.get() != null) {
				doneWhenLogged.set(future.get().isDone());
			}
			return null;
		}).when(eventLogger).handleEvent(any(), any(), any());
	}

	@Test
	void readyFutureSettlesBeforeReadyEventIsLogged() {
		final AtomicReference<CompletableFuture<?>> future = new AtomicReference<>();
		final AtomicReference<Boolean> doneWhenLogged = new AtomicReference<>();
		recordFutureStateOnEvent(ContextEventLogger.EventType.Ready, future, doneWhenLogged);

		final Context context = createContext(dataFuture);
		future.set(context.readyAsync());
		dataFuture.complete(data);

		assertEquals(Boolean.TRUE, doneWhenLogged.get());
		assertTrue(future.get().isDone());
	}

	@Test
	void readyFutureSettlesBeforeErrorEventIsLoggedOnFailedLoad() {
		final AtomicReference<CompletableFuture<?>> future = new AtomicReference<>();
		final AtomicReference<Boolean> doneWhenLogged = new AtomicReference<>();
		recordFutureStateOnEvent(ContextEventLogger.EventType.Error, future, doneWhenLogged);

		final Context context = createContext(dataFuture);
		future.set(context.readyAsync());
		dataFuture.completeExceptionally(new Exception("FAILED"));

		assertEquals(Boolean.TRUE, doneWhenLogged.get());
		assertTrue(context.isFailed());
	}

	@Test
	void refreshFutureSettlesBeforeRefreshEventIsLogged() {
		final AtomicReference<CompletableFuture<?>> future = new AtomicReference<>();
		final AtomicReference<Boolean> doneWhenLogged = new AtomicReference<>();
		recordFutureStateOnEvent(ContextEventLogger.EventType.Refresh, future, doneWhenLogged);

		final Context context = createReadyContext();
		when(dataProvider.getContextData()).thenReturn(refreshDataFuture);
		future.set(context.refreshAsync());
		refreshDataFuture.complete(refreshData);

		assertEquals(Boolean.TRUE, doneWhenLogged.get());
		assertTrue(future.get().isDone());
	}

	@Test
	void refreshFutureSettlesBeforeErrorEventIsLoggedOnFailure() {
		final AtomicReference<CompletableFuture<?>> future = new AtomicReference<>();
		final AtomicReference<Boolean> doneWhenLogged = new AtomicReference<>();
		recordFutureStateOnEvent(ContextEventLogger.EventType.Error, future, doneWhenLogged);

		final Context context = createReadyContext();
		when(dataProvider.getContextData()).thenReturn(refreshDataFuture);
		future.set(context.refreshAsync());
		refreshDataFuture.completeExceptionally(new Exception("FAILED"));

		assertEquals(Boolean.TRUE, doneWhenLogged.get());
		assertTrue(future.get().isCompletedExceptionally());
	}

	@Test
	void closeFutureSettlesBeforeCloseEventIsLogged() throws InterruptedException {
		final AtomicReference<CompletableFuture<?>> future = new AtomicReference<>();
		final AtomicReference<Boolean> doneWhenLogged = new AtomicReference<>();
		recordFutureStateOnEvent(ContextEventLogger.EventType.Close, future, doneWhenLogged);

		final Context context = createReadyContext();
		context.track("goal1", null);
		final CompletableFuture<Void> publishFuture = new CompletableFuture<>();
		when(eventHandler.publish(any(), any())).thenReturn(publishFuture);

		future.set(context.closeAsync());
		publishFuture.complete(null);
		future.get().join();

		assertEquals(Boolean.TRUE, awaitValue(doneWhenLogged));
		assertTrue(context.isClosed());
	}

	@Test
	void numericUnitsKeepTheirType() {
		final TestContextConfig config = TestContextConfig.create()
				.setUnit("session_id", "abc")
				.setUnit("user_id", 125)
				.setUnit("float_id", 125.75);
		final Context context = createContext(config, dataFutureReady);
		context.setUnit("org_id", 99L);

		assertEquals(125, context.unitValue("user_id"));
		assertEquals(125.75, context.unitValue("float_id"));
		assertEquals(99L, context.unitValue("org_id"));
		assertEquals("abc", context.unitValue("session_id"));
		assertEquals(mapOf("session_id", "abc", "user_id", 125, "float_id", 125.75, "org_id", 99L),
				context.unitValues());

		// the 1.6.3 getters keep their String types and return the string form
		assertEquals("125", context.unit("user_id"));
		assertEquals("126", context.unit("float_id"));
		assertEquals("99", context.unit("org_id"));
		assertEquals("abc", context.unit("session_id"));
		assertNull(context.unit("missing"));
		final Map<String, String> strings = context.units();
		assertEquals(mapOf("session_id", "abc", "user_id", "125", "float_id", "126", "org_id", "99"), strings);
		assertEquals(mapOf("session_id", "abc", "user_id", 125, "float_id", 125.75), config.toOptions().units());
	}

	@Test
	void numericUnitHashesLikeIntegerString() {
		assertEquals("126", Hashing.unitToString(125.75));
		assertEquals("125", Hashing.unitToString(125));
		assertEquals("-3", Hashing.unitToString(-2.5));
		assertEquals("-0", Hashing.unitToString(-0.1));
		assertEquals("0", Hashing.unitToString(-0.0));
		assertEquals("123456789", Hashing.unitToString(123456789L));
		assertEquals("9007199254740992", Hashing.unitToString(9007199254740993L));
		assertEquals("1e+21", Hashing.unitToString(1e21));
		assertEquals("1e+23", Hashing.unitToString(1e23));
		assertEquals("6.189700196426902e+26", Hashing.unitToString(Math.scalb(1.0, 89)));
		// Expected strings from Node's toFixed(0).
		assertEquals("1.1805916207174113e+21", Hashing.unitToString(Math.scalb(1.0, 70)));
		assertEquals("3.6267774588438875e+24", Hashing.unitToString(3 * Math.scalb(1.0, 80)));
		assertEquals("1.2345678901234569e+23", Hashing.unitToString(123456789012345680000000.0));
		assertEquals("1.7976931348623157e+308", Hashing.unitToString(Double.MAX_VALUE));
		assertEquals("1.2345e+22", Hashing.unitToString(1.2345e22));
		assertEquals("-1e+21", Hashing.unitToString(-1e21));
		assertEquals("999999999999999868928", Hashing.unitToString(999999999999999900000.0));

		final Context numeric = createContext(TestContextConfig.create().setUnit("user_id", 125.75), dataFutureReady);
		final Context text = createContext(TestContextConfig.create().setUnit("user_id", "126"), dataFutureReady);
		numeric.track("goal", null);
		text.track("goal", null);
		final List<PublishPayload> events = new ArrayList<>();
		Mockito.doAnswer(invocation -> {
			events.add(invocation.getArgument(1));
			return CompletableFuture.completedFuture(null);
		}).when(eventHandler).publish(any(), any());
		numeric.publish();
		text.publish();

		assertEquals(2, events.size());
		assertEquals(events.get(1).units[0].uid, events.get(0).units[0].uid);
	}

	@Test
	void setUnitRejectsSameNumericValueOnlyWhenDifferent() {
		final Context context = createContext(TestContextConfig.create().setUnit("user_id", 12345), dataFutureReady);
		assertDoesNotThrow(() -> context.setUnit("user_id", 12345));
		final UnitAlreadySetException error = assertThrows(UnitAlreadySetException.class,
				() -> context.setUnit("user_id", 54321));
		assertEquals("Unit 'user_id' UID already set.", error.getMessage());
	}

	@Test
	void setUnitChecksBlankBeforeAlreadySet() {
		final Context context = createReadyContext();
		final IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
				() -> context.setUnit("user_id", "  "));
		assertEquals("Unit 'user_id' UID must not be blank.", error.getMessage());
	}

	@Test
	void refreshOnFailedContextIsANoOp() {
		final Context context = createContext(dataFutureFailed);
		assertTrue(context.isFailed());
		Mockito.clearInvocations(eventLogger);

		context.refresh();

		verify(dataProvider, Mockito.never()).getContextData();
		Mockito.verifyNoInteractions(eventLogger);
		assertTrue(context.isFailed());
	}

	@Test
	void setUnitComparesLargeNumericUidsExactly() {
		final Context context = createContext(TestContextConfig.create().setUnit("user_id", 9007199254740992L),
				dataFutureReady);
		final UnitAlreadySetException error = assertThrows(UnitAlreadySetException.class,
				() -> context.setUnit("user_id", 9007199254740993L));
		assertEquals("Unit 'user_id' UID already set.", error.getMessage());
		assertDoesNotThrow(() -> context.setUnit("user_id", 9007199254740992.0));
		assertEquals(9007199254740992.0, context.unitValue("user_id"));
	}

	@Test
	void setUnitRejectsMutableNumbers() {
		final Context context = createReadyContext();
		final IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
				() -> context.setUnit("account_id", new java.util.concurrent.atomic.AtomicInteger(125)));
		assertEquals("Unit 'account_id' must be a string or a number.", error.getMessage());
		assertNull(context.unit("account_id"));

		assertThrows(IllegalArgumentException.class, () -> createContext(
				TestContextConfig.create().setUnit("account_id", new java.util.concurrent.atomic.AtomicLong(1)),
				dataFutureReady));
	}

	@Test
	void lateReadinessWaitersInsideReadyEventSeeSettledFuture() {
		final AtomicReference<Boolean> lateWaiterDone = new AtomicReference<>();
		final AtomicReference<Context> contextRef = new AtomicReference<>();
		Mockito.doAnswer(invocation -> {
			if (invocation.getArgument(1) == ContextEventLogger.EventType.Ready) {
				lateWaiterDone.set(contextRef.get().readyAsync().isDone());
			}
			return null;
		}).when(eventLogger).handleEvent(any(), any(), any());

		final Context context = createContext(dataFuture);
		contextRef.set(context);
		dataFuture.complete(data);

		assertEquals(Boolean.TRUE, lateWaiterDone.get());
		assertTrue(context.readyAsync().isDone());
	}

	@Test
	void lateCloseCallersInsideCloseEventSeeSettledFuture() throws InterruptedException {
		final AtomicReference<Boolean> lateCloseDone = new AtomicReference<>();
		final AtomicReference<Context> contextRef = new AtomicReference<>();
		Mockito.doAnswer(invocation -> {
			if (invocation.getArgument(1) == ContextEventLogger.EventType.Close) {
				lateCloseDone.set(contextRef.get().closeAsync().isDone());
			}
			return null;
		}).when(eventLogger).handleEvent(any(), any(), any());

		final Context context = createReadyContext();
		contextRef.set(context);
		context.track("goal1", null);
		final CompletableFuture<Void> publishFuture = new CompletableFuture<>();
		when(eventHandler.publish(any(), any())).thenReturn(publishFuture);

		final CompletableFuture<Void> closing = context.closeAsync();
		publishFuture.complete(null);
		closing.join();

		assertEquals(Boolean.TRUE, awaitValue(lateCloseDone));
		assertTrue(context.closeAsync().isDone());
	}

	@Test
	void lateCloseCallersInsideCloseEventSeeSettledFutureWithNothingPending() {
		final AtomicReference<Boolean> lateCloseDone = new AtomicReference<>();
		final AtomicReference<Context> contextRef = new AtomicReference<>();
		Mockito.doAnswer(invocation -> {
			if (invocation.getArgument(1) == ContextEventLogger.EventType.Close) {
				lateCloseDone.set(contextRef.get().closeAsync().isDone());
			}
			return null;
		}).when(eventLogger).handleEvent(any(), any(), any());

		final Context context = createReadyContext();
		contextRef.set(context);
		context.close();

		assertEquals(Boolean.TRUE, lateCloseDone.get());
		assertTrue(context.closeAsync().isDone());
	}

	@Test
	@Timeout(60)
	void concurrentClosesLogOneCloseEvent() throws Exception {
		final ExecutorService pool = Executors.newFixedThreadPool(3);
		try {
			for (int i = 0; i < 3000; ++i) {
				final java.util.concurrent.atomic.AtomicInteger closeEvents = new java.util.concurrent.atomic.AtomicInteger();
				final ContextEventLogger logger = (ctx, type, data) -> {
					if (type == ContextEventLogger.EventType.Close) {
						closeEvents.incrementAndGet();
					}
				};
				final Context context = TestContexts.create(clock, TestContextConfig.create().setUnits(units), scheduler,
						dataFutureReady, dataProvider, eventHandler, logger, variableParser, audienceMatcher);
				final java.util.concurrent.CyclicBarrier start = new java.util.concurrent.CyclicBarrier(3);
				final List<java.util.concurrent.Future<Boolean>> results = new ArrayList<>();
				for (int t = 0; t < 3; ++t) {
					results.add(pool.submit(() -> {
						start.await();
						context.closeAsync().join();
						return true;
					}));
				}
				for (final java.util.concurrent.Future<Boolean> result : results) {
					assertTrue(result.get());
				}
				assertEquals(1, closeEvents.get(), "Close event logged more than once");
			}
		} finally {
			pool.shutdownNow();
		}
	}

	@Test
	void cancellingOneCloseFutureWhilePublishingDoesNotDisturbOtherCallers() {
		final Context context = createReadyContext();
		context.track("goal1", null);
		final CompletableFuture<Void> publishFuture = new CompletableFuture<>();
		when(eventHandler.publish(any(), any())).thenReturn(publishFuture);

		final CompletableFuture<Void> first = context.closeAsync();
		final CompletableFuture<Void> second = context.closeAsync();
		assertTrue(first.cancel(false));

		assertFalse(second.isDone());
		final CompletableFuture<Void> third = context.closeAsync();
		assertFalse(third.isDone());
		assertTrue(context.isClosing());

		publishFuture.complete(null);
		second.join();
		third.join();

		assertFalse(second.isCancelled());
		assertFalse(third.isCancelled());
		assertTrue(context.isClosed());
		final CompletableFuture<Void> after = context.closeAsync();
		assertTrue(after.isDone());
		assertFalse(after.isCompletedExceptionally());
	}

	@Test
	void externallyCompletingCloseFutureDoesNotReleaseOtherCallers() {
		final Context context = createReadyContext();
		context.track("goal1", null);
		final CompletableFuture<Void> publish = new CompletableFuture<>();
		when(eventHandler.publish(any(), any())).thenReturn(publish);
		final CompletableFuture<Void> first = context.closeAsync();
		final CompletableFuture<Void> second = context.closeAsync();
		first.completeAsync(() -> null, Runnable::run).join();
		assertFalse(second.isDone());
		assertFalse(context.closeAsync().isDone());
		publish.complete(null);
		second.join();
		assertTrue(context.isClosed());
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	void eventLoggerMayWaitForReadinessInsideReadyEvent() {
		final AtomicReference<Boolean> readyInLogger = new AtomicReference<>();
		final AtomicReference<Context> contextRef = new AtomicReference<>();
		Mockito.doAnswer(invocation -> {
			if (invocation.getArgument(1) == ContextEventLogger.EventType.Ready) {
				contextRef.get().awaitLoad();
				contextRef.get().readyAsync().join();
				readyInLogger.set(contextRef.get().isReady());
			}
			return null;
		}).when(eventLogger).handleEvent(any(), any(), any());

		final Context context = createContext(dataFuture);
		contextRef.set(context);
		dataFuture.complete(data);

		assertEquals(Boolean.TRUE, readyInLogger.get());
	}

	@Test
	void customFieldReadsStillWorkWhileClosingAndAfterClose() {
		final Context context = createReadyContext();
		final List<String> keys = context.customFieldKeys();
		context.track("goal1", null);
		final CompletableFuture<Void> publishFuture = new CompletableFuture<>();
		when(eventHandler.publish(any(), any())).thenReturn(publishFuture);
		final CompletableFuture<Void> closing = context.closeAsync();
		assertTrue(context.isClosing());
		assertEquals(keys, context.customFieldKeys());

		publishFuture.complete(null);
		closing.join();
		assertTrue(context.isClosed());
		assertEquals(keys, context.customFieldKeys());
	}
}
