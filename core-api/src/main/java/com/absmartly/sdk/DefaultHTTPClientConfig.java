package com.absmartly.sdk;

import java.security.Provider;

public class DefaultHTTPClientConfig {
	public static DefaultHTTPClientConfig create() {
		return new DefaultHTTPClientConfig();
	}

	public enum HTTPVersionPolicy {
		FORCE_HTTP_1, FORCE_HTTP_2, NEGOTIATE
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

	public HTTPVersionPolicy getHTTPVersionPolicy() {
		return httpVersionPolicy_;
	}

	public DefaultHTTPClientConfig setHTTPVersionPolicy(final HTTPVersionPolicy httpVersionPolicy) {
		httpVersionPolicy_ = httpVersionPolicy;
		return this;
	}

	private Provider securityProvider_ = null;
	private long connectTimeout_ = 3000;
	private long connectionKeepAlive_ = 30000;
	private long connectionRequestTimeout_ = 1000;
	private long retryInterval_ = 333;
	private int maxRetries_ = 5;

	private HTTPVersionPolicy httpVersionPolicy_ = HTTPVersionPolicy.NEGOTIATE;
}
