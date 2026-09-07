package io.github.alphain24.staffcore.modules.punish;

import io.github.alphain24.staffcore.StaffCore;
import io.github.alphain24.staffcore.config.StaffConfig;
import io.github.alphain24.staffcore.storage.Storage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The warning ladder counts, forgets, and never acts.
 * <p>
 * The last of those is the one worth defending in a test, because it is the one a later change
 * would find tempting to "finish". An automatic ladder fires on a count rather than a
 * judgement, it is a rule players learn to sit just underneath, and the case where a count is
 * most likely to be wrong — somebody warned repeatedly by one staff member with a grudge — is
 * exactly where a human in the loop is the only safeguard there is.
 */
class WarningLadderTest {

	@TempDir
	Path world;

	private Storage storage;

	private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000f1");

	@org.junit.jupiter.api.BeforeAll
	static void bootstrap() {
		// PunishmentType's constants build Components, so the registries have to exist before
		// the class initialises. Without this the suggestion path throws NoClassDefFoundError
		// and only the tests that never reach it pass — which reads as a partly-working
		// ladder rather than as a missing bootstrap.
		net.minecraft.SharedConstants.tryDetectVersion();
		net.minecraft.server.Bootstrap.bootStrap();
	}

	@BeforeEach
	void open() {
		storage = StaffCore.storage();
		storage.open(world);
		StaffConfig.get().warnPointsDefault = 1;
		StaffConfig.get().warnDecayDays = 90;
		StaffConfig.get().warnEscalationPoints = 3;
	}

	@AfterEach
	void close() {
		if (storage != null) storage.close();
	}

	/** A warning, optionally aged and optionally already reversed. */
	private void warn(int daysAgo, int points, boolean active) throws SQLException {
		try (PreparedStatement ps = storage.conn().prepareStatement("""
				INSERT INTO punishments (target_uuid, target_name, staff_name, type, reason,
				                         created_at, active, points)
				VALUES (?,?,?,'WARN',?,?,?,?)
				""")) {
			ps.setString(1, PLAYER.toString());
			ps.setString(2, "Steve_");
			ps.setString(3, "Alice");
			ps.setString(4, "being rude");
			ps.setLong(5, System.currentTimeMillis() - daysAgo * 86_400_000L);
			ps.setInt(6, active ? 1 : 0);
			ps.setInt(7, points);
			ps.executeUpdate();
		}
	}

	@Test
	@DisplayName("below the threshold, nothing is suggested")
	void quietUntilItIsNot() throws SQLException {
		warn(1, 1, true);
		warn(2, 1, true);

		var standing = WarningPoints.standingOf(PLAYER);
		assertEquals(2, standing.points());
		assertEquals(2, standing.warnings());
		assertFalse(standing.escalates());
		assertNull(standing.suggested());
	}

	@Test
	@DisplayName("crossing the threshold suggests, and only suggests")
	void crossingSuggests() throws SQLException {
		warn(1, 1, true);
		warn(2, 1, true);
		warn(3, 1, true);

		var standing = WarningPoints.standingOf(PLAYER);
		assertTrue(standing.escalates());
		assertEquals(PunishmentType.MUTE, standing.suggested());
		assertTrue(standing.reason().contains("3 point"));

		// Nothing was applied. The record still holds three warnings and nothing else — if a
		// later change made the ladder act, this is what would catch it.
		assertEquals(0, countOfType("MUTE"), "the ladder issued a punishment by itself");
		assertEquals(0, countOfType("TEMPBAN"));
		assertEquals(3, countOfType("WARN"));
	}

	@Test
	@DisplayName("twice the threshold suggests something heavier")
	void theLadderHasTwoRungs() throws SQLException {
		for (int i = 0; i < 6; i++) warn(i + 1, 1, true);

		assertEquals(PunishmentType.TEMPBAN, WarningPoints.standingOf(PLAYER).suggested());
		assertEquals(0, countOfType("TEMPBAN"), "still only a suggestion");
	}

	@Test
	@DisplayName("old warnings decay out of the total but stay on the record")
	void decayIsAboutWeightNotDeletion() throws SQLException {
		warn(200, 1, true);
		warn(180, 1, true);
		warn(1, 1, true);

		var standing = WarningPoints.standingOf(PLAYER);

		// A player warned three times in a week is a different person from one warned three
		// times across two years, and a ladder that cannot tell them apart eventually bans
		// somebody for having been around a long time.
		assertEquals(1, standing.points(), "only the recent warning should still count");
		assertFalse(standing.escalates());

		assertEquals(3, countOfType("WARN"),
				"the warnings themselves must stay — decay is about weight, not deletion");
	}

	@Test
	@DisplayName("0 decay days keeps every warning counting forever")
	void decayCanBeSwitchedOff() throws SQLException {
		StaffConfig.get().warnDecayDays = 0;
		warn(2000, 1, true);
		warn(1500, 1, true);
		warn(1000, 1, true);

		assertTrue(WarningPoints.standingOf(PLAYER).escalates(),
				"0 should mean no decay, matching every other retention-shaped knob");
	}

	@Test
	@DisplayName("a reversed warning stops counting")
	void reversalRemovesTheWeight() throws SQLException {
		warn(1, 1, true);
		warn(2, 1, true);
		warn(3, 1, false);   // withdrawn

		var standing = WarningPoints.standingOf(PLAYER);

		// Otherwise reversing a warning would be meaningless: it would still push the next
		// one over the line, and the staff member who withdrew it would have changed nothing.
		assertEquals(2, standing.points());
		assertFalse(standing.escalates());
	}

	@Test
	@DisplayName("0 escalation points switches the ladder off entirely")
	void theLadderIsOptional() throws SQLException {
		StaffConfig.get().warnEscalationPoints = 0;
		for (int i = 0; i < 10; i++) warn(i + 1, 1, true);

		assertFalse(WarningPoints.standingOf(PLAYER).escalates(),
				"a server that does not want a ladder should be able to say so");
	}

	@Test
	@DisplayName("warnings written before points existed still count")
	void upgradingDoesNotWipeStanding() throws SQLException {
		// Migration 17 defaults points to 0, so rows written by an older build carry none.
		// Reading those as zero would silently clear everybody's standing on upgrade.
		warn(1, 0, true);
		warn(2, 0, true);
		warn(3, 0, true);

		var standing = WarningPoints.standingOf(PLAYER);
		assertEquals(3, standing.points(),
				"pre-existing warnings should fall back to the default weight");
		assertTrue(standing.escalates());
	}

	@Test
	@DisplayName("a player with no warnings has no standing and no suggestion")
	void nothingIsTheNormalCase() {
		var standing = WarningPoints.standingOf(UUID.randomUUID());
		assertEquals(0, standing.points());
		assertEquals(0, standing.warnings());
		assertFalse(standing.escalates());
	}

	@Test
	@DisplayName("nothing in WarningPoints can issue a punishment")
	void theLadderCannotAct() throws Exception {
		// Structural, and the version that survives somebody rewriting the logic: if the class
		// cannot reach PunishmentModule, it cannot escalate on its own however the arithmetic
		// changes.
		String source = java.nio.file.Files.readString(java.nio.file.Path.of(
				"src/main/java/io/github/alphain24/staffcore/modules/punish/WarningPoints.java"));

		assertFalse(source.contains(".apply("),
				"WarningPoints calls apply(). The ladder is supposed to recommend, not act — "
						+ "and the case where it is most likely to be wrong is the one where a "
						+ "human is the only safeguard.");
		assertFalse(source.contains("Mods.punish()"));
	}

	private int countOfType(String type) throws SQLException {
		try (PreparedStatement ps = storage.conn().prepareStatement(
				"SELECT COUNT(*) FROM punishments WHERE target_uuid = ? AND type = ?")) {
			ps.setString(1, PLAYER.toString());
			ps.setString(2, type);
			try (var rs = ps.executeQuery()) {
				return rs.next() ? rs.getInt(1) : -1;
			}
		}
	}
}
