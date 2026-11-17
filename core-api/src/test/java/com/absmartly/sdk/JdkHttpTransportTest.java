package com.absmartly.sdk;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
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
	void refusedConnectionIsNotRetried() {
		server.stop(0);
		final JdkHttpTransport transport = JdkHttpTransport.builder().retryInterval(Duration.ofMillis(5)).build();
		final long start = System.nanoTime();
		final CompletionException error = assertThrows(CompletionException.class,
				() -> transport.send(new Transport.Request("GET", base.resolve("/context"), Map.of(), null)).join());
		assertTrue(error.getCause() instanceof java.net.ConnectException, error.getCause().toString());
		assertTrue(System.nanoTime() - start < 2_000_000_000L, "no back-off: a refused connection is not retried");
	}

	// A server that accepts and immediately closes the first `drops` connections, then answers 200.
	@Test
	void droppedConnectionsAreRetried() throws Exception {
		final AtomicInteger accepted = new AtomicInteger();
		try (ServerSocket dropping = new ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))) {
			final Thread acceptor = new Thread(() -> {
				while (!dropping.isClosed()) {
					try (Socket socket = dropping.accept()) {
						final int n = accepted.incrementAndGet();
						if (n > 2) {
							final java.io.InputStream in = socket.getInputStream();
							final StringBuilder head = new StringBuilder();
							while (!head.toString().endsWith("\r\n\r\n")) {
								final int c = in.read();
								if (c < 0) {
									break;
								}
								head.append((char) c);
							}
							socket.getOutputStream().write(
									"HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok"
											.getBytes(StandardCharsets.US_ASCII));
							socket.getOutputStream().flush();
						}
					} catch (IOException e) {
						return;
					}
				}
			});
			acceptor.setDaemon(true);
			acceptor.start();

			final JdkHttpTransport transport = JdkHttpTransport.builder().retryInterval(Duration.ofMillis(10))
					.build();
			final URI uri = URI.create("http://127.0.0.1:" + dropping.getLocalPort() + "/context");
			final Transport.Response response = transport
					.send(new Transport.Request("PUT", uri, Map.of(), new byte[]{'{', '}'})).join();
			assertEquals(200, response.statusCode());
			assertEquals("ok", new String(response.body(), StandardCharsets.US_ASCII));
			assertEquals(3, accepted.get(), "two dropped attempts, then the third succeeds");
		}
	}

	@Test
	void droppedConnectionsStopAfterMaxRetries() throws Exception {
		final AtomicInteger accepted = new AtomicInteger();
		try (ServerSocket dropping = new ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))) {
			final Thread acceptor = new Thread(() -> {
				while (!dropping.isClosed()) {
					try (Socket socket = dropping.accept()) {
						accepted.incrementAndGet();
					} catch (IOException e) {
						return;
					}
				}
			});
			acceptor.setDaemon(true);
			acceptor.start();

			final JdkHttpTransport transport = JdkHttpTransport.builder().maxRetries(2)
					.retryInterval(Duration.ofMillis(10)).build();
			final URI uri = URI.create("http://127.0.0.1:" + dropping.getLocalPort() + "/context");
			final CompletionException error = assertThrows(CompletionException.class,
					() -> transport.send(new Transport.Request("PUT", uri, Map.of(), new byte[]{1})).join());
			assertTrue(error.getCause() instanceof IOException, error.getCause().toString());
			assertEquals(3, accepted.get(), "the first attempt and two retries");
		}
	}

	@Test
	void classifiesFailuresLikeOneDotX() {
		assertTrue(JdkHttpTransport.isRetryableFailure(new IOException("reset")));
		assertTrue(JdkHttpTransport.isRetryableFailure(new java.net.http.HttpConnectTimeoutException("slow")));
		assertFalse(JdkHttpTransport.isRetryableFailure(new java.net.http.HttpTimeoutException("slow")));
		assertFalse(JdkHttpTransport.isRetryableFailure(new java.net.ConnectException("refused")));
		assertFalse(JdkHttpTransport.isRetryableFailure(new IllegalStateException("bug")));
	}

	@Test
	void followsRedirects() {
		server.createContext("/old", exchange -> {
			exchange.getResponseHeaders().add("Location", base.resolve("/context").toString());
			exchange.sendResponseHeaders(301, -1);
			exchange.close();
		});
		final Transport.Response response = JdkHttpTransport.create()
				.send(new Transport.Request("GET", base.resolve("/old"), Map.of("X-Test", "r"), null)).join();
		assertEquals(200, response.statusCode());
		assertEquals(1, calls.get());
	}

	@Test
	void plainHttpUsesHttp11WithoutUpgrade() {
		final java.util.List<String> upgrades = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
		server.createContext("/upgrade", exchange -> {
			upgrades.add(String.valueOf(exchange.getRequestHeaders().getFirst("Upgrade")) + " "
					+ exchange.getProtocol());
			exchange.sendResponseHeaders(200, -1);
			exchange.close();
		});
		final JdkHttpTransport transport = JdkHttpTransport.create();
		transport.send(new Transport.Request("GET", base.resolve("/upgrade"), Map.of(), null)).join();
		transport.send(new Transport.Request("PUT", base.resolve("/upgrade"), Map.of(), new byte[]{1})).join();
		assertEquals(java.util.List.of("null HTTP/1.1", "null HTTP/1.1"), upgrades);
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
