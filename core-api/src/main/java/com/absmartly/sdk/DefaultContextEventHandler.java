package com.absmartly.sdk;

import javax.annotation.Nonnull;

/**
 * @deprecated Use {@link DefaultContextPublisher} instead.
 */
@Deprecated
public class DefaultContextEventHandler extends DefaultContextPublisher implements ContextEventHandler {
	public DefaultContextEventHandler(@Nonnull final Client client) {
		super(client);
	}
}
