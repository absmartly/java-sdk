package com.absmartly.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.concurrent.ExecutionException;
import java8.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.absmartly.sdk.cache.MemoryCache;
import com.absmartly.sdk.json.ContextData;

class ResilientContextDataProviderTest extends TestUtils {
	@Test
	void getContextData() throws ExecutionException, InterruptedException {
		final Client client = mock(Client.class);
		final ContextDataProvider provider = new ResilientContextDataProvider(client, new MemoryCache());

		final ContextData expected = new ContextData();
		when(client.getContextData()).thenReturn(CompletableFuture.completedFuture(expected));

		final CompletableFuture<ContextData> dataFuture = provider.getContextData();
		final ContextData actual = dataFuture.get();

		assertEquals(expected, actual);
		assertSame(expected, actual);
	}

	@Test
	void getContextDataExceptionally() throws ExecutionException, InterruptedException {
		final Client client = mock(Client.class);
		final ContextDataProvider provider = new ResilientContextDataProvider(client, null);

		final ContextData expected = new ContextData();
		when(client.getContextData()).thenReturn(CompletableFuture.completedFuture(expected));

		final CompletableFuture<ContextData> dataFuture = provider.getContextData();
		final ContextData actual = dataFuture.get();
		assertSame(expected, actual);

		Mockito.reset(client);

		final Exception failure = new Exception("FAILED");
		final CompletableFuture<ContextData> failedFuture = failedFuture(failure);
		when(client.getContextData()).thenReturn(failedFuture);

		final CompletableFuture<ContextData> dataFuture2 = provider.getContextData();
		final ContextData actual2 = dataFuture.get();
		assertSame(expected, actual2);
	}
}
