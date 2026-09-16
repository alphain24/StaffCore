package io.github.alphain24.staffcore.discord.gateway;

import io.github.alphain24.staffcore.api.DiscordAnswer;
import io.github.alphain24.staffcore.api.DiscordProfile;
import io.github.alphain24.staffcore.api.DiscordPunishment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reading what was clicked, and what the bot answers about a player.
 */
class ClickedAndRepliesTest {

	private static final UUID STEVE = UUID.fromString("11111111-2222-3333-4444-555555555555");

	@Test
	@DisplayName("the bot's own button ids are read back; anything else is ignored rather than guessed at")
	void parsing() {
		assertEquals(12, JdaGateway.Clicked.parse("sc:claim:12").id());
		assertEquals(3, JdaGateway.Clicked.parse("sc:accept:3").id());
		assertEquals(7, JdaGateway.Clicked.parse("sc:evidence:7").id());
		assertEquals("info", JdaGateway.Clicked.parse("sc:info:3").action());
		assertEquals("appealpanel", JdaGateway.Clicked.parse(
				io.github.alphain24.staffcore.discord.channels.AppealPanel.BUTTON_ID).action());
		assertEquals("reject", JdaGateway.Clicked.parse("sc:reject:4").action());
		assertEquals(null, JdaGateway.waitDays("400"), "a wait over a year was accepted");
		assertEquals(null, JdaGateway.waitDays("-1"));
		assertEquals(null, JdaGateway.waitDays("seven"));
		assertEquals(7, JdaGateway.waitDays(" 7 "));
		assertEquals(0, JdaGateway.waitDays("0"));

		// A kept file is posted under its own name, made harmless.
		assertEquals("my_proof__1_.png", JdaGateway.safeName(new io.github.alphain24.staffcore.api.DiscordEvidenceFile(
				"my proof (1).png", "image/png", 1, "a", "b", null)));
		assertEquals("evidence.hidden", JdaGateway.safeName(new io.github.alphain24.staffcore.api.DiscordEvidenceFile(
				".hidden", null, 1, "a", "b", null)));
		assertEquals("evidence", JdaGateway.safeName(new io.github.alphain24.staffcore.api.DiscordEvidenceFile(
				null, null, 1, "a", "b", null)));

		String detail = Replies.evidenceDetail(new io.github.alphain24.staffcore.api.DiscordEvidenceDetail(4, "CASE1234",
				"From Discord", "From Discord: saw it", "Mod", 1L, "https://discord.com/channels/1/2/3", "Steve_",
				2L, "look at **this**", java.util.List.of(
						new io.github.alphain24.staffcore.api.DiscordEvidenceFile("a.png", "image/png", 2048,
								"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", "CASE1234/x.png", null),
						new io.github.alphain24.staffcore.api.DiscordEvidenceFile("big.mp4", "video/mp4", 90_000_000,
								null, null, "larger than the 25 MB this server keeps"))), 1);
		assertTrue(detail.contains("Evidence #4"), detail);
		assertTrue(detail.contains("Steve\\_"), detail);
		assertFalse(detail.contains("**this**"), "the message text was not escaped: " + detail);
		assertTrue(detail.contains("a.png (2 KB)"), detail);
		assertTrue(detail.contains("not kept: larger than"), detail);
		assertTrue(detail.contains("1 file(s) not attached"), detail);
		assertNull(JdaGateway.Clicked.parse("sc:accept:" + STEVE), "an appeal button with a player where its number goes");
		assertEquals(STEVE, JdaGateway.Clicked.parse("sc:freeze:" + STEVE).player());
		assertNull(JdaGateway.Clicked.parse("sc:ban:" + STEVE), "an action no button offers");
		assertNull(JdaGateway.Clicked.parse("sc:claim:twelve"));
		assertNull(JdaGateway.Clicked.parse("sc:freeze:not-a-uuid"));
		assertNull(JdaGateway.Clicked.parse("other:claim:12"));
		assertNull(JdaGateway.Clicked.parse(null));
	}

	@Test
	@DisplayName("a profile answer is conduct only, and a refusal is passed through as it was given")
	void profile() {
		DiscordPunishment mute = new DiscordPunishment(4, "TEMPMUTE", "spam **loud**", "Mod", 1L, 2_000_000_000_000L,
				true, null, null);
		String text = Replies.profile(DiscordAnswer.of(new DiscordProfile(STEVE, "Steve_", true, 3, 2, 1, 0, null,
				mute, List.of("CASE1234"))));
		assertTrue(text.contains("Punishments: 3"), text);
		assertTrue(text.contains("Temporary mute"), text);
		assertFalse(text.contains("**loud**"), "a reason's formatting was not escaped: " + text);
		assertTrue(text.contains("`CASE1234`"), text);

		assertEquals("no", Replies.profile(DiscordAnswer.no("no")));
	}

	@Test
	@DisplayName("a punishment and its case's evidence read in full, escaped")
	void punishmentAndEvidence() {
		String text = Replies.punishment(DiscordAnswer.of(new DiscordPunishment(7, "TEMPBAN", "x-ray _fast_", "Mod", 5L,
				9_000_000_000_000L, true, null, "CASE1234")));
		assertTrue(text.contains("Temporary ban #7"), text);
		assertTrue(text.contains("in force"), text);
		assertFalse(text.contains("_fast_"), text);

		String evidence = Replies.evidence(DiscordAnswer.of(List.of(
				new io.github.alphain24.staffcore.api.DiscordEvidence(1, "Replay", "Replay of Steve, 14:02 for 10 minutes",
						5L, "system"))));
		assertTrue(evidence.contains("Replay of Steve"), evidence);
		assertEquals("The case has no evidence filed.", Replies.evidence(DiscordAnswer.of(List.of())));
		assertEquals("nope", Replies.evidence(DiscordAnswer.no("nope")));
	}

	@Test
	@DisplayName("history lists newest first and marks what was reversed")
	void history() {
		String text = Replies.history(DiscordAnswer.of(List.of(
				new DiscordPunishment(9, "BAN", "grief", "Mod", 5L, null, false, "Admin", null),
				new DiscordPunishment(8, "WARN", "rude", "Mod", 4L, null, false, null, null))));
		assertTrue(text.indexOf("#9") < text.indexOf("#8"), text);
		assertTrue(text.contains("reversed by Admin"), text);
		assertEquals("No punishments on record.", Replies.history(DiscordAnswer.of(List.of())));
	}
}
