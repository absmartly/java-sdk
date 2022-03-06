package com.absmartly.sdk;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.concurrent.ExecutionException;
import java8.util.concurrent.CompletableFuture;
import java8.util.concurrent.CompletionException;

import org.junit.jupiter.api.Test;

import com.absmartly.sdk.json.ContextData;

class DefaultContextDataProviderTest {
	@Test
	void getContextData() throws ExecutionException, InterruptedException {
		final Client client = mock(Client.class);
		final ContextDataProvider provider = new DefaultContextDataProvider(client);

		final ContextData expected = new ContextData();
		when(client.getContextData()).thenReturn(CompletableFuture.completedFuture(expected));

		final CompletableFuture<ContextData> dataFuture = provider.getContextData();
		final ContextData actual = dataFuture.get();

		assertEquals(expected, actual);
		assertSame(expected, actual);
	}

	@Test
	void getContextDataExceptionally() {
		final Client client = mock(Client.class);
		final ContextDataProvider provider = new DefaultContextDataProvider(client);

		final Exception failure = new Exception("FAILED");
		final CompletableFuture<ContextData> failedFuture = TestUtils.failedFuture(failure);
		when(client.getContextData()).thenReturn(failedFuture);

		final CompletableFuture<ContextData> dataFuture = provider.getContextData();
		final CompletionException actual = assertThrows(CompletionException.class, dataFuture::join);
		assertSame(actual.getCause(), failure);

		verify(client, times(1)).getContextData();
	}
}
