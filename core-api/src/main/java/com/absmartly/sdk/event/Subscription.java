package com.absmartly.sdk.event;

/** Removes an {@link EventListener} that was added with {@code onEvent}. */
@FunctionalInterface
public interface Subscription extends AutoCloseable {
	/** Stops delivering events to the listener. Idempotent. */
	@Override
	void close();
}
