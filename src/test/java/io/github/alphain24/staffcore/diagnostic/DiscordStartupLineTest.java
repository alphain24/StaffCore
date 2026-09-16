package io.github.alphain24.staffcore.diagnostic;

import io.github.alphain24.staffcore.api.DiscordBotStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 5.6: the startup diagnostic says where the Discord bot stands, in one ASCII line.
 */
class DiscordStartupLineTest {

	private static DiscordBotStatus status(DiscordBotStatus.Phase phase, String summary, String... problems) {
		return new DiscordBotStatus(phase, summary, List.of(problems), List.of(), -1);
	}

	@Test
	@DisplayName("each state reads as one line, and problems are counted, not repeated")
	void lines() {
		assertEquals("Discord bot: not installed.", StartupCheck.discordLine(DiscordBotStatus.NOT_INSTALLED));
		assertEquals("Discord bot: connecting - starting.",
				StartupCheck.discordLine(status(DiscordBotStatus.Phase.CONNECTING, "starting")));
		assertEquals("Discord bot: off (enabled is false in config/staffcore/discord.json).",
				StartupCheck.discordLine(status(DiscordBotStatus.Phase.OFF,
						"off (enabled is false in config/staffcore/discord.json)")));
		assertEquals("Discord bot: not configured - off: the token file is empty. 2 problem(s); "
						+ "/staff status lists them.",
				StartupCheck.discordLine(status(DiscordBotStatus.Phase.NOT_CONFIGURED,
						"off: the token file is empty", "one", "two")));
		assertEquals("Discord bot: failed.", StartupCheck.discordLine(status(DiscordBotStatus.Phase.FAILED, "")));
	}

	@Test
	@DisplayName("whatever the companion says, the console gets ASCII")
	void ascii() {
		String line = StartupCheck.discordLine(status(DiscordBotStatus.Phase.RUNNING,
				"connected as Bot \u2014 in \u00C9cole \uD83D\uDE00"));
		assertTrue(line.chars().allMatch(c -> c >= 32 && c <= 126), line);
	}
}
