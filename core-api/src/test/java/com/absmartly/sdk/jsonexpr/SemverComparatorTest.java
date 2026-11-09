package com.absmartly.sdk.jsonexpr;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashMap;

import org.junit.jupiter.api.Test;

class SemverComparatorTest {
	final ExprEvaluator evaluator = new ExprEvaluator(new HashMap<String, Operator>(), new HashMap<String, Object>());

	Integer cmp(Object lhs, Object rhs) {
		return SemverComparator.compare(evaluator, lhs, rhs);
	}

	@Test
	void equalVersions() {
		assertEquals(0, cmp("1.0.0", "1.0.0"));
		assertEquals(0, cmp("0.0.0", "0.0.0"));
		assertEquals(0, cmp("999.999.999", "999.999.999"));
	}

	@Test
	void majorMinorPatch() {
		assertEquals(1, cmp("2.0.0", "1.0.0"));
		assertEquals(-1, cmp("1.0.0", "2.0.0"));
		assertEquals(1, cmp("1.2.0", "1.1.0"));
		assertEquals(-1, cmp("1.1.0", "1.2.0"));
		assertEquals(1, cmp("1.0.2", "1.0.1"));
		assertEquals(-1, cmp("1.0.1", "1.0.2"));
	}

	@Test
	void numericNotLexicographic() {
		assertEquals(1, cmp("1.10.0", "1.9.0"));
		assertEquals(-1, cmp("1.9.0", "1.10.0"));
		assertEquals(1, cmp("10.0.0", "9.0.0"));
	}

	@Test
	void missingPartsAreZero() {
		assertEquals(0, cmp("1.2", "1.2.0"));
		assertEquals(0, cmp("1", "1.0.0"));
		assertEquals(0, cmp("1.2.0", "1.2"));
		assertEquals(0, cmp("1.0.0", "1"));
	}

	@Test
	void leadingZeros() {
		assertEquals(0, cmp("1.02.0", "1.2.0"));
		assertEquals(0, cmp("1.002.030", "1.2.30"));
		assertEquals(0, cmp("01.0.0", "1.0.0"));
		assertEquals(-1, cmp("1.02.0", "1.3.0"));
		assertEquals(0, cmp("1.00.0", "1.0.0"));
	}

	@Test
	void preRelease() {
		assertEquals(1, cmp("1.0.0", "1.0.0-alpha"));
		assertEquals(-1, cmp("1.0.0-alpha", "1.0.0"));
		assertEquals(0, cmp("1.0.0-alpha", "1.0.0-alpha"));
		assertEquals(-1, cmp("1.0.0-alpha", "1.0.0-beta"));
		assertEquals(1, cmp("1.0.0-beta", "1.0.0-alpha"));
		assertEquals(-1, cmp("1.0.0-alpha.1", "1.0.0-alpha.2"));
		assertEquals(1, cmp("1.0.0-alpha.2", "1.0.0-alpha.1"));
		assertEquals(-1, cmp("1.0.0-1", "1.0.0-2"));
		assertEquals(1, cmp("1.0.0-2", "1.0.0-1"));
		assertEquals(0, cmp("1.0.0-rc-1", "1.0.0-rc-1"));
	}

	@Test
	void numericPreReleaseLessThanAlphanumeric() {
		assertEquals(-1, cmp("1.0.0-1", "1.0.0-alpha"));
		assertEquals(1, cmp("1.0.0-alpha", "1.0.0-1"));
	}

	@Test
	void fewerPreReleaseIdentifiersIsLess() {
		assertEquals(-1, cmp("1.0.0-alpha", "1.0.0-alpha.1"));
		assertEquals(1, cmp("1.0.0-alpha.1", "1.0.0-alpha"));
	}

	@Test
	void vPrefix() {
		assertEquals(0, cmp("v1.0.0", "1.0.0"));
		assertEquals(0, cmp("V1.0.0", "1.0.0"));
		assertEquals(0, cmp("v1.0.0", "V1.0.0"));
	}

	@Test
	void nullInputs() {
		assertNull(cmp(null, "1.0.0"));
		assertNull(cmp("1.0.0", null));
		assertNull(cmp(null, null));
	}

	@Test
	void coercesNonStrings() {
		assertEquals(0, cmp(1, "1.0.0"));
		assertEquals(0, cmp("1.0.0", 1));
		assertEquals(0, cmp(true, "true"));
		assertEquals(0, cmp(2.5, "2.5.0"));
	}

	@Test
	void unconvertibleInputs() {
		assertNull(cmp(new HashMap<String, Object>(), "1.0.0"));
		assertNull(cmp("1.0.0", new HashMap<String, Object>()));
		assertNull(cmp(new ArrayList<Object>(), "1.0.0"));
	}

	@Test
	void buildMetadataIgnored() {
		assertEquals(0, cmp("1.0.0+build1", "1.0.0+build2"));
		assertEquals(0, cmp("1.0.0+build1", "1.0.0"));
		assertEquals(0, cmp("1.0.0-alpha+build1", "1.0.0-alpha+build2"));
		assertEquals(-1, cmp("1.0.0-alpha+build1", "1.0.0-beta"));
		assertEquals(-1, cmp("1.0.0-alpha+build1", "1.0.0"));
	}

	@Test
	void emptyInputs() {
		assertNull(cmp("", "1.0.0"));
		assertNull(cmp("1.0.0", ""));
		assertNull(cmp("", ""));
	}

	@Test
	void emptyCore() {
		assertNull(cmp("v", "1.0.0"));
		assertNull(cmp("V", "1.0.0"));
		assertNull(cmp("+build", "1.0.0"));
		assertNull(cmp("v+build", "1.0.0"));
		assertNull(cmp("1.0.0", "v"));
		assertNull(cmp("-alpha", "1.0.0"));
	}
}
