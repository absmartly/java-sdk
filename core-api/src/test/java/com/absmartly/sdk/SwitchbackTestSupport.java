package com.absmartly.sdk;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.absmartly.sdk.java.time.Clock;
import com.absmartly.sdk.json.Exposure;

// Helpers shared by the switchback test classes.
final class SwitchbackTestSupport {
	private SwitchbackTestSupport() {}

	static final class MutableClock extends Clock {
		long now;
		// When set, the next read returns this value once, then reads fall back to `now`.
		Long nextRead;

		MutableClock(long now) {
			this.now = now;
		}

		@Override
		public long millis() {
			if (nextRead != null) {
				final long value = nextRead;
				nextRead = null;
				return value;
			}
			return now;
		}
	}

	static final class ExposureCapture implements ContextEventLogger {
		final List<Exposure> exposures = new ArrayList<Exposure>();

		@Override
		public void handleEvent(Context context, EventType type, Object data) {
			if (type == EventType.Exposure) {
				exposures.add((Exposure) data);
			}
		}
	}

	// An ordered attribute map: name, value, name, value, ...
	static Map<String, Object> attrs(Object... entries) {
		final Map<String, Object> map = new LinkedHashMap<String, Object>();
		for (int i = 0; i < entries.length; i += 2) {
			map.put((String) entries[i], entries[i + 1]);
		}
		return map;
	}

	// An ordered switchbackClusters map: type, path, type, path, ...
	static Map<String, String> clusters(String... entries) {
		final Map<String, String> map = new LinkedHashMap<String, String>();
		for (int i = 0; i < entries.length; i += 2) {
			map.put(entries[i], entries[i + 1]);
		}
		return map;
	}
}
