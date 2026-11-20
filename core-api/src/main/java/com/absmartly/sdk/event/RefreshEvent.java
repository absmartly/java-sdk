package com.absmartly.sdk.event;

import com.absmartly.sdk.Context;
import com.absmartly.sdk.ContextData;

/** Fired when refreshed context data was installed. */
public final class RefreshEvent extends ContextEvent {
	public RefreshEvent(final Context context, final long timestamp, final ContextData data) {
		super(context, timestamp);
		data_ = data;
	}

	@Override
	public Type type() {
		return Type.REFRESH;
	}

	/** The installed data. */
	public ContextData data() {
		return data_;
	}

	@Override
	public String toString() {
		return "RefreshEvent{data=" + data_ + '}';
	}

	private final ContextData data_;
}
