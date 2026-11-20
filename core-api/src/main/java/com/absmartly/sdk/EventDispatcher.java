package com.absmartly.sdk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.absmartly.sdk.event.CloseEvent;
import com.absmartly.sdk.event.ContextEvent;
import com.absmartly.sdk.event.ErrorEvent;
import com.absmartly.sdk.event.EventListener;
import com.absmartly.sdk.event.ExposureEvent;
import com.absmartly.sdk.event.GoalEvent;
import com.absmartly.sdk.event.PublishEvent;
import com.absmartly.sdk.event.ReadyEvent;
import com.absmartly.sdk.event.RefreshEvent;
import com.absmartly.sdk.event.Subscription;
import com.absmartly.sdk.internal.Clock;
import com.absmartly.sdk.internal.Json;
import com.absmartly.sdk.internal.json.ContextPayload;
import com.absmartly.sdk.internal.json.ExposurePayload;
import com.absmartly.sdk.internal.json.GoalAchievement;
import com.absmartly.sdk.internal.json.PublishPayload;

/**
 * Turns the engine's raw {@link ContextEventLogger} calls into typed {@link ContextEvent}s and delivers them to this
 * scope's listeners and stream, then to the parent scope's (a context's parent is its client). A listener that throws
 * is logged and skipped: user callbacks never break the SDK.
 */
final class EventDispatcher {
	private static final Logger log = LoggerFactory.getLogger(EventDispatcher.class);

	EventDispatcher(final @Nullable EventDispatcher parent, final Clock clock, final Executor executor) {
		parent_ = parent;
		clock_ = clock;
		executor_ = executor;
	}

	Executor executor() {
		return executor_;
	}

	Subscription addListener(final EventListener listener) {
		listeners_.add(listener);
		return () -> listeners_.remove(listener);
	}

	// Loaded on the first call only: Flow and SubmissionPublisher need Android API 30.
	synchronized java.util.concurrent.Flow.Publisher<ContextEvent> stream() {
		if (stream_ == null) {
			final EventStream stream = new EventStream(executor_);
			if (streamClosed_) {
				stream.close();
			}
			stream_ = stream;
		}
		return ((EventStream) stream_).publisher();
	}

	synchronized void closeStream() {
		streamClosed_ = true;
		if (stream_ != null) {
			((EventStream) stream_).close();
		}
	}

	private boolean hasReceivers() {
		return !listeners_.isEmpty() || (stream_ != null) || ((parent_ != null) && parent_.hasReceivers());
	}

	void dispatch(final Context context, final ContextEventLogger.EventType type, final @Nullable Object data) {
		if (!hasReceivers()) {
			return;
		}
		deliver(toEvent(context, type, data));
	}

	private void deliver(final ContextEvent event) {
		for (final EventListener listener : listeners_) {
			try {
				listener.onEvent(event);
			} catch (final RuntimeException e) {
				log.error("ABsmartly event listener threw on {}", event.type(), e);
			}
		}
		final Object stream = stream_;
		if (stream != null) {
			((EventStream) stream).submit(event);
		}
		if (parent_ != null) {
			parent_.deliver(event);
		}
	}

	private ContextEvent toEvent(final Context context, final ContextEventLogger.EventType type,
			final @Nullable Object data) {
		final long now = clock_.millis();
		switch (type) {
		case Ready:
			return new ReadyEvent(context, now, new ContextData((ContextPayload) data));
		case Refresh:
			return new RefreshEvent(context, now, new ContextData((ContextPayload) data));
		case Exposure:
			return new ExposureEvent(context, now, toExposure((ExposurePayload) data));
		case Goal:
			return new GoalEvent(context, now, toGoal((GoalAchievement) data));
		case Publish: {
			final PublishPayload payload = (PublishPayload) data;
			return new PublishEvent(context, now, toExposures(payload.exposures), toGoals(payload.goals),
					Json.encode(payload));
		}
		case Close:
			return new CloseEvent(context, now);
		case Error:
		default:
			return new ErrorEvent(context, now, (Throwable) data);
		}
	}

	static Exposure toExposure(final ExposurePayload e) {
		return new Exposure(e.id, e.name, (e.unit != null) ? e.unit : "", e.variant, e.exposedAt, e.assigned,
				e.eligible, e.overridden, e.fullOn, e.custom, e.audienceMismatch, e.ruleOverride);
	}

	static Goal toGoal(final GoalAchievement g) {
		final Map<String, Object> properties = (g.properties != null)
				? Collections.unmodifiableMap(g.properties)
				: null;
		return new Goal(g.name, g.achievedAt, properties);
	}

	private static List<Exposure> toExposures(final ExposurePayload @Nullable [] exposures) {
		if (exposures == null) {
			return Collections.emptyList();
		}
		final List<Exposure> result = new ArrayList<Exposure>(exposures.length);
		for (final ExposurePayload exposure : exposures) {
			result.add(toExposure(exposure));
		}
		return Collections.unmodifiableList(result);
	}

	private static List<Goal> toGoals(final GoalAchievement @Nullable [] goals) {
		if (goals == null) {
			return Collections.emptyList();
		}
		final List<Goal> result = new ArrayList<Goal>(goals.length);
		for (final GoalAchievement goal : goals) {
			result.add(toGoal(goal));
		}
		return Collections.unmodifiableList(result);
	}

	private final @Nullable EventDispatcher parent_;
	private final Clock clock_;
	private final Executor executor_;
	private final List<EventListener> listeners_ = new CopyOnWriteArrayList<EventListener>();
	// An EventStream once stream() was called; typed Object so this class never links Flow types eagerly.
	private volatile @Nullable Object stream_;
	// Guarded by this.
	private boolean streamClosed_;
}
