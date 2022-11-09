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
}
