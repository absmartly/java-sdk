package com.absmartly.sdk;

import java.util.Properties;

import javax.annotation.Nonnull;

import com.absmartly.sdk.cache.LocalCache;

public class ResilienceConfig {
	public static ResilienceConfig create(@Nonnull LocalCache localCache) {
		return new ResilienceConfig(localCache);
	}

	public static ResilienceConfig createFromProperties(Properties properties) {
		return createFromProperties(properties, "");
	}

	public static ResilienceConfig createFromProperties(Properties properties, final String prefix) {
		LocalCache localCache = null;
		try {
			Class localCacheImpl = Class.forName(properties.getProperty(prefix + "localCacheImplClass"));
			localCache = (LocalCache) localCacheImpl.newInstance();
		} catch (ClassNotFoundException e) {
			throw new RuntimeException(e);
		} catch (InstantiationException e) {
			throw new RuntimeException(e);
		} catch (IllegalAccessException e) {
			throw new RuntimeException(e);
		}

		return create(localCache)
				.setBackoffPeriodInMilliseconds(
						Long.parseLong(properties.getProperty(prefix + "backoffPeriodInMilliseconds")))
				.setFailureRateThreshold(Float.valueOf(properties.getProperty(prefix + "failureRateThreshold")));
	}

	ResilienceConfig(LocalCache localCache) {
		this.localCache = localCache;
	}

	public LocalCache getLocalCache() {
		return localCache;
	}

	public float getFailureRateThreshold() {
		return failureRateThreshold;
	}

	public ResilienceConfig setFailureRateThreshold(float failureRateThreshold) {
		this.failureRateThreshold = failureRateThreshold;
		return this;
	}

	public long getBackoffPeriodInMilliseconds() {
		return backoffPeriodInMilliseconds;
	}

	public ResilienceConfig setBackoffPeriodInMilliseconds(long backoffPeriodInMilliseconds) {
		this.backoffPeriodInMilliseconds = backoffPeriodInMilliseconds;
		return this;
	}

	private LocalCache localCache;
	private float failureRateThreshold = 20;
	private long backoffPeriodInMilliseconds = 30000;

}
