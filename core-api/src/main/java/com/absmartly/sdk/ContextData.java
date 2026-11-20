package com.absmartly.sdk;

import java.nio.charset.StandardCharsets;

import com.absmartly.sdk.internal.Json;
import com.absmartly.sdk.internal.json.ContextPayload;

/**
 * The experiment data a context evaluates, as fetched from the collector. Opaque and immutable: fetch it once with
 * {@link ABsmartlyClient#contextData()} (or {@link #fromJson(String)} for data you cached) and create any number of
 * contexts from it with {@link ABsmartlyClient#context(ContextOptions, ContextData)}.
 */
public final class ContextData {
	ContextData(final ContextPayload payload) {
		payload_ = payload;
	}

	/** Parses the JSON body of {@code GET /context}. Throws {@link DecodingException} if it is not valid. */
	public static ContextData fromJson(final String json) {
		return new ContextData(Json.decodeContext(json.getBytes(StandardCharsets.UTF_8)));
	}

	/** The JSON form, accepted by {@link #fromJson(String)}. */
	public String toJson() {
		return Json.encode(payload_);
	}

	/** The names of the experiments in this data. */
	public java.util.List<String> experimentNames() {
		final java.util.List<String> names = new java.util.ArrayList<String>(payload_.experiments.length);
		for (final com.absmartly.sdk.internal.json.Experiment experiment : payload_.experiments) {
			names.add(experiment.name);
		}
		return java.util.Collections.unmodifiableList(names);
	}

	ContextPayload payload() {
		return payload_;
	}

	@Override
	public boolean equals(final Object o) {
		return (this == o) || ((o instanceof ContextData) && payload_.equals(((ContextData) o).payload_));
	}

	@Override
	public int hashCode() {
		return payload_.hashCode();
	}

	@Override
	public String toString() {
		return "ContextData{experiments=" + experimentNames() + '}';
	}

	private final ContextPayload payload_;
}
