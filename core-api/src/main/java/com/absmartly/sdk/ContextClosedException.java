package com.absmartly.sdk;

/** The context (or the client) is closing or closed, so it no longer accepts this call. */
public final class ContextClosedException extends ABsmartlyException {
	private static final long serialVersionUID = 1L;

	public ContextClosedException(final String message) {
		super(message);
	}
}
