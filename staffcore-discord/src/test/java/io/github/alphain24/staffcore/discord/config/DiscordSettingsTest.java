package io.github.alphain24.staffcore.discord.config;

import io.github.alphain24.staffcore.discord.gateway.RoleMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The settings file: checked key by key, and a role mapping that can only name real permissions.
 */
class DiscordSettingsTest {

	private static final Set<String> KNOWN = Set.of("staff.history", "staff.punish.ban", "staff.punish.mute");
	private static final String GUILD = "123456789012345678";
	private static final String ROLE = "234567890123456789";

	@TempDir
	Path dir;

	private DiscordSettings.Loaded load(String json) throws IOException {
		Path file = dir.resolve(DiscordSettings.FILE_NAME);
		Files.writeString(file, json, StandardCharsets.UTF_8);
		return DiscordSettings.load(file, KNOWN);
	}

	@Test
	@DisplayName("first start writes a file with the bot off")
	void defaults() {
		Path file = dir.resolve(DiscordSettings.FILE_NAME);
		var loaded = DiscordSettings.load(file, KNOWN);
		assertTrue(Files.isRegularFile(file));
		assertFalse(loaded.settings().enabled, "installing the jar must not start a bot");
		assertTrue(loaded.problems().isEmpty(), loaded.problems().toString());
	}

	@Test
	@DisplayName("a wildcard is refused, so a node added to StaffCore later is not granted by accident")
	void noWildcards() throws IOException {
		var loaded = load("{\"enabled\": true, \"guildId\": \"" + GUILD + "\", \"roleNodes\": {\"" + ROLE
				+ "\": [\"*\", \"staff.*\", \"staff.history\"]}}");
		assertEquals(List.of("staff.history"), loaded.settings().roleNodes.get(ROLE));
		assertEquals(2, loaded.problems().stream().filter(p -> p.contains("Wildcards")).count());
	}

	@Test
	@DisplayName("a node StaffCore does not define is refused and named")
	void onlyKnownNodes() throws IOException {
		var loaded = load("{\"roleNodes\": {\"" + ROLE + "\": [\"staff.punish.bna\", \"staff.punish.ban\"]}}");
		assertEquals(List.of("staff.punish.ban"), loaded.settings().roleNodes.get(ROLE));
		assertTrue(loaded.problems().stream().anyMatch(p -> p.contains("staff.punish.bna")));
	}

	@Test
	@DisplayName("a role mapped by name rather than id is refused, since names can be copied")
	void rolesByIdOnly() throws IOException {
		var loaded = load("{\"roleNodes\": {\"Admin\": [\"staff.punish.ban\"]}}");
		assertTrue(loaded.settings().roleNodes.isEmpty());
		assertTrue(loaded.problems().stream().anyMatch(p -> p.contains("\"Admin\"")));
	}

	@Test
	@DisplayName("switching the bot on without a real guild id leaves it off, and says why")
	void guildRequired() throws IOException {
		var loaded = load("{\"enabled\": true, \"guildId\": \"my server\"}");
		assertFalse(loaded.settings().enabled);
		assertTrue(loaded.problems().stream().anyMatch(p -> p.startsWith("guildId")));
	}

	@Test
	@DisplayName("the timeout is clamped into range")
	void timeoutClamped() throws IOException {
		assertEquals(60, load("{\"requestTimeoutSeconds\": 900}").settings().requestTimeoutSeconds);
		assertEquals(2, load("{\"requestTimeoutSeconds\": 0}").settings().requestTimeoutSeconds);
	}

	@Test
	@DisplayName("a file with a typo is left alone rather than replaced with defaults")
	void brokenFileKept() throws IOException {
		String broken = "{\"enabled\": true, \"guildId\": \"" + GUILD + "\",, }";
		var loaded = load(broken);
		assertFalse(loaded.settings().enabled);
		assertEquals(broken, Files.readString(dir.resolve(DiscordSettings.FILE_NAME)));
	}

	@Test
	@DisplayName("channels are ids or empty, and anything else posts nothing")
	void channels() throws IOException {
		var loaded = load("{\"reportsChannelId\": \"#reports\", \"alertsChannelId\": \" 345678901234567890 \"}");
		assertEquals("", loaded.settings().reportsChannelId);
		assertEquals("345678901234567890", loaded.settings().alertsChannelId);
		assertTrue(loaded.problems().stream().anyMatch(p -> p.startsWith("reportsChannelId")));
		assertTrue(loaded.settings().postsToChannels());

		var chatOnly = load("{\"staffChatChannelId\": \"345678901234567890\"}");
		assertFalse(chatOnly.settings().postsToChannels(),
				"a staff chat bridge alone must not silence the webhook, which posts other things");
	}

	@Test
	@DisplayName("alert severity is clamped, and a head address has to be https with {uuid} in it")
	void alertsAndHeads() throws IOException {
		assertEquals(100, load("{\"discordAlertSeverity\": 400}").settings().discordAlertSeverity);
		assertEquals("", load("{\"playerHeadUrl\": \"http://example.com/{uuid}\"}").settings().playerHeadUrl);
		assertEquals("", load("{\"playerHeadUrl\": \"https://example.com/head\"}").settings().playerHeadUrl);
		assertEquals("https://example.com/{uuid}.png",
				load("{\"playerHeadUrl\": \"https://example.com/{uuid}.png\"}").settings().playerHeadUrl);
	}

	@Test
	@DisplayName("roles add up, and a role nobody mapped adds nothing")
	void roleMap() {
		RoleMap map = new RoleMap(Map.of("1", List.of("a"), "2", List.of("b", "a")));
		assertEquals(Set.of("a", "b"), map.nodesFor(List.of("1", "2", "3")));
		assertTrue(map.nodesFor(List.of("3")).isEmpty());
		assertTrue(map.nodesFor(null).isEmpty());
	}
}
