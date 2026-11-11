package com.absmartly.sdk.jsonexpr;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.absmartly.sdk.TestUtils;

class JsonExprTest extends TestUtils {
	Map<String, Object> valueFor(Object x) {
		return mapOf("value", x);
	}

	Map<String, Object> varFor(Object x) {
		return mapOf("var", mapOf("path", x));
	}

	Map<String, Object> unaryOp(String op, Object arg) {
		return mapOf(op, arg);
	}

	Map<String, Object> binaryOp(String op, Object lhs, Object rhs) {
		return mapOf(op, listOf(lhs, rhs));
	}

	final Map<String, Object> John = mapOf("age", 20, "language", "en-US", "returning", false);
	final Map<String, Object> Terry = mapOf("age", 20, "language", "en-GB", "returning", true);
	final Map<String, Object> Kate = mapOf("age", 50, "language", "es-ES", "returning", false);
	final Map<String, Object> Maria = mapOf("age", 52, "language", "pt-PT", "returning", true);

	final JsonExpr jsonExpr = new JsonExpr();

	final List<Object> AgeTwentyAndUS = listOf(
			binaryOp("eq", varFor("age"), valueFor(20)),
			binaryOp("eq", varFor("language"), valueFor("en-US")));
	final List<Object> AgeOverFifty = listOf(
			binaryOp("gte", varFor("age"), valueFor(50)));

	final List<Object> AgeTwentyAndUS_Or_AgeOverFifty = listOf(
			mapOf("or", listOf(AgeTwentyAndUS, AgeOverFifty)));

	final List<Object> Returning = listOf(
			varFor("returning"));

	final List<Object> Returning_And_AgeTwentyAndUS_Or_AgeOverFifty = listOf(Returning, AgeTwentyAndUS_Or_AgeOverFifty);

	final List<Object> NotReturning_And_Spanish = listOf(unaryOp("not", Returning),
			binaryOp("eq", varFor("language"), valueFor("es-ES")));

	@Test
	void testAgeTwentyAsUSEnglish() {
		assertTrue(jsonExpr.evaluateBooleanExpr(AgeTwentyAndUS, John));
		assertFalse(jsonExpr.evaluateBooleanExpr(AgeTwentyAndUS, Terry));
		assertFalse(jsonExpr.evaluateBooleanExpr(AgeTwentyAndUS, Kate));
		assertFalse(jsonExpr.evaluateBooleanExpr(AgeTwentyAndUS, Maria));
	}

	@Test
	void testAgeOverFifty() {
		assertFalse(jsonExpr.evaluateBooleanExpr(AgeOverFifty, John));
		assertFalse(jsonExpr.evaluateBooleanExpr(AgeOverFifty, Terry));
		assertTrue(jsonExpr.evaluateBooleanExpr(AgeOverFifty, Kate));
		assertTrue(jsonExpr.evaluateBooleanExpr(AgeOverFifty, Maria));
	}

	@Test
	void testAgeTwentyAndUS_Or_AgeOverFifty() {
		assertTrue(jsonExpr.evaluateBooleanExpr(AgeTwentyAndUS_Or_AgeOverFifty, John));
		assertFalse(jsonExpr.evaluateBooleanExpr(AgeTwentyAndUS_Or_AgeOverFifty, Terry));
		assertTrue(jsonExpr.evaluateBooleanExpr(AgeTwentyAndUS_Or_AgeOverFifty, Kate));
		assertTrue(jsonExpr.evaluateBooleanExpr(AgeTwentyAndUS_Or_AgeOverFifty, Maria));
	}

	@Test
	void testReturning() {
		assertFalse(jsonExpr.evaluateBooleanExpr(Returning, John));
		assertTrue(jsonExpr.evaluateBooleanExpr(Returning, Terry));
		assertFalse(jsonExpr.evaluateBooleanExpr(Returning, Kate));
		assertTrue(jsonExpr.evaluateBooleanExpr(Returning, Maria));
	}

	@Test
	void testReturning_And_AgeTwentyAndUS_Or_AgeOverFifty() {
		assertFalse(jsonExpr.evaluateBooleanExpr(Returning_And_AgeTwentyAndUS_Or_AgeOverFifty, John));
		assertFalse(jsonExpr.evaluateBooleanExpr(Returning_And_AgeTwentyAndUS_Or_AgeOverFifty, Terry));
		assertFalse(jsonExpr.evaluateBooleanExpr(Returning_And_AgeTwentyAndUS_Or_AgeOverFifty, Kate));
		assertTrue(jsonExpr.evaluateBooleanExpr(Returning_And_AgeTwentyAndUS_Or_AgeOverFifty, Maria));
	}

	@Test
	void testNotReturning_And_Spanish() {
		assertFalse(jsonExpr.evaluateBooleanExpr(NotReturning_And_Spanish, John));
		assertFalse(jsonExpr.evaluateBooleanExpr(NotReturning_And_Spanish, Terry));
		assertTrue(jsonExpr.evaluateBooleanExpr(NotReturning_And_Spanish, Kate));
		assertFalse(jsonExpr.evaluateBooleanExpr(NotReturning_And_Spanish, Maria));
	}

	@Test
	void testSemverOperators() {
		final Map<String, Object> vars = mapOf("app_version", "2.4.1-beta.2");
		assertTrue(jsonExpr.evaluateBooleanExpr(binaryOp("semver_gte", varFor("app_version"), valueFor("2.4.0")),
				vars));
		assertTrue(jsonExpr.evaluateBooleanExpr(binaryOp("semver_lt", varFor("app_version"), valueFor("2.4.1")),
				vars));
		assertTrue(jsonExpr.evaluateBooleanExpr(binaryOp("semver_gt", varFor("app_version"), valueFor("2.4.1-beta.1")),
				vars));
		assertTrue(jsonExpr.evaluateBooleanExpr(binaryOp("semver_lte", varFor("app_version"),
				valueFor("v2.4.1-beta.2+sha")), vars));
		assertTrue(jsonExpr.evaluateBooleanExpr(binaryOp("semver_eq", varFor("app_version"), valueFor("2.4.1-beta.2")),
				vars));
		assertFalse(jsonExpr.evaluateBooleanExpr(binaryOp("semver_eq", varFor("missing"), valueFor("2.4.1")), vars));
	}

	@Test
	void testNumericOperandIsLocaleIndependent() throws InterruptedException {
		final Map<String, Object> vars = mapOf("version", 2.5);
		final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
		final Locale previous = Locale.getDefault();
		Locale.setDefault(Locale.GERMANY);
		try {
			// A new thread, so the evaluator's thread-local number format is created under the German locale. Under a
			// comma-decimal locale a numeric 2.5 would otherwise convert to "2,5" and never match its string form.
			final Thread thread = new Thread(new Runnable() {
				@Override
				public void run() {
					try {
						assertTrue(jsonExpr.evaluateBooleanExpr(
								binaryOp("eq", varFor("version"), valueFor("2.5")), vars));
						assertFalse(jsonExpr.evaluateBooleanExpr(
								binaryOp("eq", varFor("version"), valueFor("2,5")), vars));
						assertTrue(jsonExpr.evaluateBooleanExpr(
								binaryOp("in", valueFor("value is 2.5 here"), varFor("version")), vars));
						assertTrue(jsonExpr.evaluateBooleanExpr(
								binaryOp("match", varFor("version"), valueFor("^2\\.5$")), vars));
					} catch (Throwable t) {
						failure.set(t);
					}
				}
			});
			thread.start();
			thread.join();
		} finally {
			Locale.setDefault(previous);
		}
		if (failure.get() != null) {
			throw new AssertionError(failure.get());
		}
	}

	@Test
	void testSemverNumericOperandIsLocaleIndependent() throws InterruptedException {
		final Map<String, Object> vars = mapOf("version", 2.5);
		final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
		final Locale previous = Locale.getDefault();
		Locale.setDefault(Locale.GERMANY);
		try {
			// A new thread, so the evaluator's thread-local number formats are created under the German locale.
			final Thread thread = new Thread(new Runnable() {
				@Override
				public void run() {
					try {
						assertTrue(jsonExpr.evaluateBooleanExpr(
								binaryOp("semver_eq", varFor("version"), valueFor("2.5.0")), vars));
						assertFalse(jsonExpr.evaluateBooleanExpr(
								binaryOp("semver_gt", varFor("version"), valueFor("2.5.0")), vars));
						assertTrue(jsonExpr.evaluateBooleanExpr(
								binaryOp("semver_lt", varFor("version"), valueFor("2.10")), vars));
					} catch (Throwable t) {
						failure.set(t);
					}
				}
			});
			thread.start();
			thread.join();
		} finally {
			Locale.setDefault(previous);
		}
		if (failure.get() != null) {
			throw new AssertionError(failure.get());
		}
	}
}
