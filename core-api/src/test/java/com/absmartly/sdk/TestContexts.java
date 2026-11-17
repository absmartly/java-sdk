package com.absmartly.sdk;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;

import com.absmartly.sdk.internal.Clock;
import com.absmartly.sdk.internal.json.ContextPayload;

/** Builds a {@link Context} straight on the engine seams, as the 1.x {@code Context.create} overloads did. */
final class TestContexts {
	private TestContexts() {}

	static Context create(final Clock clock, final TestContextConfig config, final ScheduledExecutorService scheduler,
			final CompletableFuture<ContextPayload> dataFuture, final ContextDataProvider dataProvider,
			final ContextEventHandler eventHandler, final ContextEventLogger eventLogger,
			final VariableParser variableParser, final AudienceMatcher audienceMatcher) {
		return create(clock, config, scheduler, dataFuture, dataProvider, eventHandler, eventLogger, variableParser,
				audienceMatcher, null);
	}

	static Context create(final Clock clock, final TestContextConfig config, final ScheduledExecutorService scheduler,
			final CompletableFuture<ContextPayload> dataFuture, final ContextDataProvider dataProvider,
			final ContextEventHandler eventHandler, final ContextEventLogger eventLogger,
			final VariableParser variableParser, final AudienceMatcher audienceMatcher, final Client client) {
		final ContextEventLogger logger = (config.getEventLogger() != null) ? config.getEventLogger() : eventLogger;
		return new Context(clock, config.toOptions(), scheduler, dataFuture, dataProvider, eventHandler, logger,
				variableParser, audienceMatcher, client, null);
	}
}
