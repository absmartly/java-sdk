package com.absmartly.sdk;

import java.util.concurrent.ScheduledExecutorService;

import javax.annotation.Nonnull;

/**
 * @deprecated Use {@link com.absmartly.sdk.ABsmartlyConfig} instead.
 */
@Deprecated
public class ABSmartlyConfig extends ABsmartlyConfig {
	public static ABSmartlyConfig create() {
		return new ABSmartlyConfig();
	}

	protected ABSmartlyConfig() {
		super();
	}

	@Override
	public ABSmartlyConfig setClient(Client client) {
		super.setClient(client);
		return this;
	}

	@Override
	public ABSmartlyConfig setContextDataProvider(@Nonnull final ContextDataProvider contextDataProvider) {
		super.setContextDataProvider(contextDataProvider);
		return this;
	}

	@Override
	public ABSmartlyConfig setContextEventHandler(@Nonnull final ContextEventHandler contextEventHandler) {
		super.setContextEventHandler(contextEventHandler);
		return this;
	}

	@Override
	public ABSmartlyConfig setVariableParser(@Nonnull final VariableParser variableParser) {
		super.setVariableParser(variableParser);
		return this;
	}

	@Override
	public ABSmartlyConfig setScheduler(@Nonnull final ScheduledExecutorService scheduler) {
		super.setScheduler(scheduler);
		return this;
	}

	@Override
	public ABSmartlyConfig setContextEventLogger(@Nonnull final ContextEventLogger logger) {
		super.setContextEventLogger(logger);
		return this;
	}

	@Override
	public ABSmartlyConfig setAudienceDeserializer(@Nonnull final AudienceDeserializer audienceDeserializer) {
		super.setAudienceDeserializer(audienceDeserializer);
		return this;
	}
}
