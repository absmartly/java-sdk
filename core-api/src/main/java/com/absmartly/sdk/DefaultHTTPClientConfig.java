package com.absmartly.sdk;

import java.security.Provider;
import org.apache.hc.core5.http2.HttpVersionPolicy;

public class DefaultHTTPClientConfig {
	public static DefaultHTTPClientConfig create() {
		return new DefaultHTTPClientConfig();
	}

	DefaultHTTPClientConfig() {}

	public Provider getSecurityProvider() {
		return securityProvider_;
	}

	public DefaultHTTPClientConfig setSecurityProvider(Provider securityProvider) {
		securityProvider_ = securityProvider;
		return this;
	}

	public long getConnectTimeout() {
		return connectTimeout_;
	}

	public DefaultHTTPClientConfig setConnectTimeout(final long connectTimeoutMs) {
		connectTimeout_ = connectTimeoutMs;
		return this;
	}

	public long getConnectionKeepAlive() {
		return connectionKeepAlive_;
	}

	public DefaultHTTPClientConfig setConnectionKeepAlive(final long connectionKeepAliveMs) {
		connectionKeepAlive_ = connectionKeepAliveMs;
		return this;
	}

	public long getConnectionRequestTimeout() {
		return connectionRequestTimeout_;
	}

	public DefaultHTTPClientConfig setConnectionRequestTimeout(final long connectionRequestTimeoutMs) {
		connectionRequestTimeout_ = connectionRequestTimeoutMs;
		return this;
	}

	public int getMaxRetries() {
		return maxRetries_;
	}

	public DefaultHTTPClientConfig setMaxRetries(final int maxRetries) {
		maxRetries_ = maxRetries;
		return this;
	}

	public long getRetryInterval() {
		return retryInterval_;
	}

	public DefaultHTTPClientConfig setRetryInterval(final long retryIntervalMs) {
		retryInterval_ = retryIntervalMs;
		return this;
	}

	public HttpVersionPolicy getHttpVersionPolicy() {
		return httpVersionPolicy_;
	}

	public DefaultHTTPClientConfig setHttpVersionPolicy(final HttpVersionPolicy httpVersionPolicy) {
		httpVersionPolicy_ = httpVersionPolicy;
		return this;
	}

	private Provider securityProvider_ = null;
	private long connectTimeout_ = 3000;
	private long connectionKeepAlive_ = 30000;
	private long connectionRequestTimeout_ = 1000;
	private long retryInterval_ = 333;
	private int maxRetries_ = 5;

	private HttpVersionPolicy httpVersionPolicy_ = HttpVersionPolicy.NEGOTIATE;
}
