package io.github.alphain24.staffcore.modules.security;

import io.github.alphain24.staffcore.StaffCore;
import io.github.alphain24.staffcore.storage.Storage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The way home, and the one failure it exists to prevent.
 * <p>
 * A replay puts a staff member in spectator, in a dimension they did not walk to, at
 * coordinates they did not choose. If the record of where they were is lost, they are stranded
 * — and the way it gets lost is the server stopping, which is exactly when nobody can be asked.
 * <p>
 * Writing the record needs a live player, so that half is a gametest. What is checked here is
 * everything about the record itself: that it survives a restart, that a second entry cannot
 * overwrite it with the spectator position, and that a value it cannot read does not resolve to
 * the state it is meant to rescue somebody from.
 */
class ReplaySessionTest {

	@TempDir
	Path world;

	private Storage storage;

	private static final UUID STAFF = UUID.fromString("00000000-0000-0000-0000-0000000000c1");

	@AfterEach
	void close() {
		if (storage != null) storage.close();
	}

	private void restart() {
		if (storage != null) storage.close();
		storage = StaffCore.storage();
		storage.open(world);
	}

	/** Writes a session directly, standing in for the live-player path. */
	private void session(String gameMode, String dimension, double x, boolean vanished)
			throws SQLException {

		try (PreparedStatement ps = storage.conn().prepareStatement("""
				INSERT INTO replay_session (uuid, case_id, subject, prior_gamemode, prior_world,
				                            prior_x, prior_y, prior_z, prior_yaw, prior_pitch,
				                            prior_vanished, started_at)
				VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
				ON CONFLICT(uuid) DO NOTHING
				""")) {
			ps.setString(1, STAFF.toString());
			ps.setString(2, "C4KX9QW1");
			ps.setString(3, "Steve_");
			ps.setString(4, gameMode);
			ps.setString(5, dimension);
			ps.setDouble(6, x);
			ps.setDouble(7, 64);
			ps.setDouble(8, -30);
			ps.setFloat(9, 90);
			ps.setFloat(10, 12);
			ps.setInt(11, vanished ? 1 : 0);
			ps.setLong(12, System.currentTimeMillis());
			ps.executeUpdate();
		}
	}

	@Test
	@DisplayName("the way home survives the server stopping")
	void survivesARestart() throws SQLException {
		// The failure this class exists for. In memory, the record dies with the process and
		// leaves somebody permanently in spectator at the bottom of a stranger's excavation,
		// with nothing anywhere that says where they came from.
		restart();
		session("survival", "overworld", 100.5, false);

		restart();
		ReplaySession.Prior prior = ReplaySession.of(STAFF);

		assertNotNull(prior, "the way home was lost with the process");
		assertEquals("overworld", prior.world());
		assertEquals(100.5, prior.x(), 1e-9);
		assertEquals(net.minecraft.world.level.GameType.SURVIVAL, prior.gameMode());
		assertEquals("Steve_", prior.subject());
		assertEquals("C4KX9QW1", prior.caseId(), "the case it was opened from is part of the record");
	}

	@Test
	@DisplayName("a second entry cannot overwrite the way home")
	void enteringTwiceKeepsTheFirstPosition() throws SQLException {
		// The specific way this goes wrong: somebody already replaying runs the command
		// again, and the position recorded is the one they are standing at now — inside the
		// mine. Restoring then puts them back exactly where they did not want to be.
		restart();
		session("creative", "overworld", 100.5, false);
		session("spectator", "overworld", -4000.0, false);

		ReplaySession.Prior prior = ReplaySession.of(STAFF);
		assertEquals(100.5, prior.x(), 1e-9, "the second entry overwrote the way home");
		assertEquals(net.minecraft.world.level.GameType.CREATIVE, prior.gameMode());
	}

	@Test
	@DisplayName("an unreadable gamemode restores to survival, never to spectator")
	void theFallbackIsNotTheStateItRescuesFrom() throws SQLException {
		// The quiet failure. Defaulting to spectator would put somebody back where they
		// started and leave them unable to touch anything — which reads as the restore having
		// worked, because they are home.
		restart();
		session("adventurous", "overworld", 1, false);

		assertEquals(net.minecraft.world.level.GameType.SURVIVAL,
				ReplaySession.of(STAFF).gameMode());
	}

	@Test
	@DisplayName("the vanish state is remembered, so looking at a mine does not reveal you")
	void vanishIsPartOfTheRecord() throws SQLException {
		restart();
		session("survival", "overworld", 1, true);

		assertTrue(ReplaySession.of(STAFF).vanished(),
				"somebody already hidden would be revealed by the restore");
	}

	@Test
	@DisplayName("clearing forgets one session and nothing else")
	void clearIsPrecise() throws SQLException {
		restart();
		session("survival", "overworld", 1, false);
		assertTrue(ReplaySession.isReplaying(STAFF));

		ReplaySession.clear(STAFF);
		assertFalse(ReplaySession.isReplaying(STAFF));
		assertNull(ReplaySession.of(STAFF));
	}

	@Test
	@DisplayName("open sessions are listable, which is how a restart finds them")
	void openSessionsAreFindable() throws SQLException {
		restart();
		assertTrue(ReplaySession.open().isEmpty());

		session("survival", "overworld", 1, false);
		assertEquals(1, ReplaySession.open().size());
		assertEquals(STAFF, ReplaySession.open().get(0));
	}

	@Test
	@DisplayName("asking about somebody who is not replaying is not an error")
	void theCommonCaseIsQuiet() {
		// Four of the five callers fire for every player on every join, death and dimension
		// change. The answer for almost all of them is no, and it has to be cheap and silent.
		restart();
		assertNull(ReplaySession.of(UUID.randomUUID()));
		assertFalse(ReplaySession.isReplaying(UUID.randomUUID()));
		assertNull(ReplaySession.of(null));
	}
}
