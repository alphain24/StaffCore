package io.github.alphain24.staffcore.discord;

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
 * The companion's console lines stay ASCII, as StaffCore's do: a Windows console on a legacy code page
 * shows anything else as mojibake. Every literal in a logger call is read, and every literal in the files
 * whose sentences are logged and put in {@code /staff status} through a placeholder.
 */
class ConsoleTextTest {

	private static final Path MAIN = Path.of("src", "main", "java");
	private static final Path PACKAGE = MAIN.resolve(Path.of("io", "github", "alphain24", "staffcore", "discord"));

	private static final List<Path> LOGGED_WHOLE = List.of(
			PACKAGE.resolve("DiscordBot.java"),
			PACKAGE.resolve("config/BotToken.java"),
			PACKAGE.resolve("config/DiscordSettings.java"),
			PACKAGE.resolve("config/LegacyFiles.java"),
			PACKAGE.resolve("gateway/ChannelSetup.java"));

	private static final Pattern LOGGER_CALL = Pattern.compile("LOGGER\\s*\\.\\s*(?:info|warn|error|debug|trace)\\s*\\(");
	private static final Pattern LITERAL = Pattern.compile("\"(?:[^\"\\\\\\n]|\\\\.)*\"");

	private static String callAt(String source, int open) {
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

	private static List<String> nonAscii(String code) {
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

	private static List<String> inLoggerCalls(String source) {
		List<String> out = new ArrayList<>();
		Matcher m = LOGGER_CALL.matcher(source);
		while (m.find()) out.addAll(nonAscii(callAt(source, m.end() - 1)));
		return out;
	}

	@Test
	@DisplayName("every literal in a logger call, and in the files whose sentences are logged, is ASCII")
	void consoleTextIsAscii() throws IOException {
		List<String> offenders = new ArrayList<>();
		try (Stream<Path> files = Files.walk(MAIN)) {
			for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
				String source = Files.readString(file, StandardCharsets.UTF_8);
				List<String> found = LOGGED_WHOLE.contains(file) ? nonAscii(source) : inLoggerCalls(source);
				for (String literal : found) offenders.add(file.getFileName() + ": " + literal);
			}
		}
		assertTrue(offenders.isEmpty(), "console text that is not ASCII:\n  " + String.join("\n  ", offenders)
				+ "\n\nUse a hyphen, a colon or a semicolon, or a \\u escape for a character that is matched "
				+ "rather than printed. What is posted to Discord may say what it likes.");
	}

	@Test
	@DisplayName("the scan would notice a dash, and reads the real files")
	void theScanIsNotVacuous() {
		assertEquals(1, inLoggerCalls("LOGGER.warn(\"[StaffCore Discord] a — b\");").size());
		assertEquals(0, inLoggerCalls("reply(\"posted — fine in Discord\");").size());
		assertTrue(LOGGED_WHOLE.stream().allMatch(Files::isRegularFile), "a listed file has moved: " + LOGGED_WHOLE);
	}
}
