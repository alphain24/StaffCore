package io.github.alphain24.staffcore.discord.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Moving the companion's files into {@code config/staffcore/}: nothing lost, nothing deleted, and the token
 * never in two places.
 */
class LegacyFilesTest {

	@TempDir
	Path config;

	private Path folder() {
		return config.resolve("staffcore");
	}

	@Test
	@DisplayName("nothing to move is nothing to say")
	void nothing() {
		LegacyFiles.Settled settled = LegacyFiles.settle(folder(), config, "discord.json", "staffcore-discord.json");
		assertEquals(folder().resolve("discord.json"), settled.path());
		assertNull(settled.note());
	}

	@Test
	@DisplayName("the token file is moved, not copied, and keeps being readable only by its owner")
	void tokenMoved() throws IOException {
		Path old = config.resolve("staffcore-discord.token");
		Files.writeString(old, "secret");
		boolean posix = old.getFileSystem().supportedFileAttributeViews().contains("posix");
		if (posix) Files.setPosixFilePermissions(old, PosixFilePermissions.fromString("rw-------"));

		LegacyFiles.Settled settled = LegacyFiles.settle(folder(), config, "discord.token", "staffcore-discord.token");
		assertEquals(folder().resolve("discord.token"), settled.path());
		assertFalse(settled.problem());
		assertFalse(settled.note().contains("secret"), settled.note());
		assertFalse(Files.exists(old), "the token is still in its old place too");
		assertEquals("secret", Files.readString(settled.path()));
		if (posix) assertFalse(BotToken.worldReadable(settled.path()), "moving made the token readable by everybody");
	}

	@Test
	@DisplayName("with both, the folder's copy is used and the old one is left and mentioned")
	void both() throws IOException {
		Files.createDirectories(folder());
		Files.writeString(folder().resolve("discord.json"), "new");
		Files.writeString(config.resolve("staffcore-discord.json"), "old");

		LegacyFiles.Settled settled = LegacyFiles.settle(folder(), config, "discord.json", "staffcore-discord.json");
		assertEquals(folder().resolve("discord.json"), settled.path());
		assertTrue(settled.problem());
		assertTrue(settled.note().contains("config/staffcore-discord.json"), settled.note());
		assertEquals("old", Files.readString(config.resolve("staffcore-discord.json")));
	}

	@Test
	@DisplayName("a move that fails leaves the file where it is, in use")
	void failedMove() throws IOException {
		Files.writeString(folder(), "a file where the folder should be");
		Files.writeString(config.resolve("staffcore-discord.json"), "old");

		LegacyFiles.Settled settled = LegacyFiles.settle(folder(), config, "discord.json", "staffcore-discord.json");
		assertEquals(config.resolve("staffcore-discord.json"), settled.path());
		assertTrue(settled.problem());
		assertEquals("old", Files.readString(settled.path()));
	}
}
