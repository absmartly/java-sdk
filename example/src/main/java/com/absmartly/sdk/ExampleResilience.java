package com.absmartly.sdk;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import com.absmartly.sdk.cache.LocalCache;
import com.absmartly.sdk.cache.SqliteCache;

public class ExampleResilience {

	static public void main(String[] args) throws IOException {
		final ClientConfig clientConfig = ClientConfig.create()
				.setEndpoint("https://acme.absmartly.io/v1")
				.setAPIKey(System.getenv("ABSMARTLY_APIKEY"))
				.setApplication(System.getenv("ABSMARTLY_APP"))
				.setEnvironment(System.getenv("ABSMARTLY_ENV"));

		final LocalCache localCache = new SqliteCache();

		final ABSmartlyConfig sdkConfig = ABSmartlyConfig.create()
				.setClient(Client.create(clientConfig))
				.setResilienceConfig(ResilienceConfig.create(localCache));

		final ABSmartly sdk = ABSmartly.create(sdkConfig);

		final ContextConfig contextConfig = ContextConfig.create()
				.setUnit("user_id", Long.toString(123456));

		final Context ctx = sdk.createContext(contextConfig).waitUntilReady();

		final int treatment = ctx.getTreatment("exp_test_ab");
		System.out.println(treatment);

		final Map<String, Object> properties = new HashMap<String, Object>();
		properties.put("value", 125);
		properties.put("fee", 125);

		for (int i = 0; i < 20000; i++) {
			try {
				double randomDouble = Math.random();
				Thread.sleep((int) (randomDouble * 250));
			} catch (InterruptedException e) {
				throw new RuntimeException(e);
			}
			ctx.track("payment", properties);
		}

		ctx.close();
		sdk.close();
	}
}
