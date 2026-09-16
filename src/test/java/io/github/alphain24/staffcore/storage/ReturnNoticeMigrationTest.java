package io.github.alphain24.staffcore.storage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Migration 31: bans already over when it runs count as noticed, so an upgrade does not announce every
 * player who was ever banned; a ban still in force is left to be noticed when it ends.
 */
class ReturnNoticeMigrationTest {

	@TempDir
	Path world;

	private Storage storage;

	@AfterEach
	void close() {
		if (storage != null) storage.close();
	}

	@Test
	@DisplayName("upgrading marks bans that are over, and only those")
	void backfill() throws SQLException {
		long now = System.currentTimeMillis();
		storage = new Storage();
		storage.open(world);
		try (Statement st = storage.conn().createStatement()) {
			st.executeUpdate("DELETE FROM punishments");
			st.executeUpdate("INSERT INTO punishments (id, target_uuid, target_name, type, created_at, active) "
					+ "VALUES (1, 'u1', 'Lifted', 'BAN', 1, 0)");
			st.executeUpdate("INSERT INTO punishments (id, target_uuid, target_name, type, created_at, expires_at, active) "
					+ "VALUES (2, 'u2', 'RanOut', 'TEMPBAN', 1, " + (now - 1000) + ", 1)");
			st.executeUpdate("INSERT INTO punishments (id, target_uuid, target_name, type, created_at, active) "
					+ "VALUES (3, 'u3', 'Standing', 'BAN', 1, 1)");
			st.executeUpdate("INSERT INTO punishments (id, target_uuid, target_name, type, created_at, active) "
					+ "VALUES (4, 'u4', 'Mute', 'MUTE', 1, 0)");
			st.executeUpdate("PRAGMA user_version = 30");
		}
		storage.close();

		storage = new Storage();
		storage.open(world);
		assertTrue(noticed(1), "a lifted ban was left to be announced");
		assertTrue(noticed(2), "a ban that ran out was left to be announced");
		assertFalse(noticed(3), "a ban still in force was marked as if it had ended");
		assertFalse(noticed(4), "a mute was marked; only bans are watched");
	}

	private boolean noticed(long id) throws SQLException {
		try (PreparedStatement ps = storage.conn().prepareStatement(
				"SELECT return_noticed_at FROM punishments WHERE id=?")) {
			ps.setLong(1, id);
			try (ResultSet rs = ps.executeQuery()) {
				assertTrue(rs.next());
				rs.getLong(1);
				return !rs.wasNull();
			}
		}
	}
}
