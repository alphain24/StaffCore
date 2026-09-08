package io.github.alphain24.staffcore.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every number the documentation prints for a config key must be the number the code uses.
 * <p>
 * This exists because the x-ray sample floor was documented as 500 in the handbook's config
 * reference, 200 in the README's, and described in a third place as the value the detector
 * "will not commit below" — while the code used its own answer. All three were written
 * honestly at different times. There was nothing to notice when they stopped agreeing, so
 * nobody did, and a server owner reading the handbook could not have predicted what their
 * server would do.
 * <p>
 * Documentation drift is not usually worth a test. It is here, because these particular
 * numbers decide whether a player gets accused of cheating, and because the failure is
 * invisible: wrong prose looks exactly like right prose.
 * <p>
 * Defaults are read by reflection off a fresh {@link StaffConfig} rather than from a list kept
 * alongside — a list would be a fourth copy of the same facts and would drift the same way.
 */
class ConfigDocsTest {

	/** A {@code "key": value} pair lifted out of a documented config block. */
	private record Documented(String key, String value, Path file, int line) {}

	private static final Path README = Path.of("README.md");
	private static final Path HANDBOOK = Path.of("docs", "handbook.html");

	/**
	 * Keys whose documented value is deliberately not the literal default — placeholders like
	 * {@code [ … ]} for lists, and examples showing a non-default setting.
	 */
	private static boolean isIllustrative(String value) {
		return value.contains("…") || value.startsWith("[") || value.startsWith("{");
	}

	private static final Pattern ENTRY = Pattern.compile(
			"^\\s*\"([A-Za-z][A-Za-z0-9_]*)\"\\s*:\\s*([^,\\n]+?)\\s*,?\\s*(?://.*)?$");

	private static List<Documented> documentedIn(Path file) throws IOException {
		if (!Files.exists(file)) return List.of();

		List<Documented> out = new ArrayList<>();
		List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
		for (int i = 0; i < lines.size(); i++) {
			Matcher m = ENTRY.matcher(lines.get(i));
			if (!m.matches()) continue;

			String value = m.group(2).trim();
			if (value.endsWith("{") || isIllustrative(value)) continue;
			out.add(new Documented(m.group(1), value, file, i + 1));
		}
		return out;
	}

	/** The real defaults, straight off an unmodified instance. */
	private static Map<String, String> actualDefaults() {
		StaffConfig defaults = new StaffConfig();
		Map<String, String> out = new LinkedHashMap<>();

		// configVersion is the one field whose in-memory default is not what lands on disk:
		// it starts at 0 and is stamped with CURRENT_VERSION the first time the file is
		// written, so the number a server owner actually sees is the constant, not the field.
		try {
			Field current = StaffConfig.class.getDeclaredField("CURRENT_VERSION");
			current.setAccessible(true);
			out.put("configVersion", String.valueOf(current.getInt(null)));
		} catch (ReflectiveOperationException e) {
			throw new AssertionError("CURRENT_VERSION moved; this test needs updating", e);
		}

		for (Field field : StaffConfig.class.getDeclaredFields()) {
			if (Modifier.isStatic(field.getModifiers())) continue;
			field.setAccessible(true);
			try {
				Object value = field.get(defaults);
				if (value == null) continue;
				if (value instanceof Number || value instanceof Boolean || value instanceof String) {
					out.putIfAbsent(field.getName(), String.valueOf(value));
				}
			} catch (IllegalAccessException ignored) {
				// A field we cannot read is a field we cannot check; nothing to assert.
			}
		}
		return out;
	}

	/** Compares as values rather than as text: 12.0 and 12 are the same default. */
	private static boolean agrees(String documented, String actual) {
		// The documented form is JSON, so a multi-line default arrives with its escapes
		// intact while the Java field holds the real characters.
		String doc = documented.replaceAll("^\"|\"$", "")
				.replace("\\n", "\n")
				.replace("\\\"", "\"");
		if (doc.equals(actual)) return true;
		try {
			return Double.compare(Double.parseDouble(doc), Double.parseDouble(actual)) == 0;
		} catch (NumberFormatException e) {
			return false;
		}
	}

	@Test
	@DisplayName("every documented config default matches the code")
	void documentedDefaultsMatchTheCode() throws IOException {
		Map<String, String> actual = actualDefaults();
		List<String> wrong = new ArrayList<>();
		int checked = 0;

		for (Path file : List.of(README, HANDBOOK)) {
			for (Documented entry : documentedIn(file)) {
				String real = actual.get(entry.key());
				if (real == null) continue;   // documented example, not a config key
				checked++;
				if (!agrees(entry.value(), real)) {
					wrong.add(file + ":" + entry.line() + "  " + entry.key()
							+ " documented as " + entry.value() + ", code says " + real);
				}
			}
		}

		assertTrue(checked > 20,
				"only matched " + checked + " documented keys — the config blocks moved and this "
						+ "test is no longer reading them, which is worse than a mismatch because "
						+ "it passes");
		assertTrue(wrong.isEmpty(),
				"documentation disagrees with the code:\n  " + String.join("\n  ", wrong));
	}

	@Test
	@DisplayName("no stale x-ray thresholds are quoted in prose")
	void proseDoesNotQuoteOldThresholds() throws IOException {
		// The config blocks are mechanical to check; prose is not, and prose is where the
		// contradiction actually lived — "will not commit to a verdict below 500 mined blocks"
		// outlived the 500 by two changes. This cannot verify prose in general, so it pins the
		// specific numbers that have already drifted once.
		List<String> found = new ArrayList<>();
		StaffConfig defaults = new StaffConfig();

		for (Path file : List.of(README, HANDBOOK)) {
			if (!Files.exists(file)) continue;
			List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);

			for (int i = 0; i < lines.size(); i++) {
				String line = lines.get(i);
				// "below 500 mined blocks", "at least 500 blocks", "sample floor of 500"
				Matcher m = Pattern.compile(
						"(?:below|least|floor of|floor is)\\s+(\\d{2,4})\\s*(?:mined\\s+)?blocks?")
						.matcher(line);
				while (m.find()) {
					int quoted = Integer.parseInt(m.group(1));
					if (quoted != defaults.xrayMinimumVolume) {
						found.add(file + ":" + (i + 1) + "  quotes a minimum volume of " + quoted
								+ ", code says " + defaults.xrayMinimumVolume);
					}
				}
			}
		}
		assertTrue(found.isEmpty(), "stale thresholds in prose:\n  " + String.join("\n  ", found));
	}
}
