package com.absmartly.sdk;

import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.absmartly.sdk.event.ContextEvent;

/**
 * The {@link Flow.Publisher} behind {@code events()}: a {@link SubmissionPublisher} that delivers asynchronously on the
 * SDK executor. A subscriber that falls more than {@link Flow#defaultBufferSize()} events behind loses the newest
 * events (logged) rather than blocking the thread that caused them.
 */
final class EventStream {
	private static final Logger log = LoggerFactory.getLogger(EventStream.class);

	EventStream(final Executor executor) {
		this(new SubmissionPublisher<ContextEvent>(executor, Flow.defaultBufferSize()));
	}

	// For tests: a publisher that closes between the isClosed check and the offer.
	EventStream(final SubmissionPublisher<ContextEvent> publisher) {
		publisher_ = publisher;
	}

	Flow.Publisher<ContextEvent> publisher() {
		return publisher_;
	}

	// The stream can close between the check and the offer (the owner closing on another thread), when offer throws
	// IllegalStateException; an event racing the close is dropped, never thrown into treatment() or track().
	void submit(final ContextEvent event) {
		if (publisher_.isClosed()) {
			return;
		}
		final int lost;
		try {
			lost = publisher_.offer(event, (subscriber, dropped) -> {
				log.warn("ABsmartly event stream subscriber is too slow, dropped {}", dropped.type());
				return false;
			});
		} catch (final IllegalStateException closed) {
			return;
		} catch (final RuntimeException rejected) {
			// The executor refused to deliver (RejectedExecutionException, shut down or saturated): the subscriber
			// misses this event, but the context's own state and the caller are never affected.
			log.warn("ABsmartly event stream could not deliver {}: {}", event.type(), rejected.toString());
			return;
		}
		// offer() returns minus the number of drops.
		if (lost < 0) {
			droppedCount_.addAndGet(-lost);
		}
	}

	int droppedCount() {
		return droppedCount_.get();
	}

	void close() {
		publisher_.close();
	}

	private final SubmissionPublisher<ContextEvent> publisher_;
	private final AtomicInteger droppedCount_ = new AtomicInteger();
}
