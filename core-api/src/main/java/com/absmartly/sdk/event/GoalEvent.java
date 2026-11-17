package com.absmartly.sdk.event;

import com.absmartly.sdk.Context;
import com.absmartly.sdk.Goal;

/** Fired when a goal is queued for publishing by {@link Context#track(String, java.util.Map)}. */
public final class GoalEvent extends ContextEvent {
	public GoalEvent(final Context context, final long timestamp, final Goal goal) {
		super(context, timestamp);
		goal_ = goal;
	}

	@Override
	public Type type() {
		return Type.GOAL;
	}

	public Goal goal() {
		return goal_;
	}

	@Override
	public String toString() {
		return "GoalEvent{" + goal_ + '}';
	}

	private final Goal goal_;
}
