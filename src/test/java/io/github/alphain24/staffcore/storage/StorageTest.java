package io.github.alphain24.staffcore.storage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.HashSet;
import java.util.Set;
import io.github.alphain24.staffcore.compat.Mc;
import net.minecraft.world.entity.player.Inventory;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The storage layer, tested without a Minecraft server.
 * <p>
 * Everything here is SQLite and files — no registries, no world, no bootstrap — which is
 * exactly why it is worth testing this way. These are the paths that only run when something
 * has already gone wrong: a half-written transaction, a corrupt file, a schema arriving from
 * an older version. Those are impossible to exercise by playing the game and trivial to
 * exercise here.
 */
class StorageTest {

	@TempDir
	Path world;

	private Storage storage;

	private Storage open() {
		storage = new Storage();
		storage.open(world);
		return storage;
	}

	@AfterEach
	void close() {
		if (storage != null) storage.close();
	}

	// ------------------------------------------------------------------ schema

	@Test
	@DisplayName("a fresh database opens, creates its tables and lands on the current version")
	void freshDatabase() throws SQLException {
		Storage s = open();

		assertTrue(s.isReady(), "a new database should open");
		assertTrue(tableExists(s, "punishments"));
		assertTrue(tableExists(s, "rollback_point"));
		assertTrue(tableExists(s, "container_snapshot"));
		assertTrue(userVersion(s) > 0, "a fresh database should be stamped, not left at 0");
	}

	@Test
	@DisplayName("reopening runs no migrations and leaves the version alone")
	void reopenIsStable() throws SQLException {
		Storage first = open();
		int version = userVersion(first);
		first.close();

		Storage second = open();
		assertEquals(version, userVersion(second),
				"a second open should not advance or replay anything");
	}

	@Test
	@DisplayName("an old database is migrated forward exactly once")
	void migratesOldDatabase() throws SQLException {
		// Build a database that looks like it came from before the migrations existed:
		// the tables are there, it has data, and user_version is 0.
		Storage s = open();
		int current = userVersion(s);
		try (Statement st = s.conn().createStatement()) {
			st.executeUpdate("INSERT INTO punishments "
					+ "(target_uuid, target_name, type, created_at, active) "
					+ "VALUES ('u','Someone','BAN', 1, 1)");
			st.executeUpdate("PRAGMA user_version = 0");
		}
		s.close();

		Storage reopened = open();
		assertEquals(current, userVersion(reopened), "should be brought back up to date");
		assertEquals(1, count(reopened, "SELECT COUNT(*) FROM punishments"),
				"migrating must not lose the row that made it an old database");
		assertTrue(hasColumn(reopened, "snapshots", "kind"),
				"the migration that adds a column should have run");
	}

	// ------------------------------------------------------------ transactions

	@Test
	@DisplayName("a transaction commits every statement or none of them")
	void transactionCommits() {
		Storage s = open();

		boolean ok = s.inTransaction(c -> {
			insertNote(c, "first");
			insertNote(c, "second");
		});

		assertTrue(ok);
		assertEquals(2, count(s, "SELECT COUNT(*) FROM notes"));
	}

	@Test
	@DisplayName("a failure part-way through rolls the whole transaction back")
	void transactionRollsBack() {
		Storage s = open();

		// The shape of a real interrupted write: the first statement succeeds, the second
		// throws, and what must not survive is the first one on its own.
		boolean ok = s.inTransaction(c -> {
			insertNote(c, "written");
			throw new SQLException("simulated failure half way through");
		});

		assertFalse(ok, "a throwing unit should report failure");
		assertEquals(0, count(s, "SELECT COUNT(*) FROM notes"),
				"the first statement must not survive on its own");
	}

	@Test
	@DisplayName("auto-commit is restored after a transaction, successful or not")
	void transactionRestoresAutoCommit() throws SQLException {
		Storage s = open();
		assertTrue(s.conn().getAutoCommit());

		s.inTransaction(c -> insertNote(c, "ok"));
		assertTrue(s.conn().getAutoCommit(), "after a commit");

		s.inTransaction(c -> {
			throw new SQLException("nope");
		});
		assertTrue(s.conn().getAutoCommit(), "after a rollback");
	}

	@Test
	@DisplayName("writes outside the transaction are untouched by its rollback")
	void rollbackLeavesEarlierWritesAlone() {
		Storage s = open();
		s.inTransaction(c -> insertNote(c, "committed"));

		s.inTransaction(c -> {
			insertNote(c, "doomed");
			throw new SQLException("fail");
		});

		assertEquals(1, count(s, "SELECT COUNT(*) FROM notes"));
	}

	// --------------------------------------------------------------- backups

	@Test
	@DisplayName("a backup is written on start and is itself a usable database")
	void backupOnStartIsUsable() throws SQLException {
		Storage s = open();
		List<Path> backups = s.backups();

		assertEquals(1, backups.size(), "one backup per start");

		// The point of VACUUM INTO over a file copy: the result is a coherent database, not
		// a snapshot of bytes that may be missing whatever was still in the write-ahead log.
		Storage restored = new Storage();
		try {
			Path other = world.resolve("restored");
			Files.createDirectories(other);
			Files.copy(backups.get(0), other.resolve("staffcore.db"));
			restored.open(other);
			assertTrue(restored.isReady(), "a backup should open as a database");
			assertTrue(tableExists(restored, "punishments"));
		} catch (IOException e) {
			throw new AssertionError(e);
		} finally {
			restored.close();
		}
	}

	@Test
	@DisplayName("old backups are rotated out")
	void backupsRotate() {
		Storage s = open();
		int keep = io.github.alphain24.staffcore.config.StaffConfig.get().databaseBackups;

		for (int i = 0; i < keep + 3; i++) {
			assertNotNull(s.backup("test " + i));
		}
		assertTrue(s.backups().size() <= keep,
				"expected at most " + keep + " but found " + s.backups().size());
	}

	// ------------------------------------------------------ corruption recovery

	@Test
	@DisplayName("a corrupt database is set aside and the newest backup restored")
	void recoversFromBackup() throws Exception {
		Storage s = open();
		s.inTransaction(c -> insertNote(c, "worth recovering"));
		Path backup = s.backup("before the damage");
		assertNotNull(backup);
		s.close();

		corrupt(world.resolve("staffcore.db"));

		Storage recovered = open();
		assertTrue(recovered.isReady(), "must not refuse to open");
		assertEquals(1, count(recovered, "SELECT COUNT(*) FROM notes"),
				"the backup's contents should be back");

		try (var files = Files.list(world)) {
			assertTrue(files.anyMatch(p -> p.getFileName().toString().contains(".corrupt-")),
					"the damaged file must be kept, never deleted");
		}
	}

	@Test
	@DisplayName("with no usable backup it starts empty rather than refusing to boot")
	void startsEmptyWhenNoBackupSurvives() throws Exception {
		Storage s = open();
		s.close();

		// Destroy the database and every backup: the worst case.
		corrupt(world.resolve("staffcore.db"));
		try (var files = Files.list(world.resolve("staffcore-backups"))) {
			for (Path p : files.toList()) corrupt(p);
		}

		Storage recovered = open();
		assertTrue(recovered.isReady(),
				"a server is somebody's whole community; this mod must not be why it will not start");
		assertTrue(tableExists(recovered, "punishments"));
		assertEquals(0, count(recovered, "SELECT COUNT(*) FROM notes"));
	}

	// -------------------------------------------------------------- plumbing

	/**
	 * Overwrites the header with something that is definitely not a database.
	 * <p>
	 * Deliberately larger than SQLite's 100-byte header. A shorter file is treated as an
	 * empty database and silently accepted, which is worth knowing: truncation is not
	 * detectable as corruption, only malformed content of real size is.
	 */
	private static void corrupt(Path db) throws IOException {
		Files.write(db, "NOT A SQLITE DATABASE ".repeat(16).getBytes(),
				java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
				java.nio.file.StandardOpenOption.WRITE);
		Files.deleteIfExists(db.resolveSibling(db.getFileName() + "-wal"));
		Files.deleteIfExists(db.resolveSibling(db.getFileName() + "-shm"));
	}

	private static void insertNote(java.sql.Connection c, String text) throws SQLException {
		try (PreparedStatement ps = c.prepareStatement(
				"INSERT INTO notes (target_uuid, author_name, text, created_at) VALUES (?,?,?,?)")) {
			ps.setString(1, "target");
			ps.setString(2, "tester");
			ps.setString(3, text);
			ps.setLong(4, System.currentTimeMillis());
			ps.executeUpdate();
		}
	}

	private static int count(Storage s, String sql) {
		try (Statement st = s.conn().createStatement(); ResultSet rs = st.executeQuery(sql)) {
			return rs.next() ? rs.getInt(1) : -1;
		} catch (SQLException e) {
			throw new AssertionError(e);
		}
	}

	private static int userVersion(Storage s) throws SQLException {
		try (Statement st = s.conn().createStatement();
				ResultSet rs = st.executeQuery("PRAGMA user_version")) {
			return rs.next() ? rs.getInt(1) : -1;
		}
	}

	private static boolean tableExists(Storage s, String table) throws SQLException {
		try (PreparedStatement ps = s.conn().prepareStatement(
				"SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name=?")) {
			ps.setString(1, table);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() && rs.getInt(1) > 0;
			}
		}
	}

	/**
	 * A fresh database and a migrated one must end up identical.
	 * <p>
	 * This exists because of a trap the migration design sets for itself. A brand new file
	 * gets its tables from the {@code CREATE TABLE} statements and is then stamped straight
	 * to the current version without replaying anything — sensible, and it means a column
	 * added later has to be written down <em>twice</em>: once as a migration for databases
	 * that already exist, and once in the create statement for those that do not. Miss the
	 * second and every existing server is fine while every new one is quietly missing a
	 * column, which is the worst possible way round: it works for you and breaks for them.
	 * <p>
	 * That happened with {@code block_log.state}. Comparing the two paths column by column
	 * catches it for every future migration without anybody having to remember the rule.
	 */
	@Test
	@DisplayName("a fresh database has exactly the columns a migrated one ends up with")
	void freshAndMigratedAgree() throws SQLException {
		Storage fresh = open();
		Map<String, Set<String>> expected = columnsOf(fresh);
		int current = userVersion(fresh);
		fresh.close();

		// Wind the same file back to before the migrations and let it climb again. The row
		// matters: an empty database is treated as brand new and stamped without replaying
		// anything, so without it this would compare a fresh database against itself and
		// pass no matter what.
		Storage aged = open();
		try (Statement st = aged.conn().createStatement()) {
			st.executeUpdate("INSERT INTO punishments "
					+ "(target_uuid, target_name, type, created_at, active) "
					+ "VALUES ('u','Someone','BAN', 1, 1)");
			st.executeUpdate("PRAGMA user_version = 0");
		}
		aged.close();

		Storage migrated = open();
		assertEquals(current, userVersion(migrated), "the replay should land on the same version");

		Map<String, Set<String>> actual = columnsOf(migrated);
		for (var table : expected.entrySet()) {
			assertEquals(table.getValue(), actual.get(table.getKey()),
					"table " + table.getKey() + " differs between a fresh and a migrated database"
							+ " — a column was added as a migration but not to CREATE TABLE,"
							+ " or the other way round");
		}
	}

	/**
	 * The tables a rename has to rewrite must actually have the columns it names.
	 * <p>
	 * {@code IdentityModule} carries a table/uuid-column/name-column table it drives updates
	 * from, built by hand. A typo in it fails at runtime, on a join, inside a transaction
	 * that then rolls back — so the rename silently does nothing and the only sign is a log
	 * line nobody reads. Checking the shape here means a wrong entry cannot ship.
	 */
	@Test
	@DisplayName("every column the rename refresh writes to exists")
	void renameTargetsExist() throws SQLException {
		Storage s = open();
		Map<String, Set<String>> columns = columnsOf(s);

		for (String[] target : new String[][] {
				{"punishments", "target_uuid", "target_name"},
				{"reports", "target_uuid", "target_name"},
				{"reports", "reporter_uuid", "reporter_name"},
				{"appeals", "target_uuid", "target_name"},
				{"contraband_vault", "owner_uuid", "owner_name"},
				{"pending_actions", "uuid", "owner_name"},
				{"connections", "uuid", "name"},
		}) {
			Set<String> actual = columns.get(target[0]);
			assertTrue(actual != null, "table " + target[0] + " should exist");
			assertTrue(actual.contains(target[1]),
					target[0] + " should have the key column " + target[1]);
			assertTrue(actual.contains(target[2]),
					target[0] + " should have the name column " + target[2]);
		}
	}

	/**
	 * A database at <em>any</em> version must reach the same shape as a fresh one.
	 * <p>
	 * The existing parity test winds a database back to zero, which runs every migration and
	 * therefore cannot see a migration that is unreachable from a later starting point. That
	 * is exactly the hole a new migration inserted mid-list falls through: databases at or
	 * past its position skip it forever, while fresh installs build the column straight from
	 * {@code CREATE TABLE} and look fine.
	 * <p>
	 * It happened. {@code block_log.gamemode} went in at position six of seven, so every
	 * server already on version six never received it and every block break failed to log —
	 * on a worker thread, where the error went to the log and nowhere a person would look.
	 * Both tests that existed at the time passed.
	 * <p>
	 * Starting from each version in turn is the only check that actually covers upgrades.
	 */
	@Test
	@DisplayName("upgrading from any version lands on the same schema as a fresh install")
	void everyUpgradePathAgrees() throws SQLException {
		Storage fresh = open();
		Map<String, Set<String>> expected = columnsOf(fresh);
		int current = userVersion(fresh);
		fresh.close();

		assertTrue(current > 0, "a fresh database should be stamped");

		for (int from = 0; from < current; from++) {
			Storage aged = open();
			try (Statement st = aged.conn().createStatement()) {
				// The row matters: an empty database is treated as brand new and stamped
				// without replaying anything.
				st.executeUpdate("DELETE FROM punishments");
				st.executeUpdate("INSERT INTO punishments "
						+ "(target_uuid, target_name, type, created_at, active) "
						+ "VALUES ('u','Someone','BAN', 1, 1)");
				st.executeUpdate("PRAGMA user_version = " + from);
			}
			aged.close();

			Storage upgraded = open();
			assertEquals(current, userVersion(upgraded),
					"a database at v" + from + " should climb to v" + current);

			Map<String, Set<String>> actual = columnsOf(upgraded);
			for (var table : expected.entrySet()) {
				assertEquals(table.getValue(), actual.get(table.getKey()),
						"upgrading from v" + from + " left " + table.getKey()
								+ " different from a fresh install — a migration was inserted "
								+ "or reordered rather than appended, so databases past that "
								+ "point skip it");
			}
			upgraded.close();
		}
	}

	/**
	 * A database whose version counter is ahead of its actual shape repairs itself.
	 * <p>
	 * Migrations cannot reach this state by construction: the runner returns immediately when
	 * the recorded version is at or past the end of the list, so a database stamped up to date
	 * but missing a column stays broken through every restart forever. That is not a thought
	 * experiment — it is what a migration inserted mid-list did to servers that had already
	 * run the version it displaced, and no amount of correcting the list afterwards could
	 * help them.
	 * <p>
	 * The reconciliation pass exists for exactly this, and is the only thing standing between
	 * a bad migration and a database nobody can fix without SQL.
	 */
	@Test
	@DisplayName("a column missing past the last migration is repaired on the next start")
	void reconcilesDriftBeyondTheLastMigration() throws SQLException {
		Storage first = open();
		int current = userVersion(first);
		assertTrue(hasColumn(first, "block_log", "gamemode"), "fresh databases have it");
		first.close();

		// Stamp it beyond every migration and take the column away. Nothing in the migration
		// runner can touch this database now.
		Storage aged = open();
		try (Statement st = aged.conn().createStatement()) {
			st.executeUpdate("ALTER TABLE block_log DROP COLUMN gamemode");
			st.executeUpdate("PRAGMA user_version = " + (current + 5));
		}
		assertTrue(!hasColumn(aged, "block_log", "gamemode"), "the column really is gone");
		aged.close();

		Storage repaired = open();
		assertTrue(hasColumn(repaired, "block_log", "gamemode"),
				"the reconciliation pass has to put it back, because migrations never will");

		// And the writes that were failing work again.
		try (Statement st = repaired.conn().createStatement()) {
			st.executeUpdate("INSERT INTO block_log "
					+ "(player_name, action, block, gamemode, world, x, y, z, created_at) "
					+ "VALUES ('Alt','BREAK','minecraft:chest','survival','w',0,64,0,1)");
		}
		assertEquals(1, count(repaired, "SELECT COUNT(*) FROM block_log"));
	}

	/** Every table's column names, straight out of SQLite. */
	private static Map<String, Set<String>> columnsOf(Storage s) throws SQLException {
		Map<String, Set<String>> out = new TreeMap<>();
		List<String> tables = new ArrayList<>();

		try (Statement st = s.conn().createStatement();
				ResultSet rs = st.executeQuery(
						"SELECT name FROM sqlite_master WHERE type='table' "
								+ "AND name NOT LIKE 'sqlite_%' ORDER BY name")) {
			while (rs.next()) tables.add(rs.getString("name"));
		}

		for (String table : tables) {
			Set<String> columns = new TreeSet<>();
			try (Statement st = s.conn().createStatement();
					ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
				while (rs.next()) columns.add(rs.getString("name"));
			}
			out.put(table, columns);
		}
		return out;
	}

	// ------------------------------------------------------------- snapshot slots

	/**
	 * The other half of the armour bug.
	 * <p>
	 * {@link io.github.alphain24.staffcore.compat.Mc#PLAYER_SLOTS} pins the buffer size; this pins the
	 * storage, because the rows were never the problem — every armour and offhand slot was
	 * already being written and was still sitting in the table. Only the reader threw them
	 * away. A schema that stopped accepting a slot above 35 would produce identical symptoms
	 * from the opposite end, so both ends are held down.
	 */
	@Test
	@DisplayName("snapshot rows survive for equipment slots, not just the first 36")
	void snapshotKeepsEquipmentSlots() throws SQLException {
		Storage s = open();

		try (Statement st = s.conn().createStatement()) {
			st.executeUpdate("INSERT INTO snapshots (uuid, label, taken_by, stacks, taken_at) "
					+ "VALUES ('u', 'On death', 'system', 3, 1)");
		}

		long id;
		try (Statement st = s.conn().createStatement();
				ResultSet rs = st.executeQuery("SELECT id FROM snapshots")) {
			assertTrue(rs.next());
			id = rs.getLong(1);
		}

		// Boots, offhand and the last equipment slot there is.
		int[] slots = {0, 36, Inventory.SLOT_OFFHAND, Mc.PLAYER_SLOTS - 1};
		try (PreparedStatement ps = s.conn().prepareStatement(
				"INSERT INTO snapshot_items (snapshot_id, slot, item) VALUES (?,?,?)")) {
			for (int slot : slots) {
				ps.setLong(1, id);
				ps.setInt(2, slot);
				ps.setString(3, "item-at-" + slot);
				ps.addBatch();
			}
			ps.executeBatch();
		}

		Set<Integer> read = new HashSet<>();
		try (PreparedStatement ps = s.conn().prepareStatement(
				"SELECT slot FROM snapshot_items WHERE snapshot_id = ?")) {
			ps.setLong(1, id);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) read.add(rs.getInt("slot"));
			}
		}

		for (int slot : slots) {
			assertTrue(read.contains(slot), "slot " + slot + " should survive a round trip");
		}
	}

	private static boolean hasColumn(Storage s, String table, String column) throws SQLException {
		try (Statement st = s.conn().createStatement();
				ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
			while (rs.next()) {
				if (column.equalsIgnoreCase(rs.getString("name"))) return true;
			}
			return false;
		}
	}

}
