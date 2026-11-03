package com.absmartly.sdk;

import java.util.Properties;
import java.util.concurrent.Executor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

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
			applicationVersion_ = normalizeApplicationVersion(applicationVersion);
		}
		return this;
	}

	// Null, empty or whitespace-only means no version is set.
	private static String normalizeApplicationVersion(final String applicationVersion) {
		if (applicationVersion == null) {
			return null;
		}
		final String trimmed = applicationVersion.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}

	/**
	 * Whether an application version counts as set: not null and not "0". "0" is what the X-Application-Version header
	 * carries when no version is configured, so it is treated as unset for the app_version system attribute.
	 */
	static boolean isApplicationVersionSet(final String applicationVersion) {
		return applicationVersion != null && !applicationVersion.trim().isEmpty() && !"0".equals(applicationVersion);
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
	 * Sets the application version, sent as the X-Application-Version header when it is a whole number (the collector
	 * rejects anything else, so other versions are sent as "0") and, when system attributes are enabled, in full as the
	 * app_version attribute, for example a semantic version such as "2.4.1". Surrounding whitespace is removed;
	 * a null, empty or whitespace-only value means no version is set, which is sent as "0". A version of "0" is
	 * treated as unset, so app_version is then left out.
	 */
	public ClientConfig setApplicationVersion(@Nullable final String applicationVersion) {
		applicationVersion_ = normalizeApplicationVersion(applicationVersion);
		return this;
	}

	/**
	 * Sets a numeric application version. 0 means no version is set: it is sent as "0" and app_version is left out.
	 */
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
