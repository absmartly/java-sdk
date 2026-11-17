package com.absmartly.sdk;

/**
 * The context data could not be loaded. Thrown by {@link ABsmartlyClient#context(ContextOptions)} and
 * {@link Context#ready()}; {@link #getCause()} is the underlying failure (for example an
 * {@link HttpStatusException}).
 */
public final class ContextLoadException extends ABsmartlyException {
	private static final long serialVersionUID = 1L;

	public ContextLoadException(final Throwable cause) {
		super("ABsmartly Context failed to load: " + describe(cause), cause);
	}

	static String describe(final Throwable cause) {
		final String message = cause.getMessage();
		return message != null ? message : cause.getClass().getSimpleName();
	}
}
