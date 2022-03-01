package com.absmartly.sdk;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.apache.hc.client5.http.HttpRequestRetryStrategy;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.util.TimeValue;

public class DefaultHTTPClientRetryStrategy implements HttpRequestRetryStrategy {
	private final static int MIN_RETRY_INTERVAL = 5;

	public DefaultHTTPClientRetryStrategy(final int maxRetries, final long maxRetryIntervalMs) {
		this.maxRetries_ = maxRetries;
		this.retryIntervalUs_ = Math.max(0, (2_000 * (maxRetryIntervalMs - MIN_RETRY_INTERVAL)) / (1L << maxRetries));
	}

	@Override
	public boolean retryRequest(final HttpRequest request, final IOException exception, final int execCount,
			final HttpContext context) {
		return execCount <= maxRetries_;
	}

	@Override
	public boolean retryRequest(final HttpResponse response, final int execCount, final HttpContext context) {
		return (execCount <= maxRetries_) && retryableCodes_.contains(response.getCode());
	}

	@Override
	public TimeValue getRetryInterval(final HttpResponse response, final int execCount, final HttpContext context) {
		return TimeValue.ofMilliseconds(MIN_RETRY_INTERVAL + (((1L << (execCount - 1)) * retryIntervalUs_) / 1_000));
	}

	private final static Set<Integer> retryableCodes_ = new HashSet<>(Arrays.asList(502, 503));

	private final int maxRetries_;
	private final long retryIntervalUs_;
}
