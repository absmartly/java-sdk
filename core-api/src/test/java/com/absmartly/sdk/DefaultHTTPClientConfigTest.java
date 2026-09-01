package com.absmartly.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

import java.security.Provider;

import org.junit.jupiter.api.Test;

class DefaultHTTPClientConfigTest extends TestUtils {

	@Test
	void setSecurityProvider() {
		final Provider provider = mock(Provider.class);
		final DefaultHTTPClientConfig config = DefaultHTTPClientConfig.create()
				.setSecurityProvider(provider);
		assertSame(provider, config.getSecurityProvider());
	}

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

	@Test
	void setHttpVersionPolicy() {
		final DefaultHTTPClientConfig config = DefaultHTTPClientConfig.create()
				.setHTTPVersionPolicy(HTTPVersionPolicy.FORCE_HTTP_1);
		assertEquals(HTTPVersionPolicy.FORCE_HTTP_1, config.getHTTPVersionPolicy());
	}

	@Test
	void testNegativeConnectTimeout() {
		final DefaultHTTPClientConfig config = DefaultHTTPClientConfig.create()
				.setConnectTimeout(-1);
		assertEquals(-1, config.getConnectTimeout());
	}

	@Test
	void testNegativeConnectionKeepAlive() {
		final DefaultHTTPClientConfig config = DefaultHTTPClientConfig.create()
				.setConnectionKeepAlive(-1);
		assertEquals(-1, config.getConnectionKeepAlive());
	}

	@Test
	void testNegativeConnectionRequestTimeout() {
		final DefaultHTTPClientConfig config = DefaultHTTPClientConfig.create()
				.setConnectionRequestTimeout(-1);
		assertEquals(-1, config.getConnectionRequestTimeout());
	}

	@Test
	void testNegativeRetryInterval() {
		final DefaultHTTPClientConfig config = DefaultHTTPClientConfig.create()
				.setRetryInterval(-1);
		assertEquals(-1, config.getRetryInterval());
	}

	@Test
	void testZeroMaxRetries() {
		final DefaultHTTPClientConfig config = DefaultHTTPClientConfig.create()
				.setMaxRetries(0);
		assertEquals(0, config.getMaxRetries());
	}

	@Test
	void testNegativeMaxRetries() {
		final DefaultHTTPClientConfig config = DefaultHTTPClientConfig.create()
				.setMaxRetries(-1);
		assertEquals(-1, config.getMaxRetries());
	}

	@Test
	void testDefaultValues() {
		final DefaultHTTPClientConfig config = DefaultHTTPClientConfig.create();
		assertEquals(3000, config.getConnectTimeout());
		assertEquals(30000, config.getConnectionKeepAlive());
		assertEquals(1000, config.getConnectionRequestTimeout());
		assertEquals(5, config.getMaxRetries());
		assertEquals(333, config.getRetryInterval());
		assertEquals(HTTPVersionPolicy.NEGOTIATE, config.getHTTPVersionPolicy());
	}
}
