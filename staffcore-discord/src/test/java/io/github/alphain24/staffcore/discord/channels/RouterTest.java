package io.github.alphain24.staffcore.discord.channels;

import io.github.alphain24.staffcore.api.StaffCoreEvent;
import io.github.alphain24.staffcore.discord.channels.Outbound.Channel;
import io.github.alphain24.staffcore.discord.channels.Outbound.InThread;
import io.github.alphain24.staffcore.discord.channels.Outbound.Send;
import io.github.alphain24.staffcore.discord.channels.Outbound.Update;
import io.github.alphain24.staffcore.discord.config.DiscordSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What each StaffCore event becomes in Discord, decided without Discord.
 */
class RouterTest {

	private static final UUID STEVE = UUID.fromString("11111111-2222-3333-4444-555555555555");
	private static final long NOW = 1_700_000_000_000L;

	private DiscordSettings settings;
	private ThreadBook book;
	private Router router;

	@BeforeEach
	void setUp() {
		settings = new DiscordSettings();
		settings.guildId = "123456789012345678";
		settings.punishmentsChannelId = "200000000000000001";
		settings.reportsChannelId = "200000000000000002";
		settings.alertsChannelId = "200000000000000003";
		settings.appealsChannelId = "200000000000000004";
		settings.staffLogChannelId = "200000000000000005";
		settings.staffChatChannelId = "200000000000000006";
		settings.validate(Set.of());
		book = new ThreadBook(null, () -> NOW);
		router = new Router(settings, book, outbound -> { });
	}

	private static Send send(List<Outbound> out) {
		assertEquals(1, out.stream().filter(o -> o instanceof Send).count(), out.toString());
		return (Send) out.stream().filter(o -> o instanceof Send).findFirst().orElseThrow();
	}

	private static StaffCoreEvent.ReportFiled report(long id, String reason, String caseId) {
		return new StaffCoreEvent.ReportFiled(NOW, id, STEVE, "Steve_", 2, "Alex", reason, caseId);
	}

	// ------------------------------------------------------------------ punishments

	@Test
	@DisplayName("a punishment post carries reason, staff, the player's prior count, duration, expiry, case and id")
	void punishmentFields() {
		var out = router.route(new StaffCoreEvent.PunishmentIssued(NOW, 7, STEVE, "Steve_", 3, "TEMPBAN",
				"x-ray", "Mod_", NOW + 7 * 86_400_000L, null));
		Send post = send(out);
		assertEquals(Channel.PUNISHMENTS, post.channel());
		Embed embed = post.message().embed();
		assertEquals("Temporary ban · Steve_", embed.title());
		assertEquals("x-ray", embed.field("Reason"));
		assertEquals("Mod\\_", embed.field("Staff"));
		assertTrue(embed.field("Player").contains("3 prior"), embed.field("Player"));
		assertEquals("7 days", embed.field("Duration"));
		assertTrue(embed.field("Expires").startsWith("<t:"), embed.field("Expires"));
		assertEquals("None", embed.field("Case"));
		assertEquals("Punishment #7", embed.footer());
		assertTrue(embed.thumbnail().contains(STEVE.toString()));
	}

	@Test
	@DisplayName("a reversal edits the punishment's own post rather than posting again")
	void reversalEdits() {
		router.route(new StaffCoreEvent.PunishmentIssued(NOW, 7, STEVE, "Steve_", 0, "BAN", "grief", "Mod",
				null, null));
		var out = router.route(new StaffCoreEvent.PunishmentReversed(NOW + 1000, 7, STEVE, "Steve_", "BAN",
				"Admin", "appeal upheld", null));
		assertEquals(1, out.size());
		Update update = assertInstanceOf(Update.class, out.get(0));
		assertEquals("punishment:7", update.key());
		assertTrue(update.message().embed().field("Reversed").contains("appeal upheld"));
		assertEquals("Permanent", update.message().embed().field("Duration"));
	}

	@Test
	@DisplayName("a channel left empty posts nothing")
	void emptyChannelIsOff() {
		settings.punishmentsChannelId = "";
		assertTrue(router.route(new StaffCoreEvent.PunishmentIssued(NOW, 1, STEVE, "Steve_", 0, "WARN", "x",
				"Mod", null, null)).isEmpty());
	}

	// ------------------------------------------------------------------ reports

	@Test
	@DisplayName("a report is posted with a thread, the seven buttons, and the case it opened sharing its thread")
	void reportPost() {
		Send post = send(router.route(report(12, "flying", "CASE1234")));
		assertEquals(Channel.REPORTS, post.channel());
		assertEquals("report:12", post.key());
		assertNotNull(post.threadName());
		assertEquals(List.of("case:CASE1234"), post.sharing());

		Embed embed = post.message().embed();
		assertEquals("flying", embed.field("Reason"));
		assertEquals("Alex", embed.field("Reporter"));
		assertEquals("Unclaimed", embed.field("Assignee"));
		assertTrue(embed.field("Created").startsWith("<t:"));

		var rows = post.message().rows();
		assertEquals(List.of("Claim", "Resolve", "Escalate"), rows.get(0).stream().map(Outbound.Button::label).toList());
		assertEquals(List.of("Profile", "History", "Add Note", "Freeze"),
				rows.get(1).stream().map(Outbound.Button::label).toList());
		assertTrue(rows.get(1).get(3).id().endsWith(STEVE.toString()));
	}

	@Test
	@DisplayName("the server name appears on reports only when one is set")
	void serverName() {
		assertEquals(null, send(router.route(report(1, "x", null))).message().embed().field("Server"));
		settings.serverName = "Survival";
		assertEquals("Survival", send(router.route(report(2, "x", null))).message().embed().field("Server"));
	}

	@Test
	@DisplayName("a claim straight after the report edits the report, before Discord has even answered")
	void claimEditsTheReport() {
		router.route(report(12, "flying", null));
		var out = router.route(new StaffCoreEvent.ReportChanged(NOW + 5, 12, "CLAIMED", "Mod"));
		assertEquals(2, out.size(), out.toString());
		Update update = assertInstanceOf(Update.class, out.get(0));
		assertEquals("Claimed", update.message().embed().field("Status"));
		assertEquals("Mod", update.message().embed().field("Assignee"));
		InThread line = assertInstanceOf(InThread.class, out.get(1));
		assertFalse(line.archive());
	}

	@Test
	@DisplayName("resolving turns the report's own buttons off, keeps the player's, and closes the thread")
	void resolveDisablesAndArchives() {
		router.route(report(12, "flying", null));
		var out = router.route(new StaffCoreEvent.ReportChanged(NOW + 5, 12, "RESOLVED", "Mod"));
		Update update = (Update) out.get(0);
		assertTrue(update.message().rows().get(0).stream().allMatch(Outbound.Button::disabled));
		assertTrue(update.message().rows().get(1).stream().noneMatch(Outbound.Button::disabled));
		assertTrue(((InThread) out.get(1)).archive());
	}

	@Test
	@DisplayName("with a reports channel, a report is not posted a second time as an alert")
	void reportNotAlsoAnAlert() {
		assertTrue(router.route(new StaffCoreEvent.SignalRaised(NOW, "REPORT", STEVE, "Steve_", 70,
				"Alex reported: flying", "CASE1234", true)).isEmpty());
		settings.reportsChannelId = "";
		Send alert = send(router.route(new StaffCoreEvent.SignalRaised(NOW, "REPORT", STEVE, "Steve_", 70,
				"Alex reported: flying", "CASE9999", true)));
		assertEquals(Channel.ALERTS, alert.channel());
		assertEquals("case:CASE9999", alert.key());
	}

	@Test
	@DisplayName("a case opened by a report is discussed in the report's thread")
	void caseLivesInReportThread() {
		router.route(report(12, "flying", "CASE1234"));
		var out = router.route(new StaffCoreEvent.CaseChanged(NOW, "CASE1234", "assigned", "system",
				"assigned to Mod", false));
		InThread line = assertInstanceOf(InThread.class, out.get(0));
		assertEquals("case:CASE1234", line.key());
		assertTrue(line.text().startsWith("**StaffCore**"), line.text());
	}

	// ------------------------------------------------------------------ alerts and cases

	@Test
	@DisplayName("alerts: loud enough is posted, quieter is not, and a case always gets its post")
	void alertThreshold() {
		settings.discordAlertSeverity = 80;
		assertTrue(router.route(new StaffCoreEvent.SignalRaised(NOW, "XRAY", STEVE, "Steve_", 60, "dug", null,
				false)).isEmpty(), "below the threshold with no case");

		Send loud = send(router.route(new StaffCoreEvent.SignalRaised(NOW, "XRAY", STEVE, "Steve_", 85, "dug",
				null, false)));
		assertEquals(null, loud.threadName());

		Send opened = send(router.route(new StaffCoreEvent.SignalRaised(NOW, "MASS_GRIEF", STEVE, "Steve_", 72,
				"broke 400 blocks", "CASE5678", true)));
		assertEquals("case:CASE5678", opened.key());
		assertNotNull(opened.threadName(), "a case opened below the threshold still needs a thread");

		var quiet = router.route(new StaffCoreEvent.SignalRaised(NOW, "CONTRABAND", STEVE, "Steve_", 45,
				"bedrock", "CASE5678", false));
		assertEquals(1, quiet.size());
		assertEquals("case:CASE5678", assertInstanceOf(InThread.class, quiet.get(0)).key(),
				"a weak signal about a case with a thread goes into the thread");
	}

	@Test
	@DisplayName("a case opened by hand gets a post and thread; closing it closes the thread")
	void manualCase() {
		Send post = send(router.route(new StaffCoreEvent.CaseOpened(NOW, "CASEAAAA", STEVE, "Steve_",
				"griefing", "Mod", "saw it happen")));
		assertEquals("case:CASEAAAA", post.key());
		var closed = router.route(new StaffCoreEvent.CaseChanged(NOW, "CASEAAAA", "actioned", "Mod",
				"Ban issued — reason: grief", true));
		assertTrue(assertInstanceOf(InThread.class, closed.get(0)).archive());
		assertTrue(router.route(new StaffCoreEvent.CaseChanged(NOW, "NOTHREAD", "note", "Mod", "x", false))
				.isEmpty(), "a case with no thread has nowhere to put a line");
	}

	@Test
	@DisplayName("once a case's thread exists, other posts link to it")
	void caseLinks() {
		book.put("case:CASE1234", new ThreadBook.Entry("200000000000000003", "300", "400000000000000009", NOW, null));
		Send post = send(router.route(new StaffCoreEvent.PunishmentIssued(NOW, 8, STEVE, "Steve_", 0, "BAN",
				"x", "Mod", null, "CASE1234")));
		assertEquals("[`CASE1234`](https://discord.com/channels/123456789012345678/400000000000000009)",
				post.message().embed().field("Case"));
	}

	// ------------------------------------------------------------------ appeals

	private static final String FILER = "987654321098765432";

	private static StaffCoreEvent.AppealFiled appealFiled(long id, String discordId, String linkedTo) {
		return new StaffCoreEvent.AppealFiled(NOW, id, STEVE, "Steve_", 7, "MUTE", "spam", "Mod",
				NOW - 86_400_000L, "I was set up", 2, "DISCORD", discordId, linkedTo, null);
	}

	@Test
	@DisplayName("an appeal is posted with the original punishment, a thread and the eight buttons")
	void appeal() {
		Send post = send(router.route(appealFiled(3, FILER, null)));
		Embed embed = post.message().embed();
		assertEquals("<@" + FILER + "> · not linked", embed.field("Discord"));
		assertTrue(embed.field("Original punishment").contains("Issued by: Mod"));
		assertTrue(embed.field("Original punishment").contains("<t:"), "issued at needs its relative and absolute time");
		assertEquals("2 items", embed.field("Evidence"));
		assertNotNull(post.threadName());

		var rows = post.message().rows();
		assertEquals(List.of("Accept", "Reject", "Request More Info", "Close"),
				rows.get(0).stream().map(Outbound.Button::label).toList());
		assertEquals(List.of("Punishment", "Profile", "Evidence", "Staff Note"),
				rows.get(1).stream().map(Outbound.Button::label).toList());
		assertEquals("sc:punishment:7", rows.get(1).get(0).id());
	}

	@Test
	@DisplayName("staff can see when the account appealing is linked to somebody other than the punished player")
	void appellantMismatch() {
		assertEquals("<@" + FILER + "> · linked to this player",
				send(router.route(appealFiled(4, FILER, "steve_"))).message().embed().field("Discord"));
		assertTrue(send(router.route(appealFiled(5, FILER, "Alex"))).message().embed().field("Discord")
				.contains("linked to **Alex**, not this player"));
	}

	@Test
	@DisplayName("a verdict tells the player first, never naming who decided, then edits the post and closes the thread")
	void appealVerdict() {
		router.route(appealFiled(3, FILER, null));
		var decided = router.route(new StaffCoreEvent.AppealDecided(NOW + 10, 3, "REJECTED", "Admin_Secret", FILER,
				NOW + 7 * 86_400_000L));

		Outbound.Direct dm = assertInstanceOf(Outbound.Direct.class, decided.get(0));
		assertEquals(FILER, dm.userId());
		assertEquals("appeal:3", dm.fallbackKey());
		assertFalse(dm.message().content().contains("Admin"), "the player was told who rejected them");
		assertTrue(dm.message().content().contains("again <t:"), dm.message().content());

		Update update = assertInstanceOf(Update.class, decided.get(1));
		assertTrue(update.message().embed().field("Status").startsWith("Rejected"));
		assertTrue(update.message().rows().get(0).stream().allMatch(Outbound.Button::disabled));
		assertTrue(update.message().rows().get(1).stream().noneMatch(Outbound.Button::disabled));
		assertTrue(assertInstanceOf(InThread.class, decided.get(2)).archive());
	}

	@Test
	@DisplayName("an appeal filed in game has nobody on Discord to tell")
	void gameAppealNoMessage() {
		router.route(new StaffCoreEvent.AppealFiled(NOW, 6, STEVE, "Steve_", 7, "MUTE", "spam", "Mod", NOW, "x", 0,
				"GAME", FILER, "Steve_", null));
		var decided = router.route(new StaffCoreEvent.AppealDecided(NOW, 6, "STALE", "StaffCore", null, null));
		assertTrue(decided.stream().noneMatch(o -> o instanceof Outbound.Direct), decided.toString());
	}

	@Test
	@DisplayName("a question goes to the player and into the thread; their answer only into the thread")
	void appealConversation() {
		router.route(appealFiled(3, FILER, null));
		var asked = router.route(new StaffCoreEvent.AppealConversation(NOW, 3, "Mod", "which account?", false, FILER));
		Outbound.Direct dm = assertInstanceOf(Outbound.Direct.class, asked.get(0));
		assertTrue(dm.message().content().contains("which account?"));
		assertFalse(dm.message().content().contains("Mod"), "the player was told which staff member asked");
		assertTrue(assertInstanceOf(InThread.class, asked.get(1)).text().contains("**Mod** asked"));

		var answered = router.route(new StaffCoreEvent.AppealConversation(NOW, 3, "Steve_", "my main", true, FILER));
		assertEquals(1, answered.size());
		assertTrue(assertInstanceOf(InThread.class, answered.get(0)).text().contains("my main"));
	}

	@Test
	@DisplayName("a Discord id that is not an id is never written into a mention or messaged")
	void appealDiscordIdChecked() {
		Send post = send(router.route(appealFiled(4, "123> @everyone <@1", null)));
		assertEquals("Unknown account", post.message().embed().field("Discord"));
		assertTrue(router.route(new StaffCoreEvent.AppealDecided(NOW, 4, "ACCEPTED", "Mod", "123> @everyone", null))
				.stream().noneMatch(o -> o instanceof Outbound.Direct));
	}

	// ------------------------------------------------------------------ staff

	@Test
	@DisplayName("the staff log says who, what, the player, when and the case")
	void staffLog() {
		Send post = send(router.route(new StaffCoreEvent.StaffAction(NOW, "Mod", "/staff ban Steve_ `x`",
				"Steve_", "CASE1234")));
		Embed embed = post.message().embed();
		assertTrue(embed.description().contains("/staff ban Steve_ 'x'"), embed.description());
		assertEquals("Steve\\_", embed.field("Player"));
		assertTrue(embed.field("When").startsWith("<t:"));
		assertEquals("`CASE1234`", embed.field("Case"));
	}

	@Test
	@DisplayName("staff chat goes out, and a line that came from Discord is not sent back")
	void staffChatNoEcho() {
		Send post = send(router.route(new StaffCoreEvent.StaffChat(NOW, "Mod", "on my way", false)));
		assertEquals("**Mod**: on my way", post.message().content());
		assertTrue(router.route(new StaffCoreEvent.StaffChat(NOW, "Mod", "from discord", true)).isEmpty());
	}

	// ------------------------------------------------------------------ what players type

	@Test
	@DisplayName("a report reason cannot ping everybody, fake a link or break out of formatting")
	void playerTextIsInert() {
		String hostile = "@everyone [free stuff](https://evil.example) **bold** <@&123456789012345678>";
		List<Outbound> out = new ArrayList<>();
		out.addAll(router.route(report(20, hostile, null)));
		out.addAll(router.route(new StaffCoreEvent.StaffChat(NOW, "Mod", hostile, false)));
		out.addAll(router.route(new StaffCoreEvent.AppealFiled(NOW, 5, STEVE, "Steve_", 1, "BAN", hostile, "Mod",
				NOW, hostile, 0, "GAME", null, null, null)));

		for (Outbound outbound : out) {
			Send post = (Send) outbound;
			List<String> texts = new ArrayList<>();
			if (post.message().content() != null) texts.add(post.message().content());
			if (post.message().embed() != null) {
				post.message().embed().fields().forEach(f -> texts.add(f.value()));
			}
			for (String text : texts) {
				assertFalse(text.contains("@everyone"), text);
				assertFalse(text.contains("](https"), text);
				assertFalse(text.contains("<@&"), text);
				assertFalse(text.contains("**bold**"), text);
			}
		}
	}
}
