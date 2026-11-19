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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
			final byte[] body = "loop".getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Location", "/loop");
			exchange.sendResponseHeaders(302, body.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(body);
			}
		});
		final Transport.Response response = JdkHttpTransport.create()
				.send(new Transport.Request("GET", base.resolve("/loop"), Map.of(), null)).join();
		assertEquals(302, response.statusCode());
		assertEquals("loop", new String(response.body(), StandardCharsets.UTF_8), "the capped 3xx keeps its body");
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
	void permanentAndFoundRedirectsKeepOtherMethods() {
		for (final int status : new int[]{301, 302}) {
			for (final String method : new String[]{"PATCH", "DELETE"}) {
				final String path = "/" + method.toLowerCase(java.util.Locale.ROOT) + status;
				server.createContext(path, exchange -> {
					exchange.getRequestBody().readAllBytes();
					exchange.getResponseHeaders().add("Location", "/context");
					exchange.sendResponseHeaders(status, -1);
					exchange.close();
				});
				final Transport.Response response = JdkHttpTransport.create().send(new Transport.Request(method,
						base.resolve(path), Map.of("X-Test", "q"), "b".getBytes(StandardCharsets.UTF_8))).join();
				assertEquals(method + " null q application/json; charset=UTF-8 b",
						new String(response.body(), StandardCharsets.UTF_8), method + " behind " + status);
			}
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
	void stalledRedirectBodyDoesNotHoldUpTheRequest() {
		server.createContext("/stall-redirect", exchange -> {
			exchange.getResponseHeaders().add("Location", "/context");
			exchange.sendResponseHeaders(307, 2);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write('x');
				out.flush();
				Thread.sleep(5000);
			} catch (IOException | InterruptedException e) {}
		});
		final long start = System.nanoTime();
		final Transport.Response response = JdkHttpTransport.create()
				.send(new Transport.Request("GET", base.resolve("/stall-redirect"), Map.of(), null))
				.orTimeout(4, TimeUnit.SECONDS).join();
		assertEquals(200, response.statusCode());
		assertEquals(1, calls.get(), "the destination was reached without waiting for the redirect's body");
		assertTrue(System.nanoTime() - start < 900_000_000L, "the hop waits at most 100 ms for the drain");
	}

	@Test
	void followedRedirectsReuseTheConnection() {
		final java.util.Set<String> clients = java.util.concurrent.ConcurrentHashMap.newKeySet();
		server.createContext("/reuse", exchange -> {
			clients.add(exchange.getRemoteAddress().toString());
			final byte[] body = "moved".getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Location", "/reused");
			exchange.sendResponseHeaders(307, body.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(body);
			}
		});
		server.createContext("/reused", exchange -> {
			clients.add(exchange.getRemoteAddress().toString());
			final byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, body.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(body);
			}
		});
		final JdkHttpTransport transport = JdkHttpTransport.create();
		for (int i = 0; i < 10; ++i) {
			assertEquals(200, transport.send(new Transport.Request("GET", base.resolve("/reuse"), Map.of(), null))
					.join().statusCode());
		}
		assertEquals(1, clients.size(), "each redirect's body drains before the next hop, so one connection serves "
				+ "all 20 exchanges");
	}

	@Test
	void redirectDrainStaysWithinTheRequestDeadline() {
		server.createContext("/held-redirect", exchange -> {
			exchange.getResponseHeaders().add("Location", "/context");
			exchange.sendResponseHeaders(307, 2);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write('x');
				out.flush();
				Thread.sleep(2000);
			} catch (IOException | InterruptedException e) {}
		});
		final JdkHttpTransport transport = JdkHttpTransport.builder().requestTimeout(Duration.ofMillis(30)).build();
		// Warm up the connection and the JIT with retries: a cold first request can itself exceed 30 ms.
		for (int attempt = 0; attempt < 5; ++attempt) {
			try {
				transport.send(new Transport.Request("GET", base.resolve("/context"), Map.of(), null)).join();
				break;
			} catch (final CompletionException e) {
				assertTrue(e.getCause() instanceof RequestTimeoutException, e.getCause().toString());
			}
		}
		final long start = System.nanoTime();
		final CompletionException error = assertThrows(CompletionException.class, () -> transport
				.send(new Transport.Request("GET", base.resolve("/held-redirect"), Map.of(), null)).join());
		final long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
		assertTrue(error.getCause() instanceof RequestTimeoutException, error.getCause().toString());
		// An uncut drain wait would take its full 100 ms; cut to the deadline, the request fails after about 30 ms.
		assertTrue(elapsedMs < 80, "the drain wait is cut to the 30 ms deadline, not a fresh 100 ms: " + elapsedMs);
	}

	@Test
	void stalledRedirectHopRunsOnTheCallbackExecutor() {
		server.createContext("/stall-hop", exchange -> {
			exchange.getResponseHeaders().add("Location", "/hop-target");
			exchange.sendResponseHeaders(307, 2);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write('x');
				out.flush();
				Thread.sleep(2000);
			} catch (IOException | InterruptedException e) {}
		});
		// The hop fails before any I/O hand-off (the target refuses), so where its failure surfaces is where the hop
		// itself ran: the callback executor, never the JDK's shared delay thread.
		server.createContext("/hop-target", exchange -> {
			exchange.sendResponseHeaders(500, -1);
			exchange.close();
		});
		final java.util.concurrent.ExecutorService callbacks = Executors
				.newSingleThreadExecutor(runnable -> new Thread(runnable, "transport-callbacks"));
		try {
			final JdkHttpTransport transport = JdkHttpTransport.builder().executor(callbacks).build();
			final AtomicReference<String> hopThread = new AtomicReference<>();
			final JdkHttpTransport.HopObserver previous = JdkHttpTransport.hopObserver;
			JdkHttpTransport.hopObserver = () -> hopThread.set(Thread.currentThread().getName());
			try {
				assertEquals(500, transport.send(new Transport.Request("GET", base.resolve("/stall-hop"), Map.of(),
						null)).join().statusCode());
			} finally {
				JdkHttpTransport.hopObserver = previous;
			}
			assertEquals("transport-callbacks", hopThread.get(),
					"the hop after a drain timeout never runs on the delay thread");
		} finally {
			callbacks.shutdownNow();
		}
	}

	@Test
	void redirectDrainErrorsCompleteImmediately() throws Exception {
		final Class<?> type = Class.forName("com.absmartly.sdk.JdkHttpTransport$DiscardingSubscriber");
		final java.lang.reflect.Constructor<?> constructor = type.getDeclaredConstructor();
		constructor.setAccessible(true);
		final java.net.http.HttpResponse.BodySubscriber<?> subscriber = (java.net.http.HttpResponse.BodySubscriber<?>) constructor
				.newInstance();
		final java.lang.reflect.Field field = type.getDeclaredField("drained");
		field.setAccessible(true);
		final CompletableFuture<?> drained = (CompletableFuture<?>) field.get(subscriber);
		assertFalse(drained.isDone());
		subscriber.onError(new IOException("connection reset"));
		assertTrue(drained.isDone(), "a failed body must release the drain wait immediately");
	}

	// A DiscardingSubscriber, with the given drain-cancel timer, or the production one when it is null.
	private static java.net.http.HttpResponse.BodySubscriber<?> discardingSubscriber(
			final java.util.function.BiConsumer<Long, Runnable> timer) throws Exception {
		final Class<?> type = Class.forName("com.absmartly.sdk.JdkHttpTransport$DiscardingSubscriber");
		final java.lang.reflect.Constructor<?> constructor = (timer != null)
				? type.getDeclaredConstructor(java.util.function.BiConsumer.class)
				: type.getDeclaredConstructor();
		constructor.setAccessible(true);
		return (java.net.http.HttpResponse.BodySubscriber<?>) ((timer != null) ? constructor.newInstance(timer)
				: constructor.newInstance());
	}

	// A subscription that only runs onCancel when cancelled.
	private static java.util.concurrent.Flow.Subscription subscription(final Runnable onCancel) {
		return new java.util.concurrent.Flow.Subscription() {
			@Override
			public void request(final long count) {}

			@Override
			public void cancel() {
				onCancel.run();
			}
		};
	}

	@Test
	void redirectDrainCancellationHasAOneSecondDeadline() throws Exception {
		// A manual timer: the scheduled delay is checked directly and the timer fired by hand, so the test does not
		// depend on host timing.
		final java.util.List<Long> delays = new java.util.ArrayList<>();
		final java.util.List<Runnable> tasks = new java.util.ArrayList<>();
		final java.net.http.HttpResponse.BodySubscriber<?> subscriber = discardingSubscriber((delay, task) -> {
			delays.add(delay);
			tasks.add(task);
		});
		final AtomicInteger cancels = new AtomicInteger();
		subscriber.onSubscribe(subscription(cancels::incrementAndGet));
		assertEquals(java.util.List.of(1000L), delays, "the drain is cancelled after one second");
		assertEquals(0, cancels.get(), "nothing is cancelled before the timer fires");
		tasks.get(0).run();
		assertEquals(1, cancels.get(), "a still-draining body is cancelled when the timer fires");
	}

	@Test
	void productionDrainTimerCancelsAfterOneSecond() throws Exception {
		// The no-arg subscriber is the one real redirects get: its timer must wait the requested delay, not longer.
		final CompletableFuture<Long> cancelledAt = new CompletableFuture<>();
		final java.net.http.HttpResponse.BodySubscriber<?> subscriber = discardingSubscriber(null);
		final long start = System.nanoTime();
		subscriber.onSubscribe(subscription(() -> cancelledAt.complete(System.nanoTime())));
		final long elapsedMs = TimeUnit.NANOSECONDS.toMillis(cancelledAt.get(5, TimeUnit.SECONDS) - start);
		assertTrue(elapsedMs >= 1000, "cancelled after " + elapsedMs + " ms, before the one-second deadline");
		assertTrue(elapsedMs < 1500, "cancelled after " + elapsedMs + " ms, well past the one-second deadline");
	}

	@Test
	void finishedDrainIsNotCancelledByTheTimer() throws Exception {
		final java.util.List<Runnable> tasks = new java.util.ArrayList<>();
		final java.net.http.HttpResponse.BodySubscriber<?> subscriber = discardingSubscriber(
				(delay, task) -> tasks.add(task));
		final AtomicInteger cancels = new AtomicInteger();
		subscriber.onSubscribe(subscription(cancels::incrementAndGet));
		subscriber.onComplete();
		tasks.get(0).run();
		assertEquals(0, cancels.get(), "a body that finished draining is left alone");
	}

	@Test
	// An upper-bound check on a real connection; the one-second timer is pinned by the subscriber tests above.
	void tricklingRedirectBodyIsCancelledWithinTheDrainLimit() throws Exception {
		final java.util.concurrent.CountDownLatch cut = new java.util.concurrent.CountDownLatch(1);
		server.createContext("/trickle", exchange -> {
			exchange.getResponseHeaders().add("Location", "/context");
			exchange.sendResponseHeaders(307, 25);
			try (OutputStream out = exchange.getResponseBody()) {
				for (int i = 0; i < 25; ++i) {
					out.write('x');
					out.flush();
					Thread.sleep(200);
				}
			} catch (IOException e) {
				cut.countDown();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});
		assertEquals(200, JdkHttpTransport.create()
				.send(new Transport.Request("GET", base.resolve("/trickle"), Map.of(), null))
				.orTimeout(5, TimeUnit.SECONDS).join().statusCode());
		if (Runtime.version().feature() >= 16) {
			assertTrue(cut.await(2500, TimeUnit.MILLISECONDS), "a drain still running after 1 s is cancelled");
		}
	}

	@Test
	void largeRedirectBodyIsCancelledAndTheHopStillRuns() throws Exception {
		// 16 MiB: more than the socket buffers can absorb, so a cancelled drain always reaches the server.
		final java.util.concurrent.CountDownLatch cut = new java.util.concurrent.CountDownLatch(1);
		server.createContext("/big-redirect", exchange -> {
			exchange.getResponseHeaders().add("Location", "/context");
			exchange.sendResponseHeaders(307, 1 << 24);
			try (OutputStream out = exchange.getResponseBody()) {
				final byte[] chunk = new byte[8192];
				for (int i = 0; i < 2048; ++i) {
					out.write(chunk);
					out.flush();
				}
			} catch (IOException e) {
				cut.countDown();
			}
		});
		final Transport.Response response = JdkHttpTransport.create()
				.send(new Transport.Request("GET", base.resolve("/big-redirect"), Map.of(), null))
				.orTimeout(5, TimeUnit.SECONDS).join();
		assertEquals(200, response.statusCode());
		if (Runtime.version().feature() >= 16) {
			assertTrue(cut.await(3, TimeUnit.SECONDS), "a redirect body over 64 KiB is cancelled rather than drained");
		}
	}

	@Test
	void crossOriginRedirectBodyIsStillRead() {
		server.createContext("/away-body", exchange -> {
			final byte[] body = "moved".getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Location", "http://elsewhere.invalid/x");
			exchange.sendResponseHeaders(302, body.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(body);
			}
		});
		final Transport.Response response = JdkHttpTransport.create()
				.send(new Transport.Request("GET", base.resolve("/away-body"), Map.of(), null)).join();
		assertEquals(302, response.statusCode());
		assertEquals("moved", new String(response.body(), StandardCharsets.UTF_8));
	}

	@Test
	void expiredDeadlineDoesNotStartTheNextHop() throws Exception {
		// A redirect hop is an attempt with the deadline it inherited; once that deadline has passed (a busy callback
		// executor handled the 307 late), the hop is not sent: the publish must not reach the collector while the
		// caller is told it timed out and queues it again.
		final JdkHttpTransport transport = JdkHttpTransport.builder().requestTimeout(Duration.ofMillis(300)).build();
		final java.net.http.HttpRequest hop = java.net.http.HttpRequest.newBuilder(base.resolve("/context"))
				.PUT(java.net.http.HttpRequest.BodyPublishers
						.ofByteArray("{\"goal\":1}".getBytes(StandardCharsets.UTF_8)))
				.build();
		final CompletionException error = assertThrows(CompletionException.class,
				() -> transport.attempt(hop, 1, 1, System.nanoTime() - 1).orTimeout(5, TimeUnit.SECONDS).join());
		assertTrue(error.getCause() instanceof RequestTimeoutException, error.getCause().toString());
		assertEquals(0, transport.exchangesStarted_.get(), "the hop was never handed to the HTTP client");
		Thread.sleep(200);
		assertEquals(0, calls.get(), "and never reached the collector");
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

	// A blocking callback on a timed-out request must not hold the JDK's single shared delay thread, which also runs
	// the retry back-off.
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
	void rejectedCompletionHandOffFailsTheRequest() {
		final java.util.concurrent.Executor refusing = command -> {
			throw new java.util.concurrent.RejectedExecutionException("saturated");
		};
		final java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(base.resolve("/context"))
				.build();
		final CompletableFuture<String> done = CompletableFuture.completedFuture("response");
		final CompletionException error = assertThrows(CompletionException.class,
				() -> JdkHttpTransport.onExecutor(done, refusing, request).orTimeout(5, TimeUnit.SECONDS).join());
		assertTrue(error.getCause() instanceof TransportException, error.getCause().toString());
		assertEquals("GET /context could not be scheduled on the transport executor", error.getCause().getMessage());
		final java.util.concurrent.ExecutorService pool = Executors.newSingleThreadExecutor();
		try {
			assertEquals("response", JdkHttpTransport.onExecutor(done, pool, request).join());
		} finally {
			pool.shutdownNow();
		}
	}

	@Test
	void rejectedRetrySchedulingFailsTheRequest() {
		failFirst = 1;
		final java.util.concurrent.ExecutorService shut = Executors.newSingleThreadExecutor();
		// Refuses only the retry hand-off, which the JDK's delay thread submits after the back-off, as a saturated
		// executor would; the HTTP client's own tasks on the same executor still run.
		final java.util.concurrent.Executor failingLater = command -> {
			if (Thread.currentThread().getName().toLowerCase(java.util.Locale.ROOT).contains("delay")) {
				throw new java.util.concurrent.RejectedExecutionException("saturated");
			}
			shut.execute(command);
		};
		try {
			final JdkHttpTransport transport = JdkHttpTransport.builder().retryInterval(Duration.ofMillis(10))
					.executor(failingLater).build();
			final CompletionException error = assertThrows(CompletionException.class, () -> transport
					.send(new Transport.Request("GET", base.resolve("/context"), Map.of(), null))
					.orTimeout(5, java.util.concurrent.TimeUnit.SECONDS).join());
			assertTrue(error.getCause() instanceof TransportException, error.getCause().toString());
			assertTrue(error.getCause().getCause() instanceof java.util.concurrent.RejectedExecutionException);
		} finally {
			shut.shutdownNow();
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
