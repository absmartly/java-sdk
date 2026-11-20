package com.absmartly.sdk;

import org.jspecify.annotations.Nullable;

/**
 * The engine's raw event hook: every lifecycle point of a {@link Context} calls it with the internal payload.
 * {@link EventDispatcher} turns these into the public typed {@link com.absmartly.sdk.event.ContextEvent}s.
 */
interface ContextEventLogger {
	enum EventType {
		Error, Ready, Refresh, Publish, Exposure, Goal, Close
	}

	void handleEvent(Context context, EventType type, @Nullable Object data);
}
