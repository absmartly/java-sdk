package com.absmartly.sdk;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.absmartly.sdk.internal.SdkVersion;
import com.absmartly.sdk.internal.json.ContextPayload;
import com.absmartly.sdk.internal.json.PublishPayload;

@Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class ClientTest extends TestUtils {
	static Client client(final String endpoint, final String applicationVersion, final Transport transport) {
		return new Client(endpoint, "test-api-key", "website", "dev", applicationVersion, transport, null);
	}

	static Map<String, String> sdkHeaders(final String applicationVersionHeader) {
		return mapOf(
				"X-API-Key", "test-api-key",
				"X-Application", "website",
				"X-Environment", "dev",
				"X-Application-Version", applicationVersionHeader,
				"X-Agent", "absmartly-java-sdk/" + SdkVersion.VERSION);
	}

	@Test
	void createThrowsWithInvalidConfig() {
		final FakeTransport transport = FakeTransport.ok("{}");
		assertEquals("Missing Endpoint configuration", assertThrows(IllegalArgumentException.class,
				() -> new Client("", "k", "a", "e", null, transport, null)).getMessage());
		assertEquals("Missing APIKey configuration", assertThrows(IllegalArgumentException.class,
				() -> new Client("https://localhost/v1", "", "a", "e", null, transport, null)).getMessage());
		assertEquals("Missing Application configuration", assertThrows(IllegalArgumentException.class,
				() -> new Client("https://localhost/v1", "k", " ", "e", null, transport, null)).getMessage());
		assertEquals("Missing Environment configuration", assertThrows(IllegalArgumentException.class,
				() -> new Client("https://localhost/v1", "k", "a", "", null, transport, null)).getMessage());
		assertThrows(IllegalArgumentException.class,
				() -> new Client("ftp://localhost/v1", "k", "a", "e", null, transport, null));
		assertDoesNotThrow(() -> new Client("http://localhost/v1", "k", "a", "e", null, transport, null));
	}

	@Test
	void getContextDataSendsQueryAndSdkHeaders() {
		final FakeTransport transport = FakeTransport.ok(new String(getResourceBytes("context.json"),
				StandardCharsets.UTF_8));
		final ContextPayload data = client("https://localhost/v1", "1004", transport).getContextData().join();

		assertEquals(4, data.experiments.length);
		assertEquals(1, transport.requests.size());
		final Transport.Request request = transport.requests.get(0);
		assertEquals("GET", request.method());
		assertEquals(URI.create("https://localhost/v1/context?application=website&environment=dev"), request.uri());
		assertEquals(sdkHeaders("1004"), request.headers());
		assertNull(request.body());
	}

	@Test
	void getAndPublishSendTheSameHeaders() {
		// cross-sdk scenario 320: the context GET carries the SDK headers, including X-Agent, like the publish PUT
		final FakeTransport transport = FakeTransport.ok("{}");
		final Client client = client("https://localhost/v1", null, transport);
		client.getContextData().join();
		client.publish(null, new PublishPayload()).join();

		assertEquals(2, transport.requests.size());
		final Map<String, String> publishHeaders = new java.util.HashMap<>(transport.requests.get(1).headers());
		assertEquals("application/json; charset=UTF-8", publishHeaders.remove("Content-Type"));
		assertEquals(transport.requests.get(0).headers(), publishHeaders);
		assertTrue(transport.requests.get(0).headers().get("X-Agent")
				.matches("^absmartly-[a-z0-9]+-sdk/[0-9]+\\.[0-9]+\\.[0-9]+([-+][0-9A-Za-z.+-]*)?$"));
	}

	@Test
	void endpointTrailingSlashIsNormalized() {
		final FakeTransport transport = FakeTransport.ok("{}");
		final Client client = client("https://localhost/v1/", null, transport);
		client.getContextData().join();
		client.publish(null, new PublishPayload()).join();

		assertEquals("/v1/context", transport.requests.get(0).uri().getPath());
		assertEquals(URI.create("https://localhost/v1/context"), transport.requests.get(1).uri());
	}

	@Test
	void queryParametersAreEncoded() {
		final FakeTransport transport = FakeTransport.ok("{}");
		new Client("https://localhost/v1", "k", "my app", "pré&prod", null, transport, null).getContextData().join();
		assertEquals("application=my%20app&environment=pr%C3%A9%26prod",
				transport.requests.get(0).uri().getRawQuery());
	}

	@Test
	void publishPutsJsonBody() {
		final FakeTransport transport = FakeTransport.ok("{}");
		final PublishPayload event = new PublishPayload();
		event.hashed = true;
		event.publishedAt = 123;
		client("https://localhost/v1", null, transport).publish(null, event).join();

		final Transport.Request request = transport.requests.get(0);
		assertEquals("PUT", request.method());
		final Map<String, String> expectedHeaders = sdkHeaders("0");
		expectedHeaders.put("Content-Type", "application/json; charset=UTF-8");
		assertEquals(expectedHeaders, request.headers());
		assertEquals("{\"hashed\":true,\"publishedAt\":123}", new String(request.body(), StandardCharsets.UTF_8));
	}

	@Test
	void getContextDataFailsWithHttpStatus() {
		final Client client = client("https://localhost/v1", null,
				new FakeTransport(r -> FakeTransport.status(500, "Internal Server Error")));
		final CompletionException actual = assertThrows(CompletionException.class,
				() -> client.getContextData().join());
		final HttpStatusException cause = (HttpStatusException) actual.getCause();
		assertEquals(500, cause.statusCode());
		assertEquals("Internal Server Error", cause.getMessage());
	}

	@Test
	void missingReasonPhraseUsesStandardOne() {
		final Client client = client("https://localhost/v1", null,
				new FakeTransport(r -> FakeTransport.status(503, "")));
		final CompletionException actual = assertThrows(CompletionException.class,
				() -> client.publish(null, new PublishPayload()).join());
		assertEquals("Service Unavailable", actual.getCause().getMessage());

		final Client other = client("https://localhost/v1", null,
				new FakeTransport(r -> FakeTransport.status(599, "")));
		assertEquals("HTTP 599", assertThrows(CompletionException.class,
				() -> other.getContextData().join()).getCause().getMessage());
	}

	@Test
	void connectionFailureIsWrapped() {
		final RuntimeException failure = new RuntimeException("connection refused");
		final Client client = client("https://localhost/v1", null, new FakeTransport(r -> failedFuture(failure)));
		final CompletionException actual = assertThrows(CompletionException.class,
				() -> client.getContextData().join());
		assertTrue(actual.getCause() instanceof TransportException);
		assertSame(failure, actual.getCause().getCause());
		assertEquals("GET /v1/context failed: connection refused", actual.getCause().getMessage());
	}

	@Test
	void transportThatThrowsFailsTheFuture() {
		final Client client = client("https://localhost/v1", null, new FakeTransport(r -> {
			throw new IllegalStateException("boom");
		}));
		final CompletionException actual = assertThrows(CompletionException.class,
				() -> client.publish(null, new PublishPayload()).join());
		assertTrue(actual.getCause() instanceof TransportException);
	}

	@Test
	void transportAbsmartlyExceptionIsKept() {
		final RequestTimeoutException timeout = new RequestTimeoutException("timed out", null);
		final Client client = client("https://localhost/v1", null, new FakeTransport(r -> failedFuture(timeout)));
		assertSame(timeout, assertThrows(CompletionException.class, () -> client.getContextData().join()).getCause());
	}

	@Test
	void emptyOrInvalidBodyFailsWithDecodingException() {
		final Client empty = client("https://localhost/v1", null,
				new FakeTransport(r -> CompletableFuture.completedFuture(new Transport.Response(200, "OK", null))));
		assertTrue(assertThrows(CompletionException.class,
				() -> empty.getContextData().join()).getCause() instanceof DecodingException);

		final Client invalid = client("https://localhost/v1", null, FakeTransport.ok("invalid"));
		assertTrue(assertThrows(CompletionException.class,
				() -> invalid.getContextData().join()).getCause() instanceof DecodingException);

		final Client nullBody = client("https://localhost/v1", null, FakeTransport.ok("null"));
		assertTrue(assertThrows(CompletionException.class,
				() -> nullBody.getContextData().join()).getCause() instanceof DecodingException);
	}

	@Test
	void applicationVersionHeaderIsSentOnlyWhenNumeric() {
		// The collector parses X-Application-Version as a Long and rejects the request otherwise.
		assertEquals("1004", Client.applicationVersionHeader("1004"));
		assertEquals("0", Client.applicationVersionHeader("0"));
		assertEquals("0", Client.applicationVersionHeader("2.4.1"));
		assertEquals("0", Client.applicationVersionHeader("1.0"));
		assertEquals("0", Client.applicationVersionHeader("abc"));
		assertEquals("0", Client.applicationVersionHeader("-1"));
		assertEquals("0", Client.applicationVersionHeader("99999999999999999999"));
		assertEquals("999999999999999999", Client.applicationVersionHeader("999999999999999999"));
	}

	@Test
	void applicationVersionIsNormalized() {
		assertEquals("0", client("https://localhost/v1", null, FakeTransport.ok("{}")).getApplicationVersion());
		assertEquals("0", client("https://localhost/v1", "  ", FakeTransport.ok("{}")).getApplicationVersion());
		assertEquals("2.4.1", client("https://localhost/v1", " 2.4.1 ", FakeTransport.ok("{}"))
				.getApplicationVersion());

		final FakeTransport transport = FakeTransport.ok("{}");
		client("https://localhost/v1", "2.4.1", transport).getContextData().join();
		assertEquals("0", transport.requests.get(0).headers().get("X-Application-Version"));
	}

	@Test
	void closeClosesTransport() {
		final FakeTransport transport = FakeTransport.ok("{}");
		client("https://localhost/v1", null, transport).close();
		assertTrue(transport.closed);
	}
}
