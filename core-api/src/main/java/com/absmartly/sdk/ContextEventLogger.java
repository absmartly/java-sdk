package com.absmartly.sdk;

public interface ContextEventLogger {
	enum EventType {
		Error, Ready, Refresh, Publish, Exposure, Goal, Close
	}

	/**
	 * Called synchronously by the context. Ready, Refresh, Close and Error events are delivered
	 * before the matching future ({@code waitUntilReadyAsync}, {@code refreshAsync},
	 * {@code closeAsync}) completes, so an implementation must not wait on that future: it would
	 * block forever.
	 */
	void handleEvent(Context context, EventType type, Object data);
}
