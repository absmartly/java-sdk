package com.absmartly.example;

import java.net.URI;
import java.util.Map;

import com.absmartly.sdk.ABsmartlyClient;
import com.absmartly.sdk.Context;
import com.absmartly.sdk.ContextOptions;
import com.absmartly.sdk.event.ContextEvent;
import com.absmartly.sdk.event.ExposureEvent;

public class Example {
	public static void main(String[] args) {
		try (ABsmartlyClient client = ABsmartlyClient.builder()
				.endpoint(URI.create("https://acme.absmartly.io/v1"))
				.apiKey(System.getenv("ABSMARTLY_APIKEY"))
				.application(System.getenv("ABSMARTLY_APP"))
				.environment(System.getenv("ABSMARTLY_ENV"))
				.build()) {
			client.onEvent(event -> {
				if (event.type() == ContextEvent.Type.EXPOSURE) {
					System.out.println("exposed: " + ((ExposureEvent) event).exposure().name());
				}
			});

			try (Context context = client.context(ContextOptions.builder()
					.unit("session_id", "bf06d8cb5d8137290c4abb64155584fbdb64d8")
					.unit("user_id", 123456)
					.build())) {
				final int treatment = context.treatment("exp_test_ab");
				System.out.println(treatment);

				final String color = context.variable("button.color", "blue");
				System.out.println(color);

				context.track("payment", Map.of("value", 125, "fee", 125));
			}
		}
	}
}
