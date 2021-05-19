package com.absmartly.sdk;

import java.io.Closeable;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public interface HTTPClient extends Closeable {
	interface Response {
		int getStatusCode();

		String getStatusMessage();

		String getContentType();

		byte[] getContent();
	}

	CompletableFuture<Response> get(@Nonnull String url, @Nullable Map<String, Object> headers);

	CompletableFuture<Response> put(@Nonnull String url, @Nullable Map<String, Object> headers, @Nonnull byte[] body);

	CompletableFuture<Response> post(@Nonnull String url, @Nullable Map<String, Object> headers, @Nonnull byte[] body);
}
