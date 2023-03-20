package com.absmartly.sdk;

import java.util.List;
import java8.util.concurrent.CompletableFuture;
import java8.util.function.Supplier;

import javax.annotation.Nonnull;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.geckotechnology.simpleCircuitBreaker.BreakerStateEventListener;
import com.geckotechnology.simpleCircuitBreaker.BreakerStateType;
import com.geckotechnology.simpleCircuitBreaker.CircuitBreakerStateChangeEvent;

import com.absmartly.sdk.cache.LocalCache;
import com.absmartly.sdk.circuitbreaker.CircuitBreakerHelper;
import com.absmartly.sdk.java.time.Clock;
import com.absmartly.sdk.json.PublishEvent;

public class ResilientContextEventHandler extends DefaultContextEventHandler {
	private static final Logger log = LoggerFactory.getLogger(ResilientContextEventHandler.class);

	public ResilientContextEventHandler(@Nonnull final Client client, @Nonnull ResilienceConfig resilienceConfig) {
		super(client);
		this.localCache = resilienceConfig.getLocalCache();
		this.circuitBreakerHelper = new CircuitBreakerHelper(resilienceConfig,
				new BreakerStateEventListener() {
					@Override
					public void onCircuitBreakerStateChangeEvent(CircuitBreakerStateChangeEvent event) {
						ResilientContextEventHandler.this.onCircuitStateEventChange(event);
					}
				});
	}

	@Override
	public CompletableFuture<Void> publish(final Context context, @Nonnull final PublishEvent event) {
		CompletableFuture<Void> decoratedSupplier = this.circuitBreakerHelper
				.decorateCompletionFuture(new Supplier<CompletableFuture<Void>>() {
					@Override
					public CompletableFuture<Void> get() {
						return ResilientContextEventHandler.this.client_.publish(event);
					}
				});
		if (decoratedSupplier.isCompletedExceptionally()) {
			localCache.writeEvent(event);
		}
		return decoratedSupplier;
	}

	public void flushCache() {
		List<PublishEvent> events = localCache.retrieveEvents();
		System.out.println("Sending events in cache: " + events.size());
		for (PublishEvent event : events) {
			event.publishedAt = Clock.systemUTC().millis();
			this.publish(null, event);
		}
	}

	private void onCircuitStateEventChange(CircuitBreakerStateChangeEvent event) {
		System.out.println(event);
		if (event.getNewBreakerStateType().equals(BreakerStateType.CLOSED)) {
			this.flushCache();
		}
	}

	private LocalCache localCache;
	private CircuitBreakerHelper circuitBreakerHelper;
}
