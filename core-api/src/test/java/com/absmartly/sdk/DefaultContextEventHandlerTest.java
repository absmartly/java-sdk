package com.absmartly.sdk;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.concurrent.ExecutionException;
import java8.util.concurrent.CompletableFuture;
import java8.util.concurrent.CompletionException;

import org.junit.jupiter.api.Test;

import com.absmartly.sdk.json.PublishEvent;

class DefaultContextEventHandlerTest {
	@Test
	void publish() throws ExecutionException, InterruptedException {
		final Context context = mock(Context.class);
		final Client client = mock(Client.class);
		final ContextEventHandler eventHandler = new DefaultContextEventHandler(client);

		final PublishEvent event = new PublishEvent();
		when(client.publish(event)).thenReturn(CompletableFuture.completedFuture(null));

		final CompletableFuture<Void> dataFuture = eventHandler.publish(context, event);
		final Void actual = dataFuture.get();

		assertNull(actual);
	}

	@Test
	void publishExceptionally() {
		final Context context = mock(Context.class);
		final Client client = mock(Client.class);
		final ContextEventHandler eventHandler = new DefaultContextEventHandler(client);

		final PublishEvent event = new PublishEvent();
		final Exception failure = new Exception("FAILED");
		final CompletableFuture<Void> failedFuture = TestUtils.failedFuture(failure);
		when(client.publish(event)).thenReturn(failedFuture);

		final CompletableFuture<Void> publishFuture = eventHandler.publish(context, event);
		final CompletionException actual = assertThrows(CompletionException.class, publishFuture::join);
		assertSame(actual.getCause(), failure);

		verify(client, times(1)).publish(event);
	}
}
