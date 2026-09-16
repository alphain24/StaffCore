package io.github.alphain24.staffcore.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the staff panel is told about the Discord bot, including when the companion says nothing, says
 * it the old way, or fails while saying it.
 */
class DiscordBotStatusTest {

	@Test
	@DisplayName("with no companion the panel is told it is not installed, and a companion's report replaces that")
	void reporting() {
		try {
			StaffCoreApi.reportDiscordBot(null);
			assertSame(DiscordBotStatus.NOT_INSTALLED, StaffCoreApi.discordBot());

			DiscordBotStatus running = new DiscordBotStatus(DiscordBotStatus.Phase.RUNNING, "connected",
					List.of(), List.of(new DiscordBotStatus.Channel("reports", true, "posting")), 50);
			StaffCoreApi.reportDiscordBot(() -> running);
			assertTrue(StaffCoreApi.discordBot().running());
			assertEquals(50, StaffCoreApi.discordBot().pingMillis());

			StaffCoreApi.reportDiscordBot(() -> null);
			assertEquals(DiscordBotStatus.Phase.FAILED, StaffCoreApi.discordBot().phase());
		} finally {
			StaffCoreApi.reportDiscordBot(null);
		}
	}

	@Test
	@DisplayName("a report that throws is named by its class, never its message")
	void failingReport() {
		try {
			StaffCoreApi.reportDiscordBot(() -> {
				throw new IllegalStateException("Bot abc.def.ghi");
			});
			DiscordBotStatus status = StaffCoreApi.discordBot();
			assertEquals(DiscordBotStatus.Phase.FAILED, status.phase());
			assertTrue(status.summary().contains("IllegalStateException"), status.summary());
			assertFalse(String.join(" ", status.lines()).contains("abc.def.ghi"), status.lines().toString());
		} finally {
			StaffCoreApi.reportDiscordBot(null);
		}
	}

	@Test
	@DisplayName("a companion that only writes status lines is still shown as installed")
	void olderCompanion() {
		StaffCoreApi.reportDiscordBot(null);
		StaffCoreApi.addStatus("Discord", () -> List.of("disabled: built for a different StaffCore API version"));
		try {
			DiscordBotStatus status = StaffCoreApi.discordBot();
			assertEquals(DiscordBotStatus.Phase.FAILED, status.phase());
			assertEquals("disabled: built for a different StaffCore API version", status.summary());
		} finally {
			StaffCoreApi.removeStatus("Discord");
		}
		assertSame(DiscordBotStatus.NOT_INSTALLED, StaffCoreApi.discordBot());
	}

	@Test
	@DisplayName("the status lines are the summary and then every problem, and the lists cannot be changed afterwards")
	void lines() {
		List<String> problems = new java.util.ArrayList<>(List.of("one", "two"));
		DiscordBotStatus status = new DiscordBotStatus(DiscordBotStatus.Phase.FAILED, "summary", problems, null, -1);
		problems.add("three");
		assertEquals(List.of("summary", "one", "two"), status.lines());
		assertEquals(List.of(), status.channels());
		assertEquals(List.of("off"), new DiscordBotStatus(DiscordBotStatus.Phase.OFF, null, null, null, -1).lines());
	}
}
