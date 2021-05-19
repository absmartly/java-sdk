package com.absmartly.sdk;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class DefaultHTTPClientConfigTest {

	@Test
	void setConnectTimeout() {
		final DefaultHTTPClientConfig config = DefaultHTTPClientConfig.create()
				.setConnectTimeout(123);
		assertEquals(123, config.getConnectTimeout());
	}

	@Test
	void setConnectionKeepAlive() {
		final DefaultHTTPClientConfig config = DefaultHTTPClientConfig.create()
				.setConnectionKeepAlive(123);
		assertEquals(123, config.getConnectionKeepAlive());
	}

	@Test
	void setConnectionRequestTimeout() {
		final DefaultHTTPClientConfig config = DefaultHTTPClientConfig.create()
				.setConnectionRequestTimeout(123);
		assertEquals(123, config.getConnectionRequestTimeout());
	}

	@Test
	void setMaxRetries() {
		final DefaultHTTPClientConfig config = DefaultHTTPClientConfig.create()
				.setMaxRetries(123);
		assertEquals(123, config.getMaxRetries());
	}

	@Test
	void setRetryInterval() {
		final DefaultHTTPClientConfig config = DefaultHTTPClientConfig.create()
				.setRetryInterval(123);
		assertEquals(123, config.getRetryInterval());
	}
}
