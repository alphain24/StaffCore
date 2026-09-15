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

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The companion reaches StaffCore only through its published API.
 * <p>
 * Everything else in StaffCore — the modules, the storage, permission resolution, the actor type —
 * is where the checks live. A companion that called a module directly would be a second door
 * with none of them, and the whole security model of phase 5 is that there is one door.
 */
class ApiBoundaryTest {

	private static final Pattern REFERENCE =
			Pattern.compile("io\\.github\\.alphain24\\.staffcore\\.([a-zA-Z_][\\w.]*)");

	@Test
	@DisplayName("the companion uses nothing of StaffCore but io.github.alphain24.staffcore.api")
	void onlyThePublishedApi() throws IOException {
		Path sources = Path.of("src/main/java");
		List<String> reaches = new ArrayList<>();
		try (Stream<Path> files = Files.walk(sources)) {
			for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
				String body = Files.readString(file, StandardCharsets.UTF_8);
				Matcher m = REFERENCE.matcher(body);
				while (m.find()) {
					String rest = m.group(1);
					boolean own = rest.startsWith("discord.") || rest.equals("discord");
					boolean api = rest.startsWith("api.") && !rest.startsWith("api.internal");
					if (!own && !api) reaches.add(sources.relativize(file) + ": " + m.group());
				}
			}
		}
		assertTrue(reaches.isEmpty(), "The companion reaches past StaffCore's API:\n  "
				+ String.join("\n  ", reaches));
	}
}
