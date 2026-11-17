package com.absmartly.sdk;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.sun.net.httpserver.HttpServer;

@Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class JdkHttpTransportTest {
	HttpServer server;
	URI base;
	final AtomicInteger calls = new AtomicInteger();
	volatile int failFirst;
	volatile int failStatus = 503;
	volatile long delayMs;

	@BeforeEach
	void start() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/context", exchange -> {
			final int call = calls.incrementAndGet();
			if (delayMs > 0) {
				try {
					Thread.sleep(delayMs);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}
			final byte[] request = exchange.getRequestBody().readAllBytes();
			final int status = (call <= failFirst) ? failStatus : 200;
			final String body = exchange.getRequestMethod() + " " + exchange.getRequestURI().getRawQuery() + " "
					+ exchange.getRequestHeaders().getFirst("X-Test") + " "
					+ exchange.getRequestHeaders().getFirst("Content-Type") + " "
					+ new String(request, StandardCharsets.UTF_8);
			final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(status, bytes.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(bytes);
			}
		});
		server.setExecutor(Executors.newCachedThreadPool());
		server.start();
		base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
	}

	@AfterEach
	void stop() {
		server.stop(0);
	}

	@Test
	void sendsGetAndPut() {
		final JdkHttpTransport transport = JdkHttpTransport.builder().httpVersion(HttpClient.Version.HTTP_1_1)
				.build();
		final Transport.Response get = transport.send(new Transport.Request("GET",
				base.resolve("/context?application=a"), Map.of("X-Test", "1"), null)).join();
		assertEquals(200, get.statusCode());
		assertEquals("", get.statusMessage());
		assertEquals("GET application=a 1 null ", new String(get.body(), StandardCharsets.UTF_8));

		final Transport.Response put = transport.send(new Transport.Request("PUT", base.resolve("/context"),
				Map.of("X-Test", "2"), "{}".getBytes(StandardCharsets.UTF_8))).join();
		assertEquals("PUT null 2 application/json; charset=UTF-8 {}", new String(put.body(), StandardCharsets.UTF_8));
		transport.close();
	}

	@Test
	void retriesBadGatewayAndUnavailable() {
		failFirst = 2;
		final JdkHttpTransport transport = JdkHttpTransport.builder().retryInterval(Duration.ofMillis(10)).build();
		final Transport.Response response = transport
				.send(new Transport.Request("GET", base.resolve("/context"), Map.of(), null)).join();
		assertEquals(200, response.statusCode());
		assertEquals(3, calls.get());
	}

	@Test
	void givesUpAfterMaxRetries() {
		failFirst = 100;
		failStatus = 502;
		final JdkHttpTransport transport = JdkHttpTransport.builder().maxRetries(2)
				.retryInterval(Duration.ofMillis(10)).build();
		assertEquals(502, transport.send(new Transport.Request("GET", base.resolve("/context"), Map.of(), null))
				.join().statusCode());
		assertEquals(3, calls.get());
	}

	@Test
	void doesNotRetryOtherStatuses() {
		failFirst = 100;
		failStatus = 500;
		final JdkHttpTransport transport = JdkHttpTransport.create();
		assertEquals(500, transport.send(new Transport.Request("GET", base.resolve("/context"), Map.of(), null))
				.join().statusCode());
		assertEquals(1, calls.get());
	}

	@Test
	void retriesConnectionFailuresThenFails() {
		server.stop(0);
		final JdkHttpTransport transport = JdkHttpTransport.builder().maxRetries(1)
				.retryInterval(Duration.ofMillis(5)).connectTimeout(Duration.ofSeconds(1)).build();
		final CompletionException error = assertThrows(CompletionException.class,
				() -> transport.send(new Transport.Request("GET", base.resolve("/context"), Map.of(), null)).join());
		assertTrue(error.getCause() instanceof IOException, error.getCause().toString());
	}

	@Test
	void requestTimeoutFailsWithRequestTimeoutException() {
		delayMs = 1000;
		final JdkHttpTransport transport = JdkHttpTransport.builder().requestTimeout(Duration.ofMillis(100))
				.executor(Executors.newSingleThreadExecutor()).build();
		final CompletionException error = assertThrows(CompletionException.class,
				() -> transport.send(new Transport.Request("GET", base.resolve("/context"), Map.of(), null)).join());
		assertTrue(error.getCause() instanceof RequestTimeoutException, error.getCause().toString());
		assertEquals(1, calls.get(), "a timed-out attempt is not retried");
	}

	@Test
	void backoffMatchesOneDotX() {
		final JdkHttpTransport transport = JdkHttpTransport.create();
		// 5 ms + ((2000 * (333 - 5)) / 2^5) us doubling per retry, as DefaultHTTPClientRetryStrategy computed.
		assertEquals(25, transport.retryDelayMillis(1));
		assertEquals(46, transport.retryDelayMillis(2));
		assertEquals(87, transport.retryDelayMillis(3));
		assertEquals(169, transport.retryDelayMillis(4));
		assertEquals(333, transport.retryDelayMillis(5));
		assertTrue(JdkHttpTransport.isRetryableStatus(502));
		assertTrue(JdkHttpTransport.isRetryableStatus(503));
		assertFalse(JdkHttpTransport.isRetryableStatus(504));
	}

	@Test
	void builderValidates() {
		assertThrows(IllegalArgumentException.class, () -> JdkHttpTransport.builder().maxRetries(-1));
		assertThrows(IllegalArgumentException.class, () -> JdkHttpTransport.builder().maxRetries(31));
		assertThrows(IllegalArgumentException.class, () -> JdkHttpTransport.builder().connectTimeout(Duration.ZERO));
		assertThrows(IllegalArgumentException.class,
				() -> JdkHttpTransport.builder().requestTimeout(Duration.ofMillis(-1)));
		assertThrows(IllegalArgumentException.class,
				() -> JdkHttpTransport.builder().retryInterval(Duration.ofMillis(-1)));
		assertEquals(5, JdkHttpTransport.builder().retryInterval(Duration.ZERO).build().retryDelayMillis(1));
	}
}
