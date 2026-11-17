package com.absmartly.sdk.event;

import com.absmartly.sdk.Context;

/** Fired once, when the context finished closing. */
public final class CloseEvent extends ContextEvent {
	public CloseEvent(final Context context, final long timestamp) {
		super(context, timestamp);
	}

	@Override
	public Type type() {
		return Type.CLOSE;
	}

	@Override
	public String toString() {
		return "CloseEvent{}";
	}
}
