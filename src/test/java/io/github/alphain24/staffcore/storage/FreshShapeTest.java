package io.github.alphain24.staffcore.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A brand new database is built at the current shape, with nothing left for the repair to do.
 * <p>
 * A fresh database skips the migrations and is stamped up to date, so the {@code CREATE TABLE}
 * statements have to describe the current shape. They had fallen seventeen columns behind, and the
 * safety net that catches a migration that did not run filled them in on every new server, with a
 * warning per column saying something had gone wrong. The database worked; the first thing a new
 * owner read was twenty lines saying it was damaged.
 */
class FreshShapeTest {

	@TempDir
	Path dir;

	private Connection fresh() throws SQLException {
		return DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("fresh.db"));
	}

	@Test
	@DisplayName("a new database needs no column repaired")
	void nothingToRepair() throws SQLException {
		try (Connection conn = fresh()) {
			assertEquals(List.of(), Schema.create(conn),
					"a fresh database was built without these columns, so the repair added them. Add them "
							+ "to the CREATE TABLE statement as well as to the migration.");
		}
	}

	/** Every column a migration adds, and every index anything in the schema makes, by name. */
	private static final Pattern ADDED = Pattern.compile("addColumn\\(conn, \"([a-z_]+)\", \"([a-z_]+)\"");
	private static final Pattern INDEX = Pattern.compile("CREATE (?:UNIQUE )?INDEX IF NOT EXISTS ([a-z_]+)");

	@Test
	@DisplayName("a new database has every column and index the migrations make")
	void everythingTheMigrationsMake() throws SQLException, IOException {
		String schema = Files.readString(Path.of("src/main/java/io/github/alphain24/staffcore/storage/Schema.java"),
				StandardCharsets.UTF_8);

		Set<String> columns = new LinkedHashSet<>();
		Matcher added = ADDED.matcher(schema);
		while (added.find()) columns.add(added.group(1) + "." + added.group(2));
		assertTrue(columns.size() > 30, "the scan found only " + columns.size() + " columns: " + columns);

		Set<String> indexes = new LinkedHashSet<>();
		Matcher index = INDEX.matcher(schema);
		while (index.find()) indexes.add(index.group(1));
		assertTrue(indexes.size() > 15, "the scan found only " + indexes.size() + " indexes: " + indexes);

		try (Connection conn = fresh()) {
			Schema.create(conn);
			Set<String> missing = new LinkedHashSet<>();
			for (String column : columns) {
				String[] parts = column.split("\\.");
				try (ResultSet rs = conn.getMetaData().getColumns(null, null, parts[0], parts[1])) {
					if (!rs.next()) missing.add(column);
				}
			}
			try (Statement st = conn.createStatement();
					ResultSet rs = st.executeQuery("SELECT name FROM sqlite_master WHERE type = 'index'")) {
				Set<String> present = new LinkedHashSet<>();
				while (rs.next()) present.add(rs.getString(1));
				for (String name : indexes) if (!present.contains(name)) missing.add("index " + name);
			}
			assertEquals(Set.of(), missing, "a fresh database lacks what a migration makes");
		}
	}
}
