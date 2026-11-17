package com.absmartly.sdk.event;

import com.absmartly.sdk.Context;
import com.absmartly.sdk.ContextData;

/**
 * Fired when the context data loaded. It is dispatched on the thread that completed the load, right after the
 * futures waiting for it complete, so {@code ABsmartlyClient.context(...)} or {@code Context.ready()} can return a
 * moment before listeners see this event (as with the 1.x event logger).
 */
public final class ReadyEvent extends ContextEvent {
	public ReadyEvent(final Context context, final long timestamp, final ContextData data) {
		super(context, timestamp);
		data_ = data;
	}

	@Override
	public Type type() {
		return Type.READY;
	}

	/** The installed data. */
	public ContextData data() {
		return data_;
	}

	@Override
	public String toString() {
		return "ReadyEvent{data=" + data_ + '}';
	}

	private final ContextData data_;
}
