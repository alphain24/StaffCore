package io.github.alphain24.staffcore.storage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Migration 30: every appeal code issued before the codes table is copied into it, as issued, so an
 * upgrade leaves every code a player photographed working exactly as it did.
 */
class AppealCodesMigrationTest {

	@TempDir
	Path world;

	private Storage storage;

	@AfterEach
	void close() {
		if (storage != null) storage.close();
	}

	@Test
	@DisplayName("upgrading from v29 copies each punishment's appeal code in, usable from the day it was issued")
	void backfill() throws SQLException {
		storage = new Storage();
		storage.open(world);
		try (Statement st = storage.conn().createStatement()) {
			st.executeUpdate("DELETE FROM punishments");
			st.executeUpdate("INSERT INTO punishments (target_uuid, target_name, type, created_at, active, appeal_code) "
					+ "VALUES ('u1','One','BAN', 1000, 1, 'ABCDEFGHJKMN')");
			st.executeUpdate("INSERT INTO punishments (target_uuid, target_name, type, created_at, active, appeal_code) "
					+ "VALUES ('u2','Two','KICK', 2000, 0, NULL)");
			st.executeUpdate("DROP TABLE appeal_codes");
			st.executeUpdate("PRAGMA user_version = 29");
		}
		storage.close();

		storage = new Storage();
		storage.open(world);
		try (Statement st = storage.conn().createStatement();
				ResultSet rs = st.executeQuery("SELECT code, issued_at, usable_from, retired_at FROM appeal_codes")) {
			assertTrue(rs.next(), "the ban's code was not copied in");
			assertEquals("ABCDEFGHJKMN", rs.getString("code"));
			assertEquals(1000, rs.getLong("issued_at"));
			assertEquals(1000, rs.getLong("usable_from"));
			rs.getLong("retired_at");
			assertTrue(rs.wasNull(), "a copied code was retired");
			assertTrue(!rs.next(), "a punishment without a code was given one");
		}
	}
}
