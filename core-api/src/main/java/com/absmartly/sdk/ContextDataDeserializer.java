package com.absmartly.sdk;

import javax.annotation.Nonnull;

import com.absmartly.sdk.json.ContextData;

interface ContextDataDeserializer {
	ContextData deserialize(@Nonnull byte[] bytes, int offset, int length);
}
