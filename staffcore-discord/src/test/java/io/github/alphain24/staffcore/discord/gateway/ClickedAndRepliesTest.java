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
		assertEquals(12, JdaGateway.Clicked.parse("sc:claim:12").reportId());
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
