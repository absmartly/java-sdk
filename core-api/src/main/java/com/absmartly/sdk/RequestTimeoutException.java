package com.absmartly.sdk;

import org.jspecify.annotations.Nullable;

/** A request to the collector did not complete within the configured timeout. */
public final class RequestTimeoutException extends TransportException {
	private static final long serialVersionUID = 1L;

	public RequestTimeoutException(final String message, final @Nullable Throwable cause) {
		super(message, cause);
	}
}
