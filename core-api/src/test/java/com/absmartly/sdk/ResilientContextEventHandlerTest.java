package com.absmartly.sdk;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java8.util.concurrent.CompletableFuture;
import java8.util.concurrent.CompletionException;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.absmartly.sdk.cache.LocalCache;
import com.absmartly.sdk.json.PublishEvent;

class ResilientContextEventHandlerTest extends TestUtils {

	@Test
	void publishExceptionallyWithResilience() {
		final Context context = mock(Context.class);
		final Client client = mock(Client.class);
		final LocalCache localCache = mock(LocalCache.class);
		final ContextEventHandler eventHandler = new ResilientContextEventHandler(client,
				ResilienceConfig.create(localCache));

		final PublishEvent event = new PublishEvent();
		final Exception failure = new RuntimeException("FAILED");
		final CompletableFuture<Void> failedFuture = failedFuture(failure);
		when(client.publish(event)).thenAnswer(invocation -> {
			try {
				Thread.sleep((int) (Math.random() * 15));
			} catch (InterruptedException e) {
				throw new RuntimeException(e);
			}
			return failedFuture;
		});

		for (int i = 0; i < 300; i++) {
			CompletableFuture<Void> publishFuture = eventHandler.publish(context, event);
			CompletionException actual = assertThrows(CompletionException.class, publishFuture::join);
			System.out.println(actual.getCause().getMessage());
			assertTrue(actual.getCause().getMessage().contains(failure.getMessage())
					|| actual.getCause().getMessage().contains("is opened"));
		}
	}

	@Test
	void shouldCallFlushCacheWhenStart() throws ExecutionException, InterruptedException {
		final Context context = mock(Context.class);
		final Client client = mock(Client.class);
		final LocalCache localCache = spy(mock(LocalCache.class));
		final ContextEventHandler eventHandler = new ResilientContextEventHandler(client,
				ResilienceConfig.create(localCache));
		final ContextEventHandler eventHandlerSpied = spy(eventHandler);

		PublishEvent cacheEvent = new PublishEvent();
		cacheEvent.publishedAt = 123L;
		when(localCache.retrievePublishEvents()).thenReturn(Arrays.asList(cacheEvent));

		verify(localCache, times(1)).retrievePublishEvents();
	}

	@Test
	void shouldCallFlushCacheWhenCircuitChangeToClosed() throws ExecutionException, InterruptedException {
		final Context context = mock(Context.class);
		final Client client = mock(Client.class);
		final LocalCache localCache = mock(LocalCache.class);

		final ContextEventHandler eventHandler = new ResilientContextEventHandler(client,
				ResilienceConfig.create(localCache)
						.setFailureRateThreshold(20)
						.setBackoffPeriodInMilliseconds(2000));
		final ContextEventHandler eventHandlerSpied = spy(eventHandler);

		final AtomicInteger i = new AtomicInteger(0);
		final AtomicInteger errorCount = new AtomicInteger(0);
		final AtomicInteger recoveryCount = new AtomicInteger(0);
		final AtomicInteger successCount = new AtomicInteger(0);

		final ArgumentCaptor<PublishEvent> eventArgumentCaptor = ArgumentCaptor
				.forClass(PublishEvent.class);

		PublishEvent cacheEvent = new PublishEvent();
		cacheEvent.publishedAt = 123L;
		when(localCache.retrievePublishEvents()).thenReturn(Arrays.asList(cacheEvent));

		final PublishEvent event = new PublishEvent();
		ArrayList<PublishEvent> events = new ArrayList<>();

		when(client.publish(any())).then(invocation -> {
			CompletableFuture<Void> future = new CompletableFuture<>();

			if (i.get() >= 80 && i.get() <= 140) {
				errorCount.incrementAndGet();
				future.completeExceptionally(new RuntimeException("BAM!"));
			} else {
				try {
					Thread.sleep((int) (Math.random() * 50));
				} catch (InterruptedException e) {
					throw new RuntimeException(e);
				}
				successCount.incrementAndGet();
				future.complete(null);
			}
			return future;
		});

		while (i.incrementAndGet() <= 300) {
			PublishEvent pub = new PublishEvent();
			pub.publishedAt = Long.valueOf(i.get());
			CompletableFuture<Void> publishFuture = eventHandlerSpied.publish(context, pub);
			publishFuture.whenComplete((unused, throwable) -> {
				if (throwable != null) {
					try {
						Thread.sleep((int) (Math.random() * 200));
					} catch (InterruptedException e) {}
				}
			});
		}

		verify(localCache, atLeast(1)).writePublishEvent(eventArgumentCaptor.capture());
		List<PublishEvent> cachedEvents = eventArgumentCaptor.getAllValues();

		System.out.println("Cached Events: " + cachedEvents.size());
		System.out.println("Success: " + successCount.get());
		System.out.println("Error in client.publish: " + errorCount.get());
		System.out.println("Error in open circuit: " + (300 - successCount.get() - errorCount.get()));

		assertEquals(301, successCount.get() + cachedEvents.size());
		assertTrue(errorCount.get() > 0);

		verify(localCache, atLeast(1)).retrievePublishEvents();

	}
}
