package com.absmartly.sdk;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.util.concurrent.ScheduledExecutorService;
import java8.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import com.absmartly.sdk.json.ContextData;

class ABsmartlyFixTest extends TestUtils {
	Client client;

	@BeforeEach
	void setUp() {
		client = mock(Client.class);
	}

	@Test
	void createContextThrowsAfterClose() throws IOException {
		final ABsmartlyConfig config = ABsmartlyConfig.create()
				.setClient(client);

		final ABsmartly absmartly = ABsmartly.create(config);
		absmartly.close();

		assertThrows(IllegalStateException.class, () -> {
			absmartly.createContext(ContextConfig.create().setUnit("user_id", "123"));
		});
	}

	@Test
	void createContextWithThrowsAfterClose() throws IOException {
		final ABsmartlyConfig config = ABsmartlyConfig.create()
				.setClient(client);

		final ABsmartly absmartly = ABsmartly.create(config);
		absmartly.close();

		assertThrows(IllegalStateException.class, () -> {
			absmartly.createContextWith(ContextConfig.create().setUnit("user_id", "123"), new ContextData());
		});
	}

	@Test
	void getContextDataThrowsAfterClose() throws IOException {
		final ContextDataProvider dataProvider = mock(ContextDataProvider.class);
		when(dataProvider.getContextData()).thenReturn(mock(CompletableFuture.class));

		final ABsmartlyConfig config = ABsmartlyConfig.create()
				.setClient(client)
				.setContextDataProvider(dataProvider);

		final ABsmartly absmartly = ABsmartly.create(config);
		absmartly.close();

		assertThrows(IllegalStateException.class, absmartly::getContextData);
	}

	@Test
	void closeIsIdempotent() throws IOException {
		final ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);

		final ABsmartlyConfig config = ABsmartlyConfig.create()
				.setClient(client)
				.setScheduler(scheduler);

		final ABsmartly absmartly = ABsmartly.create(config);
		absmartly.close();
		absmartly.close();

		verify(client, times(1)).close();
	}
}
