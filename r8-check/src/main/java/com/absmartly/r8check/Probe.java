package com.absmartly.r8check;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.absmartly.sdk.ABsmartlyClient;
import com.absmartly.sdk.Context;
import com.absmartly.sdk.ContextOptions;
import com.absmartly.sdk.ContextState;
import com.absmartly.sdk.Transport;

/** Exercises what the consumer rules must keep: decoding (with an unknown field), typed reads, Kotlin defaults. */
public final class Probe {
	public static final class Banner {
		public String color;
		public int size;
		// A generic field: Jackson needs the Signature attribute to build Slot elements rather than maps.
		public List<Slot> slots;
	}

	public static final class Slot {
		public String name;
	}

	private static final String JSON = "{\"futureField\":1,\"experiments\":[{\"id\":1,\"name\":\"exp\","
			+ "\"unitType\":\"session_id\",\"iteration\":1,\"seedHi\":0,\"seedLo\":0,\"split\":[0,1],"
			+ "\"trafficSeedHi\":0,\"trafficSeedLo\":0,\"trafficSplit\":[0,1],\"fullOnVariant\":1,"
			+ "\"applications\":[{\"name\":\"a\"}],\"variants\":[{\"name\":\"A\",\"config\":null},{\"name\":\"B\","
			+ "\"config\":\"{\\\"items\\\":[\\\"x\\\"],\\\"limits\\\":{\\\"cart\\\":3},"
			+ "\\\"banner\\\":{\\\"color\\\":\\\"red\\\",\\\"size\\\":2,"
			+ "\\\"slots\\\":[{\\\"name\\\":\\\"top\\\"}]}}\"}]}]}";

	public static void main(final String[] args) {
		final Transport transport = request -> CompletableFuture.completedFuture(new Transport.Response(200, "OK",
				"GET".equals(request.method()) ? JSON.getBytes(StandardCharsets.UTF_8) : new byte[0]));
		try (ABsmartlyClient client = ABsmartlyClient.builder().endpoint(URI.create("https://x")).apiKey("k")
				.application("a").environment("e").transport(transport).build();
				Context context = client.context(ContextOptions.builder().unit("session_id", "u1").build())) {
			check(context.state() == ContextState.READY, "ready");
			check(context.treatment("exp") == 1, "treatment");
			check(List.of("x").equals(context.variable("items", List.<String> of())), "list default");
			check(List.of("x").equals(context.variable("items", kotlin.collections.CollectionsKt.<String> emptyList())),
					"Kotlin emptyList default");
			check(Map.of("cart", 3).equals(context.variable("limits",
					kotlin.collections.MapsKt.<String, Object> emptyMap())), "Kotlin emptyMap default");
			final Banner banner = context.variable("banner", Banner.class, null);
			check((banner != null) && "red".equals(banner.color) && (banner.size == 2), "class mapping");
			check((banner.slots != null) && "top".equals(banner.slots.get(0).name), "generic field mapping");
			check(java.util.Set.of("x").equals(context.variable("items",
					kotlin.collections.SetsKt.<String> emptySet())), "Kotlin emptySet default");
			context.track("goal");
		}
		// A minified JVM app with no Transport of its own: the default transport is found by name.
		ABsmartlyClient.builder().endpoint(URI.create("https://x")).apiKey("k").application("a").environment("e")
				.build().close();
		System.out.println("R8 probe OK");
	}

	private static void check(final boolean ok, final String what) {
		if (!ok) {
			throw new AssertionError("R8 probe failed: " + what);
		}
	}
}
