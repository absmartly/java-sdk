package com.absmartly.sdk;

import java.io.Closeable;
import java.util.Map;
import java8.util.concurrent.CompletableFuture;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public interface HTTPClient extends Closeable {
	interface Response {
		int getStatusCode();

		String getStatusMessage();

		String getContentType();

		byte[] getContent();
	}

	CompletableFuture<Response> get(@Nonnull final String url, @Nullable final Map<String, String> query,
			@Nullable final Map<String, String> headers);

	CompletableFuture<Response> put(@Nonnull final String url, @Nullable final Map<String, String> query,
			@Nullable final Map<String, String> headers, @Nonnull final byte[] body);

	CompletableFuture<Response> post(@Nonnull final String url, @Nullable final Map<String, String> query,
			@Nullable final Map<String, String> headers, @Nonnull final byte[] body);
}
