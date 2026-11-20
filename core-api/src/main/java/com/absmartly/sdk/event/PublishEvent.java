package com.absmartly.sdk.event;

import java.util.List;

import com.absmartly.sdk.Context;
import com.absmartly.sdk.Exposure;
import com.absmartly.sdk.Goal;

/** Fired after queued exposures and goals were published. */
public final class PublishEvent extends ContextEvent {
	public PublishEvent(final Context context, final long timestamp, final List<Exposure> exposures,
			final List<Goal> goals, final String json) {
		super(context, timestamp);
		exposures_ = exposures;
		goals_ = goals;
		json_ = json;
	}

	@Override
	public Type type() {
		return Type.PUBLISH;
	}

	public List<Exposure> exposures() {
		return exposures_;
	}

	public List<Goal> goals() {
		return goals_;
	}

	/** The request body that was sent, as JSON (units are hashed). */
	public String toJson() {
		return json_;
	}

	@Override
	public String toString() {
		return "PublishEvent{exposures=" + exposures_ + ", goals=" + goals_ + '}';
	}

	private final List<Exposure> exposures_;
	private final List<Goal> goals_;
	private final String json_;
}
