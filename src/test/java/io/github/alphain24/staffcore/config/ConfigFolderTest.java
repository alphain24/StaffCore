package io.github.alphain24.staffcore.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code config/staffcore/}: an upgrade keeps every setting, and nothing an owner wrote is deleted.
 */
class ConfigFolderTest {

	@TempDir
	Path config;

	@Test
	@DisplayName("a fresh server gets its files in config/staffcore/")
	void freshInstall() {
		Path settings = ConfigFolder.file(config, ConfigFolder.SETTINGS, ConfigFolder.LEGACY_SETTINGS);
		assertEquals(config.resolve("staffcore").resolve("staffcore.json"), settings);

		StaffConfig.loadFrom(settings);
		assertTrue(Files.isRegularFile(settings), "the defaults were not written into the folder");
		assertFalse(Files.exists(config.resolve("staffcore.json")), "a file was left loose in config/");
	}

	@Test
	@DisplayName("an upgrade moves the old files into the folder, contents and all")
	void upgradeMoves() throws IOException {
		Files.writeString(config.resolve("staffcore.json"), "{\"configVersion\": 1, \"canaryDensity\": 3}");
		Files.writeString(config.resolve("staffcore-permissions.json"), "{\"operatorsBypass\": false}");

		Path settings = ConfigFolder.file(config, ConfigFolder.SETTINGS, ConfigFolder.LEGACY_SETTINGS);
		Path permissions = ConfigFolder.file(config, ConfigFolder.PERMISSIONS, ConfigFolder.LEGACY_PERMISSIONS);

		assertEquals(config.resolve("staffcore/staffcore.json"), settings);
		assertEquals(config.resolve("staffcore/permissions.json"), permissions);
		assertTrue(Files.readString(settings).contains("\"canaryDensity\": 3"), "the settings were not carried over");
		assertEquals("{\"operatorsBypass\": false}", Files.readString(permissions));
		assertFalse(Files.exists(config.resolve("staffcore.json")), "the old settings file is still loose");
		assertFalse(Files.exists(config.resolve("staffcore-permissions.json")), "the old permissions file is still loose");

		// Asked again, nothing moves and nothing changes.
		assertEquals(settings, ConfigFolder.file(config, ConfigFolder.SETTINGS, ConfigFolder.LEGACY_SETTINGS));
		assertTrue(Files.readString(settings).contains("\"canaryDensity\": 3"));
	}

	@Test
	@DisplayName("with both, the folder's copy wins and the old one is left alone")
	void bothKept() throws IOException {
		Files.createDirectories(config.resolve("staffcore"));
		Files.writeString(config.resolve("staffcore/staffcore.json"), "new");
		Files.writeString(config.resolve("staffcore.json"), "old");

		Path settings = ConfigFolder.file(config, ConfigFolder.SETTINGS, ConfigFolder.LEGACY_SETTINGS);
		assertEquals(config.resolve("staffcore/staffcore.json"), settings);
		assertEquals("new", Files.readString(settings));
		assertEquals("old", Files.readString(config.resolve("staffcore.json")), "somebody's old file was touched");
	}

	@Test
	@DisplayName("when the folder cannot be made, the old file is used where it is")
	void moveFails() throws IOException {
		// A file where the folder should be: the move cannot happen, and the settings must not be lost.
		Files.writeString(config.resolve("staffcore"), "not a folder");
		Files.writeString(config.resolve("staffcore.json"), "old");

		Path settings = ConfigFolder.file(config, ConfigFolder.SETTINGS, ConfigFolder.LEGACY_SETTINGS);
		assertEquals(config.resolve("staffcore.json"), settings);
		assertEquals("old", Files.readString(settings));
	}
}
