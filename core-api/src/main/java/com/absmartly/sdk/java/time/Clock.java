package com.absmartly.sdk.java.time;

import com.absmartly.sdk.java.time.internal.FixedClock;
import com.absmartly.sdk.java.time.internal.SystemClockUTC;

public abstract class Clock {
	public abstract long millis();

	static public Clock fixed(long millis) {
		return new FixedClock(millis);
	}

	static public Clock systemUTC() {
		if (utc_ != null) {
			return utc_;
		}

		return utc_ = new SystemClockUTC();
	}

	static SystemClockUTC utc_;
}
