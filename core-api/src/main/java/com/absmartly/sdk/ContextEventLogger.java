package com.absmartly.sdk;

public interface ContextEventLogger {
	enum EventType {
		Error, Ready, Refresh, Publish, Exposure, Goal, Close
	}

	void handleEvent(Context context, EventType type, Object data);
}
