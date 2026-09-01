package com.absmartly.sdk;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Hermetic end-to-end integration test that drives the public SDK API against a real local
 * HTTP server (JDK built-in {@link HttpServer}) on an ephemeral port. This exercises the
 * SDK's real Apache HttpAsyncClient transport (no Java-level mocking) and asserts the
	 * on-the-wire request shape documented in the ABSmartly SDK ↔ Collector wire contract:
 * <ul>
 * <li>GET /context with {@code application}/{@code environment} query params and NO auth
 * headers (Java authenticates the fetch via query params).</li>
 * <li>PUT /context publish with the full auth header set and a JSON body containing
 * {@code hashed}, {@code units}, {@code publishedAt}, plus {@code exposures}/{@code goals}
 * when present.</li>
 * </ul>
 */
class LocalHttpServerIntegrationTest {
	private static final ObjectMapper MAPPER = new ObjectMapper();

	static class RecordedRequest {
		String method;
		String path;
		String rawQuery;
		Map<String, String> headers = new HashMap<String, String>();
		byte[] body;
	}

	private HttpServer server;
	private String endpoint;
	private final LinkedBlockingQueue<RecordedRequest> requests = new LinkedBlockingQueue<RecordedRequest>();

	@BeforeEach
	void startServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/context", exchange -> {
			final RecordedRequest recorded = record(exchange);
			requests.add(recorded);

			final byte[] responseBody;
			if ("GET".equals(recorded.method)) {
				// Minimal valid ContextData so the context reaches "ready".
				responseBody = "{\"experiments\":[]}".getBytes(StandardCharsets.UTF_8);
			} else {
				responseBody = "{}".getBytes(StandardCharsets.UTF_8);
			}

			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, responseBody.length);
			try (OutputStream os = exchange.getResponseBody()) {
				os.write(responseBody);
			}
		});
		server.start();

		final int port = server.getAddress().getPort();
		endpoint = "http://127.0.0.1:" + port;
	}

	@AfterEach
	void stopServer() {
		if (server != null) {
			server.stop(0);
		}
	}

	private static RecordedRequest record(final HttpExchange exchange) throws IOException {
		final RecordedRequest recorded = new RecordedRequest();
		recorded.method = exchange.getRequestMethod();
		recorded.path = exchange.getRequestURI().getPath();
		recorded.rawQuery = exchange.getRequestURI().getRawQuery();
		for (final Map.Entry<String, java.util.List<String>> header : exchange.getRequestHeaders().entrySet()) {
			recorded.headers.put(header.getKey().toLowerCase(), String.join(",", header.getValue()));
		}
		recorded.body = readAll(exchange);
		return recorded;
	}

	private static byte[] readAll(final HttpExchange exchange) throws IOException {
		final java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
		final byte[] chunk = new byte[4096];
		int read;
		while ((read = exchange.getRequestBody().read(chunk)) != -1) {
			buffer.write(chunk, 0, read);
		}
		return buffer.toByteArray();
	}

	private static Map<String, String> parseQuery(final String rawQuery) {
		final Map<String, String> params = new HashMap<String, String>();
		if (rawQuery == null) {
			return params;
		}
		for (final String pair : rawQuery.split("&")) {
			final int eq = pair.indexOf('=');
			if (eq < 0) {
				continue;
			}
			try {
				final String key = URLDecoder.decode(pair.substring(0, eq), "UTF-8");
				final String value = URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
				params.put(key, value);
			} catch (final IOException e) {
				throw new RuntimeException(e);
			}
		}
		return params;
	}

	@Test
	void drivesRealHttpForFetchAndPublish() throws Exception {
		final String application = "www";
		final String environment = "test-env";

		final ABSmartly sdk = ABSmartly.builder()
				.endpoint(endpoint)
				.apiKey("test-api-key")
				.application(application)
				.environment(environment)
				.build();

		try {
			final ContextConfig contextConfig = ContextConfig.create()
					.setUnit("user_id", "123456");

			final Context context = sdk.createContext(contextConfig);
			context.waitUntilReady();
			assertTrue(context.isReady());

			// --- Assert the real GET /context fetch ---
			final RecordedRequest get = requests.poll(5, TimeUnit.SECONDS);
			assertNotNull(get, "expected a GET /context request");
			assertEquals("GET", get.method);
			assertEquals("/context", get.path);

			final Map<String, String> query = parseQuery(get.rawQuery);
			assertEquals(application, query.get("application"));
			assertEquals(environment, query.get("environment"));

			// Per the wire contract: Java sends NO auth headers on GET (auth via query params).
			assertFalse(get.headers.containsKey("x-api-key"), "GET must not carry X-API-Key");
			assertFalse(get.headers.containsKey("x-application"), "GET must not carry X-Application");
			assertFalse(get.headers.containsKey("x-environment"), "GET must not carry X-Environment");

			// --- Queue an exposure + a goal, then publish ---
			context.getTreatment("an_experiment");

			final Map<String, Object> properties = new HashMap<String, Object>();
			properties.put("value", 125);
			context.track("a_goal", properties);

			assertTrue(context.getPendingCount() > 0, "expected pending events before publish");

			context.publish();

			// --- Assert the real PUT /context publish ---
			final RecordedRequest put = requests.poll(5, TimeUnit.SECONDS);
			assertNotNull(put, "expected a PUT /context request");
			assertEquals("PUT", put.method);
			assertEquals("/context", put.path);
			assertNull(put.rawQuery, "PUT must not carry query params");

			assertEquals("test-api-key", put.headers.get("x-api-key"));
			assertEquals(application, put.headers.get("x-application"));
			assertEquals(environment, put.headers.get("x-environment"));
			assertEquals("0", put.headers.get("x-application-version"));
			assertNotNull(put.headers.get("x-agent"), "X-Agent must be present");
			assertFalse(put.headers.get("x-agent").isEmpty(), "X-Agent must be non-empty");
			assertTrue(put.headers.containsKey("content-type"), "Content-Type must be present");
			assertTrue(put.headers.get("content-type").contains("application/json"),
					"Content-Type must be application/json, was: " + put.headers.get("content-type"));

			final JsonNode body = MAPPER.readTree(put.body);
			assertTrue(body.has("hashed"), "body must contain 'hashed'");
			assertTrue(body.get("hashed").isBoolean());
			assertTrue(body.has("publishedAt"), "body must contain 'publishedAt'");
			assertTrue(body.get("publishedAt").canConvertToLong());

			assertTrue(body.has("units"), "body must contain 'units'");
			assertTrue(body.get("units").isArray());
			assertTrue(body.get("units").size() > 0, "units must be non-empty");
			final JsonNode unit = body.get("units").get(0);
			assertTrue(unit.has("type"));
			assertTrue(unit.has("uid"));
			assertEquals("user_id", unit.get("type").asText());

			// We tracked a goal, so 'goals' must be present and non-empty.
			assertTrue(body.has("goals"), "body must contain 'goals' after track()");
			assertTrue(body.get("goals").isArray());
			assertTrue(body.get("goals").size() > 0, "goals must be non-empty");
			assertEquals("a_goal", body.get("goals").get(0).get("name").asText());

			// getTreatment queued an exposure, so 'exposures' must be present and non-empty.
			assertTrue(body.has("exposures"), "body must contain 'exposures' after getTreatment()");
			assertTrue(body.get("exposures").isArray());
			assertTrue(body.get("exposures").size() > 0, "exposures must be non-empty");
			assertEquals("an_experiment", body.get("exposures").get(0).get("name").asText());
		} finally {
			sdk.close();
		}
	}
}
