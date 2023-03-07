package com.absmartly.sdk.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.absmartly.sdk.TestUtils;
import com.absmartly.sdk.java.time.Clock;
import com.absmartly.sdk.json.Exposure;
import com.absmartly.sdk.json.PublishEvent;
import com.absmartly.sdk.json.Unit;

public class SqliteCacheTest extends TestUtils {
	@Test
	void parseDoesNotThrow() {
		SqliteCache cache = new SqliteCache();

		Clock clock = Clock.fixed(1620000000000L);
		final Unit[] publishUnits = new Unit[]{
				new Unit("user_id", "JfnnlDI7RTiF9RgfG2JNCw"),
				new Unit("session_id", "pAE3a1i5Drs5mKRNq56adA"),
				new Unit("email", "IuqYkNRfEx5yClel4j3NbA")
		};

		final PublishEvent expected = new PublishEvent();
		expected.hashed = true;
		expected.publishedAt = clock.millis();
		expected.units = publishUnits;
		expected.exposures = new Exposure[]{
				new Exposure(1, "exp_test_ab", "session_id", 1, clock.millis(), true, true, false, false, false, false),
		};

		cache.writeEvent(expected);

		List<PublishEvent> events = cache.retrieveEvents();

		assertEquals(1, events.size());

		assertEquals(expected, events.get(0));
	}
}
