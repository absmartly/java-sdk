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

public class ABsmartly implements Closeable {
	public static ABsmartly create(@Nonnull ABsmartlyConfig config) {
		return new ABsmartly(config);
	}

	public static ABsmartly create(@Nonnull String endpoint, @Nonnull String apiKey,
			@Nonnull String application, @Nonnull String environment) {
		final ClientConfig clientConfig = ClientConfig.create()
				.setEndpoint(endpoint)
				.setAPIKey(apiKey)
				.setApplication(application)
				.setEnvironment(environment);

		final ABsmartlyConfig config = ABsmartlyConfig.create()
				.setClient(Client.create(clientConfig));

		return create(config);
	}

	protected ABsmartly(@Nonnull ABsmartlyConfig config) {
		contextDataProvider_ = config.getContextDataProvider();
		contextEventHandler_ = config.getContextEventHandler();
		contextEventLogger_ = config.getContextEventLogger();
		variableParser_ = config.getVariableParser();
		audienceDeserializer_ = config.getAudienceDeserializer();
		scheduler_ = config.getScheduler();

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
		return Context.create(Clock.systemUTC(), config, scheduler_, contextDataProvider_.getContextData(),
				contextDataProvider_, contextEventHandler_, contextEventLogger_, variableParser_,
				new AudienceMatcher(audienceDeserializer_));
	}

	public Context createContextWith(@Nonnull ContextConfig config, ContextData data) {
		return Context.create(Clock.systemUTC(), config, scheduler_, CompletableFuture.completedFuture(data),
				contextDataProvider_, contextEventHandler_, contextEventLogger_, variableParser_,
				new AudienceMatcher(audienceDeserializer_));
	}

	public CompletableFuture<ContextData> getContextData() {
		return contextDataProvider_.getContextData();
	}

	@Override
	public void close() throws IOException {
		if (client_ != null) {
			client_.close();
			client_ = null;
		}

		if (scheduler_ != null) {
			scheduler_.shutdown();
			try {
				if (!scheduler_.awaitTermination(5000, TimeUnit.MILLISECONDS)) {
					scheduler_.shutdownNow();
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				scheduler_.shutdownNow();
			}
			scheduler_ = null;
		}
	}

	private Client client_;
	private ContextDataProvider contextDataProvider_;
	private ContextEventHandler contextEventHandler_;
	private ContextEventLogger contextEventLogger_;
	private VariableParser variableParser_;

	private AudienceDeserializer audienceDeserializer_;
	private ScheduledExecutorService scheduler_;
}
