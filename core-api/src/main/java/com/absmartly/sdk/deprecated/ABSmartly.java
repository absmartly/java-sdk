package com.absmartly.sdk.deprecated;

import javax.annotation.Nonnull;

import com.absmartly.sdk.ABsmartly;

@Deprecated
public class ABSmartly extends ABsmartly {
	public static ABSmartly create(@Nonnull ABSmartlyConfig config) {
		return new ABSmartly(config);
	}

	protected ABSmartly(@Nonnull ABSmartlyConfig config) {
		super(config);
	}
}
