package com.absmartly.sdk.java.time.internal;

import com.absmartly.sdk.java.time.Clock;

public class SystemClockUTC extends Clock {
	@Override
	public long millis() {
		return System.currentTimeMillis();
	}
}
