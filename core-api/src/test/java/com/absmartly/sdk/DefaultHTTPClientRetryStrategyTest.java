package com.absmartly.sdk;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.apache.hc.client5.http.ConnectTimeoutException;
import org.apache.hc.client5.http.async.methods.SimpleHttpResponse;
import org.apache.hc.client5.http.async.methods.SimpleRequestBuilder;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.protocol.BasicHttpContext;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.util.TimeValue;
import org.junit.jupiter.api.Test;

class DefaultHTTPClientRetryStrategyTest extends TestUtils {
	@Test
	void retryRequest() {
		final DefaultHTTPClientRetryStrategy strategy = new DefaultHTTPClientRetryStrategy(7, 1_000);
		final HttpRequest request = SimpleRequestBuilder.get("http://localhost/v1/context").build();
		final HttpContext context = new BasicHttpContext();

		int i = 1;
		for (; i <= 7; ++i) {
			assertTrue(strategy.retryRequest(request, new ConnectTimeoutException("timeout"), i, context));
		}
		assertFalse(strategy.retryRequest(request, new ConnectTimeoutException("timeout"), i, context));
	}

	@Test
	void testRetryRequest() {
		final DefaultHTTPClientRetryStrategy strategy = new DefaultHTTPClientRetryStrategy(7, 1_000);
		final HttpContext context = new BasicHttpContext();
		final Set<Integer> retryableCodes = setOf(502, 503);

		for (int code : retryableCodes) {
			final HttpResponse response = new SimpleHttpResponse(code);

			int i = 1;
			for (; i <= 7; ++i) {
				assertTrue(strategy.retryRequest(response, i, context));
			}
			assertFalse(strategy.retryRequest(response, i, context));
		}
	}

	@Test
	void getRetryInterval() {
		final long maxIntervalMs = 3_049;
		final DefaultHTTPClientRetryStrategy strategy = new DefaultHTTPClientRetryStrategy(7, maxIntervalMs);
		final HttpContext context = new BasicHttpContext();

		long previous = 0;
		final HttpResponse response = new SimpleHttpResponse(502);

		int i = 1;
		for (; i <= 7; ++i) {
			final TimeValue actual = strategy.getRetryInterval(response, i, context);
			assertTrue(previous < actual.toMilliseconds());
			previous = actual.toMilliseconds();
		}

		assertTrue(Math.abs(maxIntervalMs - previous) <= 1);
	}
}
