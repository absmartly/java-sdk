package com.absmartly.sdk;

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java8.util.concurrent.CompletableFuture;

import javax.annotation.Nonnull;

import com.absmartly.sdk.java.time.Clock;
import com.absmartly.sdk.json.ContextData;

public class ABSmartly implements Closeable {
	public static ABSmartly create(@Nonnull ABSmartlyConfig config) {
		return new ABSmartly(config);
	}

	public static Builder builder() {
		return new Builder();
	}

	public static class Builder {
		private String endpoint;
		private String apiKey;
		private String application;
		private String environment;
		private ContextEventLogger eventLogger;

		Builder() {}

		public Builder endpoint(@Nonnull String endpoint) {
			this.endpoint = endpoint;
			return this;
		}

		public Builder apiKey(@Nonnull String apiKey) {
			this.apiKey = apiKey;
			return this;
		}

		public Builder application(@Nonnull String application) {
			this.application = application;
			return this;
		}

		public Builder environment(@Nonnull String environment) {
			this.environment = environment;
			return this;
		}

		public Builder eventLogger(@Nonnull ContextEventLogger eventLogger) {
			this.eventLogger = eventLogger;
			return this;
		}

		public ABSmartly build() {
			if (endpoint == null)
				throw new IllegalArgumentException("endpoint is required");
			if (apiKey == null)
				throw new IllegalArgumentException("apiKey is required");
			if (application == null)
				throw new IllegalArgumentException("application is required");
			if (environment == null)
				throw new IllegalArgumentException("environment is required");

			final ClientConfig clientConfig = ClientConfig.create()
					.setEndpoint(endpoint)
					.setAPIKey(apiKey)
					.setApplication(application)
					.setEnvironment(environment);

			final ABSmartlyConfig config = ABSmartlyConfig.create()
					.setClient(Client.create(clientConfig));

			if (eventLogger != null) {
				config.setContextEventLogger(eventLogger);
			}

			return create(config);
		}
	}

	private ABSmartly(@Nonnull ABSmartlyConfig config) {
		contextDataProvider_ = config.getContextDataProvider();
		contextEventHandler_ = config.getContextEventHandler();
		contextEventLogger_ = config.getContextEventLogger();
		variableParser_ = config.getVariableParser();
		audienceDeserializer_ = config.getAudienceDeserializer();
		scheduler_ = config.getScheduler();
		ownsScheduler_ = scheduler_ == null;

		if ((contextDataProvider_ == null) || (contextEventHandler_ == null)) {
			client_ = config.getClient();
			if (client_ == null) {
				throw new IllegalArgumentException("Missing Client instance");
			}

			if (contextDataProvider_ == null) {
				contextDataProvider_ = new DefaultContextDataProvider(client_);
			}

			if (contextEventHandler_ == null) {
				contextEventHandler_ = new DefaultContextEventHandler(client_);
			}
		}

		if (variableParser_ == null) {
			variableParser_ = new DefaultVariableParser();
		}

		if (audienceDeserializer_ == null) {
			audienceDeserializer_ = new DefaultAudienceDeserializer();
		}

		if (scheduler_ == null) {
			scheduler_ = new ScheduledThreadPoolExecutor(1);
		}
	}

	public Context createContext(@Nonnull ContextConfig config) {
		checkNotClosed();
		return Context.create(Clock.systemUTC(), config, scheduler_, contextDataProvider_.getContextData(),
				contextDataProvider_, contextEventHandler_, contextEventLogger_, variableParser_,
				new AudienceMatcher(audienceDeserializer_));
	}

	public Context createContextWith(@Nonnull ContextConfig config, ContextData data) {
		checkNotClosed();
		return Context.create(Clock.systemUTC(), config, scheduler_, CompletableFuture.completedFuture(data),
				contextDataProvider_, contextEventHandler_, contextEventLogger_, variableParser_,
				new AudienceMatcher(audienceDeserializer_));
	}

	public CompletableFuture<ContextData> getContextData() {
		checkNotClosed();
		return contextDataProvider_.getContextData();
	}

	private void checkNotClosed() {
		if (closed_) {
			throw new IllegalStateException("ABSmartly instance is closed");
		}
	}

	@Override
	public void close() throws IOException {
		if (closed_) {
			return;
		}
		closed_ = true;

		try {
			if (client_ != null) {
				client_.close();
			}
		} finally {
			// A caller-supplied scheduler remains under caller ownership and must be left running.
			if ((scheduler_ != null) && ownsScheduler_) {
				scheduler_.shutdown();
				try {
					if (!scheduler_.awaitTermination(5000, TimeUnit.MILLISECONDS)) {
						scheduler_.shutdownNow();
					}
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					scheduler_.shutdownNow();
				}
			}
		}
	}

	private volatile boolean closed_;
	private Client client_;
	private ContextDataProvider contextDataProvider_;
	private ContextEventHandler contextEventHandler_;
	private ContextEventLogger contextEventLogger_;
	private VariableParser variableParser_;

	private AudienceDeserializer audienceDeserializer_;
	private ScheduledExecutorService scheduler_;
	private final boolean ownsScheduler_;
}
