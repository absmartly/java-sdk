package com.absmartly.sdk;

import javax.annotation.Nonnull;

import com.absmartly.sdk.json.PublishEvent;

public interface ContextEventSerializer {
	byte[] serialize(@Nonnull final PublishEvent publishEvent);

	PublishEvent deserialize(@Nonnull final byte[] bytes, final int offset, final int length);
}
