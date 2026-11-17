package com.absmartly.sdk;

import java.util.Arrays;

import com.absmartly.sdk.internal.Json;
import com.absmartly.sdk.internal.json.ContextPayload;
import com.absmartly.sdk.internal.json.PublishPayload;

/** The 1.x deserializer and serializer call shapes over {@link Json}, for the ported tests. */
final class TestJson {
	private TestJson() {}

	static final class Deserializer {
		// 1.x returned null for an undecodable body; 2.0 throws DecodingException (see JsonContextDecodingTest).
		ContextPayload deserialize(final byte[] bytes, final int offset, final int length) {
			try {
				return Json.decodeContext(Arrays.copyOfRange(bytes, offset, offset + length));
			} catch (final DecodingException e) {
				return null;
			}
		}
	}

	static final class Serializer {
		byte[] serialize(final PublishPayload event) {
			return Json.encodeBytes(event);
		}
	}

	/** A client for the given application and environment that is never used for I/O. */
	static Client client(final String application, final String environment, final String applicationVersion) {
		return new Client("https://localhost/v1", "test-api-key", application, environment, applicationVersion,
				FakeTransport.ok("{}"), null);
	}
}
