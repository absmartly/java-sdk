package com.absmartly.sdk.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.absmartly.sdk.TestUtils;
import com.absmartly.sdk.internal.hashing.Hashing;

class VariantAssignerTest {
	@Test
	void chooseVariant() {
		assertEquals(1, VariantAssigner.chooseVariant(new double[]{0.0, 1.0}, 0.0));
		assertEquals(1, VariantAssigner.chooseVariant(new double[]{0.0, 1.0}, 0.5));
		assertEquals(1, VariantAssigner.chooseVariant(new double[]{0.0, 1.0}, 1.0));

		assertEquals(0, VariantAssigner.chooseVariant(new double[]{1.0, 0.0}, 0.0));
		assertEquals(0, VariantAssigner.chooseVariant(new double[]{1.0, 0.0}, 0.5));
		assertEquals(1, VariantAssigner.chooseVariant(new double[]{1.0, 0.0}, 1.0));

		assertEquals(0, VariantAssigner.chooseVariant(new double[]{0.5, 0.5}, 0.0));
		assertEquals(0, VariantAssigner.chooseVariant(new double[]{0.5, 0.5}, 0.25));
		assertEquals(0, VariantAssigner.chooseVariant(new double[]{0.5, 0.5}, 0.49999999));
		assertEquals(1, VariantAssigner.chooseVariant(new double[]{0.5, 0.5}, 0.5));
		assertEquals(1, VariantAssigner.chooseVariant(new double[]{0.5, 0.5}, 0.50000001));
		assertEquals(1, VariantAssigner.chooseVariant(new double[]{0.5, 0.5}, 0.75));
		assertEquals(1, VariantAssigner.chooseVariant(new double[]{0.5, 0.5}, 1.0));

		assertEquals(0, VariantAssigner.chooseVariant(new double[]{0.333, 0.333, 0.334}, 0.0));
		assertEquals(0, VariantAssigner.chooseVariant(new double[]{0.333, 0.333, 0.334}, 0.25));
		assertEquals(0, VariantAssigner.chooseVariant(new double[]{0.333, 0.333, 0.334}, 0.33299999));
		assertEquals(1, VariantAssigner.chooseVariant(new double[]{0.333, 0.333, 0.334}, 0.333));
		assertEquals(1, VariantAssigner.chooseVariant(new double[]{0.333, 0.333, 0.334}, 0.33300001));
		assertEquals(1, VariantAssigner.chooseVariant(new double[]{0.333, 0.333, 0.334}, 0.5));
		assertEquals(1, VariantAssigner.chooseVariant(new double[]{0.333, 0.333, 0.334}, 0.66599999));
		assertEquals(2, VariantAssigner.chooseVariant(new double[]{0.333, 0.333, 0.334}, 0.666));
		assertEquals(2, VariantAssigner.chooseVariant(new double[]{0.333, 0.333, 0.334}, 0.66600001));
		assertEquals(2, VariantAssigner.chooseVariant(new double[]{0.333, 0.333, 0.334}, 0.75));
		assertEquals(2, VariantAssigner.chooseVariant(new double[]{0.333, 0.333, 0.334}, 1.0));
		assertEquals(1, VariantAssigner.chooseVariant(new double[]{0.0, 1.0}, 0.0));
		assertEquals(1, VariantAssigner.chooseVariant(new double[]{0.0, 1.0}, 1.0));
	}

	@ParameterizedTest(name = "testAssignmentsMatch_{0}{1}")
	@MethodSource("com.absmartly.sdk.internal.VariantAssignerTest#testAssignmentsMatchArgs")
	void testAssignmentsMatch(Object unitUID, List<Integer> expectedVariants) {
		final List<List<Double>> splits = TestUtils.listOf(
				TestUtils.listOf(0.5, 0.5),
				TestUtils.listOf(0.5, 0.5),
				TestUtils.listOf(0.5, 0.5),
				TestUtils.listOf(0.5, 0.5),
				TestUtils.listOf(0.5, 0.5),
				TestUtils.listOf(0.5, 0.5),
				TestUtils.listOf(0.5, 0.5),
				TestUtils.listOf(0.33, 0.33, 0.34),
				TestUtils.listOf(0.33, 0.33, 0.34),
				TestUtils.listOf(0.33, 0.33, 0.34),
				TestUtils.listOf(0.33, 0.33, 0.34),
				TestUtils.listOf(0.33, 0.33, 0.34),
				TestUtils.listOf(0.33, 0.33, 0.34),
				TestUtils.listOf(0.33, 0.33, 0.34));

		final List<List<Integer>> seeds = TestUtils.listOf(
				TestUtils.listOf(0x00000000, 0x00000000),
				TestUtils.listOf(0x00000000, 0x00000001),
				TestUtils.listOf(0x8015406f, 0x7ef49b98),
				TestUtils.listOf(0x3b2e7d90, 0xca87df4d),
				TestUtils.listOf(0x52c1f657, 0xd248bb2e),
				TestUtils.listOf(0x865a84d0, 0xaa22d41a),
				TestUtils.listOf(0x27d1dc86, 0x845461b9),
				TestUtils.listOf(0x00000000, 0x00000000),
				TestUtils.listOf(0x00000000, 0x00000001),
				TestUtils.listOf(0x8015406f, 0x7ef49b98),
				TestUtils.listOf(0x3b2e7d90, 0xca87df4d),
				TestUtils.listOf(0x52c1f657, 0xd248bb2e),
				TestUtils.listOf(0x865a84d0, 0xaa22d41a),
				TestUtils.listOf(0x27d1dc86, 0x845461b9));

		final byte[] unitHash = Hashing.hashUnit(unitUID.toString());
		final VariantAssigner assigner = new VariantAssigner(unitHash);
		for (int i = 0; i < seeds.size(); ++i) {
			final List<Integer> frags = seeds.get(i);
			final double[] split = splits.get(i).stream().mapToDouble(x -> x).toArray();
			final int variant = assigner.assign(split, frags.get(0), frags.get(1));
			assertEquals(expectedVariants.get(i), variant);
		}
	}

	static Stream<? extends Arguments> testAssignmentsMatchArgs() {
		return Stream.of(
				Arguments.of(123456789, TestUtils.listOf(1, 0, 1, 1, 1, 0, 0, 2, 1, 2, 2, 2, 0, 0)),
				Arguments.of("bleh@absmartly.com", TestUtils.listOf(0, 1, 0, 0, 0, 0, 1, 0, 2, 0, 0, 0, 1, 1)),
				Arguments.of("e791e240fcd3df7d238cfc285f475e8152fcc0ec",
						TestUtils.listOf(1, 0, 1, 1, 0, 0, 0, 2, 0, 2, 1, 0, 0, 1)));
	}
}
