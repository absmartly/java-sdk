package com.absmartly.sdk;

import org.jspecify.annotations.Nullable;

/**
 * The root of every exception the SDK throws for its own failures. Unchecked, so reads such as
 * {@link Context#treatment(String)} never declare a checked exception. Invalid arguments are reported as
 * {@link IllegalArgumentException}, as everywhere in the JDK.
 */
public class ABsmartlyException extends RuntimeException {
	private static final long serialVersionUID = 1L;

	public ABsmartlyException(final String message) {
		super(message);
	}

	public ABsmartlyException(final String message, final @Nullable Throwable cause) {
		super(message, cause);
	}
}
