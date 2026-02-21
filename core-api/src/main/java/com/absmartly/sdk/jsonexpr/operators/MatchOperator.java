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
	private static final int REGEX_TIMEOUT_MS = 100;
	private static final ExecutorService executor = Executors.newCachedThreadPool(r -> {
		Thread t = new Thread(r);
		t.setDaemon(true);
		return t;
	});

	@Override
	public Object binary(Evaluator evaluator, Object lhs, Object rhs) {
		final String text = evaluator.stringConvert(lhs);
		if (text != null) {
			final String pattern = evaluator.stringConvert(rhs);
			if (pattern != null) {
				// Validate pattern length to prevent ReDoS
				if (pattern.length() > MAX_PATTERN_LENGTH) {
					log.warn("Regex pattern exceeds maximum length of {}: {}", MAX_PATTERN_LENGTH,
							pattern.substring(0, 50) + "...");
					return null;
				}

				try {
					final Pattern compiled = Pattern.compile(pattern);

					// Execute regex matching with timeout to prevent ReDoS
					Future<Boolean> future = executor.submit(() -> {
						final Matcher matcher = compiled.matcher(text);
						return matcher.find();
					});

					try {
						return future.get(REGEX_TIMEOUT_MS, TimeUnit.MILLISECONDS);
					} catch (TimeoutException e) {
						future.cancel(true);
						log.warn("Regex pattern timed out after {}ms, possible ReDoS attack: {}", REGEX_TIMEOUT_MS,
								pattern);
						return null;
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						return null;
					} catch (ExecutionException e) {
						log.warn("Regex execution failed: {}", e.getCause().getMessage());
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
}
