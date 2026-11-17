package com.absmartly.sdk;

import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.jspecify.annotations.Nullable;

import com.absmartly.sdk.event.ContextEvent;
import com.absmartly.sdk.event.EventListener;
import com.absmartly.sdk.event.Subscription;
import com.absmartly.sdk.internal.AsyncExecutor;
import com.absmartly.sdk.internal.Clock;
import com.absmartly.sdk.internal.Futures;
import com.absmartly.sdk.internal.json.ContextPayload;

/**
 * The ABsmartly SDK entry point: holds the collector connection and creates {@link Context}s. Create one per
 * application and share it; it is thread-safe. Close it on shutdown to release its HTTP client and scheduler.
 *
 * <pre>{@code
 * ABsmartlyClient client = ABsmartlyClient.builder()
 * 		.endpoint(URI.create("https://acme.absmartly.io/v1"))
 * 		.apiKey(apiKey)
 * 		.application("website")
 * 		.environment("production")
 * 		.build();
 *
 * try (Context context = client.context(ContextOptions.builder().unit("session_id", sessionId).build())) {
 * 	int variant = context.treatment("exp_checkout");
 * }
 * }</pre>
 */
public final class ABsmartlyClient implements AutoCloseable {
	private static final String JDK_TRANSPORT = "com.absmartly.sdk.JdkHttpTransport";

	public static Builder builder() {
		return new Builder();
	}

	private ABsmartlyClient(final Builder builder) {
		if (builder.endpoint_ == null) {
			throw new IllegalArgumentException("Missing Endpoint configuration");
		}
		if (builder.apiKey_ == null) {
			throw new IllegalArgumentException("Missing APIKey configuration");
		}
		if (builder.application_ == null) {
			throw new IllegalArgumentException("Missing Application configuration");
		}
		if (builder.environment_ == null) {
			throw new IllegalArgumentException("Missing Environment configuration");
		}

		final Transport transport = (builder.transport_ != null) ? builder.transport_ : defaultTransport();
		ownsTransport_ = builder.transport_ == null;
		try {
			client_ = new Client(builder.endpoint_.toString(), builder.apiKey_, builder.application_,
					builder.environment_, builder.applicationVersion_, transport, builder.executor_);
		} catch (final RuntimeException e) {
			if (ownsTransport_) {
				transport.close();
			}
			throw e;
		}
		clock_ = builder.clock_;
		scheduler_ = (builder.scheduler_ != null) ? builder.scheduler_ : newScheduler();
		ownsScheduler_ = builder.scheduler_ == null;
		events_ = new EventDispatcher(null, clock_,
				(builder.executor_ != null) ? builder.executor_ : AsyncExecutor.get());
		variableParser_ = new DefaultVariableParser();
		audienceDeserializer_ = new DefaultAudienceDeserializer();
		dataProvider_ = (builder.dataProvider_ != null) ? builder.dataProvider_ : client_;
		eventHandler_ = (builder.eventHandler_ != null) ? builder.eventHandler_ : client_;
	}

	// JdkHttpTransport lives in a separate source set that Android never loads; it is only resolved here, by name, when
	// the caller supplied no transport.
	private static Transport defaultTransport() {
		try {
			final Class<?> type = Class.forName(JDK_TRANSPORT);
			return (Transport) type.getMethod("create").invoke(null);
		} catch (final ReflectiveOperationException | LinkageError e) {
			throw new IllegalStateException("The default java.net.http transport is not available on this platform "
					+ "(Android has no java.net.http): set one with ABsmartlyClient.Builder.transport(...)", e);
		}
	}

	private static ScheduledExecutorService newScheduler() {
		final AtomicInteger count = new AtomicInteger();
		final ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1, runnable -> {
			final Thread thread = new Thread(runnable, "absmartly-sdk-scheduler-" + count.incrementAndGet());
			thread.setDaemon(true);
			return thread;
		});
		scheduler.setRemoveOnCancelPolicy(true);
		return scheduler;
	}

	/**
	 * Creates a context and blocks until its data is loaded. Throws {@link ContextLoadException} if the data cannot be
	 * fetched.
	 */
	public Context context(final ContextOptions options) {
		return newContext(options).ready();
	}

	/** Creates a context and completes once its data is loaded, or fails with {@link ContextLoadException}. */
	public CompletableFuture<Context> contextAsync(final ContextOptions options) {
		return newContext(options).readyAsync();
	}

	/**
	 * Creates a context that is ready immediately, from data fetched earlier with {@link #contextData()} (or
	 * {@link Context#data()}): no request is made.
	 */
	public Context context(final ContextOptions options, final ContextData data) {
		checkNotClosed();
		return create(options, Futures.completed(data.payload()));
	}

	/**
	 * Creates a context and starts loading its data without waiting: {@link Context#state()} is
	 * {@link ContextState#LOADING} until it loads. Use {@link Context#ready()} or {@link Context#readyAsync()} to wait.
	 */
	public Context newContext(final ContextOptions options) {
		checkNotClosed();
		return create(options, dataProvider_.getContextData());
	}

	private Context create(final ContextOptions options, final CompletableFuture<ContextPayload> data) {
		return new Context(clock_, options, scheduler_, data, dataProvider_, eventHandler_, null, variableParser_,
				new AudienceMatcher(audienceDeserializer_), client_, events_);
	}

	/** Fetches the experiment data once, to create many contexts with {@link #context(ContextOptions, ContextData)}. */
	public CompletableFuture<ContextData> contextDataAsync() {
		checkNotClosed();
		return dataProvider_.getContextData().thenApply(ContextData::new);
	}

	/** The blocking form of {@link #contextDataAsync()}. */
	public ContextData contextData() {
		return Context.join(contextDataAsync());
	}

	/** Adds a listener for the events of every context this client creates. */
	public Subscription onEvent(final EventListener listener) {
		return events_.addListener(listener);
	}

	/**
	 * The events of every context this client creates, as a {@link Flow.Publisher}, delivered asynchronously and
	 * completed when the client closes. Needs Android API 30+; use {@link #onEvent(EventListener)} below that.
	 */
	public Flow.Publisher<ContextEvent> events() {
		return events_.stream();
	}

	/** The configured application name. */
	public String application() {
		return client_.getApplication();
	}

	/** The configured environment name. */
	public String environment() {
		final String environment = client_.getEnvironment();
		return (environment != null) ? environment : "";
	}

	/**
	 * Closes the transport (when the client created it) and the scheduler (when the client created it). Open contexts
	 * are not closed: close them first so their pending events are published. Idempotent.
	 */
	@Override
	public void close() {
		synchronized (this) {
			if (closed_) {
				return;
			}
			closed_ = true;
		}

		try {
			events_.closeStream();
			if (ownsTransport_) {
				client_.close();
			}
		} finally {
			// A caller-supplied scheduler remains under caller ownership and must be left running.
			if (ownsScheduler_) {
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

	private void checkNotClosed() {
		if (closed_) {
			throw new ContextClosedException("ABsmartlyClient is closed");
		}
	}

	/** Builds an {@link ABsmartlyClient}. Endpoint, API key, application and environment are required. */
	public static final class Builder {
		Builder() {}

		/** The collector endpoint, for example {@code https://acme.absmartly.io/v1}. Must be http or https. */
		public Builder endpoint(final URI endpoint) {
			endpoint_ = endpoint;
			return this;
		}

		/** The SDK API key. */
		public Builder apiKey(final String apiKey) {
			apiKey_ = apiKey;
			return this;
		}

		/** The application name, as configured in ABsmartly. */
		public Builder application(final String application) {
			application_ = application;
			return this;
		}

		/** The environment name, as configured in ABsmartly. */
		public Builder environment(final String environment) {
			environment_ = environment;
			return this;
		}

		/**
		 * The application version, sent as the X-Application-Version header when it is a whole number of at most 18
		 * digits (otherwise "0", which the collector treats as unset).
		 */
		public Builder applicationVersion(final String applicationVersion) {
			applicationVersion_ = applicationVersion;
			return this;
		}

		/** A numeric application version; 0 means unset. */
		public Builder applicationVersion(final long applicationVersion) {
			applicationVersion_ = Long.toString(applicationVersion);
			return this;
		}

		/**
		 * The HTTP transport. Defaults to {@link JdkHttpTransport} ({@code java.net.http}). Required on Android. A
		 * supplied transport is closed by {@link ABsmartlyClient#close()} only if the client created it, so this one
		 * stays under your ownership.
		 */
		public Builder transport(final Transport transport) {
			transport_ = transport;
			return this;
		}

		/** The executor for the SDK's asynchronous work (decoding, encoding, event streams). Defaults to a daemon pool. */
		public Builder executor(final Executor executor) {
			executor_ = executor;
			return this;
		}

		/**
		 * The scheduler for automatic publishing and refreshing. Defaults to a single daemon thread that the client shuts
		 * down on close; a supplied scheduler is left running.
		 */
		public Builder scheduler(final ScheduledExecutorService scheduler) {
			scheduler_ = scheduler;
			return this;
		}

		// Test seams: a fixed clock, and a data source and publisher that bypass the collector.
		Builder clock(final Clock clock) {
			clock_ = clock;
			return this;
		}

		Builder dataProvider(final ContextDataProvider dataProvider) {
			dataProvider_ = dataProvider;
			return this;
		}

		Builder eventHandler(final ContextEventHandler eventHandler) {
			eventHandler_ = eventHandler;
			return this;
		}

		public ABsmartlyClient build() {
			return new ABsmartlyClient(this);
		}

		private @Nullable URI endpoint_;
		private @Nullable String apiKey_;
		private @Nullable String application_;
		private @Nullable String environment_;
		private @Nullable String applicationVersion_;
		private @Nullable Transport transport_;
		private @Nullable Executor executor_;
		private @Nullable ScheduledExecutorService scheduler_;
		private Clock clock_ = Clock.systemUTC();
		private @Nullable ContextDataProvider dataProvider_;
		private @Nullable ContextEventHandler eventHandler_;
	}

	private final Client client_;
	private final boolean ownsTransport_;
	private final Clock clock_;
	private final ScheduledExecutorService scheduler_;
	private final boolean ownsScheduler_;
	private final EventDispatcher events_;
	private final VariableParser variableParser_;
	private final AudienceDeserializer audienceDeserializer_;
	private final ContextDataProvider dataProvider_;
	private final ContextEventHandler eventHandler_;
	private volatile boolean closed_;
}
