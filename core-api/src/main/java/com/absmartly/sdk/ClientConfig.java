package com.absmartly.sdk;

import javax.annotation.Nonnull;
import java.util.concurrent.Executor;

public class ClientConfig {
	public static ClientConfig create() {
		return new ClientConfig();
	}

	ClientConfig() {}

	public String getEndpoint() {
		return endpoint_;
	}

	public ClientConfig setEndpoint(@Nonnull final String endpoint) {
		endpoint_ = endpoint;
		return this;
	}

	public String getAPIKey() {
		return apiKey_;
	}

	public ClientConfig setAPIKey(@Nonnull final String apiKey) {
		apiKey_ = apiKey;
		return this;
	}

	public String getEnvironment() {
		return environment_;
	}

	public ClientConfig setEnvironment(@Nonnull final String environment) {
		environment_ = environment;
		return this;
	}

	public String getApplication() {
		return application_;
	}

	public ClientConfig setApplication(@Nonnull final String application) {
		application_ = application;
		return this;
	}

	public ContextDataDeserializer getContextDataDeserializer() {
		return deserializer_;
	}

	public ClientConfig setContextDataDeserializer(@Nonnull final ContextDataDeserializer deserializer) {
		deserializer_ = deserializer;
		return this;
	}

	public ContextEventSerializer getContextEventSerializer() {
		return serializer_;
	}

	public ClientConfig setContextEventSerializer(@Nonnull final ContextEventSerializer serializer) {
		serializer_ = serializer;
		return this;
	}

	Executor getExecutor() {
		return executor_;
	}

	public ClientConfig setExecutor(@Nonnull final Executor executor) {
		executor_ = executor;
		return this;
	}

	private String endpoint_;
	private String apiKey_;
	private String environment_;
	private String application_;
	private ContextDataDeserializer deserializer_;
	private ContextEventSerializer serializer_;
	private Executor executor_;
}
