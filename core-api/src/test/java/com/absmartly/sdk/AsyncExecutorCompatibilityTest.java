package com.absmartly.sdk;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.absmartly.sdk.json.ContextData;

// In core-api-java6, streamsupport's default async pool cannot initialize on JDK 24+ (TLR reflects on the
// removed Thread.inheritedAccessControlContext), so the SDK must never fall back to it.
@Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class AsyncExecutorCompatibilityTest extends TestUtils {
	static class FakeHTTPClient implements HTTPClient {
		final ExecutorService responder = Executors.newSingleThreadExecutor();
		final List<String> requests = Collections.synchronizedList(new ArrayList<String>());

		@Override
		public CompletableFuture<Response> get(String url, Map<String, String> query, Map<String, String> headers) {
			requests.add("GET " + url);
			return respond(getResourceBytes("context.json"));
		}

		@Override
		public CompletableFuture<Response> put(String url, Map<String, String> query, Map<String, String> headers,
				byte[] body) {
			requests.add("PUT " + url);
			return respond(new byte[0]);
		}

		@Override
		public CompletableFuture<Response> post(String url, Map<String, String> query, Map<String, String> headers,
				byte[] body) {
			requests.add("POST " + url);
			return respond(new byte[0]);
		}

		private CompletableFuture<Response> respond(final byte[] content) {
			final Response response = new DefaultHTTPClient.DefaultResponse(200, "OK", "application/json", content);
			return CompletableFuture.supplyAsync(() -> response, responder);
		}

		@Override
		public void close() {
			responder.shutdownNow();
		}
	}

	FakeHTTPClient httpClient;
	ABSmartly sdk;

	@BeforeEach
	void setUp() {
		httpClient = new FakeHTTPClient();
	}

	@AfterEach
	void tearDown() throws IOException {
		if (sdk != null) {
			sdk.close();
		}
	}

	private ABSmartly createSDK(ClientConfig clientConfig) {
		clientConfig.setEndpoint("https://test.absmartly.io/v1")
				.setAPIKey("test-api-key")
				.setApplication("website")
				.setEnvironment("production");
		return ABSmartly.create(ABSmartlyConfig.create().setClient(Client.create(clientConfig, httpClient)));
	}

	private Context createContext() {
		return sdk
				.createContext(
						ContextConfig.create().setUnit("session_id", "e791e240fcd3df7d238cfc285f475e8152fcc0ec"));
	}

	private void runLifecycle() {
		final Context context = createContext();
		context.waitUntilReady();
		assertTrue(context.isReady());
		assertFalse(context.isFailed());

		context.getTreatment("exp_test_ab");
		context.track("goal1", null);
		context.publish();

		context.refresh();
		assertFalse(context.isFailed());

		context.getTreatment("exp_test_abc");
		context.close();
		assertTrue(context.isClosed());
	}

	@Test
	void lifecycleWithDefaultExecutor() {
		sdk = createSDK(ClientConfig.create());

		runLifecycle();

		assertEquals(4, httpClient.requests.size());
		assertEquals("GET https://test.absmartly.io/v1/context", httpClient.requests.get(0));
		assertEquals("PUT https://test.absmartly.io/v1/context", httpClient.requests.get(1));
		assertEquals("GET https://test.absmartly.io/v1/context", httpClient.requests.get(2));
		assertEquals("PUT https://test.absmartly.io/v1/context", httpClient.requests.get(3));
	}

	@Test
	void lifecycleWithConfiguredExecutor() {
		final ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			sdk = createSDK(ClientConfig.create().setExecutor(executor));

			runLifecycle();

			assertEquals(4, httpClient.requests.size());
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void callerAsyncStagesWithoutExecutor() {
		sdk = createSDK(ClientConfig.create());
		final Context context = createContext();

		assertSame(context, context.waitUntilReadyAsync().thenApplyAsync(ctx -> ctx).join());
		assertSame(context, context.waitUntilReadyAsync().thenApplyAsync(ctx -> ctx).join());
		final ContextData data = context.getData();

		final AtomicInteger runs = new AtomicInteger();
		context.track("goal1", null);
		context.publishAsync().thenRunAsync(runs::incrementAndGet).join();
		context.publishAsync().thenRunAsync(runs::incrementAndGet).join();
		context.refreshAsync().thenRunAsync(runs::incrementAndGet).join();
		context.track("goal2", null);
		context.closeAsync().thenRunAsync(runs::incrementAndGet).join();
		context.closeAsync().thenRunAsync(runs::incrementAndGet).join();
		assertEquals(5, runs.get());

		assertNotNull(sdk.getContextData().thenApplyAsync(fetched -> fetched.experiments).join());
		final Context prefetched = sdk.createContextWith(ContextConfig.create(), data);
		assertSame(prefetched, prefetched.waitUntilReadyAsync().thenApplyAsync(ctx -> ctx).join());
	}

	@Test
	void returnedFuturesUseVariantDefaultExecutor() {
		sdk = createSDK(ClientConfig.create());
		final Context context = createContext();

		assertReturnedFuture(context.waitUntilReadyAsync());
		context.waitUntilReady();
		assertReturnedFuture(context.waitUntilReadyAsync());
		assertReturnedFuture(context.waitUntilReadyAsync().thenApply(ctx -> ctx));
		assertReturnedFuture(context.waitUntilReadyAsync().copy());
		final ContextData data = context.getData();

		context.track("goal1", null);
		assertReturnedFuture(context.publishAsync());
		assertReturnedFuture(context.publishAsync());
		assertReturnedFuture(context.refreshAsync());
		assertReturnedFuture(context.closeAsync());
		assertReturnedFuture(context.finalizeAsync());

		assertReturnedFuture(sdk.getContextData());
		assertReturnedFuture(sdk.createContextWith(ContextConfig.create(), data).waitUntilReadyAsync());
	}

	private static void assertReturnedFuture(CompletableFuture<?> future) {
		ReturnedFutures.assertDefaultExecutor(future);
		future.join();
	}
}
