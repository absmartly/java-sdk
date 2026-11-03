package com.absmartly.sdk;

import java.util.Properties;
import java.util.concurrent.Executor;

import javax.annotation.Nonnull;

public class ClientConfig {
	public static ClientConfig create() {
		return new ClientConfig();
	}

	public static ClientConfig createFromProperties(Properties properties) {
		return createFromProperties(properties, "");
	}

	public static ClientConfig createFromProperties(Properties properties, final String prefix) {
		return create()
				.setEndpoint(properties.getProperty(prefix + "endpoint"))
				.setEnvironment(properties.getProperty(prefix + "environment"))
				.setApplication(properties.getProperty(prefix + "application"))
				.setAPIKey(properties.getProperty(prefix + "apikey"))
				.setApplicationVersionIfPresent(properties.getProperty(prefix + "application_version"));
	}

	ClientConfig() {}

	private ClientConfig setApplicationVersionIfPresent(final String applicationVersion) {
		if (applicationVersion != null) {
			applicationVersion_ = applicationVersion;
		}
		return this;
	}

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

	public String getApplicationVersion() {
		return applicationVersion_;
	}

	/**
	 * Sets the application version sent as the X-Application-Version header and, when system attributes are enabled,
	 * as the app_version attribute. Accepts a semantic version string (e.g. "2.4.1") or a number.
	 */
	public ClientConfig setApplicationVersion(@Nonnull final String applicationVersion) {
		applicationVersion_ = applicationVersion;
		return this;
	}

	public ClientConfig setApplicationVersion(final long applicationVersion) {
		applicationVersion_ = Long.toString(applicationVersion);
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
	private String applicationVersion_;
	private ContextDataDeserializer deserializer_;
	private ContextEventSerializer serializer_;
	private Executor executor_;
}
