package io.github.alphain24.staffcore.command;

import io.github.alphain24.staffcore.StaffCore;
import io.github.alphain24.staffcore.storage.Storage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Completing a name the server has seen but is not currently holding.
 * <p>
 * Vanilla completes the online list, which is the population staff least need: the player who
 * has to be looked up is the one who logged off before the report arrived. Typing that name
 * from memory is where the spelling goes wrong, and a wrong spelling on a punishment command
 * is a punishment on somebody else.
 * <p>
 * The server-dependent half of this — resolving a name to a profile — needs a live server and
 * is covered by the gametests. What is checked here is the database half, which is where the
 * offline names come from and where a query bug would silently return nothing.
 */
class KnownPlayersTest {

	@TempDir
	Path world;

	private Storage storage;

	@BeforeEach
	void open() {
		storage = StaffCore.storage();
		storage.open(world);
	}

	@AfterEach
	void close() {
		if (storage != null) storage.close();
	}

	private void connected(String name) throws SQLException {
		try (PreparedStatement ps = storage.conn().prepareStatement(
				"INSERT INTO connections (uuid, name, ip, first_seen, last_seen, joins) "
						+ "VALUES (?,?,?,?,?,1)")) {
			ps.setString(1, UUID.randomUUID().toString());
			ps.setString(2, name);
			ps.setString(3, "hash-" + name);
			ps.setLong(4, System.currentTimeMillis());
			ps.setLong(5, System.currentTimeMillis());
			ps.executeUpdate();
		}
	}

	private void punished(String name) throws SQLException {
		try (PreparedStatement ps = storage.conn().prepareStatement(
				"INSERT INTO punishments (target_uuid, target_name, staff_name, type, reason, "
						+ "created_at, active) VALUES (?,?,'Alice','BAN','x',?,1)")) {
			ps.setString(1, UUID.randomUUID().toString());
			ps.setString(2, name);
			ps.setLong(3, System.currentTimeMillis());
			ps.executeUpdate();
		}
	}

	@Test
	@DisplayName("names come from everyone who has joined, not just whoever is online")
	void offlineNamesAreOffered() throws SQLException {
		connected("Steve_");
		connected("Steven");
		connected("Notch");

		List<String> suggestions = KnownPlayers.startingWith(null, "Ste", 40);

		assertTrue(suggestions.contains("Steve_"), "an offline name was not offered");
		assertTrue(suggestions.contains("Steven"));
		assertFalse(suggestions.contains("Notch"), "a name that does not match was offered");
	}

	@Test
	@DisplayName("somebody punished while offline and never seen since is still findable")
	void punishmentTargetsCount() throws SQLException {
		// The case where staff most need the name and are least likely to remember it: an
		// account banned by name, which never connected again and so has no connection row.
		punished("GhostAccount");

		assertTrue(KnownPlayers.startingWith(null, "Ghost", 40).contains("GhostAccount"));
	}

	@Test
	@DisplayName("a name is offered once, however many places know it")
	void noDuplicates() throws SQLException {
		connected("Steve_");
		punished("Steve_");

		assertEquals(1, KnownPlayers.startingWith(null, "Steve", 40).stream()
				.filter("Steve_"::equals).count(),
				"the same name appeared twice, which reads as two accounts");
	}

	@Test
	@DisplayName("completion is case-insensitive, because typing is")
	void caseDoesNotMatter() throws SQLException {
		connected("Steve_");

		assertTrue(KnownPlayers.startingWith(null, "ste", 40).contains("Steve_"));
		assertTrue(KnownPlayers.startingWith(null, "STE", 40).contains("Steve_"));
	}

	@Test
	@DisplayName("a name containing a LIKE wildcard matches itself and nothing else")
	void wildcardsInNamesAreEscaped() throws SQLException {
		// Underscores are legal and common in Minecraft names, and unescaped an underscore is
		// a single-character wildcard. "Steve_" would quietly match "Steven" too, and the
		// ambiguity check would then refuse a name that is not actually ambiguous.
		connected("Steve_");
		connected("Steven");

		List<String> exact = KnownPlayers.startingWith(null, "Steve_", 40);
		assertTrue(exact.contains("Steve_"));
		assertFalse(exact.contains("Steven"),
				"the underscore was treated as a wildcard, so a distinct name matched");
	}

	@Test
	@DisplayName("the limit is respected, so completion cannot flood a chat box")
	void boundedOutput() throws SQLException {
		for (int i = 0; i < 60; i++) connected("Player" + i);

		assertTrue(KnownPlayers.startingWith(null, "Player", 10).size() <= 10);
	}

	@Test
	@DisplayName("an empty database answers nothing rather than failing")
	void nothingKnownIsNotAnError() {
		assertTrue(KnownPlayers.startingWith(null, "anyone", 40).isEmpty());
		assertFalse(KnownPlayers.resolve(null, "anyone").isResolved());
		assertFalse(KnownPlayers.resolve(null, "anyone").isAmbiguous());
	}
}
