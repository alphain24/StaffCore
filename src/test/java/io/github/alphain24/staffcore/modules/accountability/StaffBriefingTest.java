package io.github.alphain24.staffcore.modules.accountability;

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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The three lines a staff member sees on joining.
 * <p>
 * The count is the design. A briefing that also mentions the weather is one people scroll
 * past, and a line reading "0 reports waiting" trains everybody to skip the line that says
 * four — so a quiet server says nothing at all, and that is the case worth defending.
 */
class StaffBriefingTest {

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

	/** A ban expiring in {@code hours}, or already gone when negative. */
	private void banExpiringIn(long hours, boolean active) throws SQLException {
		try (PreparedStatement ps = storage.conn().prepareStatement(
				"INSERT INTO punishments (target_uuid, target_name, staff_name, type, reason, "
						+ "created_at, expires_at, active) VALUES (?,?,'Alice','TEMPBAN','x',?,?,?)")) {
			ps.setString(1, UUID.randomUUID().toString());
			ps.setString(2, "Steve_");
			ps.setLong(3, System.currentTimeMillis());
			ps.setLong(4, System.currentTimeMillis() + hours * 3_600_000L);
			ps.setInt(5, active ? 1 : 0);
			ps.executeUpdate();
		}
	}

	@Test
	@DisplayName("a quiet server says nothing")
	void silenceWhenThereIsNothing() {
		// The important half. A briefing that always prints is one nobody reads, and then the
		// evening something is actually waiting looks exactly like every other evening.
		assertTrue(new StaffBriefing.Standing(0, 0, 0).isQuiet());
		assertFalse(new StaffBriefing.Standing(0, 1, 0).isQuiet());
		assertFalse(new StaffBriefing.Standing(0, 0, 1).isQuiet());
	}

	@Test
	@DisplayName("only punishments ending in the next day are counted")
	void expiringTodayIsToday() throws SQLException {
		banExpiringIn(2, true);       // today
		banExpiringIn(20, true);      // today
		banExpiringIn(72, true);      // this week, not today
		banExpiringIn(-5, true);      // already over, and the sweep will retire it

		assertEquals(2, StaffBriefing.expiringToday(),
				"a ban ending today is the last chance to decide it should not; one ending "
						+ "in three days is not that");
	}

	@Test
	@DisplayName("a lifted punishment is not still ending today")
	void inactiveDoesNotCount() throws SQLException {
		banExpiringIn(2, false);

		assertEquals(0, StaffBriefing.expiringToday(),
				"something already lifted has nothing left to end");
	}

	@Test
	@DisplayName("a permanent ban never ends today")
	void permanentIsNotCounted() throws SQLException {
		try (PreparedStatement ps = storage.conn().prepareStatement(
				"INSERT INTO punishments (target_uuid, target_name, staff_name, type, reason, "
						+ "created_at, active) VALUES (?,'Steve_','Alice','BAN','x',?,1)")) {
			ps.setString(1, UUID.randomUUID().toString());
			ps.setLong(2, System.currentTimeMillis());
			ps.executeUpdate();
		}
		assertEquals(0, StaffBriefing.expiringToday());
	}
}
