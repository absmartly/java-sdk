package com.absmartly.sdk.cache;

import java.util.List;

import com.absmartly.sdk.json.ContextData;
import com.absmartly.sdk.json.PublishEvent;

public interface LocalCache {
	void writePublishEvent(PublishEvent event);

	List<PublishEvent> retrievePublishEvents();

	void writeContextData(ContextData event);

	ContextData getContextData();
}
