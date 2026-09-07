package io.github.alphain24.staffcore.diagnostic;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A gametest class that nobody registered runs zero times and reports success.
 * <p>
 * The entrypoint list in {@code fabric.mod.json} is written by hand, so adding a test file is
 * two steps and only one of them fails loudly if it is missed. Skip the second and the suite
 * still passes, the count still goes up in nobody's head, and the thing the test was written
 * to catch stays uncaught — which is precisely the failure shape this project keeps finding.
 * <p>
 * Cheap to prevent: the files are on disk and the list is in a file, so the two can be
 * compared without running anything.
 */
class GametestRegistrationTest {

	private static final Path GAMETESTS =
			Path.of("src", "gametest", "java", "io", "github", "alphain24", "staffcore",
					"gametest");

	private static final Path MANIFEST =
			Path.of("src", "gametest", "resources", "fabric.mod.json");

	@Test
	@DisplayName("every gametest class is in the entrypoint list")
	void nothingIsWrittenAndNeverRun() throws IOException {
		String manifest = Files.readString(MANIFEST, StandardCharsets.UTF_8);
		List<String> unregistered = new ArrayList<>();

		try (Stream<Path> files = Files.list(GAMETESTS)) {
			for (Path file : files.filter(f -> f.toString().endsWith("Tests.java")).toList()) {
				String name = file.getFileName().toString().replace(".java", "");
				if (!manifest.contains(name)) unregistered.add(name);
			}
		}

		assertTrue(unregistered.isEmpty(),
				"These gametest classes exist and never run:\n  "
						+ String.join("\n  ", unregistered)
						+ "\n\nAdd them to the fabric-gametest entrypoints in "
						+ MANIFEST + ". Until then they pass by not existing, which is the "
						+ "one result a test must never be able to give.");
	}

	@Test
	@DisplayName("the entrypoint list names no class that has gone")
	void nothingIsRegisteredAndMissing() throws IOException {
		// The other direction. A stale entry is not silent — Fabric fails to load the
		// entrypoint — but it fails at runtime in the nightly job rather than here, which is
		// hours later and on somebody else's screen.
		String manifest = Files.readString(MANIFEST, StandardCharsets.UTF_8);
		List<String> missing = new ArrayList<>();

		for (String line : manifest.split("\n")) {
			String trimmed = line.trim().replace("\"", "").replace(",", "");
			if (!trimmed.startsWith("io.github.alphain24.staffcore.gametest.")) continue;

			String simple = trimmed.substring(trimmed.lastIndexOf('.') + 1);
			if (!Files.exists(GAMETESTS.resolve(simple + ".java"))) missing.add(simple);
		}

		assertTrue(missing.isEmpty(),
				"The entrypoint list names classes that do not exist:\n  "
						+ String.join("\n  ", missing));
	}

	@Test
	@DisplayName("the scan sees the classes that are there, so a pass means something")
	void theScanIsNotVacuous() throws IOException {
		try (Stream<Path> files = Files.list(GAMETESTS)) {
			long found = files.filter(f -> f.toString().endsWith("Tests.java")).count();
			assertTrue(found >= 5,
					"only " + found + " gametest class(es) found, which is fewer than this "
							+ "repository has. The scan is looking in the wrong place.");
		}
	}
}
