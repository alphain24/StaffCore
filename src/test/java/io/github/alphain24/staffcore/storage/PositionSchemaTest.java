package io.github.alphain24.staffcore.storage;

import io.github.alphain24.staffcore.StaffCore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The position log is shaped the way the storage estimate assumes it is.
 *
 * <h2>Why a schema fact is worth a test here and almost nowhere else</h2>
 * {@code position_log} is declared {@code WITHOUT ROWID}, which is not a detail — it is the
 * reason the table does not carry a second copy of its own key. A rowid table with an index on
 * {@code (run, ms)} stores those two columns twice, and on the largest table in the database
 * that is most of the difference between the measured growth figure and roughly double it.
 * <p>
 * If somebody rewrites this DDL and drops the clause, everything still works. Every read
 * returns the right rows, every test that asks about behaviour passes, and the only symptom is
 * that servers with position tracking on quietly use about twice the disk the documentation
 * promises. That is the failure this file is for: a change that is invisible from every angle
 * except the one nobody looks at.
 *
 * <h2>Asked behaviourally, not textually</h2>
 * Reading the {@code CREATE TABLE} text back out of {@code sqlite_master} and grepping it for
 * "WITHOUT ROWID" would only confirm that the string we wrote is the string we wrote. A
 * {@code WITHOUT ROWID} table has no {@code rowid} column, so asking SQLite for one is a
 * question only the real property can answer — and {@code position_run}, an ordinary table
 * created in the same migration, is the control that proves the question is answerable at all.
 */
class PositionSchemaTest {

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

	/** Whether {@code SELECT rowid FROM <table>} is a question this table can answer. */
	private boolean hasRowid(String table) {
		try (Statement st = storage.conn().createStatement();
				ResultSet rs = st.executeQuery("SELECT rowid FROM \"" + table + "\" LIMIT 1")) {
			rs.next();
			return true;
		} catch (SQLException noSuchColumn) {
			return false;
		}
	}

	@Test
	@DisplayName("position_log is WITHOUT ROWID, so it does not store its key twice")
	void samplesAreKeyedByTheirAccessPath() {
		assertFalse(hasRowid("position_log"),
				"position_log has a rowid, which means it is an ordinary table and the "
						+ "(run, ms) primary key is a separate index over a second copy of "
						+ "those columns. The measured growth per player-hour in "
						+ "docs/decisions.md no longer describes this schema.");
	}

	@Test
	@DisplayName("an ordinary table does have a rowid, so the question above means something")
	void theControl() {
		// Without this, a typo in the table name would make the assertion above pass by
		// failing to find anything at all — which is the shape of bug this project keeps
		// producing. position_run is created by the same migration and is deliberately a
		// normal table, so it is the right control.
		assertTrue(hasRowid("position_run"),
				"position_run has no rowid either, so the check above cannot distinguish a "
						+ "WITHOUT ROWID table from a table that does not exist");
	}

	@Test
	@DisplayName("both position tables exist on a brand new database")
	void aFreshInstallGetsThem() {
		// Migrations do not run on a fresh file — it is stamped current and skips all of
		// them. Anything written only as a migration is missing on exactly the servers that
		// never see a warning. This has happened twice in this repository.
		Set<String> tables = new LinkedHashSet<>();
		try (Statement st = storage.conn().createStatement();
				ResultSet rs = st.executeQuery(
						"SELECT name FROM sqlite_master WHERE type='table'")) {
			while (rs.next()) tables.add(rs.getString(1));
		} catch (SQLException e) {
			throw new AssertionError("could not list tables", e);
		}

		assertTrue(tables.contains("position_run"),
				"position_run is missing on a fresh install. It is in the migration list and "
						+ "not in REQUIRED_TABLES; a new server skips every migration.");
		assertTrue(tables.contains("position_log"),
				"position_log is missing on a fresh install, for the same reason.");
	}

	@Test
	@DisplayName("the sample columns are integers, not reals")
	void deltasAreStoredAsIntegers() {
		// SQLite stores a REAL in eight bytes whatever its value, and an INTEGER in as few as
		// it needs — one byte for the small numbers a sample delta actually is. Storing the
		// deltas as REAL would cost roughly four times as much for no extra precision that
		// survives the 1/32-block quantisation anyway.
		try (Statement st = storage.conn().createStatement();
				ResultSet rs = st.executeQuery("PRAGMA table_info(position_log)")) {
			int checked = 0;
			while (rs.next()) {
				String column = rs.getString("name");
				if (!Set.of("dx", "dy", "dz", "yaw", "pitch", "ms", "run").contains(column)) {
					continue;
				}
				assertEquals("INTEGER", rs.getString("type"),
						column + " is not an INTEGER. SQLite gives every REAL eight bytes "
								+ "whatever it holds, so this multiplies the size of the "
								+ "largest table in the database.");
				checked++;
			}
			assertEquals(7, checked,
					"expected seven sample columns to check and found " + checked + " — the "
							+ "table shape has changed and this test is now looking at "
							+ "something else");
		} catch (SQLException e) {
			throw new AssertionError("could not read position_log's columns", e);
		}
	}
}
