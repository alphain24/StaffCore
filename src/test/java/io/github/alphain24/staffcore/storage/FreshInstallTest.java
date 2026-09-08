package io.github.alphain24.staffcore.storage;

import io.github.alphain24.staffcore.StaffCore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A brand new database has every table the code reads from.
 * <p>
 * This is the project's most-repeated bug and it has a specific shape. Migrations exist for
 * databases that already have a previous version, so a fresh file skips all of them and is
 * stamped up to date — which means anything written <em>only</em> as a migration does not
 * exist on a new server, while the version counter says the schema is current. The failure
 * then appears at runtime, on somebody else's machine, in the one population that never sees a
 * warning: new installs.
 * <p>
 * It has happened twice. First with a column ({@code block_log.gamemode}, added mid-list, so
 * every upgraded server missed it and every fresh one had it — the mirror image). Then with a
 * whole table ({@code incident_witness}, written as a migration only, so every fresh server
 * lacked it). Both times the tests in place passed.
 * <p>
 * So this stops asking whether a particular table was remembered and asks the general
 * question: open a new database, find every table the code reads or writes, and check they are
 * all there.
 */
class FreshInstallTest {

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

	/**
	 * Table names out of the SQL in the source.
	 * <p>
	 * Textual, and deliberately so: anything cleverer would need the schema it is checking to
	 * already be right in order to work.
	 * <p>
	 * Case-sensitive on the keyword, which is what separates SQL from prose here. Every query
	 * in this codebase writes {@code FROM} and {@code INTO} in capitals and every javadoc
	 * sentence writes them in lower case, so the distinction is free — and without it the scan
	 * reads "restored from disk" as a table called {@code disk}.
	 */
	private static final Pattern TABLE = Pattern.compile(
			"\\b(?:FROM|INTO|UPDATE|JOIN)\\s+([a-z_][a-z0-9_]*)");

	/** SQL keywords the pattern above will happily read as a table name. */
	private static final Set<String> NOT_TABLES = Set.of(
			"select", "where", "set", "values", "on", "as", "and", "or", "table", "index",
			"if", "not", "exists", "pragma", "sqlite_master", "sqlite_sequence", "distinct",
			"with");

	@Test
	@DisplayName("every table the code touches exists in a database created from scratch")
	void nothingIsMigrationOnly() throws IOException, SQLException {
		Set<String> referenced = tablesReferencedInSource();
		Set<String> present = tablesIn(storage);

		List<String> missing = referenced.stream()
				.filter(name -> !present.contains(name))
				.sorted()
				.toList();

		assertTrue(missing.isEmpty(), """
				A fresh database is missing tables the code reads from:

				  """ + String.join("\n  ", missing) + """


				A fresh install skips every migration by design and is stamped up to date, so a \
				table written only as a migration does not exist on a new server while the \
				schema version says everything is current. Add it to Schema.REQUIRED_TABLES as \
				well as to the migration: the migration is how the change reaches databases \
				that already exist, and that list is how a new one gets it.""");
	}

	@Test
	@DisplayName("the scan finds real tables, so a pass means something")
	void theScanIsNotVacuous() throws IOException {
		// A regex that stopped matching would make the test above pass forever while the
		// schema quietly drifted. Counting what it found is what makes the pass meaningful.
		Set<String> referenced = tablesReferencedInSource();

		assertTrue(referenced.size() > 15,
				"the SQL scan found only " + referenced.size() + " table(s), which is far fewer "
						+ "than this mod uses. The pattern has stopped matching and the check "
						+ "above is passing vacuously.");
		assertTrue(referenced.contains("punishments") && referenced.contains("block_log"),
				"the scan missed tables that are certainly there: " + referenced);
	}

	@Test
	@DisplayName("a fresh database is stamped at the current version, not replayed")
	void freshIsStampedNotMigrated() throws SQLException {
		// The behaviour that causes the problem above, asserted rather than assumed — if this
		// ever changes, the reasoning in the other test stops applying and somebody should
		// find out by reading a failure rather than by reasoning it out again.
		try (Statement st = storage.conn().createStatement();
				ResultSet rs = st.executeQuery("PRAGMA user_version")) {
			assertTrue(rs.next() && rs.getInt(1) > 0,
					"a new database should be stamped at the current schema version");
		}
	}

	private static Set<String> tablesReferencedInSource() throws IOException {
		Set<String> found = new LinkedHashSet<>();
		Path source = Path.of("src", "main", "java");

		try (Stream<Path> files = Files.walk(source)) {
			for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
				Matcher m = TABLE.matcher(Files.readString(file, StandardCharsets.UTF_8));
				while (m.find()) {
					String name = m.group(1).toLowerCase(Locale.ROOT);
					if (!NOT_TABLES.contains(name)) found.add(name);
				}
			}
		}
		return found;
	}

	private static Set<String> tablesIn(Storage storage) throws SQLException {
		Set<String> out = new LinkedHashSet<>();
		List<String> kinds = new ArrayList<>(List.of("table", "view"));

		for (String kind : kinds) {
			try (Statement st = storage.conn().createStatement();
					ResultSet rs = st.executeQuery(
							"SELECT name FROM sqlite_master WHERE type = '" + kind + "'")) {
				while (rs.next()) out.add(rs.getString(1).toLowerCase(Locale.ROOT));
			}
		}
		return out;
	}
}
