package com.absmartly.sdk.cache;

import java.util.List;

import com.absmartly.sdk.json.ContextData;
import com.absmartly.sdk.json.PublishEvent;

public interface LocalCache {
	void writeEvent(PublishEvent event);
	List<PublishEvent> retrieveEvents();
	void writeContextData(ContextData event);
	ContextData getContextData();
}
