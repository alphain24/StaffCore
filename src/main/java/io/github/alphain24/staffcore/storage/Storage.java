package io.github.alphain24.staffcore.storage;

import io.github.alphain24.staffcore.StaffCore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * SQLite-backed storage. One connection, opened when the server starts.
 * <p>
 * Three things here matter more than the plumbing:
 * <ul>
 *   <li><b>A corrupt database must not stop the server booting.</b> Moderation data is
 *       important, but it is not worth a server that will not start — so a file that fails
 *       its integrity check is set aside, the newest good backup is tried, and failing that
 *       StaffCore starts empty rather than not at all.</li>
 *   <li><b>Multi-statement work is transactional.</b> A crash between two writes used to
 *       leave a half-applied change nobody would ever notice; {@link #inTransaction} makes
 *       the pair atomic.</li>
 *   <li><b>Backups exist.</b> Everything above is damage control; a copy of yesterday's
 *       file is the only thing that actually gets the data back.</li>
 * </ul>
 */
public final class Storage {

	private static final String FILE = "staffcore.db";
	/**
	 * Milliseconds included because {@code VACUUM INTO} refuses to overwrite.
	 * <p>
	 * At second resolution two backups taken in the same second collide on the filename and
	 * the second one silently fails — which is not a hypothetical: a backup is written on
	 * start, and an admin who runs {@code /staff backup} immediately afterwards would get
	 * nothing and be told the backup failed.
	 */
	private static final DateTimeFormatter STAMP =
			DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS");

	private Connection conn;
	private Path databasePath;
	private Path worldDir;

	public void open(Path worldDir) {
		this.worldDir = worldDir;
		Path db = worldDir.resolve(FILE);
		this.databasePath = db;

		try {
			// Force the driver to load through our own classloader — the JiJ'd sqlite-jdbc
			// is not always visible to the JDBC ServiceLoader under Fabric's module setup.
			Class.forName("org.sqlite.JDBC");
		} catch (ClassNotFoundException e) {
			StaffCore.LOGGER.error("[StaffCore] SQLite driver missing - persistent features are disabled", e);
			return;
		}

		if (!connect(db) && !recover(db)) {
			StaffCore.LOGGER.error("[StaffCore] Storage unavailable - persistent features are disabled.");
			conn = null;
			return;
		}

		try {
			Schema.create(conn);
			StaffCore.LOGGER.info("[StaffCore] Storage ready at {}", db);
			backupOnStart();
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[StaffCore] Could not prepare the schema", e);
			close();
		}
	}

	/**
	 * Opens the file and satisfies itself that it is actually readable.
	 * <p>
	 * {@code integrity_check} rather than "did the connection open": SQLite will happily
	 * hand back a connection to a file whose pages are damaged, and the failure then shows
	 * up much later as one table that throws — usually the first time somebody needs it.
	 */
	private boolean connect(Path db) {
		try {
			conn = DriverManager.getConnection("jdbc:sqlite:" + db);
			try (Statement st = conn.createStatement()) {
				st.executeUpdate("PRAGMA journal_mode=WAL");
				st.executeUpdate("PRAGMA synchronous=NORMAL");
				st.executeUpdate("PRAGMA foreign_keys=ON");
			}

			if (!Files.exists(db) || Files.size(db) == 0) return true;   // brand new

			try (Statement st = conn.createStatement();
					ResultSet rs = st.executeQuery("PRAGMA integrity_check")) {
				String result = rs.next() ? rs.getString(1) : "unknown";
				if ("ok".equalsIgnoreCase(result)) return true;

				StaffCore.LOGGER.error("[StaffCore] Database failed its integrity check: {}", result);
			}
			close();
			return false;
		} catch (SQLException | IOException e) {
			StaffCore.LOGGER.error("[StaffCore] Could not open {}: {}", db, e.getMessage());
			close();
			return false;
		}
	}

	/**
	 * Sets a damaged file aside and tries to carry on.
	 * <p>
	 * The broken file is kept, not deleted — it is the only copy of whatever was in it, and
	 * a database that will not open through JDBC can still often be salvaged by hand. Then
	 * the newest backup is tried, and if there is none, StaffCore starts with an empty file.
	 * <p>
	 * Starting empty is a real loss and is logged as one. It is still better than refusing
	 * to boot: the server is somebody's whole community, and StaffCore is one mod on it.
	 */
	private boolean recover(Path db) {
		Path quarantined = db.resolveSibling(FILE + ".corrupt-" + LocalDateTime.now().format(STAMP));
		try {
			if (Files.exists(db)) {
				Files.move(db, quarantined, StandardCopyOption.REPLACE_EXISTING);
				StaffCore.LOGGER.error("[StaffCore] Damaged database moved to {}", quarantined.getFileName());
			}
		} catch (IOException e) {
			StaffCore.LOGGER.error("[StaffCore] Could not set the damaged database aside", e);
			return false;
		}

		for (Path backup : backups()) {
			try {
				Files.copy(backup, db, StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException e) {
				continue;
			}
			if (connect(db)) {
				StaffCore.LOGGER.warn("[StaffCore] Recovered from backup {} - anything since is lost.",
						backup.getFileName());
				return true;
			}
			StaffCore.LOGGER.warn("[StaffCore] Backup {} is unusable too; trying the next one.",
					backup.getFileName());
		}

		// Every backup failed, and the last attempt left its unusable copy sitting at the
		// database path — so starting clean means actually clearing it first. Without this
		// the final fallback re-opens the corrupt backup and fails as well, turning
		// "start empty" into "do not start at all".
		try {
			Files.deleteIfExists(db);
			Files.deleteIfExists(db.resolveSibling(FILE + "-wal"));
			Files.deleteIfExists(db.resolveSibling(FILE + "-shm"));
		} catch (IOException e) {
			StaffCore.LOGGER.error("[StaffCore] Could not clear the database path", e);
			return false;
		}

		if (connect(db)) {
			StaffCore.LOGGER.error("[StaffCore] No usable backup - starting with an empty database.");
			StaffCore.LOGGER.error("[StaffCore] The damaged file is kept at {}", quarantined.getFileName());
			return true;
		}
		return false;
	}

	// ---------------------------------------------------------------- transactions

	/** Work that writes more than one row, and must not be observed half-done. */
	@FunctionalInterface
	public interface Unit {
		void run(Connection conn) throws SQLException;
	}

	/**
	 * Runs several statements as one atomic change.
	 * <p>
	 * Most of this mod writes one row at a time, where a transaction buys nothing. The
	 * places that do not — deleting a snapshot and its items, retiring a rollback and
	 * banking its restore point, handing a vaulted item back and clearing its queue row —
	 * are the places where a crash in the gap leaves something that is neither done nor
	 * undone, and which nothing will ever notice or repair.
	 * <p>
	 * Also the cheap fix for bulk work: a rollback writing four thousand restore-point rows
	 * outside a transaction is four thousand separate commits.
	 *
	 * @return true when the work committed
	 */
	public boolean inTransaction(Unit work) {
		if (conn == null) return false;

		boolean previous;
		try {
			previous = conn.getAutoCommit();
		} catch (SQLException e) {
			return false;
		}

		try {
			conn.setAutoCommit(false);
			work.run(conn);
			conn.commit();
			return true;
		} catch (SQLException e) {
			try {
				conn.rollback();
			} catch (SQLException rollbackFailed) {
				StaffCore.LOGGER.error("[StaffCore] Rollback failed after a failed transaction",
						rollbackFailed);
			}
			StaffCore.LOGGER.error("[StaffCore] Transaction rolled back", e);
			return false;
		} finally {
			try {
				conn.setAutoCommit(previous);
			} catch (SQLException ignored) {
				// The connection is already in trouble; the error above is the useful one.
			}
		}
	}

	// -------------------------------------------------------------------- backups

	public Path backupDir() {
		return worldDir == null ? null : worldDir.resolve("staffcore-backups");
	}

	/** Existing backups, newest first. */
	public List<Path> backups() {
		Path dir = backupDir();
		if (dir == null || !Files.isDirectory(dir)) return List.of();

		try (var stream = Files.list(dir)) {
			List<Path> found = new ArrayList<>(stream
					.filter(p -> p.getFileName().toString().endsWith(".db"))
					.toList());
			found.sort(Comparator.comparing((Path p) -> {
				try {
					return Files.getLastModifiedTime(p);
				} catch (IOException e) {
					return java.nio.file.attribute.FileTime.fromMillis(0);
				}
			}).reversed());
			return found;
		} catch (IOException e) {
			return List.of();
		}
	}

	/**
	 * Writes a consistent copy of the database.
	 * <p>
	 * {@code VACUUM INTO} rather than copying the file: it goes through SQLite, so the copy
	 * is a coherent snapshot of committed state even with the server writing at the time.
	 * Copying the bytes underneath a live WAL-mode database gives you a file that may be
	 * missing whatever was in the log, which is the kind of backup you only discover is
	 * useless when you need it.
	 *
	 * @return the file written, or null on failure
	 */
	public Path backup(String reason) {
		if (conn == null) return null;

		Path dir = backupDir();
		if (dir == null) return null;

		try {
			Files.createDirectories(dir);
			Path out = dir.resolve("staffcore-" + LocalDateTime.now().format(STAMP) + ".db");

			try (Statement st = conn.createStatement()) {
				// The path is quoted and single quotes doubled; it comes from the world
				// directory rather than from a user, but building SQL by concatenation is
				// worth being careful with regardless.
				st.executeUpdate("VACUUM INTO '" + out.toAbsolutePath().toString()
						.replace("'", "''") + "'");
			}
			StaffCore.LOGGER.info("[StaffCore] Backup written to {} ({})", out.getFileName(), reason);
			trimBackups();
			return out;
		} catch (IOException | SQLException e) {
			StaffCore.LOGGER.error("[StaffCore] Backup failed", e);
			return null;
		}
	}

	/** One backup per boot, so there is always something recent to fall back to. */
	private void backupOnStart() {
		int keep = io.github.alphain24.staffcore.config.StaffConfig.get().databaseBackups;
		if (keep <= 0) return;
		backup("server start");
	}

	private void trimBackups() {
		int keep = io.github.alphain24.staffcore.config.StaffConfig.get().databaseBackups;
		if (keep <= 0) return;

		List<Path> all = backups();
		for (int i = keep; i < all.size(); i++) {
			try {
				Files.deleteIfExists(all.get(i));
			} catch (IOException e) {
				StaffCore.LOGGER.warn("[StaffCore] Could not delete old backup {}", all.get(i).getFileName());
			}
		}
	}

	// -------------------------------------------------------------------- export

	/**
	 * Writes every table out as CSV, one file per table, into a timestamped folder.
	 * <p>
	 * A backup is a database — it restores, but you cannot read it, diff it, or hand it to
	 * somebody who asked what you hold about them. CSV is the format that opens in anything,
	 * which is what makes it the right one for a request that ends outside the server.
	 * <p>
	 * Reads the table list from SQLite itself rather than a hardcoded list, so a table added
	 * later is exported without anybody remembering to come back here.
	 *
	 * @return the folder written, or null on failure
	 */
	public Path export() {
		return export(false);
	}

	/**
	 * Dumps every table to CSV, redacting addresses unless asked not to.
	 * <p>
	 * The export is the one place this mod turns its database into a file that leaves the
	 * server, and it was writing every address anybody had ever connected from into a
	 * spreadsheet. Redacting by default is the right way round: an export is nearly always
	 * wanted for the punishment history or the grief log, and the addresses came along only
	 * because they happened to be in the same database.
	 *
	 * @param includePersonal write the personal data too — addresses in whatever form they are
	 *                        stored, and the position history in full. The caller is
	 *                        responsible for having asked a human first.
	 */
	public Path export(boolean includePersonal) {
		if (conn == null || worldDir == null) return null;

		Path dir = worldDir.resolve("staffcore-export")
				.resolve(LocalDateTime.now().format(STAMP));
		try {
			Files.createDirectories(dir);
		} catch (IOException e) {
			StaffCore.LOGGER.error("[StaffCore] Could not create the export folder", e);
			return null;
		}

		List<String> tables = new ArrayList<>();
		try (Statement st = conn.createStatement();
				ResultSet rs = st.executeQuery(
						"SELECT name FROM sqlite_master WHERE type='table' "
								+ "AND name NOT LIKE 'sqlite_%' ORDER BY name")) {
			while (rs.next()) tables.add(rs.getString(1));
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[StaffCore] Could not list tables for export", e);
			return null;
		}

		int written = 0;
		for (String table : tables) {
			if (exportTable(table, dir.resolve(table + ".csv"), includePersonal)) written++;
		}

		StaffCore.LOGGER.info("[StaffCore] Exported {} table(s) to {}", written, dir);
		return dir;
	}

	/** Columns holding an address, redacted unless the caller explicitly asked for them. */
	private static final java.util.Set<String> ADDRESS_COLUMNS =
			java.util.Set.of("ip", "ip_prefix", "address", "staff_ip");

	/**
	 * Tables whose rows are withheld entirely from an ordinary export.
	 * <p>
	 * Position history gets the same treatment as addresses because it is the same kind of
	 * record: not something a player did, but where a player was. It is withheld by the row
	 * rather than by the column because there is no column of it that is not the point — a
	 * position log with its coordinates redacted is a list of timestamps saying somebody was
	 * online, which is both useless and still a disclosure.
	 * <p>
	 * The header line is still written, so anything reading the folder finds the file where it
	 * expects it and can tell a withheld table from a missing one.
	 */
	private static final java.util.Set<String> PERSONAL_TABLES =
			java.util.Set.of("position_run", "position_log");

	private boolean exportTable(String table, Path out, boolean includePersonal) {
		boolean withheld = !includePersonal && PERSONAL_TABLES.contains(table);
		// Identifiers cannot be bound, and this one came from sqlite_master rather than from
		// a user — but it is quoted anyway so a table with an odd name cannot break the SQL.
		String sql = "SELECT * FROM \"" + table.replace("\"", "\"\"") + "\"";

		try (Statement st = conn.createStatement();
				ResultSet rs = st.executeQuery(sql);
				var writer = Files.newBufferedWriter(out, java.nio.charset.StandardCharsets.UTF_8)) {

			var meta = rs.getMetaData();
			int columns = meta.getColumnCount();

			boolean[] redact = new boolean[columns + 1];
			for (int i = 1; i <= columns; i++) {
				String label = meta.getColumnLabel(i);
				redact[i] = !includePersonal
						&& ADDRESS_COLUMNS.contains(label.toLowerCase(java.util.Locale.ROOT));
				if (i > 1) writer.write(',');
				writer.write(csv(label));
			}
			writer.write('\n');

			if (withheld) {
				StaffCore.LOGGER.info("[StaffCore] {} withheld from the export - it is position "
						+ "history. /staff export personal confirm includes it.", table);
				return true;
			}

			while (rs.next()) {
				for (int i = 1; i <= columns; i++) {
					if (i > 1) writer.write(',');
					// The column stays, so the file keeps its shape and anything reading it
					// keeps working; only the value goes.
					writer.write(redact[i] ? csv("[redacted]") : csv(rs.getString(i)));
				}
				writer.write('\n');
			}
			return true;
		} catch (IOException | SQLException e) {
			StaffCore.LOGGER.warn("[StaffCore] Could not export {}: {}", table, e.getMessage());
			return false;
		}
	}

	/**
	 * One CSV field.
	 * <p>
	 * Always quoted. Item stacks are stored as SNBT and are full of commas, quotes and
	 * newlines, so the choice is between quoting everything and getting this subtly wrong
	 * for exactly the rows somebody most wants to read.
	 */
	private static String csv(String value) {
		if (value == null) return "";
		return '"' + value.replace("\"", "\"\"") + '"';
	}

	// ------------------------------------------------------------------- lifecycle

	public Connection conn() {
		return conn;
	}

	public boolean isReady() {
		return conn != null;
	}

	public Path path() {
		return databasePath;
	}

	public void close() {
		try {
			if (conn != null) conn.close();
		} catch (SQLException ignored) {
			// nothing useful to do during shutdown
		} finally {
			conn = null;
		}
	}
}
