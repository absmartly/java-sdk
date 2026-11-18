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
	void followsSameOriginRedirectsWithTheirHeaders() {
		server.createContext("/old", exchange -> {
			exchange.getResponseHeaders().add("Location", "/context");
			exchange.sendResponseHeaders(301, -1);
			exchange.close();
		});
		final Transport.Response response = JdkHttpTransport.create()
				.send(new Transport.Request("GET", base.resolve("/old"), Map.of("X-Test", "r"), null)).join();
		assertEquals(200, response.statusCode());
		assertEquals("GET null r null ", new String(response.body(), StandardCharsets.UTF_8));
		assertEquals(1, calls.get());
	}

	@Test
	void doesNotFollowRedirectsToAnotherOrigin() throws Exception {
		final AtomicInteger otherCalls = new AtomicInteger();
		final java.util.List<String> otherKeys = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
		final HttpServer other = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		other.createContext("/", exchange -> {
			otherCalls.incrementAndGet();
			otherKeys.add(String.valueOf(exchange.getRequestHeaders().getFirst("X-API-Key")));
			exchange.sendResponseHeaders(200, -1);
			exchange.close();
		});
		other.start();
		try {
			final String elsewhere = "http://127.0.0.1:" + other.getAddress().getPort() + "/steal";
			server.createContext("/away", exchange -> {
				exchange.getResponseHeaders().add("Location", elsewhere);
				exchange.sendResponseHeaders(307, -1);
				exchange.close();
			});
			final Transport.Response response = JdkHttpTransport.create().send(new Transport.Request("PUT",
					base.resolve("/away"), Map.of("X-API-Key", "secret"), new byte[]{1})).join();
			assertEquals(307, response.statusCode(), "the cross-origin redirect is returned, not followed");
			assertEquals(0, otherCalls.get(), "the API key never reaches the other origin: " + otherKeys);
		} finally {
			other.stop(0);
		}
	}

	@Test
	void temporaryRedirectKeepsMethodAndBody() {
		server.createContext("/moved", exchange -> {
			exchange.getRequestBody().readAllBytes();
			exchange.getResponseHeaders().add("Location", base.resolve("/context").toString());
			exchange.sendResponseHeaders(308, -1);
			exchange.close();
		});
		final Transport.Response response = JdkHttpTransport.create().send(new Transport.Request("PUT",
				base.resolve("/moved"), Map.of("X-Test", "p"), "{}".getBytes(StandardCharsets.UTF_8))).join();
		assertEquals("PUT null p application/json; charset=UTF-8 {}",
				new String(response.body(), StandardCharsets.UTF_8));
	}

	@Test
	void redirectLoopsStopAfterFiveHops() {
		final AtomicInteger hits = new AtomicInteger();
		server.createContext("/loop", exchange -> {
			hits.incrementAndGet();
			exchange.getResponseHeaders().add("Location", "/loop");
			exchange.sendResponseHeaders(302, -1);
			exchange.close();
		});
		assertEquals(302, JdkHttpTransport.create()
				.send(new Transport.Request("GET", base.resolve("/loop"), Map.of(), null)).join().statusCode());
		assertEquals(6, hits.get(), "the first request and five redirects");
	}

	@Test
	void permanentAndFoundRedirectsKeepAPutAndItsBody() {
		for (final int status : new int[]{301, 302}) {
			final String path = "/put" + status;
			server.createContext(path, exchange -> {
				exchange.getRequestBody().readAllBytes();
				exchange.getResponseHeaders().add("Location", "/context");
				exchange.sendResponseHeaders(status, -1);
				exchange.close();
			});
			final Transport.Response response = JdkHttpTransport.create().send(new Transport.Request("PUT",
					base.resolve(path), Map.of("X-Test", "s"), "{\"e\":1}".getBytes(StandardCharsets.UTF_8))).join();
			assertEquals("PUT null s application/json; charset=UTF-8 {\"e\":1}",
					new String(response.body(), StandardCharsets.UTF_8), "a redirected publish keeps its events");
		}
	}

	@Test
	void permanentAndFoundRedirectsTurnAPostIntoAGet() {
		for (final int status : new int[]{301, 302}) {
			final String path = "/post" + status;
			server.createContext(path, exchange -> {
				exchange.getRequestBody().readAllBytes();
				exchange.getResponseHeaders().add("Location", "/context");
				exchange.sendResponseHeaders(status, -1);
				exchange.close();
			});
			final Transport.Response response = JdkHttpTransport.create().send(new Transport.Request("POST",
					base.resolve(path), Map.of("X-Test", "q"), new byte[]{1})).join();
			assertEquals("GET null q null ", new String(response.body(), StandardCharsets.UTF_8));
		}
	}

	@Test
	void seeOtherTurnsIntoAGetWithoutContentType() {
		server.createContext("/other", exchange -> {
			exchange.getRequestBody().readAllBytes();
			exchange.getResponseHeaders().add("Location", "/context");
			exchange.sendResponseHeaders(303, -1);
			exchange.close();
		});
		final Transport.Response response = JdkHttpTransport.create().send(new Transport.Request("PUT",
				base.resolve("/other"), Map.of("X-Test", "o"), new byte[]{1})).join();
		assertEquals("GET null o null ", new String(response.body(), StandardCharsets.UTF_8));
	}

	@Test
	void redirectHopsShareOneDeadline() {
		server.createContext("/slow-hop", exchange -> {
			try {
				Thread.sleep(700);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			exchange.getResponseHeaders().add("Location", "/slow-end");
			exchange.sendResponseHeaders(302, -1);
			exchange.close();
		});
		server.createContext("/slow-end", exchange -> {
			try {
				Thread.sleep(700);
				exchange.sendResponseHeaders(200, -1);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			exchange.close();
		});
		final JdkHttpTransport transport = JdkHttpTransport.builder().requestTimeout(Duration.ofMillis(1000))
				.build();
		final CompletionException error = assertThrows(CompletionException.class,
				() -> transport.send(new Transport.Request("GET", base.resolve("/slow-hop"), Map.of(), null)).join());
		assertTrue(error.getCause() instanceof RequestTimeoutException, error.getCause().toString());
	}

	@Test
	void sameOriginComparesSchemeHostAndPort() {
		assertTrue(JdkHttpTransport.sameOrigin(URI.create("https://a.io/v1"), URI.create("https://A.io:443/x")));
		assertFalse(JdkHttpTransport.sameOrigin(URI.create("https://a.io/v1"), URI.create("http://a.io/v1")));
		assertFalse(JdkHttpTransport.sameOrigin(URI.create("https://a.io/v1"), URI.create("https://b.io/v1")));
		assertFalse(JdkHttpTransport.sameOrigin(URI.create("http://a.io/v1"), URI.create("http://a.io:8080/v1")));
		assertFalse(JdkHttpTransport.sameOrigin(URI.create("https://a.io:8443/"), URI.create("http://a.io:8443/")),
				"a downgrade to http on the same port would send the API key in plaintext");
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
	void requestTimeoutCoversAStalledBody() {
		server.createContext("/stall", exchange -> {
			exchange.sendResponseHeaders(200, 4);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write('{');
				out.flush();
				Thread.sleep(3000);
				out.write("}  ".getBytes(StandardCharsets.US_ASCII), 0, 3);
			} catch (InterruptedException | IOException e) {
				Thread.currentThread().interrupt();
			}
		});
		final JdkHttpTransport transport = JdkHttpTransport.builder().requestTimeout(Duration.ofMillis(500))
				.build();
		final long start = System.nanoTime();
		final CompletionException error = assertThrows(CompletionException.class,
				() -> transport.send(new Transport.Request("GET", base.resolve("/stall"), Map.of(), null)).join());
		final long elapsedMs = (System.nanoTime() - start) / 1_000_000;
		assertTrue(error.getCause() instanceof RequestTimeoutException, error.getCause().toString());
		assertEquals("GET /stall timed out", error.getCause().getMessage());
		assertTrue(elapsedMs < 2500, "timed out after " + elapsedMs + " ms, not after the body arrived");
	}

	// A blocking callback on a timed-out request must not hold the thread that runs the retry back-off: before the
	// fix both ran on the JDK's single shared delay thread, so this publish never sent its retry.
	@Test
	void blockingTimeoutCallbackDoesNotStallRetries() {
		failFirst = 1;
		server.createContext("/slow", exchange -> {
			try {
				Thread.sleep(1500);
				exchange.sendResponseHeaders(200, -1);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			exchange.close();
		});
		final JdkHttpTransport transport = JdkHttpTransport.builder().requestTimeout(Duration.ofMillis(200))
				.retryInterval(Duration.ofMillis(20)).build();
		final String result = transport.send(new Transport.Request("GET", base.resolve("/slow"), Map.of(), null))
				.handle((response, error) -> {
					// Blocks inside the timeout callback on a request that needs a 503 retry.
					return transport.send(new Transport.Request("PUT", base.resolve("/context"), Map.of(),
							new byte[]{1})).orTimeout(5, java.util.concurrent.TimeUnit.SECONDS).join().statusCode()
							+ "";
				}).join();
		assertEquals("200", result);
		assertEquals(2, calls.get(), "the 503 and its retry");
	}

	// Cancelling the timed-out exchange closes the connection from JDK 16; JDK 11's client keeps reading in the
	// background, a JDK limit, so the assertion only runs where the JDK can stop it.
	@Test
	void timedOutDownloadIsCancelled() throws Exception {
		final AtomicInteger written = new AtomicInteger();
		final java.util.concurrent.CountDownLatch finished = new java.util.concurrent.CountDownLatch(1);
		server.createContext("/chunks", exchange -> {
			exchange.sendResponseHeaders(200, 0);
			try (OutputStream out = exchange.getResponseBody()) {
				for (int i = 0; i < 40; ++i) {
					out.write(new byte[1024]);
					out.flush();
					written.incrementAndGet();
					Thread.sleep(100);
				}
			} catch (IOException | InterruptedException e) {
				// The client closed the connection.
			} finally {
				finished.countDown();
			}
		});
		final JdkHttpTransport transport = JdkHttpTransport.builder().requestTimeout(Duration.ofMillis(500))
				.build();
		final CompletionException error = assertThrows(CompletionException.class,
				() -> transport.send(new Transport.Request("GET", base.resolve("/chunks"), Map.of(), null)).join());
		assertTrue(error.getCause() instanceof RequestTimeoutException, error.getCause().toString());
		if (Runtime.version().feature() >= 16) {
			assertTrue(finished.await(3, java.util.concurrent.TimeUnit.SECONDS), "the server stops writing");
			assertTrue(written.get() < 40, "the download was cut short after " + written.get() + " chunks");
		}
	}

	@Test
	void callerContentTypeIsNotDuplicated() {
		final java.util.List<java.util.List<String>> contentTypes = java.util.Collections
				.synchronizedList(new java.util.ArrayList<>());
		server.createContext("/headers", exchange -> {
			exchange.getRequestBody().readAllBytes();
			contentTypes.add(exchange.getRequestHeaders().get("Content-Type"));
			exchange.sendResponseHeaders(200, -1);
			exchange.close();
		});
		final JdkHttpTransport transport = JdkHttpTransport.create();
		transport.send(new Transport.Request("PUT", base.resolve("/headers"), Map.of("content-type", "text/plain"),
				new byte[]{1})).join();
		transport.send(new Transport.Request("PUT", base.resolve("/headers"), Map.of(), new byte[]{1})).join();
		assertEquals(java.util.List.of(java.util.List.of("text/plain"),
				java.util.List.of("application/json; charset=UTF-8")), contentTypes);
	}

	@Test
	void retryBackoffRunsOnTheCallbackExecutor() {
		failFirst = 1;
		final java.util.concurrent.ExecutorService callbacks = Executors
				.newSingleThreadExecutor(runnable -> new Thread(runnable, "transport-callbacks"));
		// Records where each task was submitted from: the delayed retry is submitted by the JDK's delay thread, and
		// must land on this executor rather than run on that shared thread.
		final java.util.List<String> submitters = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
		final java.util.concurrent.Executor recording = command -> {
			submitters.add(Thread.currentThread().getName());
			callbacks.execute(command);
		};
		try {
			final JdkHttpTransport transport = JdkHttpTransport.builder().retryInterval(Duration.ofMillis(20))
					.executor(recording).build();
			transport.send(new Transport.Request("GET", base.resolve("/context"), Map.of(), null)).join();
			assertEquals(2, calls.get());
			// The JDK's delay thread: CompletableFutureDelayScheduler, or ForkJoinPool.commonPool-delayScheduler on
			// JDK 25.
			assertTrue(submitters.stream().anyMatch(name -> name.toLowerCase(java.util.Locale.ROOT).contains("delay")),
					"the back-off hands the retry to the callback executor: " + submitters);
		} finally {
			callbacks.shutdownNow();
		}
	}

	@Test
	void timeoutCallbacksRunOnTheCallbackExecutor() {
		server.createContext("/slow", exchange -> {
			try {
				Thread.sleep(2000);
				exchange.sendResponseHeaders(200, -1);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			exchange.close();
		});
		final java.util.concurrent.ExecutorService callbacks = Executors
				.newSingleThreadExecutor(runnable -> new Thread(runnable, "transport-callbacks"));
		try {
			final JdkHttpTransport transport = JdkHttpTransport.builder().requestTimeout(Duration.ofMillis(200))
					.executor(callbacks).build();
			final String thread = transport.send(new Transport.Request("GET", base.resolve("/slow"), Map.of(), null))
					.handle((response, error) -> Thread.currentThread().getName()).join();
			assertEquals("transport-callbacks", thread, "never the JDK's shared CompletableFuture delay thread");
		} finally {
			callbacks.shutdownNow();
		}
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
	void connectTimeoutDefaultsToThreeSeconds() throws Exception {
		final java.lang.reflect.Field field = JdkHttpTransport.class.getDeclaredField("httpClient_");
		field.setAccessible(true);
		assertEquals(java.util.Optional.of(Duration.ofSeconds(3)),
				((HttpClient) field.get(JdkHttpTransport.create())).connectTimeout());
		assertEquals(java.util.Optional.of(Duration.ofMillis(750)), ((HttpClient) field.get(
				JdkHttpTransport.builder().connectTimeout(Duration.ofMillis(750)).build())).connectTimeout());
	}

	@Test
	void exhaustedConnectTimeoutsRaiseRequestTimeoutException() {
		// 10.255.255.1 is unroutable, so the connect attempt times out rather than being refused.
		final JdkHttpTransport transport = JdkHttpTransport.builder().connectTimeout(Duration.ofMillis(50))
				.maxRetries(1).retryInterval(Duration.ofMillis(5)).build();
		final CompletionException error = assertThrows(CompletionException.class, () -> transport
				.send(new Transport.Request("GET", URI.create("http://10.255.255.1:9/context"), Map.of(), null))
				.join());
		if (error.getCause() instanceof RequestTimeoutException) {
			assertEquals("GET /context timed out connecting", error.getCause().getMessage());
			assertTrue(error.getCause().getCause() instanceof java.net.http.HttpConnectTimeoutException);
		} else {
			// Sandboxes without a route fail fast with "network unreachable" instead; that is not a timeout.
			assertTrue(error.getCause() instanceof IOException, error.getCause().toString());
		}
	}

	@Test
	void builderValidates() {
		assertThrows(IllegalArgumentException.class, () -> JdkHttpTransport.builder().maxRetries(-1));
		assertThrows(IllegalArgumentException.class, () -> JdkHttpTransport.builder().maxRetries(31));
		assertThrows(IllegalArgumentException.class, () -> JdkHttpTransport.builder().connectTimeout(Duration.ZERO));
		assertThrows(IllegalArgumentException.class,
				() -> JdkHttpTransport.builder().requestTimeout(Duration.ofMillis(-1)));
		assertThrows(IllegalArgumentException.class,
				() -> JdkHttpTransport.builder().requestTimeout(Duration.ofSeconds(Long.MAX_VALUE)));
		assertThrows(IllegalArgumentException.class,
				() -> JdkHttpTransport.builder().retryInterval(Duration.ofMillis(-1)));
		assertEquals(5, JdkHttpTransport.builder().retryInterval(Duration.ZERO).build().retryDelayMillis(1));
	}
}
