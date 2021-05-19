package com.absmartly.sdk;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.time.Clock;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import com.absmartly.sdk.json.ContextData;

class ABSmartlyTest {
	@Test
	void create() {
		final ABSmartlyConfig config = ABSmartlyConfig.create()
				.setEndpoint("https://localhost/v1")
				.setAPIKey("test-api-key")
				.setApplication("website")
				.setEnvironment("development");

		final ABSmartly absmartly = ABSmartly.create(config);
		assertNotNull(absmartly);
	}

	@Test
	void createThrowsWithInvalidConfig() {
		assertThrows(IllegalArgumentException.class, () -> {
			final ABSmartlyConfig config = ABSmartlyConfig.create()
					.setAPIKey("test-api-key")
					.setApplication("website")
					.setEnvironment("development");

			final ABSmartly absmartly = ABSmartly.create(config);
		}, "Missing Endpoint configuration");

		assertThrows(IllegalArgumentException.class, () -> {
			final ABSmartlyConfig config = ABSmartlyConfig.create()
					.setEndpoint("https://localhost/v1")
					.setApplication("website")
					.setEnvironment("development");

			final ABSmartly absmartly = ABSmartly.create(config);
		}, "Missing APIKey configuration");

		assertThrows(IllegalArgumentException.class, () -> {
			final ABSmartlyConfig config = ABSmartlyConfig.create()
					.setEndpoint("https://localhost/v1")
					.setAPIKey("test-api-key")
					.setEnvironment("development");

			final ABSmartly absmartly = ABSmartly.create(config);
		}, "Missing Application configuration");

		assertThrows(IllegalArgumentException.class, () -> {
			final ABSmartlyConfig config = ABSmartlyConfig.create()
					.setEndpoint("https://localhost/v1")
					.setAPIKey("test-api-key")
					.setApplication("website");

			final ABSmartly absmartly = ABSmartly.create(config);
		}, "Missing Environment configuration");
	}

	@Test
	void createContext() {
		final ABSmartlyConfig config = ABSmartlyConfig.create()
				.setEndpoint("https://localhost/v1")
				.setAPIKey("test-api-key")
				.setApplication("website")
				.setEnvironment("development");

		final CompletableFuture<ContextData> dataFuture = (CompletableFuture<ContextData>) mock(
				CompletableFuture.class);
		try (final MockedConstruction<DefaultContextDataProvider> dataProviderCtor = mockConstruction(
				DefaultContextDataProvider.class, (mock, context) -> {
					when(mock.getContextData()).thenReturn(dataFuture);
				})) {
			final ABSmartly absmartly = ABSmartly.create(config);
			assertEquals(1, dataProviderCtor.constructed().size());

			try (final MockedStatic<Context> contextStatic = mockStatic(Context.class)) {
				final Context contextMock = mock(Context.class);
				contextStatic.when(() -> Context.create(any(), any(), any(), any(), any(), any(), any()))
						.thenReturn(contextMock);

				final ContextConfig contextConfig = ContextConfig.create().setUnit("user_id", "1234567");
				final Context context = absmartly.createContext(contextConfig);
				assertSame(contextMock, context);

				final ArgumentCaptor<Clock> clockCaptor = ArgumentCaptor.forClass(Clock.class);
				final ArgumentCaptor<ContextConfig> configCaptor = ArgumentCaptor.forClass(ContextConfig.class);
				final ArgumentCaptor<ScheduledExecutorService> schedulerCaptor = ArgumentCaptor
						.forClass(ScheduledExecutorService.class);
				final ArgumentCaptor<CompletableFuture<ContextData>> dataFutureCaptor = ArgumentCaptor
						.forClass(CompletableFuture.class);
				final ArgumentCaptor<ContextDataProvider> dataProviderCaptor = ArgumentCaptor
						.forClass(ContextDataProvider.class);
				final ArgumentCaptor<ContextEventHandler> eventHandlerCaptor = ArgumentCaptor
						.forClass(ContextEventHandler.class);
				final ArgumentCaptor<VariableParser> variableParserCaptor = ArgumentCaptor
						.forClass(VariableParser.class);

				contextStatic.verify(times(1), () -> Context.create(any(), any(), any(), any(), any(), any(), any()));
				contextStatic.verify(times(1),
						() -> Context.create(clockCaptor.capture(), configCaptor.capture(), schedulerCaptor.capture(),
								dataFutureCaptor.capture(), dataProviderCaptor.capture(), eventHandlerCaptor.capture(),
								variableParserCaptor.capture()));

				assertEquals(Clock.systemUTC(), clockCaptor.getValue());
				assertSame(contextConfig, configCaptor.getValue());
				assertTrue(schedulerCaptor.getValue() instanceof ScheduledThreadPoolExecutor);
				assertSame(dataFuture, dataFutureCaptor.getValue());
				assertTrue(dataProviderCaptor.getValue() instanceof DefaultContextDataProvider);
				assertTrue(eventHandlerCaptor.getValue() instanceof DefaultContextEventHandler);
				assertTrue(variableParserCaptor.getValue() instanceof DefaultVariableParser);
			}
		}
	}

	@Test
	void createContextWith() {
		final ABSmartlyConfig config = ABSmartlyConfig.create()
				.setEndpoint("https://localhost/v1")
				.setAPIKey("test-api-key")
				.setApplication("website")
				.setEnvironment("development");

		final ContextData data = new ContextData();
		try (final MockedConstruction<DefaultContextDataProvider> dataProviderCtor = mockConstruction(
				DefaultContextDataProvider.class)) {
			final ABSmartly absmartly = ABSmartly.create(config);
			assertEquals(1, dataProviderCtor.constructed().size());

			try (final MockedStatic<Context> contextStatic = mockStatic(Context.class)) {
				final Context contextMock = mock(Context.class);
				contextStatic.when(() -> Context.create(any(), any(), any(), any(), any(), any(), any()))
						.thenReturn(contextMock);

				final ContextConfig contextConfig = ContextConfig.create().setUnit("user_id", "1234567");
				final Context context = absmartly.createContextWith(contextConfig, data);
				assertSame(contextMock, context);

				verify(dataProviderCtor.constructed().get(0), times(0)).getContextData();

				final ArgumentCaptor<Clock> clockCaptor = ArgumentCaptor.forClass(Clock.class);
				final ArgumentCaptor<ContextConfig> configCaptor = ArgumentCaptor.forClass(ContextConfig.class);
				final ArgumentCaptor<ScheduledExecutorService> schedulerCaptor = ArgumentCaptor
						.forClass(ScheduledExecutorService.class);
				final ArgumentCaptor<CompletableFuture<ContextData>> dataFutureCaptor = ArgumentCaptor
						.forClass(CompletableFuture.class);
				final ArgumentCaptor<ContextDataProvider> dataProviderCaptor = ArgumentCaptor
						.forClass(ContextDataProvider.class);
				final ArgumentCaptor<ContextEventHandler> eventHandlerCaptor = ArgumentCaptor
						.forClass(ContextEventHandler.class);
				final ArgumentCaptor<VariableParser> variableParserCaptor = ArgumentCaptor
						.forClass(VariableParser.class);

				contextStatic.verify(times(1), () -> Context.create(any(), any(), any(), any(), any(), any(), any()));
				contextStatic.verify(times(1),
						() -> Context.create(clockCaptor.capture(), configCaptor.capture(), schedulerCaptor.capture(),
								dataFutureCaptor.capture(), dataProviderCaptor.capture(), eventHandlerCaptor.capture(),
								variableParserCaptor.capture()));

				assertEquals(Clock.systemUTC(), clockCaptor.getValue());
				assertSame(contextConfig, configCaptor.getValue());
				assertTrue(schedulerCaptor.getValue() instanceof ScheduledThreadPoolExecutor);
				assertDoesNotThrow(() -> assertSame(data, dataFutureCaptor.getValue().get()));
				assertTrue(dataProviderCaptor.getValue() instanceof DefaultContextDataProvider);
				assertTrue(eventHandlerCaptor.getValue() instanceof DefaultContextEventHandler);
				assertTrue(variableParserCaptor.getValue() instanceof DefaultVariableParser);
			}
		}
	}

	@Test
	void createContextWithCustomImpls() {
		final CompletableFuture<ContextData> dataFuture = mock(CompletableFuture.class);
		final ContextDataProvider dataProvider = mock(ContextDataProvider.class);
		when(dataProvider.getContextData()).thenReturn(dataFuture);

		final ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
		final ContextEventHandler eventHandler = mock(ContextEventHandler.class);
		final VariableParser variableParser = mock(VariableParser.class);

		final ABSmartlyConfig config = ABSmartlyConfig.create()
				.setEndpoint("https://localhost/v1")
				.setAPIKey("test-api-key")
				.setApplication("website")
				.setEnvironment("development")
				.setContextDataProvider(dataProvider)
				.setContextEventHandler(eventHandler)
				.setScheduler(scheduler)
				.setVariableParser(variableParser);

		final ABSmartly absmartly = ABSmartly.create(config);

		try (final MockedStatic<Context> contextStatic = mockStatic(Context.class)) {
			final Context contextMock = mock(Context.class);
			contextStatic.when(() -> Context.create(any(), any(), any(), any(), any(), any(), any()))
					.thenReturn(contextMock);

			final ContextConfig contextConfig = ContextConfig.create().setUnit("user_id", "1234567");
			final Context context = absmartly.createContext(contextConfig);
			assertSame(contextMock, context);

			final ArgumentCaptor<Clock> clockCaptor = ArgumentCaptor.forClass(Clock.class);
			final ArgumentCaptor<ContextConfig> configCaptor = ArgumentCaptor.forClass(ContextConfig.class);
			final ArgumentCaptor<ScheduledExecutorService> schedulerCaptor = ArgumentCaptor
					.forClass(ScheduledExecutorService.class);
			final ArgumentCaptor<CompletableFuture<ContextData>> dataFutureCaptor = ArgumentCaptor
					.forClass(CompletableFuture.class);
			final ArgumentCaptor<ContextDataProvider> dataProviderCaptor = ArgumentCaptor
					.forClass(ContextDataProvider.class);
			final ArgumentCaptor<ContextEventHandler> eventHandlerCaptor = ArgumentCaptor
					.forClass(ContextEventHandler.class);
			final ArgumentCaptor<VariableParser> variableParserCaptor = ArgumentCaptor.forClass(VariableParser.class);

			contextStatic.verify(times(1), () -> Context.create(any(), any(), any(), any(), any(), any(), any()));
			contextStatic.verify(times(1),
					() -> Context.create(clockCaptor.capture(), configCaptor.capture(), schedulerCaptor.capture(),
							dataFutureCaptor.capture(), dataProviderCaptor.capture(), eventHandlerCaptor.capture(),
							variableParserCaptor.capture()));

			assertEquals(Clock.systemUTC(), clockCaptor.getValue());
			assertSame(contextConfig, configCaptor.getValue());
			assertSame(scheduler, schedulerCaptor.getValue());
			assertSame(dataFuture, dataFutureCaptor.getValue());
			assertSame(dataProvider, dataProviderCaptor.getValue());
			assertSame(eventHandler, eventHandlerCaptor.getValue());
			assertSame(variableParser, variableParserCaptor.getValue());
		}
	}

	@Test
	void close() throws IOException, InterruptedException {
		final ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);

		final ABSmartlyConfig config = ABSmartlyConfig.create()
				.setEndpoint("https://localhost/v1")
				.setAPIKey("test-api-key")
				.setApplication("website")
				.setEnvironment("development")
				.setScheduler(scheduler);

		final ABSmartly absmartly = ABSmartly.create(config);

		try (final MockedStatic<Context> contextStatic = mockStatic(Context.class)) {
			final Context contextMock = mock(Context.class);
			contextStatic.when(() -> Context.create(any(), any(), any(), any(), any(), any(), any()))
					.thenReturn(contextMock);

			final ContextConfig contextConfig = ContextConfig.create().setUnit("user_id", "1234567");
			final Context context = absmartly.createContext(contextConfig);
			assertSame(contextMock, context);

			absmartly.close();

			verify(scheduler, times(1)).awaitTermination(anyLong(), any());
		}
	}
}
