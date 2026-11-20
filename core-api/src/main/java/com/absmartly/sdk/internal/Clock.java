package com.absmartly.sdk.internal;

public abstract class Clock {
	public abstract long millis();

	public static Clock fixed(final long millis) {
		return new Clock() {
			@Override
			public long millis() {
				return millis;
			}
		};
	}

	public static Clock systemUTC() {
		return SYSTEM;
	}

	private static final Clock SYSTEM = new Clock() {
		@Override
		public long millis() {
			return System.currentTimeMillis();
		}
	};
}
