package com.absmartly.sdk.event;

import com.absmartly.sdk.Context;
import com.absmartly.sdk.Exposure;

/** Fired when an exposure is queued for publishing. */
public final class ExposureEvent extends ContextEvent {
	public ExposureEvent(final Context context, final long timestamp, final Exposure exposure) {
		super(context, timestamp);
		exposure_ = exposure;
	}

	@Override
	public Type type() {
		return Type.EXPOSURE;
	}

	public Exposure exposure() {
		return exposure_;
	}

	@Override
	public String toString() {
		return "ExposureEvent{" + exposure_ + '}';
	}

	private final Exposure exposure_;
}
