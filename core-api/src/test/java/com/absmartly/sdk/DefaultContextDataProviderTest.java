package com.absmartly.sdk;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.concurrent.ExecutionException;
import java8.util.concurrent.CompletableFuture;
import java8.util.concurrent.CompletionException;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.absmartly.sdk.json.ContextData;

class DefaultContextDataProviderTest extends TestUtils {
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
		final CompletableFuture<ContextData> failedFuture = failedFuture(failure);
		when(client.getContextData()).thenReturn(failedFuture);

		final CompletableFuture<ContextData> dataFuture = provider.getContextData();
		final CompletionException actual = assertThrows(CompletionException.class, dataFuture::join);
		assertSame(actual.getCause(), failure);

		verify(client, Mockito.timeout(5000).times(1)).getContextData();
	}

	@Test
	void getContextDataWithEmptyExperiments() throws ExecutionException, InterruptedException {
		final Client client = mock(Client.class);
		final ContextDataProvider provider = new DefaultContextDataProvider(client);

		final ContextData emptyData = new ContextData();
		emptyData.experiments = new com.absmartly.sdk.json.Experiment[0];
		when(client.getContextData()).thenReturn(CompletableFuture.completedFuture(emptyData));

		final CompletableFuture<ContextData> dataFuture = provider.getContextData();
		final ContextData actual = dataFuture.get();

		assertNotNull(actual);
		assertNotNull(actual.experiments);
		assertEquals(0, actual.experiments.length);
	}

	@Test
	void getContextDataMultipleCalls() throws ExecutionException, InterruptedException {
		final Client client = mock(Client.class);
		final ContextDataProvider provider = new DefaultContextDataProvider(client);

		final ContextData firstData = new ContextData();
		final ContextData secondData = new ContextData();
		when(client.getContextData())
				.thenReturn(CompletableFuture.completedFuture(firstData))
				.thenReturn(CompletableFuture.completedFuture(secondData));

		final CompletableFuture<ContextData> firstFuture = provider.getContextData();
		final ContextData firstActual = firstFuture.get();
		assertSame(firstData, firstActual);

		final CompletableFuture<ContextData> secondFuture = provider.getContextData();
		final ContextData secondActual = secondFuture.get();
		assertSame(secondData, secondActual);

		verify(client, Mockito.timeout(5000).times(2)).getContextData();
	}

	@Test
	void getContextDataWithTimeoutException() {
		final Client client = mock(Client.class);
		final ContextDataProvider provider = new DefaultContextDataProvider(client);

		final java.util.concurrent.TimeoutException timeoutException =
				new java.util.concurrent.TimeoutException("Request timed out");
		final CompletableFuture<ContextData> failedFuture = failedFuture(timeoutException);
		when(client.getContextData()).thenReturn(failedFuture);

		final CompletableFuture<ContextData> dataFuture = provider.getContextData();
		final CompletionException actual = assertThrows(CompletionException.class, dataFuture::join);
		assertTrue(actual.getCause() instanceof java.util.concurrent.TimeoutException);

		verify(client, Mockito.timeout(5000).times(1)).getContextData();
	}
}
