package com.absmartly.sdk;

import javax.annotation.Nonnull;

import com.absmartly.sdk.json.ContextData;

interface ContextDataDeserializer {
	ContextData deserialize(@Nonnull final byte[] bytes, final int offset, final int length);
}
