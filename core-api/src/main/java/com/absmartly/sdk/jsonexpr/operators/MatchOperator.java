package com.absmartly.sdk.jsonexpr.operators;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.absmartly.sdk.jsonexpr.Evaluator;

public class MatchOperator extends BinaryOperator {
	private static final Logger log = LoggerFactory.getLogger(MatchOperator.class);

	@Override
	public Object binary(Evaluator evaluator, Object lhs, Object rhs) {
		final String text = evaluator.stringConvert(lhs);
		if (text != null) {
			final String pattern = evaluator.stringConvert(rhs);
			if (pattern != null) {
				try {
					final Pattern compiled = Pattern.compile(pattern);
					final Matcher matcher = compiled.matcher(text);
					return matcher.find();
				} catch (PatternSyntaxException e) {
					log.warn("Invalid regex pattern: {}", e.getMessage());
				} catch (Throwable ignored) {}
			}
		}
		return null;
	}
}
