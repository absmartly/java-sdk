package com.absmartly.sdk;

import java8.util.concurrent.CompletableFuture;
import java8.util.concurrent.CompletionException;
import java8.util.function.BiConsumer;
import java8.util.function.Function;

import javax.annotation.Nonnull;

import com.absmartly.sdk.cache.LocalCache;
import com.absmartly.sdk.json.ContextData;

public class ResilientContextDataProvider extends DefaultContextDataProvider {
	public ResilientContextDataProvider(@Nonnull final Client client, @Nonnull final LocalCache localCache) {
		super(client);
		this.localCache = localCache;
	}

	@Override
	public CompletableFuture<ContextData> getContextData() {
		return super.getContextData()
				.whenComplete(new BiConsumer<ContextData, Throwable>() {
					@Override
					public void accept(ContextData contextData, Throwable throwable) {
						if (throwable == null
								&& localCache != null) {
							localCache.writeContextData(contextData);
						}
					}
				})
				.exceptionally(new Function<Throwable, ContextData>() {
					@Override
					public ContextData apply(Throwable throwable) {
						ContextData contextData = localCache != null ? localCache.getContextData() : null;
						if (contextData != null)
							return contextData;

						throw (CompletionException) throwable;
					}
				});
	}

	private LocalCache localCache;
}
