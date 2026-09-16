package io.github.alphain24.staffcore.discord.channels;

import io.github.alphain24.staffcore.api.DiscordLadder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PunishPanelTest {

	@Test
	@DisplayName("the panel's ids share one prefix, and the ladder and confirmation say what will happen")
	void panel() {
		Outbound.Message message = PunishPanel.message();
		assertEquals(PunishPanel.OPEN, message.rows().get(0).get(0).id());
		for (String id : List.of(PunishPanel.OPEN, PunishPanel.PLAYER_FORM, PunishPanel.PICK, PunishPanel.CONFIRM,
				PunishPanel.CANCEL)) {
			assertTrue(id.startsWith(PunishPanel.PREFIX), id);
		}
		assertTrue(message.embed().description().contains("discord.punishpanel"));

		DiscordLadder.Rung grief = new DiscordLadder.Rung("griefing", "Griefing", "Breaking builds", 1, "Ban · 3d",
				"BAN", "Ban · 14d", true);
		DiscordLadder.Rung cheat = new DiscordLadder.Rung("cheating", "Hacking", "Clients", 0, "Ban · permanent",
				"BAN", null, false);
		DiscordLadder ladder = new DiscordLadder(UUID.randomUUID(), "Steve_", 3, "Ban, 2d left: grief", null,
				List.of(grief, cheat));
		Embed shown = PunishPanel.ladder(ladder);
		assertTrue(shown.field("Offences").contains("Griefing"));
		assertTrue(shown.field("Offences").contains("not yours to give"));
		assertTrue(shown.field("Banned now").contains("2d left"));

		Embed confirm = PunishPanel.confirmation("Steve_", grief);
		assertTrue(confirm.title().contains("Ban · 3d"));
		assertEquals("Ban · 14d", confirm.field("Next time"));
		assertEquals("Top of the ladder", PunishPanel.confirmation("Steve_", cheat).field("Next time"));
		assertEquals("griefing|1", PunishPanel.optionValue(grief));
		assertFalse(PunishPanel.optionValue(grief).contains(":"), "a ':' would break the id parsing");
	}
}
