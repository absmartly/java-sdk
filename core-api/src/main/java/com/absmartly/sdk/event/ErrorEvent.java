package com.absmartly.sdk.event;

import com.absmartly.sdk.Context;

/** Fired when loading, refreshing or publishing failed. */
public final class ErrorEvent extends ContextEvent {
	public ErrorEvent(final Context context, final long timestamp, final Throwable cause) {
		super(context, timestamp);
		cause_ = cause;
	}

	@Override
	public Type type() {
		return Type.ERROR;
	}

	/** The failure, never a {@link java.util.concurrent.CompletionException} wrapper. */
	public Throwable cause() {
		return cause_;
	}

	@Override
	public String toString() {
		return "ErrorEvent{" + cause_ + '}';
	}

	private final Throwable cause_;
}
