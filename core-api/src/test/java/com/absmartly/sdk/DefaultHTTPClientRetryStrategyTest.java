package com.absmartly.sdk;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;

import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.message.BasicHttpResponse;
import org.junit.jupiter.api.Test;

class DefaultHTTPClientRetryStrategyTest {
	private static HttpResponse response(final int code) {
		return new BasicHttpResponse(code, "");
	}

	@Test
	void retriesRetryableCodesUpToMaxRetries() {
		final DefaultHTTPClientRetryStrategy strategy = new DefaultHTTPClientRetryStrategy(3, 100);

		for (final int code : new int[]{502, 503}) {
			assertTrue(strategy.retryRequest(response(code), 1, null));
			assertTrue(strategy.retryRequest(response(code), 3, null));
			assertFalse(strategy.retryRequest(response(code), 4, null));
		}
	}

	@Test
	void doesNotRetryNonRetryableCodes() {
		final DefaultHTTPClientRetryStrategy strategy = new DefaultHTTPClientRetryStrategy(3, 100);

		for (final int code : new int[]{200, 400, 404, 500, 504}) {
			assertFalse(strategy.retryRequest(response(code), 1, null));
		}
	}

	@Test
	void retriesIOExceptionsUpToMaxRetries() {
		final DefaultHTTPClientRetryStrategy strategy = new DefaultHTTPClientRetryStrategy(2, 100);
		final IOException exception = new IOException("connection reset");

		assertTrue(strategy.retryRequest(null, exception, 1, null));
		assertTrue(strategy.retryRequest(null, exception, 2, null));
		assertFalse(strategy.retryRequest(null, exception, 3, null));
	}

	@Test
	void retryIntervalGrowsExponentiallyAndStaysWithinBudget() {
		final long maxRetryIntervalMs = 1000;
		final DefaultHTTPClientRetryStrategy strategy = new DefaultHTTPClientRetryStrategy(3, maxRetryIntervalMs);

		final long first = strategy.getRetryInterval(response(503), 1, null).toMilliseconds();
		final long second = strategy.getRetryInterval(response(503), 2, null).toMilliseconds();
		final long third = strategy.getRetryInterval(response(503), 3, null).toMilliseconds();

		assertTrue(second > first, "interval must grow: " + first + " -> " + second);
		assertTrue(third > second, "interval must grow: " + second + " -> " + third);
		assertEquals(second - first, (third - second) / 2, "growth must double each attempt");
		assertTrue(third <= maxRetryIntervalMs, "final interval " + third + " exceeds budget " + maxRetryIntervalMs);
	}

	@Test
	void zeroMaxRetriesDisablesRetrying() {
		final DefaultHTTPClientRetryStrategy strategy = new DefaultHTTPClientRetryStrategy(0, 100);

		assertFalse(strategy.retryRequest(response(503), 1, null));
		assertFalse(strategy.retryRequest(null, new IOException("boom"), 1, null));
	}
}
