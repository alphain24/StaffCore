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
	@DisplayName("the timeout and the queue size are clamped into range")
	void timeoutClamped() throws IOException {
		assertEquals(60, load("{\"requestTimeoutSeconds\": 900}").settings().requestTimeoutSeconds);
		assertEquals(2, load("{\"requestTimeoutSeconds\": 0}").settings().requestTimeoutSeconds);
		assertEquals(10_000, load("{\"outboundQueueSize\": 999999}").settings().outboundQueueSize);
		assertEquals(50, load("{\"outboundQueueSize\": 0}").settings().outboundQueueSize);
		assertTrue(load("{\"outboundQueueSize\": 0}").problems().stream().anyMatch(p -> p.contains("outboundQueueSize")));
		assertEquals(500, new DiscordSettings().outboundQueueSize);
		assertEquals(io.github.alphain24.staffcore.discord.channels.PostQueue.DEFAULT_CAPACITY,
				new DiscordSettings().outboundQueueSize);
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

		var chatOnly = load("{\"staffChatChannelId\": \"345678901234567890\", \"punishmentsChannelId\": \"\", "
				+ "\"reportsChannelId\": \"\", \"alertsChannelId\": \"\", \"appealsChannelId\": \"\", "
				+ "\"staffLogChannelId\": \"\"}");
		assertFalse(chatOnly.settings().postsToChannels(),
				"a staff chat bridge alone must not silence the webhook, which posts other things");
	}

	@Test
	@DisplayName("an appeal intake channel without an appeals channel is reported, since nothing would be taken")
	void intakeNeedsAppeals() throws IOException {
		var loaded = load("{\"appealIntakeChannelId\": \"345678901234567890\", \"appealsChannelId\": \"\"}");
		assertTrue(loaded.problems().stream().anyMatch(p -> p.startsWith("appealIntakeChannelId")), loaded.problems().toString());
		assertTrue(load("{\"appealIntakeChannelId\": \"345678901234567890\", \"appealsChannelId\": \"345678901234567891\"}")
				.problems().isEmpty());
	}

	@Test
	@DisplayName("a new file asks for every channel to be made")
	void channelsAreCreatedByDefault() {
		var loaded = DiscordSettings.load(dir.resolve(DiscordSettings.FILE_NAME), KNOWN);
		DiscordSettings s = loaded.settings();
		for (var channel : List.of(io.github.alphain24.staffcore.discord.channels.Outbound.Channel.PUNISHMENTS,
				io.github.alphain24.staffcore.discord.channels.Outbound.Channel.REPORTS,
				io.github.alphain24.staffcore.discord.channels.Outbound.Channel.ALERTS,
				io.github.alphain24.staffcore.discord.channels.Outbound.Channel.APPEALS,
				io.github.alphain24.staffcore.discord.channels.Outbound.Channel.STAFF_LOG,
				io.github.alphain24.staffcore.discord.channels.Outbound.Channel.STAFF_CHAT,
				io.github.alphain24.staffcore.discord.channels.Outbound.Channel.PUNISH_PANEL)) {
			assertTrue(s.toCreate(channel), channel + " is not made by default");
		}
		// The players' channel is made too, as a public one.
		assertEquals(DiscordSettings.CREATE, s.appealIntakeChannelId);
	}

	@Test
	@DisplayName("\"create\" is accepted in any case, the players' appeal channel included")
	void createValue() throws IOException {
		var loaded = load("{\"reportsChannelId\": \"Create\", \"appealIntakeChannelId\": \"CREATE\"}");
		assertEquals(DiscordSettings.CREATE, loaded.settings().reportsChannelId);
		assertEquals(DiscordSettings.CREATE, loaded.settings().appealIntakeChannelId);
		assertTrue(loaded.problems().isEmpty(), loaded.problems().toString());
	}

	@Test
	@DisplayName("made channels are written into the file in place of \"create\", and nothing else is rewritten")
	void createdIdsAreWrittenBack() throws IOException {
		String original = "{\"enabled\": true, \"guildId\": \"" + GUILD + "\", \"reportsChannelId\": \"create\", "
				+ "\"alertsChannelId\": \"create\", \"roleNodes\": {\"" + ROLE + "\": [\"staff.*\"]}, \"somethingElse\": 5}";
		var loaded = load(original);
		assertTrue(loaded.settings().roleNodes.get(ROLE).isEmpty(), "the wildcard should have been dropped in memory");

		String problem = loaded.settings().recordCreated(java.util.Map.of(
				io.github.alphain24.staffcore.discord.channels.Outbound.Channel.REPORTS, "456789012345678901"));
		assertEquals(null, problem);
		assertEquals("456789012345678901", loaded.settings().reportsChannelId);

		String written = Files.readString(dir.resolve(DiscordSettings.FILE_NAME));
		var json = com.google.gson.JsonParser.parseString(written).getAsJsonObject();
		assertEquals("456789012345678901", json.get("reportsChannelId").getAsString());
		assertEquals("create", json.get("alertsChannelId").getAsString(), "a channel not made yet was changed");
		assertEquals("staff.*", json.getAsJsonObject("roleNodes").getAsJsonArray(ROLE).get(0).getAsString(),
				"writing the ids back deleted the owner's mistake, and the warning about it with it");
		assertEquals(5, json.get("somethingElse").getAsInt(), "a key this build does not know was removed");

		assertEquals(null, loaded.settings().recordIntakeCreated("567890123456789012"));
		written = Files.readString(dir.resolve(DiscordSettings.FILE_NAME));
		json = com.google.gson.JsonParser.parseString(written).getAsJsonObject();
		assertEquals("567890123456789012", json.get("appealIntakeChannelId").getAsString());
		assertEquals("456789012345678901", json.get("reportsChannelId").getAsString(), "the earlier id was lost");

		var reloaded = DiscordSettings.load(dir.resolve(DiscordSettings.FILE_NAME), KNOWN);
		assertEquals("456789012345678901", reloaded.settings().reportsChannelId);
		assertEquals("567890123456789012", reloaded.settings().appealIntakeChannelId);
	}

	@Test
	@DisplayName("alert severity is clamped, and a head address has to be https with {uuid} in it")
	void alertsAndHeads() throws IOException {
		assertEquals(100, load("{\"discordAlertSeverity\": 400}").settings().discordAlertSeverity);
		assertEquals(500, load("{\"evidenceMaxMegabytes\": 9000}").settings().evidenceMaxMegabytes);
		assertEquals(0, load("{\"evidenceMaxMegabytes\": -3}").settings().evidenceMaxMegabytes);
		assertEquals(25, new DiscordSettings().evidenceMaxMegabytes);
		assertEquals("", load("{\"playerHeadUrl\": \"http://example.com/{uuid}\"}").settings().playerHeadUrl);
		assertEquals("", load("{\"playerHeadUrl\": \"https://example.com/head\"}").settings().playerHeadUrl);
		assertEquals("https://example.com/{uuid}.png",
				load("{\"playerHeadUrl\": \"https://example.com/{uuid}.png\"}").settings().playerHeadUrl);
	}

	@Test
	@DisplayName("the contact channel needs somewhere for requests to go, and both are made by default")
	void contactChannels() throws IOException {
		assertEquals(DiscordSettings.CREATE, new DiscordSettings().contactStaffChannelId);
		assertEquals(DiscordSettings.CREATE, new DiscordSettings().helpRequestsChannelId);
		var orphan = load("{\"contactStaffChannelId\": \"create\", \"helpRequestsChannelId\": \"\"}");
		assertTrue(orphan.problems().stream().anyMatch(p -> p.contains("helpRequestsChannelId")), orphan.problems().toString());
		assertEquals("", load("{\"contactStaffChannelId\": \"general\"}").settings().contactStaffChannelId);
	}

	@Test
	@DisplayName("the appeal channel's category is Help unless set, and is kept within what Discord takes")
	void intakeCategory() throws IOException {
		assertEquals("Help", new DiscordSettings().appealIntakeCategory);
		assertEquals("Staff Help", load("{\"appealIntakeCategory\": \"  Staff Help \"}").settings().appealIntakeCategory);
		assertEquals("", load("{\"appealIntakeCategory\": \"\"}").settings().appealIntakeCategory);
		assertEquals("", load("{\"appealIntakeCategory\": null}").settings().appealIntakeCategory);
		assertEquals("a b", load("{\"appealIntakeCategory\": \"a\nb\"}").settings().appealIntakeCategory);
		var long_ = load("{\"appealIntakeCategory\": \"" + "x".repeat(150) + "\"}");
		assertEquals(100, long_.settings().appealIntakeCategory.length());
		assertTrue(long_.problems().stream().anyMatch(p -> p.contains("appealIntakeCategory")));
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
