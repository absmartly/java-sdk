package com.absmartly.sdk;

import org.jspecify.annotations.Nullable;

/** A response body, or a variable value, could not be decoded. */
public final class DecodingException extends ABsmartlyException {
	private static final long serialVersionUID = 1L;

	public DecodingException(final String message, final @Nullable Throwable cause) {
		super(message, cause);
	}
}
