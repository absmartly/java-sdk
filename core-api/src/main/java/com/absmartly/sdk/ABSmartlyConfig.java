package com.absmartly.sdk;

import java.util.concurrent.ScheduledExecutorService;

import javax.annotation.Nonnull;

public class ABSmartlyConfig {
	public static ABSmartlyConfig create() {
		return new ABSmartlyConfig();
	}

	private ABSmartlyConfig() {}

	public String getEndpoint() {
		return endpoint_;
	}

	public ABSmartlyConfig setEndpoint(@Nonnull String endpoint) {
		endpoint_ = endpoint;
		return this;
	}

	public String getAPIKey() {
		return apiKey_;
	}

	public ABSmartlyConfig setAPIKey(@Nonnull String apiKey) {
		apiKey_ = apiKey;
		return this;
	}

	public String getEnvironment() {
		return environment_;
	}

	public ABSmartlyConfig setEnvironment(@Nonnull String environment) {
		environment_ = environment;
		return this;
	}

	public String getApplication() {
		return application_;
	}

	public ABSmartlyConfig setApplication(@Nonnull String application) {
		application_ = application;
		return this;
	}

	public ContextDataProvider getContextDataProvider() {
		return contextDataProvider_;
	}

	public ABSmartlyConfig setContextDataProvider(@Nonnull ContextDataProvider contextDataProvider) {
		contextDataProvider_ = contextDataProvider;
		return this;
	}

	public ContextEventHandler getContextEventHandler() {
		return contextEventHandler_;
	}

	public ABSmartlyConfig setContextEventHandler(@Nonnull ContextEventHandler contextEventHandler) {
		contextEventHandler_ = contextEventHandler;
		return this;
	}

	public VariableParser getVariableParser() {
		return variableParser_;
	}

	public ABSmartlyConfig setVariableParser(@Nonnull VariableParser variableParser) {
		variableParser_ = variableParser;
		return this;
	}

	public ScheduledExecutorService getScheduler() {
		return scheduler_;
	}

	public ABSmartlyConfig setScheduler(@Nonnull ScheduledExecutorService scheduler) {
		scheduler_ = scheduler;
		return this;
	}

	private String endpoint_;
	private String apiKey_;
	private String environment_;
	private String application_;

	private ContextDataProvider contextDataProvider_;
	private ContextEventHandler contextEventHandler_;
	private VariableParser variableParser_;
	private ScheduledExecutorService scheduler_;
}
