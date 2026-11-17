package com.absmartly.sdk.event;

import com.absmartly.sdk.Context;

/**
 * An event in a context's life. Switch on {@link #type()} and cast to the matching subclass (on Java 16+ user code can
 * use {@code instanceof} patterns instead):
 *
 * <pre>{@code
 * switch (event.type()) {
 * case EXPOSURE:
 * 	Exposure exposure = ((ExposureEvent) event).exposure();
 * 	break;
 * default:
 * }
 * }</pre>
 */
public abstract class ContextEvent {
	/** The kind of a {@link ContextEvent}; each kind has its own subclass. */
	public enum Type {
		/** {@link ReadyEvent}: the context data loaded. */
		READY,
		/** {@link RefreshEvent}: refreshed context data was installed. */
		REFRESH,
		/** {@link ExposureEvent}: an exposure was queued. */
		EXPOSURE,
		/** {@link GoalEvent}: a goal was queued. */
		GOAL,
		/** {@link PublishEvent}: queued events were published. */
		PUBLISH,
		/** {@link CloseEvent}: the context closed. */
		CLOSE,
		/** {@link ErrorEvent}: loading, refreshing or publishing failed. */
		ERROR
	}

	ContextEvent(final Context context, final long timestamp) {
		context_ = context;
		timestamp_ = timestamp;
	}

	/** The context the event happened in. */
	public final Context context() {
		return context_;
	}

	/** When the event happened, in milliseconds since the epoch. */
	public final long timestamp() {
		return timestamp_;
	}

	public abstract Type type();

	private final Context context_;
	private final long timestamp_;
}
