package com.absmartly.sdk;

import java.io.IOException;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.hc.client5.http.HttpRequestRetryStrategy;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.util.TimeValue;

public class DefaultHTTPClientRetryStrategy implements HttpRequestRetryStrategy {
	private final static int MIN_RETRY_INTERVAL = 5;

	public DefaultHTTPClientRetryStrategy(int maxRetries, long maxRetryIntervalMs) {
		this.maxRetries_ = maxRetries;
		this.retryIntervalUs_ = Math.max(0, (2_000 * (maxRetryIntervalMs - MIN_RETRY_INTERVAL)) / (1L << maxRetries));
	}

	@Override
	public boolean retryRequest(HttpRequest request, IOException exception, int execCount, HttpContext context) {
		return execCount <= maxRetries_;
	}

	@Override
	public boolean retryRequest(HttpResponse response, int execCount, HttpContext context) {
		return (execCount <= maxRetries_) && retryableCodes_.contains(response.getCode());
	}

	@Override
	public TimeValue getRetryInterval(HttpResponse response, int execCount, HttpContext context) {
		return TimeValue.ofMilliseconds(MIN_RETRY_INTERVAL + (((1L << (execCount - 1)) * retryIntervalUs_) / 1_000));
	}

	private final static Set<Integer> retryableCodes_ = Stream.of(502, 503).collect(Collectors.toSet());

	private final int maxRetries_;
	private final long retryIntervalUs_;
}
