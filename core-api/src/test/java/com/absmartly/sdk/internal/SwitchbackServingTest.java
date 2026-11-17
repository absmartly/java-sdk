package com.absmartly.sdk.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class SwitchbackServingTest {
	static SwitchbackServing.Unit unit(final String type, final Object uid) {
		return new SwitchbackServing.Unit() {
			@Override
			public String type() {
				return type;
			}

			@Override
			public Object uid() {
				return uid;
			}
		};
	}

	// The declared source order decides, not the order of the units: with units listed user first
	// and sources declared session first, both agreeing, session serves.
	@Test
	void sourceOrderDecidesWhateverTheUnitOrder() {
		final List<SwitchbackServing.Unit> units = Arrays.asList(unit("user_id", "u1"), unit("session_id", "x1"));
		final Map<String, Object> attributes = new HashMap<String, Object>();
		attributes.put("session_store", "s-1");
		attributes.put("user_store", "s-1");
		final SwitchbackServing serving = SwitchbackServing.resolve(new String[]{"session_id", "user_id"},
				new SwitchbackAttributePath[]{SwitchbackAttributePath.parseSupported("session_store"),
						SwitchbackAttributePath.parseSupported("user_store")},
				null, units, new SwitchbackServing.AttributeReader() {
					@Override
					public Object read(final SwitchbackAttributePath path) {
						return path.resolve(attributes);
					}
				});

		assertEquals(SwitchbackServing.ELIGIBLE, serving.status);
		assertEquals("session_id", serving.unit.type());
		assertEquals("s-1", serving.clusterKey);
	}
}
