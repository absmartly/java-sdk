package com.absmartly.sdk;

import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;

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
		publisher_ = new SubmissionPublisher<ContextEvent>(executor, Flow.defaultBufferSize());
	}

	Flow.Publisher<ContextEvent> publisher() {
		return publisher_;
	}

	void submit(final ContextEvent event) {
		if (publisher_.isClosed()) {
			return;
		}
		publisher_.offer(event, (subscriber, dropped) -> {
			log.warn("ABsmartly event stream subscriber is too slow, dropped {}", dropped.type());
			return false;
		});
	}

	void close() {
		publisher_.close();
	}

	private final SubmissionPublisher<ContextEvent> publisher_;
}
