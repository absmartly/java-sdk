package com.absmartly.sdk;

/** A read that needs experiment data was made before the context finished loading. */
public final class ContextNotReadyException extends ABsmartlyException {
	private static final long serialVersionUID = 1L;

	public ContextNotReadyException() {
		super("ABsmartly Context is not yet ready");
	}
}
