package com.absmartly.sdk.jsonexpr.operators;

import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.absmartly.sdk.jsonexpr.Evaluator;

public class MatchOperator extends BinaryOperator {
	private static final Logger log = LoggerFactory.getLogger(MatchOperator.class);
	private static final int MAX_PATTERN_LENGTH = 1000;
	private static final int MAX_TEXT_LENGTH = 10000;
	private static final int REGEX_TIMEOUT_MS = 100;
	private static final ExecutorService REGEX_POOL = new ThreadPoolExecutor(
			0, 4, 60L, TimeUnit.SECONDS,
			new SynchronousQueue<Runnable>(),
			new DaemonThreadFactory(),
			new ThreadPoolExecutor.AbortPolicy());

	@Override
	public Object binary(Evaluator evaluator, Object lhs, Object rhs) {
		final String text = evaluator.stringConvert(lhs);
		if (text != null) {
			final String pattern = evaluator.stringConvert(rhs);
			if (pattern != null) {
				if (pattern.length() > MAX_PATTERN_LENGTH) {
					log.warn("Regex pattern exceeds maximum length of {}: {}", MAX_PATTERN_LENGTH,
							pattern.substring(0, 50) + "...");
					return null;
				}

				if (text.length() > MAX_TEXT_LENGTH) {
					log.warn("Regex input text exceeds maximum length of {}", MAX_TEXT_LENGTH);
					return null;
				}

				try {
					final Pattern compiled = Pattern.compile(pattern);
					final InterruptibleCharSequence interruptible = new InterruptibleCharSequence(text);

					Future<Boolean> future;
					try {
						future = REGEX_POOL.submit(new Callable<Boolean>() {
							@Override
							public Boolean call() {
								final Matcher matcher = compiled.matcher(interruptible);
								return matcher.find();
							}
						});
					} catch (RejectedExecutionException e) {
						log.warn("Regex pool saturated; skipping match to avoid blocking caller");
						return null;
					}

					try {
						return future.get(REGEX_TIMEOUT_MS, TimeUnit.MILLISECONDS);
					} catch (TimeoutException e) {
						future.cancel(true);
						log.warn("Regex pattern timed out after {}ms, possible ReDoS attack: {}", REGEX_TIMEOUT_MS,
								pattern);
						return null;
					} catch (InterruptedException e) {
						future.cancel(true);
						Thread.currentThread().interrupt();
						return null;
					} catch (ExecutionException e) {
						Throwable cause = e.getCause();
						if (cause instanceof InterruptibleCharSequence.InterruptedCharAccessException) {
							log.warn("Regex pattern interrupted after timeout, possible ReDoS attack: {}", pattern);
							return null;
						}
						log.warn("Regex execution failed: {}", cause != null ? cause.getMessage() : e.getMessage());
						return null;
					}
				} catch (PatternSyntaxException e) {
					log.warn("Invalid regex pattern from server: {}", e.getMessage());
					return null;
				}
			}
		}
		return null;
	}

	static class InterruptibleCharSequence implements CharSequence {
		private final CharSequence delegate;

		InterruptibleCharSequence(CharSequence delegate) {
			this.delegate = delegate;
		}

		@Override
		public int length() {
			if (Thread.currentThread().isInterrupted()) {
				throw new InterruptedCharAccessException();
			}
			return delegate.length();
		}

		@Override
		public char charAt(int index) {
			if (Thread.currentThread().isInterrupted()) {
				throw new InterruptedCharAccessException();
			}
			return delegate.charAt(index);
		}

		@Override
		public CharSequence subSequence(int start, int end) {
			return new InterruptibleCharSequence(delegate.subSequence(start, end));
		}

		@Override
		public String toString() {
			return delegate.toString();
		}

		static class InterruptedCharAccessException extends RuntimeException {
			InterruptedCharAccessException() {
				super("CharSequence access interrupted");
			}
		}
	}

	private static class DaemonThreadFactory implements ThreadFactory {
		@Override
		public Thread newThread(Runnable r) {
			Thread t = new Thread(r);
			t.setDaemon(true);
			t.setName("absmartly-regex-" + t.getId());
			return t;
		}
	}
}
