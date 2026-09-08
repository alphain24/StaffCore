package io.github.alphain24.staffcore.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every audit row records the build it was written under.
 * <p>
 * The reason is the same one that put those columns on a signal in the first place: an action
 * taken while a hook was silently broken means something different from the same action on a
 * healthy server, and after the fact there is nothing else in the row that can tell an
 * investigation which it was. A punishment issued the week a detector was misapplying, an
 * inventory edit made while the gateway was half-wired — both look exactly like the healthy
 * case from the data alone.
 * <p>
 * Checked by reading the SQL rather than by writing rows, because the failure this guards
 * against is a column that exists and is never populated. A row test would need every write
 * path to have a live server behind it; the statement is right there in the source, and a
 * write that does not name the column cannot possibly fill it.
 */
class AuditVersionsTest {

	private static final Path SOURCE = Path.of("src", "main", "java");

	/**
	 * The tables an investigation actually starts from.
	 * <p>
	 * Not every table in the mod. {@code connections} and {@code block_log} are observations
	 * of what happened rather than records of what somebody decided, they are written
	 * thousands of times an hour, and two more columns on each would cost real space to answer
	 * a question nobody asks of them.
	 */
	private static final List<String> AUDIT_TABLES = List.of(
			"punishments", "inventory_audit", "case_events", "command_log", "notes");

	@Test
	@DisplayName("every audit table's insert names both version columns")
	void nothingIsWrittenWithoutItsBuild() throws IOException {
		Map<String, String> inserts = insertsInSource();
		List<String> missing = new ArrayList<>();

		for (String table : AUDIT_TABLES) {
			String sql = inserts.get(table);
			if (sql == null) {
				missing.add(table + " — no INSERT found at all");
				continue;
			}
			if (!sql.contains("server_version")) missing.add(table + ".server_version");
			if (!sql.contains("mod_version")) missing.add(table + ".mod_version");
		}

		assertTrue(missing.isEmpty(), """
				An audit row is written without the build it happened under:

				  """ + String.join("\n  ", missing) + """


				A column that exists and is never populated is worse than one that does not \
				exist: it reads as recorded. Add both to the INSERT and set them from \
				Versions.minecraft() and Versions.mod().""");
	}

	@Test
	@DisplayName("the scan found the statements it claims to check")
	void theScanIsNotVacuous() throws IOException {
		// If the pattern stops matching, every table above silently reports "no INSERT found"
		// — which the test above would catch — or, worse, the map comes back empty and a
		// future refactor of this test reads the empty case as a pass.
		Map<String, String> inserts = insertsInSource();

		assertEquals(AUDIT_TABLES.size(),
				AUDIT_TABLES.stream().filter(inserts::containsKey).count(),
				"the SQL scan did not find an INSERT for every audit table. It found: "
						+ inserts.keySet());
	}

	/**
	 * The column list of each {@code INSERT INTO table (...)} in the source.
	 * <p>
	 * Bracket-matched rather than regex-terminated, because these statements are text blocks
	 * that wrap across lines and a pattern stopping at the first {@code )} would read half a
	 * column list and call the rest missing.
	 */
	private static Map<String, String> insertsInSource() throws IOException {
		Pattern insert = Pattern.compile("INSERT INTO\\s+([a-z_]+)\\s*\\(");
		Map<String, String> out = new LinkedHashMap<>();

		try (Stream<Path> files = Files.walk(SOURCE)) {
			for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
				String body = Files.readString(file, StandardCharsets.UTF_8);
				Matcher m = insert.matcher(body);

				while (m.find()) {
					String columns = bracketed(body, m.end() - 1);
					if (columns != null) out.merge(m.group(1), columns, (a, b) -> a + " " + b);
				}
			}
		}
		return out;
	}

	private static String bracketed(String body, int openIndex) {
		int depth = 0;
		for (int i = openIndex; i < body.length(); i++) {
			char c = body.charAt(i);
			if (c == '(') depth++;
			else if (c == ')') {
				depth--;
				if (depth == 0) return body.substring(openIndex + 1, i);
			}
		}
		return null;
	}
}
