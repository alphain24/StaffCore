package io.github.alphain24.staffcore.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An existing config file gains new settings without losing the ones somebody chose.
 *
 * <h2>The bug this closes</h2>
 * Gson fills the fields a file mentions and leaves the rest at their Java defaults, so a new
 * setting always <em>worked</em> on an existing server — it simply never appeared in
 * {@code staffcore.json}. The file was rewritten only when {@code configVersion} moved, and most
 * releases add a setting without needing a migration.
 * <p>
 * So a server owner could not configure what they could not see, and the workaround people found
 * was deleting the file to make it regenerate — which silently reset every choice they had ever
 * made. By the time somebody noticed, four settings were invisible this way.
 * <p>
 * It is a quiet failure in the same family as the schema one: the version counter is a claim
 * about history, not a description of what is actually on disk. The database already reconciles
 * the two on every boot. This is the config doing the same.
 *
 * <h2>What is actually being protected</h2>
 * Not "the key appears" — that is the easy half. The half that matters is that <b>reconciling
 * does not touch anything the owner set</b>. A fix that added the missing keys by regenerating
 * the file would pass a naive test and destroy the thing the bug was already destroying.
 */
class ConfigUpgradeTest {

	@TempDir
	Path dir;

	/** The real config is a static singleton; put it back so the rest of the suite is unaffected. */
	@AfterEach
	void restore() {
		StaffConfig.reset();
	}

	private Path write(String json) throws IOException {
		Path file = dir.resolve("staffcore.json");
		Files.writeString(file, json, StandardCharsets.UTF_8);
		return file;
	}

	private String read(Path file) throws IOException {
		return Files.readString(file, StandardCharsets.UTF_8);
	}

	@Test
	@DisplayName("a config written before a feature existed gains its settings")
	void missingKeysAreAdded() throws IOException {
		// A plausible older file: valid, current version, and simply predating the position
		// history keys. This is what every upgraded server actually has.
		Path file = write("""
				{
				  "configVersion": 3,
				  "canaryDensity": 6,
				  "griefLogRetentionDays": 14
				}
				""");

		assertFalse(read(file).contains("positionTracking"),
				"the fixture already mentions positionTracking, so this test cannot show that "
						+ "loading added it");

		StaffConfig.loadFrom(file);

		String after = read(file);
		for (String key : new String[] {"positionTracking", "positionSampleHz",
				"positionRetentionDays", "canaryForceWithBulkAntiXray"}) {
			assertTrue(after.contains(key),
					key + " is still missing from the file after loading. It works in memory, "
							+ "because Gson leaves absent fields at their defaults — but a "
							+ "server owner cannot set what their config does not mention, and "
							+ "the only workaround is deleting the file, which resets "
							+ "everything they ever chose.");
		}
	}

	@Test
	@DisplayName("reconciling keeps every value the owner chose")
	void nothingTheOwnerSetIsLost() throws IOException {
		// The assertion that stops the cure being the disease. A fix that regenerated the file
		// would make the test above pass and throw away exactly what the old workaround threw
		// away.
		Path file = write("""
				{
				  "configVersion": 3,
				  "canaryDensity": 19,
				  "griefLogRetentionDays": 45,
				  "requireTwoPersonApproval": false,
				  "discordInvite": "https://example.invalid/chosen-by-hand"
				}
				""");

		StaffConfig.loadFrom(file);
		StaffConfig cfg = StaffConfig.get();

		assertEquals(19, cfg.canaryDensity, "a chosen canaryDensity was reset");
		assertEquals(45, cfg.griefLogRetentionDays, "a chosen retention was reset");
		assertFalse(cfg.requireTwoPersonApproval,
				"a deliberately disabled safety setting was switched back on, which is worse "
						+ "than the bug: it changes behaviour rather than only visibility");
		assertEquals("https://example.invalid/chosen-by-hand", cfg.discordInvite,
				"a chosen string was reset");

		// And they survive in the file, not just in memory.
		String after = read(file);
		assertTrue(after.contains("\"canaryDensity\": 19"), "the file lost canaryDensity");
		assertTrue(after.contains("chosen-by-hand"), "the file lost discordInvite");
	}

	@Test
	@DisplayName("an already-complete config is left alone")
	void completeFilesAreNotRewritten() throws IOException {
		// Rewriting on every boot would work and would be wrong: it would churn the file
		// forever, and it would hide a real change among a hundred no-op ones.
		// Generated the way the mod generates it — loadFrom on a file that does not exist is
		// the fresh-install path, and it stamps configVersion. Writing the fixture by hand
		// from a default instance produced a file with configVersion 0, which migrate then
		// correctly upgraded, so the first version of this test was measuring a migration.
		Path file = dir.resolve("staffcore.json");
		StaffConfig.loadFrom(file);
		String before = read(file);

		assertTrue(before.contains("positionTracking"),
				"the generated fixture is not actually complete, so this proves nothing");

		StaffConfig.loadFrom(file);

		assertEquals(before, read(file),
				"a config that already had every setting was rewritten anyway. Rewriting on "
						+ "every boot would work and would be wrong: it churns the file "
						+ "forever and hides a real change among a hundred no-op ones.");
	}

	@Test
	@DisplayName("a value the code has to clamp is written back clamped")
	void correctionsArePersisted() throws IOException {
		// validate() clamps out-of-range values. Before this, the clamp happened after the
		// decision to save, so the file went on saying something the server was not doing —
		// and the next person to read it would have been misled by their own config.
		Path file = write("""
				{
				  "configVersion": 3,
				  "positionSampleHz": 999
				}
				""");

		StaffConfig.loadFrom(file);

		assertEquals(10, StaffConfig.get().positionSampleHz, "the value was not clamped");
		assertFalse(read(file).contains("999"),
				"the file still says positionSampleHz is 999 while the server is using 10. A "
						+ "config that disagrees with the running server is worse than one "
						+ "that is merely out of date.");
	}

	@Test
	@DisplayName("a settings file that is not JSON at all does not take the server down")
	void garbageFallsBackRatherThanThrowing() throws IOException {
		Path file = write("this is not json {{{");

		StaffConfig.loadFrom(file);

		assertEquals(6, StaffConfig.get().canaryDensity,
				"an unreadable config should fall back to defaults in memory rather than "
						+ "throwing during boot");
	}
}
