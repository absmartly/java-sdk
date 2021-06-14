package com.absmartly.sdk;

import java.util.concurrent.ScheduledExecutorService;

import javax.annotation.Nonnull;

public class ABSmartlyConfig {
	public static ABSmartlyConfig create() {
		return new ABSmartlyConfig();
	}

	private ABSmartlyConfig() {}

	public ContextDataProvider getContextDataProvider() {
		return contextDataProvider_;
	}

	public ABSmartlyConfig setContextDataProvider(@Nonnull final ContextDataProvider contextDataProvider) {
		contextDataProvider_ = contextDataProvider;
		return this;
	}

	public ContextEventHandler getContextEventHandler() {
		return contextEventHandler_;
	}

	public ABSmartlyConfig setContextEventHandler(@Nonnull final ContextEventHandler contextEventHandler) {
		contextEventHandler_ = contextEventHandler;
		return this;
	}

	public VariableParser getVariableParser() {
		return variableParser_;
	}

	public ABSmartlyConfig setVariableParser(@Nonnull final VariableParser variableParser) {
		variableParser_ = variableParser;
		return this;
	}

	public ScheduledExecutorService getScheduler() {
		return scheduler_;
	}

	public ABSmartlyConfig setScheduler(@Nonnull final ScheduledExecutorService scheduler) {
		scheduler_ = scheduler;
		return this;
	}

	public Client getClient() {
		return client_;
	}

	public ABSmartlyConfig setClient(Client client) {
		client_ = client;
		return this;
	}

	private ContextDataProvider contextDataProvider_;
	private ContextEventHandler contextEventHandler_;
	private VariableParser variableParser_;
	private ScheduledExecutorService scheduler_;
	private Client client_;
}
