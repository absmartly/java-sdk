package com.absmartly.sdk.deprecated;

import java.util.concurrent.ScheduledExecutorService;

import javax.annotation.Nonnull;

import com.absmartly.sdk.ABsmartlyConfig;
import com.absmartly.sdk.AudienceDeserializer;
import com.absmartly.sdk.Client;
import com.absmartly.sdk.ContextDataProvider;
import com.absmartly.sdk.ContextEventHandler;
import com.absmartly.sdk.ContextEventLogger;
import com.absmartly.sdk.VariableParser;

@Deprecated
public class ABSmartlyConfig extends ABsmartlyConfig {
	public static ABSmartlyConfig create() {
		return new ABSmartlyConfig();
	}

	protected ABSmartlyConfig() {
		super();
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

	@Override
	public ABSmartlyConfig setClient(Client client) {
		super.setClient(client);
		return this;
	}
}
