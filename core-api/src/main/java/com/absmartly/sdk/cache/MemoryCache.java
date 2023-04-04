package com.absmartly.sdk.cache;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

import com.absmartly.sdk.json.ContextData;
import com.absmartly.sdk.json.PublishEvent;

public final class MemoryCache implements LocalCache {

	private final List<PublishEvent> eventCache = new ArrayList<PublishEvent>();
	private final ReentrantLock cacheLock = new ReentrantLock();
	private ContextData contextData;

	public void writePublishEvent(PublishEvent event) {
		cacheLock.lock();
		eventCache.add(event);
		cacheLock.unlock();
	}

	public List<PublishEvent> retrievePublishEvents() {
		cacheLock.lock();
		List<PublishEvent> eventsToRetrieve = new ArrayList<PublishEvent>(eventCache);
		eventCache.clear();
		cacheLock.unlock();
		return eventsToRetrieve;
	}

	@Override
	public void writeContextData(ContextData contextData) {
		this.contextData = contextData;
	}

	@Override
	public ContextData getContextData() {
		return this.contextData;
	}
}
