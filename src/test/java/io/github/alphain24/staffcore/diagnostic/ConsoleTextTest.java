package io.github.alphain24.staffcore.diagnostic;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * StaffCore's console lines stay ASCII, including the ones a clean boot never prints.
 * <p>
 * The boot check in CI reads the real log and fails on a non-ASCII line, because a Windows console on a
 * legacy code page turns an em dash into mojibake. It can only see the lines a healthy boot writes. The
 * anti-xray startup line carried an em dash for months and failed that check on every push; the hook
 * names, which are only printed when a hook breaks, carried them too, and nothing could have noticed.
 * This reads the source instead: every literal inside a logger call, and every literal in the files
 * whose strings are handed to one.
 */
class ConsoleTextTest {

	private static final Path MAIN = Path.of("src", "main", "java");
	private static final Path PACKAGE = MAIN.resolve(Path.of("io", "github", "alphain24", "staffcore"));

	/** Files whose strings are logged through a placeholder, so a scan of the logger calls misses them. */
	private static final List<Path> LOGGED_WHOLE = List.of(
			PACKAGE.resolve("diagnostic/StartupCheck.java"),
			PACKAGE.resolve("diagnostic/SelfTest.java"),
			PACKAGE.resolve("diagnostic/MixinFailureRecorder.java"),
			PACKAGE.resolve("modules/security/AntiXrayCompanion.java"),
			PACKAGE.resolve("config/ConfigFolder.java"));

	private static final Pattern LOGGER_CALL = Pattern.compile("LOGGER\\s*\\.\\s*(?:info|warn|error|debug|trace)\\s*\\(");
	private static final Pattern LITERAL = Pattern.compile("\"(?:[^\"\\\\\\n]|\\\\.)*\"");

	/** The text of the call starting at the parenthesis, quotes respected. */
	static String callAt(String source, int open) {
		int depth = 0;
		boolean inString = false;
		boolean escaped = false;
		for (int i = open; i < source.length(); i++) {
			char c = source.charAt(i);
			if (inString) {
				if (escaped) escaped = false;
				else if (c == '\\') escaped = true;
				else if (c == '"') inString = false;
			} else if (c == '"') {
				inString = true;
			} else if (c == '(') {
				depth++;
			} else if (c == ')' && --depth == 0) {
				return source.substring(open, i + 1);
			}
		}
		return source.substring(open);
	}

	/** Literals holding anything outside printable ASCII, skipping comment lines. */
	static List<String> nonAscii(String code) {
		List<String> out = new ArrayList<>();
		for (String line : code.split("\n")) {
			String trimmed = line.strip();
			if (trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*")) continue;
			Matcher m = LITERAL.matcher(line);
			while (m.find()) {
				if (m.group().chars().anyMatch(c -> c > 126 || (c < 32 && c != '\t'))) out.add(m.group());
			}
		}
		return out;
	}

	static List<String> inLoggerCalls(String source) {
		List<String> out = new ArrayList<>();
		Matcher m = LOGGER_CALL.matcher(source);
		while (m.find()) out.addAll(nonAscii(callAt(source, m.end() - 1)));
		return out;
	}

	@Test
	@DisplayName("every literal in a logger call, and in the files whose strings are logged, is ASCII")
	void consoleTextIsAscii() throws IOException {
		List<String> offenders = new ArrayList<>();
		try (Stream<Path> files = Files.walk(MAIN)) {
			for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
				String source = Files.readString(file, StandardCharsets.UTF_8);
				List<String> found = LOGGED_WHOLE.contains(file) ? nonAscii(source) : inLoggerCalls(source);
				for (String literal : found) offenders.add(file.getFileName() + ": " + literal);
			}
		}
		assertTrue(offenders.isEmpty(), "console text that is not ASCII — it arrives as mojibake on a Windows "
				+ "console, and the CI boot check fails on it:\n  " + String.join("\n  ", offenders)
				+ "\n\nUse a hyphen, a colon or a semicolon. Chat and menus may say what they like; they travel "
				+ "as UTF-8 inside Minecraft's own packets.");
	}

	@Test
	@DisplayName("the scan finds a dash in a logger call and in a logged file, and nothing in a chat line")
	void theScanIsNotVacuous() throws IOException {
		assertEquals(1, inLoggerCalls("LOGGER.info(\"[StaffCore] a — b\", x);").size());
		assertEquals(1, inLoggerCalls("StaffCore.LOGGER.warn(\"one \"\n + \"two …\");").size());
		assertEquals(0, inLoggerCalls("player.sendSystemMessage(Theme.info(\"ready — go\"));").size());
		assertEquals(0, nonAscii("\t * a comment — with \"a dash\"").size());
		assertEquals(1, nonAscii("\t\treturn \"logged — whole\";").size());
		assertTrue(LOGGED_WHOLE.stream().allMatch(Files::isRegularFile), "a listed file has moved: " + LOGGED_WHOLE);

		// And it reads the real tree: there are logger calls to scan.
		long calls;
		try (Stream<Path> files = Files.walk(MAIN)) {
			calls = files.filter(p -> p.toString().endsWith(".java")).mapToLong(p -> {
				try {
					return LOGGER_CALL.matcher(Files.readString(p, StandardCharsets.UTF_8)).results().count();
				} catch (IOException e) {
					return 0;
				}
			}).sum();
		}
		assertTrue(calls > 100, "only " + calls + " logger calls found; the scan is looking in the wrong place");
	}
}
