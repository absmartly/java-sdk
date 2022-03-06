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

	private ABSmartly(@Nonnull ABSmartlyConfig config) {
		contextDataProvider_ = config.getContextDataProvider();
		contextEventHandler_ = config.getContextEventHandler();
		variableParser_ = config.getVariableParser();
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
		if (client_ != null) {
			client_.close();
			client_ = null;
		}

		if (scheduler_ != null) {
			try {
				scheduler_.awaitTermination(5000, TimeUnit.MILLISECONDS);
			} catch (InterruptedException ignored) {}
			scheduler_ = null;
		}
	}

	private Client client_;
	private ContextDataProvider contextDataProvider_;
	private ContextEventHandler contextEventHandler_;
	private VariableParser variableParser_;
	private ScheduledExecutorService scheduler_;
}
