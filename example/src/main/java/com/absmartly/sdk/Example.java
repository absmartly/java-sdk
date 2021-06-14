package com.absmartly.sdk;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class Example {
	static public void main(String[] args) throws IOException {
		final ClientConfig clientConfig = ClientConfig.create()
				.setEndpoint("https://acme.absmartly.io/v1")
				.setAPIKey(System.getenv("ABSMARTLY_APIKEY"))
				.setApplication(System.getenv("ABSMARTLY_APP"))
				.setEnvironment(System.getenv("ABSMARTLY_ENV"));

		final ABSmartlyConfig sdkConfig = ABSmartlyConfig.create()
				.setClient(Client.create(clientConfig));

		final ABSmartly sdk = ABSmartly.create(sdkConfig);

		final ContextConfig contextConfig = ContextConfig.create()
				.setUnit("session_id", "bf06d8cb5d8137290c4abb64155584fbdb64d8")
				.setUnit("user_id", Long.toString(123456));

		final Context ctx = sdk.createContext(contextConfig).waitUntilReady();

		final int treatment = ctx.getTreatment("exp_test_ab");
		System.out.println(treatment);

		final Map<String, Object> properties = new HashMap<>();
		properties.put("value", 125);
		properties.put("fee", 125);

		ctx.track("payment", properties);

		ctx.close();
		sdk.close();
	}
}
