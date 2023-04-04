package com.absmartly.sdk.cache;

import com.absmartly.sdk.ContextDataSerializer;
import com.absmartly.sdk.ContextEventSerializer;

public abstract class SerializableCache implements LocalCache {

	ContextDataSerializer contextDataSerializer;
	ContextEventSerializer contextEventSerializer;

	public SerializableCache(final ContextDataSerializer contextDataSerializer,
			final ContextEventSerializer contextEventSerializer) {
		this.contextDataSerializer = contextDataSerializer;
		this.contextEventSerializer = contextEventSerializer;
	}

}
