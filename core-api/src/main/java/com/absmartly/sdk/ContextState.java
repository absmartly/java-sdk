package com.absmartly.sdk;

/** The lifecycle of a {@link Context}. */
public enum ContextState {
	/** The context data is being fetched. Reads throw {@link ContextNotReadyException}. */
	LOADING,
	/** The data is loaded; reads and writes work. */
	READY,
	/**
	 * The data could not be loaded ({@link Context#failure()} holds why). The context still works with no
	 * experiments: every treatment is 0 and every variable its default, and nothing is published.
	 */
	FAILED,
	/** {@link Context#close()} is publishing the pending events. */
	CLOSING,
	/** Closed: writes throw {@link ContextClosedException}. */
	CLOSED
}
