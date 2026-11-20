package com.absmartly.sdk;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/** A {@link Transport} that records requests and answers them with a stubbed function. */
final class FakeTransport implements Transport {
	FakeTransport(final Function<Request, CompletableFuture<Response>> responder) {
		responder_ = responder;
	}

	static FakeTransport ok(final String getBody) {
		return new FakeTransport(request -> CompletableFuture.completedFuture(
				new Response(200, "OK", "GET".equals(request.method()) ? getBody.getBytes(StandardCharsets.UTF_8)
						: new byte[0])));
	}

	static CompletableFuture<Response> status(final int code, final String message) {
		return CompletableFuture.completedFuture(new Response(code, message, null));
	}

	@Override
	public CompletableFuture<Response> send(final Request request) {
		requests.add(request);
		return responder_.apply(request);
	}

	@Override
	public void close() {
		closed = true;
	}

	final List<Request> requests = Collections.synchronizedList(new ArrayList<Request>());
	volatile boolean closed;
	private final Function<Request, CompletableFuture<Response>> responder_;
}
