package com.absmartly.sdk;

import javax.annotation.Nonnull;

import com.absmartly.sdk.json.PublishEvent;

public interface ContextEventSerializer {
	byte[] serialize(@Nonnull final PublishEvent publishEvent);
}
