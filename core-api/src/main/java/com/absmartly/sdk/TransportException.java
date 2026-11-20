package com.absmartly.sdk;

import org.jspecify.annotations.Nullable;

/** A request to the collector failed without a response (connection refused, reset, TLS failure...). */
public class TransportException extends ABsmartlyException {
	private static final long serialVersionUID = 1L;

	public TransportException(final String message, final @Nullable Throwable cause) {
		super(message, cause);
	}
}
