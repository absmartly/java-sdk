package com.absmartly.sdk.jsonexpr;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

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
}
