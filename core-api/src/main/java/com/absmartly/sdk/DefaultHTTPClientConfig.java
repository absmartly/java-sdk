package com.absmartly.sdk;

public class DefaultHTTPClientConfig {
	public static DefaultHTTPClientConfig create() {
		return new DefaultHTTPClientConfig();
	}

	DefaultHTTPClientConfig() {}

	public long getConnectTimeout() {
		return connectTimeout_;
	}

	public DefaultHTTPClientConfig setConnectTimeout(long connectTimeoutMs) {
		connectTimeout_ = connectTimeoutMs;
		return this;
	}

	public long getConnectionKeepAlive() {
		return connectionKeepAlive_;
	}

	public DefaultHTTPClientConfig setConnectionKeepAlive(long connectionKeepAliveMs) {
		connectionKeepAlive_ = connectionKeepAliveMs;
		return this;
	}

	public long getConnectionRequestTimeout() {
		return connectionRequestTimeout_;
	}

	public DefaultHTTPClientConfig setConnectionRequestTimeout(long connectionRequestTimeoutMs) {
		connectionRequestTimeout_ = connectionRequestTimeoutMs;
		return this;
	}

	public int getMaxRetries() {
		return maxRetries_;
	}

	public DefaultHTTPClientConfig setMaxRetries(int maxRetries) {
		maxRetries_ = maxRetries;
		return this;
	}

	public long getRetryInterval() {
		return retryInterval_;
	}

	public DefaultHTTPClientConfig setRetryInterval(long retryIntervalMs) {
		retryInterval_ = retryIntervalMs;
		return this;
	}

	private long connectTimeout_ = 1_000;
	private long connectionKeepAlive_ = 30_000;
	private long connectionRequestTimeout_ = 1_000;
	private long retryInterval_ = 333;
	private int maxRetries_ = 5;
}
