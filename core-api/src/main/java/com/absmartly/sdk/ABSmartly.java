package com.absmartly.sdk;

import javax.annotation.Nonnull;

/**
 * @deprecated Use {@link com.absmartly.sdk.ABsmartly} instead.
 */
@Deprecated
public class ABSmartly extends ABsmartly {
	public static ABSmartly create(@Nonnull ABSmartlyConfig config) {
		return new ABSmartly(config);
	}

	private ABSmartly(@Nonnull ABSmartlyConfig config) {
		super(config);
	}
}
