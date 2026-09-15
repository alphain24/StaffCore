package io.github.alphain24.staffcore.discord.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The token file: read once, never repeated back, and complained about if others can read it.
 */
public class BotTokenTest {

	/**
	 * Shaped like a token and belonging to nobody.
	 * <p>
	 * Built at runtime rather than written out: a literal shaped closely enough to pass the check
	 * is also shaped closely enough for a repository's secret scanner to refuse the push, and
	 * teaching anybody to click "allow this secret" is the wrong habit for this file of all files.
	 */
	public static final String FAKE = fake();

	private static String fake() {
		StringBuilder token = new StringBuilder();
		for (int part = 0; part < 3; part++) {
			if (part > 0) token.append('.');
			int length = part == 1 ? 6 : 30;
			for (int i = 0; i < length; i++) token.append((char) ('a' + (i * 7 + part) % 26));
		}
		return token.toString();
	}

	@TempDir
	Path dir;

	private Path write(String content) throws IOException {
		Path file = dir.resolve(BotToken.FILE_NAME);
		Files.writeString(file, content, StandardCharsets.UTF_8);
		return file;
	}

	@Test
	@DisplayName("a token file is read, trimmed, and forgives a pasted Bot prefix")
	void reads() throws IOException {
		var loaded = BotToken.load(write("﻿  Bot " + FAKE + "\n"));
		assertNull(loaded.problem());
		assertEquals(FAKE, loaded.token().revealForLogin());
	}

	@Test
	@DisplayName("the token never appears in how it prints")
	void neverPrinted() throws IOException {
		BotToken token = BotToken.load(write(FAKE)).token();
		assertFalse(String.valueOf(token).contains(FAKE));
		assertFalse(("token=" + token).contains(FAKE));
		assertEquals("failed for [token]", token.redact("failed for " + FAKE));
		assertTrue(token.appearsIn("x" + FAKE + "y"));
	}

	@Test
	@DisplayName("a file that is not a token is described, and what it holds is not repeated")
	void problemsDoNotQuote() throws IOException {
		// The case that matters: a real token with one stray character, which fails the shape check
		// while still being almost all secret.
		String almost = FAKE + " !";
		var loaded = BotToken.load(write(almost));
		assertNull(loaded.token());
		assertNotNull(loaded.problem());
		assertFalse(loaded.problem().contains(FAKE.substring(0, 20)),
				"the problem quoted the file: " + loaded.problem());

		assertNotNull(BotToken.load(write("   ")).problem());
		assertNotNull(BotToken.load(dir.resolve("missing.token")).problem());
	}

	@Test
	@DisplayName("a token file every user can read is reported; one only its owner can read is not")
	void worldReadable() throws IOException {
		Path file = write(FAKE);

		PosixFileAttributeView posix = Files.getFileAttributeView(file, PosixFileAttributeView.class);
		if (posix != null) {
			posix.setPermissions(PosixFilePermissions.fromString("rw-------"));
			assertFalse(BotToken.worldReadable(file));
			posix.setPermissions(PosixFilePermissions.fromString("rw-r--r--"));
			assertTrue(BotToken.worldReadable(file));
			assertTrue(BotToken.load(file).worldReadable());
			return;
		}

		AclFileAttributeView acl = Files.getFileAttributeView(file, AclFileAttributeView.class);
		assumeTrue(acl != null, "this file system reports neither POSIX permissions nor ACLs");

		UserPrincipal everyone;
		try {
			everyone = file.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName("Everyone");
		} catch (IOException e) {
			assumeTrue(false, "no Everyone principal on this machine");
			return;
		}

		// Only the owner first, so the "no" is a real no rather than whatever the temp folder
		// happened to inherit.
		UserPrincipal owner = Files.getOwner(file);
		acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner)
				.setPermissions(EnumSet.allOf(AclEntryPermission.class)).build()));
		assertFalse(BotToken.worldReadable(file), "an owner-only file was reported as readable by all");

		List<AclEntry> open = new ArrayList<>(acl.getAcl());
		open.add(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(everyone)
				.setPermissions(AclEntryPermission.READ_DATA).build());
		acl.setAcl(open);
		assertTrue(BotToken.worldReadable(file), "a file Everyone can read was not reported");
	}
}
