package com.absmartly.sdk;

import java.util.concurrent.ScheduledExecutorService;

import javax.annotation.Nonnull;

public class ABsmartlyConfig {
	public static ABsmartlyConfig create() {
		return new ABsmartlyConfig();
	}

	protected ABsmartlyConfig() {}

	public ContextDataProvider getContextDataProvider() {
		return contextDataProvider_;
	}

	public ABsmartlyConfig setContextDataProvider(@Nonnull final ContextDataProvider contextDataProvider) {
		contextDataProvider_ = contextDataProvider;
		return this;
	}

	public ContextEventHandler getContextEventHandler() {
		return contextEventHandler_;
	}

	public ABsmartlyConfig setContextEventHandler(@Nonnull final ContextEventHandler contextEventHandler) {
		contextEventHandler_ = contextEventHandler;
		return this;
	}

	public VariableParser getVariableParser() {
		return variableParser_;
	}

	public ABsmartlyConfig setVariableParser(@Nonnull final VariableParser variableParser) {
		variableParser_ = variableParser;
		return this;
	}

	public ScheduledExecutorService getScheduler() {
		return scheduler_;
	}

	public ABsmartlyConfig setScheduler(@Nonnull final ScheduledExecutorService scheduler) {
		scheduler_ = scheduler;
		return this;
	}

	public ContextEventLogger getContextEventLogger() {
		return contextEventLogger_;
	}

	public ABsmartlyConfig setContextEventLogger(@Nonnull final ContextEventLogger logger) {
		contextEventLogger_ = logger;
		return this;
	}

	public AudienceDeserializer getAudienceDeserializer() {
		return audienceDeserializer_;
	}

	public ABsmartlyConfig setAudienceDeserializer(@Nonnull final AudienceDeserializer audienceDeserializer) {
		audienceDeserializer_ = audienceDeserializer;
		return this;
	}

	public Client getClient() {
		return client_;
	}

	public ABsmartlyConfig setClient(Client client) {
		client_ = client;
		return this;
	}

	private ContextDataProvider contextDataProvider_;
	private ContextEventHandler contextEventHandler_;

	private ContextEventLogger contextEventLogger_;
	private VariableParser variableParser_;

	private AudienceDeserializer audienceDeserializer_;
	private ScheduledExecutorService scheduler_;
	private Client client_;
}
