package com.absmartly.sdk;

import java.io.Closeable;
import java.io.IOException;
import java.time.Clock;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nonnull;

import com.absmartly.sdk.json.ContextData;

public class ABSmartly implements Closeable {
	public static ABSmartly create(@Nonnull ABSmartlyConfig config) {
		return new ABSmartly(config);
	}

	private ABSmartly(@Nonnull ABSmartlyConfig config) {
		contextDataProvider_ = config.getContextDataProvider();
		contextEventHandler_ = config.getContextEventHandler();
		variableParser_ = config.getVariableParser();
		scheduler_ = config.getScheduler();

		final String endpoint = config.getEndpoint();
		if ((endpoint == null) || endpoint.isEmpty()) {
			throw new IllegalArgumentException("Missing Endpoint configuration");
		}

		final String apiKey = config.getAPIKey();
		if ((apiKey == null) || apiKey.isEmpty()) {
			throw new IllegalArgumentException("Missing APIKey configuration");
		}

		final String application = config.getApplication();
		if ((application == null) || application.isEmpty()) {
			throw new IllegalArgumentException("Missing Application configuration");
		}

		final String environment = config.getEnvironment();
		if ((environment == null) || environment.isEmpty()) {
			throw new IllegalArgumentException("Missing Environment configuration");
		}

		if ((contextDataProvider_ == null) || (contextEventHandler_ == null)) {
			httpClient_ = new DefaultHTTPClient(DefaultHTTPClientConfig.create());

			if (contextDataProvider_ == null) {
				contextDataProvider_ = new DefaultContextDataProvider(endpoint, httpClient_,
						new DefaultContextDataDeserializer());
			}

			if (contextEventHandler_ == null) {
				contextEventHandler_ = new DefaultContextEventHandler(endpoint, apiKey, application, environment,
						httpClient_, new DefaultContextEventSerializer());
			}
		}

		if (variableParser_ == null) {
			variableParser_ = new DefaultVariableParser();
		}

		if (scheduler_ == null) {
			scheduler_ = new ScheduledThreadPoolExecutor(0);
		}
	}

	public Context createContext(@Nonnull ContextConfig config) {
		return Context.create(Clock.systemUTC(), config, scheduler_, contextDataProvider_.getContextData(),
				contextDataProvider_, contextEventHandler_, variableParser_);
	}

	public Context createContextWith(@Nonnull ContextConfig config, ContextData data) {
		return Context.create(Clock.systemUTC(), config, scheduler_, CompletableFuture.completedFuture(data),
				contextDataProvider_, contextEventHandler_, variableParser_);
	}

	public CompletableFuture<ContextData> getContextData() {
		return contextDataProvider_.getContextData();
	}

	@Override
	public void close() throws IOException {
		if (httpClient_ != null) {
			httpClient_.close();
			httpClient_ = null;
		}

		if (scheduler_ != null) {
			try {
				scheduler_.awaitTermination(5_000, TimeUnit.MILLISECONDS);
			} catch (InterruptedException ignored) {}
			scheduler_ = null;
		}
	}

	private HTTPClient httpClient_;
	private ContextDataProvider contextDataProvider_;
	private ContextEventHandler contextEventHandler_;
	private VariableParser variableParser_;
	private ScheduledExecutorService scheduler_;
	private DefaultHTTPClientConfig clientConfig_;
}
