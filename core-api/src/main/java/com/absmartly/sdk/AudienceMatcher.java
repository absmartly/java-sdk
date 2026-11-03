package com.absmartly.sdk;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.absmartly.sdk.java.nio.charset.StandardCharsets;
import com.absmartly.sdk.jsonexpr.JsonExpr;

public class AudienceMatcher {
	private static final Logger log = LoggerFactory.getLogger(AudienceMatcher.class);

	// Same check as the backend's experiment split: the gap from 100 is rounded to two decimals before being
	// compared with this tolerance.
	private static final double SPLIT_PERCENTAGES_SUM_TOLERANCE = 0.01;

	public AudienceMatcher(AudienceDeserializer deserializer) {
		this(deserializer, new JsonExpr());
	}

	AudienceMatcher(AudienceDeserializer deserializer, JsonExpr jsonExpr) {
		deserializer_ = deserializer;
		jsonExpr_ = jsonExpr;
	}

	public static class Result {
		public Result(boolean result) {
			this.result = result;
		}

		public boolean get() {
			return result;
		}

		private final boolean result;
	}

	/**
	 * The action of the first matching assignment rule: either a fixed variant ({@link #isSplit()} false) or a split
	 * to randomize the unit over ({@link #isSplit()} true). Instances only come from the rule parser, so a split is
	 * always valid (non-negative fractions summing to 1).
	 */
	static final class RuleAction {
		private RuleAction(final int variant, final double[] split) {
			variant_ = variant;
			split_ = split;
		}

		static RuleAction assign(final int variant) {
			return new RuleAction(variant, null);
		}

		static RuleAction split(final double[] split) {
			return new RuleAction(0, split.clone());
		}

		boolean isSplit() {
			return split_ != null;
		}

		/** The variant to assign. Throws when this is a split. */
		int getVariant() {
			if (isSplit()) {
				throw new IllegalStateException("A split rule action has no fixed variant");
			}
			return variant_;
		}

		/** The split fractions, one per variant. Throws when this is a fixed variant. */
		double[] getSplit() {
			if (!isSplit()) {
				throw new IllegalStateException("An assign rule action has no split");
			}
			return split_.clone();
		}

		@Override
		public boolean equals(Object o) {
			if (this == o)
				return true;
			if (o == null || getClass() != o.getClass())
				return false;
			final RuleAction that = (RuleAction) o;
			return variant_ == that.variant_ && Arrays.equals(split_, that.split_);
		}

		@Override
		public int hashCode() {
			return 31 * variant_ + Arrays.hashCode(split_);
		}

		@Override
		public String toString() {
			return isSplit() ? "RuleAction{split=" + Arrays.toString(split_) + "}"
					: "RuleAction{variant=" + variant_ + "}";
		}

		private final int variant_;
		private final double[] split_;
	}

	/**
	 * Evaluates the audience filter. Returns null when the audience is empty or has no filter. When evaluating the
	 * filter fails (including a filter nested deeply enough to overflow the stack) the result is a mismatch, so a
	 * strict audience fails closed and a malformed audience never throws into the caller.
	 */
	public Result evaluate(String audience, Map<String, Object> attributes) {
		final Map<String, Object> audienceMap = deserialize(audience);
		if (audienceMap != null) {
			final Object filter = audienceMap.get("filter");
			if (filter instanceof Map || filter instanceof List) {
				try {
					return new Result(jsonExpr_.evaluateBooleanExpr(filter, attributes));
				} catch (RuntimeException e) {
					log.warn("Failed to evaluate audience filter; treating it as a mismatch", e);
					return new Result(false);
				} catch (StackOverflowError e) {
					log.warn(
							"Failed to evaluate audience filter: expression is nested too deeply; treating it as a mismatch");
					return new Result(false);
				}
			}
		}

		return null;
	}

	/**
	 * Assignment rules parsed once from their JSON string. {@link #isInvalid()} is true when the string is not valid
	 * JSON; a valid document without a {@code rules} list has no rules.
	 */
	static final class ParsedRules {
		ParsedRules(final List<?> rules) {
			rules_ = rules;
			invalid_ = false;
		}

		private ParsedRules(final boolean invalid) {
			rules_ = null;
			invalid_ = invalid;
		}

		boolean isInvalid() {
			return invalid_;
		}

		private final List<?> rules_;
		private final boolean invalid_;
	}

	private static final ParsedRules INVALID_RULES = new ParsedRules(true);
	private static final ParsedRules NO_RULES = new ParsedRules(false);

	/** Parses an assignment rules JSON string, or returns null when it is null or empty. */
	ParsedRules parseRules(final String assignmentRules) {
		if (assignmentRules == null || assignmentRules.isEmpty()) {
			return null;
		}
		final Map<String, Object> rulesMap = deserialize(assignmentRules);
		if (rulesMap == null) {
			return INVALID_RULES;
		}
		final Object rules = rulesMap.get("rules");
		return rules instanceof List ? new ParsedRules((List<?>) rules) : NO_RULES;
	}

	/** Parses and evaluates assignment rules in one step. See {@link #evaluateRules(ParsedRules, String, Map, String)}. */
	RuleAction evaluateRules(String assignmentRules, String environmentName, Map<String, Object> attributes) {
		return evaluateRules(parseRules(assignmentRules), environmentName, attributes, null);
	}

	/**
	 * Evaluates parsed assignment rules in order and returns the action of the first rule that matches, or null when
	 * none does (or the rules are null or invalid).
	 *
	 * A rule is skipped when it is not an object, its type is unknown, an "assign" rule has a non-integer variant, a
	 * "split" rule has invalid percentages, its environments are present but not a list, its non-empty environments
	 * list does not contain {@code environmentName} (a null environment name never matches a non-empty list), its
	 * conditions are not an object, or its conditions do not evaluate to true. Conditions that throw, or are nested
	 * deeply enough to overflow the stack, are logged with the experiment name and the rule is skipped. A rule without
	 * conditions always matches, and an empty environments list matches every environment. Variant bounds are the
	 * caller's responsibility.
	 */
	RuleAction evaluateRules(final ParsedRules parsed, final String environmentName,
			final Map<String, Object> attributes, final String experimentName) {
		if (parsed == null || parsed.rules_ == null) {
			return null;
		}

		for (final Object ruleObj : parsed.rules_) {
			if (!(ruleObj instanceof Map)) {
				continue;
			}
			final Map<?, ?> rule = (Map<?, ?>) ruleObj;

			final RuleAction action = parseRuleAction(rule);
			if (action == null) {
				continue;
			}

			final Object environments = rule.get("environments");
			if (environments != null) {
				if (!(environments instanceof List)) {
					continue;
				}

				final List<?> envs = (List<?>) environments;
				if (!envs.isEmpty() && (environmentName == null || !envs.contains(environmentName))) {
					continue;
				}
			}

			final Object conditions = rule.get("conditions");
			if (conditions == null) {
				return action;
			}

			if (!(conditions instanceof Map)) {
				continue;
			}

			try {
				if (jsonExpr_.evaluateBooleanExpr(conditions, attributes)) {
					return action;
				}
			} catch (RuntimeException e) {
				log.warn("Failed to evaluate assignment rule conditions for experiment {}, rule {}", experimentName,
						rule.get("name"), e);
			} catch (StackOverflowError e) {
				log.warn("Failed to evaluate assignment rule conditions for experiment {}, rule {}: nested too deeply",
						experimentName, rule.get("name"));
			}
		}

		return null;
	}

	private Map<String, Object> deserialize(final String json) {
		if (json == null || json.isEmpty()) {
			return null;
		}
		final byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
		return deserializer_.deserialize(bytes, 0, bytes.length);
	}

	private static RuleAction parseRuleAction(final Map<?, ?> rule) {
		final Object type = rule.get("type");
		if ("assign".equals(type)) {
			final Integer variant = asInteger(rule.get("variant"));
			return variant != null ? RuleAction.assign(variant) : null;
		} else if ("split".equals(type)) {
			final double[] split = parseSplitPercentages(rule.get("percentages"));
			return split != null ? RuleAction.split(split) : null;
		}
		return null;
	}

	// Mirrors JavaScript's Number.isInteger: integral numbers (including 2.0) within int range.
	private static Integer asInteger(final Object value) {
		if (!(value instanceof Number)) {
			return null;
		}
		final double d = ((Number) value).doubleValue();
		if (Double.isNaN(d) || Double.isInfinite(d) || d != Math.floor(d) || d > Integer.MAX_VALUE
				|| d < Integer.MIN_VALUE) {
			return null;
		}
		return (int) d;
	}

	static double[] parseSplitPercentages(final Object percentages) {
		if (!(percentages instanceof String)) {
			return null;
		}

		final String[] parts = ((String) percentages).split("/", -1);
		final double[] values = new double[parts.length];
		double total = 0.0;
		for (int i = 0; i < parts.length; ++i) {
			final double value = parseFloat(parts[i]);
			if (Double.isNaN(value) || value < 0) {
				return null;
			}
			values[i] = value;
			total += value;
		}

		final double gap = Math.abs(100 - total);
		if (Double.isNaN(gap) || Double.isInfinite(gap)) {
			return null;
		}

		// JavaScript's toFixed(2) rounds the exact binary value half up.
		final double roundedGap = new BigDecimal(gap).setScale(2, RoundingMode.HALF_UP).doubleValue();
		if (roundedGap > SPLIT_PERCENTAGES_SUM_TOLERANCE) {
			return null;
		}

		for (int i = 0; i < values.length; ++i) {
			values[i] = values[i] / 100;
		}
		return values;
	}

	// JavaScript parseFloat semantics: leading whitespace is skipped and the longest valid decimal prefix is parsed,
	// so "50abc" is 50 and "abc" is NaN.
	static double parseFloat(final String s) {
		int i = 0;
		final int n = s.length();
		while (i < n && Character.isWhitespace(s.charAt(i))) {
			++i;
		}

		final int start = i;
		if (i < n && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
			++i;
		}

		if (s.startsWith("Infinity", i)) {
			return s.charAt(start) == '-' ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
		}

		boolean digits = false;
		while (i < n && isAsciiDigit(s.charAt(i))) {
			++i;
			digits = true;
		}
		if (i < n && s.charAt(i) == '.') {
			++i;
			while (i < n && isAsciiDigit(s.charAt(i))) {
				++i;
				digits = true;
			}
		}
		if (!digits) {
			return Double.NaN;
		}

		int end = i;
		if (i < n && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
			int j = i + 1;
			if (j < n && (s.charAt(j) == '+' || s.charAt(j) == '-')) {
				++j;
			}
			final int expStart = j;
			while (j < n && isAsciiDigit(s.charAt(j))) {
				++j;
			}
			if (j > expStart) {
				end = j;
			}
		}

		return Double.parseDouble(s.substring(start, end));
	}

	private static boolean isAsciiDigit(final char c) {
		return c >= '0' && c <= '9';
	}

	private final AudienceDeserializer deserializer_;
	private final JsonExpr jsonExpr_;
}
