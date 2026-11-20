package com.absmartly.sdk.event;

/**
 * Receives a context's events synchronously, on the thread that caused them (for example the caller of
 * {@code treatment()}), so keep it fast. An exception thrown by a listener is logged and does not affect the SDK.
 */
@FunctionalInterface
public interface EventListener {
	void onEvent(ContextEvent event);
}
