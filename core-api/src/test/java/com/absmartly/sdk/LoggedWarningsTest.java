package com.absmartly.sdk;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.absmartly.sdk.internal.Json;

/**
 * Checks the WARN lines the SDK writes through SLF4J. A child JVM runs with slf4j-simple on its class path, so the
 * real logger output can be read without a test hook in production code or a logging binding in the main suite.
 */
class LoggedWarningsTest {
	public static void main(final String[] args) {
		VariableConverter.convert("wild", Json.UNDECODABLE, Integer.class, -1);
	}

	@Test
	@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
	void anUndecodableTypedReadLogsAWarning() throws Exception {
		final String logging = System.getProperty("absmartly.loggedWarningsClasspath");
		assertNotNull(logging, "the build passes the slf4j-simple class path");
		final String java = new File(new File(System.getProperty("java.home"), "bin"), "java").getPath();
		final List<String> command = new ArrayList<>();
		command.add(java);
		command.add("-cp");
		command.add(System.getProperty("java.class.path") + File.pathSeparator + logging);
		command.add(LoggedWarningsTest.class.getName());
		final Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
		try {
			final String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			assertTrue(process.waitFor(30, TimeUnit.SECONDS));
			assertEquals(0, process.exitValue(), output);
			assertTrue(output.contains(
					"WARN com.absmartly.sdk.VariableConverter - ABsmartly variable 'wild' cannot be read exactly"),
					output);
		} finally {
			// A hung child must not outlive a timed-out test.
			process.destroyForcibly();
		}
	}
}
