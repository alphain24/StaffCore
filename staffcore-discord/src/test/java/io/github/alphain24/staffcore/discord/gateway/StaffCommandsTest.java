package io.github.alphain24.staffcore.discord.gateway;

import io.github.alphain24.staffcore.api.DiscordAnalytics;
import io.github.alphain24.staffcore.api.DiscordAnswer;
import io.github.alphain24.staffcore.api.DiscordCase;
import io.github.alphain24.staffcore.api.DiscordNote;
import io.github.alphain24.staffcore.api.DiscordStaffAction;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code /staff} in Discord: the commands the brief lists, and what they answer.
 */
class StaffCommandsTest {

	@Test
	@DisplayName("/staff has exactly the reads and writes the brief lists, and nothing that reaches IP bans, rollback or inventories")
	void theSubcommands() {
		Set<String> names = StaffCommands.definition().getSubcommands().stream().map(SubcommandData::getName)
				.collect(Collectors.toSet());
		assertEquals(Set.of("history", "staff-history", "notes", "evidence", "case", "profile", "analytics",
				"ban", "unban", "mute", "unmute", "freeze", "unfreeze", "note", "warn"), names);
		for (String name : names) {
			assertFalse(name.contains("ip") || name.contains("rollback") || name.contains("invsee")
					|| name.contains("inventory") || name.contains("approve"), name);
		}
	}

	@Test
	@DisplayName("every player option completes names, and a ban or mute's duration is optional")
	void theOptions() {
		for (SubcommandData sub : StaffCommands.definition().getSubcommands()) {
			for (OptionData option : sub.getOptions()) {
				if (option.getName().equals("player")) {
					assertTrue(option.isAutoComplete(), sub.getName() + " does not complete player names");
					assertTrue(option.isRequired(), sub.getName() + " can be run without a player");
				}
				if (option.getName().equals("duration")) assertFalse(option.isRequired(), sub.getName());
				if (option.getName().equals("case")) {
					assertTrue(option.isAutoComplete(), sub.getName() + " does not complete case ids");
				}
			}
		}
	}

	@Test
	@DisplayName("notes, staff history, a case and analytics read cleanly and escape what people typed")
	void replies() {
		String notes = Replies.notes(DiscordAnswer.of(List.of(
				new DiscordNote(1, "Mod", "saw **them** at spawn", 5L, null, null),
				new DiscordNote(2, "Mod", "wrong player", 4L, null, "Admin"))));
		assertFalse(notes.contains("**them**"), notes);
		assertTrue(notes.contains("retracted by Admin"), notes);

		String history = Replies.staffHistory(DiscordAnswer.of(List.of(
				new DiscordStaffAction(5L, "command", "/staff ban Steve_ x-ray", "CASE1234"))));
		assertTrue(history.contains("/staff ban Steve\\_ x-ray"), history);
		assertTrue(history.contains("`CASE1234`"), history);

		String view = Replies.caseView(DiscordAnswer.of(new DiscordCase("CASE1234", UUID.randomUUID(), "Steve_",
				"investigating", "x-ray", 80, "found diamonds", 1L, "SYSTEM", null, null, null, null, 3, 2, 1,
				List.of(new DiscordCase.Event(2L, "Mod", "note", "looked at the dig")))));
		assertTrue(view.contains("investigating"), view);
		assertTrue(view.contains("assigned to nobody"), view);
		assertTrue(view.contains("3 signal(s) · 2 evidence · 1 linked record(s)"), view);
		assertTrue(view.contains("looked at the dig"), view);

		String stats = Replies.analytics(DiscordAnswer.of(new DiscordAnalytics(10, 2, 1, 5,
				List.of(new DiscordAnalytics.Staff("Mod", 4, 3, 2, 1, 60_000L, 20, 0L)))));
		assertTrue(stats.contains("10 punishment(s)"), stats);
		assertTrue(stats.contains("**Mod** — 4 punishment(s)"), stats);

		assertEquals("no", Replies.caseView(DiscordAnswer.no("no")));
	}
}
