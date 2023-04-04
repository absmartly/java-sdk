package com.absmartly.sdk;

import javax.annotation.Nonnull;

import com.absmartly.sdk.json.ContextData;

public interface ContextDataSerializer {

	byte[] serialize(@Nonnull final ContextData contextData);

	ContextData deserialize(@Nonnull final byte[] bytes, final int offset, final int length);
}
