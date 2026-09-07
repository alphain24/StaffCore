package io.github.alphain24.staffcore.diagnostic;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Console output has to be plain ASCII.
 * <p>
 * Chat and menu text can say whatever it likes: it travels as UTF-8 inside Minecraft's own
 * packets and arrives intact. The server console does not. It is whatever code page the host
 * happens to be using, and on Windows that is a legacy one — so an em-dash written as UTF-8
 * arrives as {@code ΓÇö} and the line reads as if something is corrupt.
 * <p>
 * That is a small thing that costs real trust: the first impression of a mod is its boot log,
 * and mojibake there suggests carelessness before anybody has used a single feature.
 * <p>
 * This checks the source rather than the output, because the output only exists on somebody
 * else's machine with somebody else's code page.
 */
class LogEncodingTest {

	/** Where the source lives, relative to the module the tests run from. */
	private static final Path SOURCE = Path.of("src/main/java");

	@Test
	@DisplayName("no logger message contains a character a legacy console would mangle")
	void loggerMessagesAreAscii() throws IOException {
		List<String> offenders = new ArrayList<>();

		try (Stream<Path> files = Files.walk(SOURCE)) {
			for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
				String source = Files.readString(file, StandardCharsets.UTF_8);
				scan(file, source, offenders);
			}
		}

		assertTrue(offenders.isEmpty(),
				"these log messages contain non-ASCII and will arrive as mojibake on a console "
						+ "that is not UTF-8:\n  " + String.join("\n  ", offenders));
	}

	/**
	 * Finds every {@code LOGGER.x(...)} call and checks the literals inside it.
	 * <p>
	 * Balanced-paren scanning rather than a regex, because these calls wrap across lines and a
	 * line-based check would miss every continuation — which is most of them.
	 */
	private static void scan(Path file, String source, List<String> offenders) {
		int from = 0;
		while (true) {
			int at = source.indexOf("LOGGER.", from);
			if (at < 0) return;

			int open = source.indexOf('(', at);
			if (open < 0) return;

			int depth = 0;
			int end = open;
			boolean inString = false;
			boolean escaped = false;

			while (end < source.length()) {
				char c = source.charAt(end);
				if (inString) {
					if (escaped) escaped = false;
					else if (c == '\\') escaped = true;
					else if (c == '"') inString = false;
				} else if (c == '"') {
					inString = true;
				} else if (c == '(') {
					depth++;
				} else if (c == ')' && --depth == 0) {
					break;
				}
				end++;
			}

			String call = source.substring(at, Math.min(end + 1, source.length()));
			for (char c : call.toCharArray()) {
				if (c > 127) {
					int line = (int) source.substring(0, at).chars().filter(x -> x == '\n').count() + 1;
					offenders.add(file.getFileName() + ":" + line + " contains '" + c + "'");
					break;
				}
			}
			from = end + 1;
		}
	}
}
