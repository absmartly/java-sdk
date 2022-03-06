package com.absmartly.sdk.java.time.internal;

import com.absmartly.sdk.java.time.Clock;

public class FixedClock extends Clock {
	public FixedClock(long millis) {
		millis_ = millis;
	}

	@Override
	public long millis() {
		return millis_;
	}

	protected long millis_;
}
