package io.github.alphain24.staffcore.gametest;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import io.github.alphain24.staffcore.module.Mods;
import io.github.alphain24.staffcore.modules.cases.Case;
import io.github.alphain24.staffcore.modules.cases.CaseCategory;
import io.github.alphain24.staffcore.modules.punish.PunishmentType;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.NameAndId;

import java.util.UUID;

/**
 * The command tree as registered on a running server, and the case commands that read from it:
 * one command per job, case ids that complete, and a case history that always says why.
 */
public class CommandTreeTests {

	private static CommandDispatcher<CommandSourceStack> dispatcher(GameTestHelper helper) {
		return Harness.server(helper).getCommands().getDispatcher();
	}

	private static CommandNode<CommandSourceStack> node(GameTestHelper helper, String... path) {
		CommandNode<CommandSourceStack> at = dispatcher(helper).getRoot();
		for (String step : path) {
			if (at == null) return null;
			at = at.getChild(step);
		}
		return at;
	}

	private static NameAndId stranger() {
		return new NameAndId(UUID.randomUUID(),
				("t" + UUID.randomUUID().toString().replace("-", "")).substring(0, 12));
	}

	@GameTest
	public void everyJobHasOneCommand(GameTestHelper helper) {
		String[][] gone = {
				{"staff", "panel"}, {"staff", "lookup"}, {"staff", "say"},
				{"staff", "rollback", "undo"}, {"staff", "owed", "undo"}, {"staff", "xray", "exit"},
		};
		for (String[] path : gone) {
			Harness.check(helper, node(helper, path) == null,
					"/" + String.join(" ", path) + " is still registered, and it does the same "
							+ "as another command");
		}
		Harness.check(helper, node(helper, "staff", "case", "id", "claim") == null,
				"/staff case <id> claim is still registered beside assign");
		Harness.check(helper, node(helper, "staff", "notes", "target", "add") == null,
				"/staff notes <player> add is still registered beside /staff note");

		String[][] kept = {
				{"staff", "undo"}, {"staff", "note"}, {"staff", "replay", "exit"},
				{"staff", "rollback", "list"}, {"sc"},
		};
		for (String[] path : kept) {
			Harness.check(helper, node(helper, path) != null,
					"/" + String.join(" ", path) + " is missing, and it is the one left for that job");
		}
		helper.succeed();
	}

	@GameTest
	public void caseIdsCompleteAsYouType(GameTestHelper helper) {
		MinecraftServer server = Harness.server(helper);
		NameAndId subject = stranger();
		String id = Mods.cases().store().openManually(subject.id(), subject.name(), "gametest",
				"completion", 10, CaseCategory.OTHER);
		Harness.check(helper, id != null, "the case was not opened");

		String typed = "staff case " + id.substring(0, 4).toLowerCase(java.util.Locale.ROOT);
		var parse = dispatcher(helper).parse(typed, server.createCommandSourceStack());
		var suggestions = dispatcher(helper).getCompletionSuggestions(parse).join();

		Harness.check(helper, suggestions.getList().stream().anyMatch(s -> s.getText().equals(id)),
				"typing the start of " + id + " did not suggest it: " + suggestions.getList());
		helper.succeed();
	}

	@GameTest
	public void closingACaseWithThePunishmentPutsItsReasonInTheHistory(GameTestHelper helper) {
		MinecraftServer server = Harness.server(helper);
		NameAndId subject = stranger();
		String id = Mods.cases().store().openManually(subject.id(), subject.name(), "gametest",
				"x-ray", 60, CaseCategory.OTHER);

		var ban = Mods.punish().apply(server, subject, stranger().name(), PunishmentType.BAN, null,
				"x-ray, found with decoys", null, null,
				io.github.alphain24.staffcore.permission.Actor.console());
		Harness.check(helper, ban != null, "the ban was not issued");

		// Issued from the player's file, not the case: the case is still told, with the reason.
		var events = Mods.cases().store().eventsFor(id);
		Harness.check(helper, events.stream().anyMatch(e -> e.body() != null
						&& e.body().contains("x-ray, found with decoys")),
				"a ban issued while the case was open is not in its history with its reason: "
						+ events.stream().map(e -> e.kind() + ": " + e.body()).toList());

		server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
				"staff case " + id + " actioned");

		Case closed = Mods.cases().store().byId(id).orElseThrow();
		Harness.checkEquals(helper, Case.Status.ACTIONED, closed.status(),
				"/staff case <id> actioned did not close it with the punishment");
		var after = Mods.cases().store().eventsFor(id);
		Harness.check(helper, after.stream().anyMatch(e -> "actioned".equals(e.kind())
						&& e.body() != null && e.body().contains("Ban")
						&& e.body().contains("x-ray, found with decoys")),
				"the close does not say what they got and why: "
						+ after.stream().map(e -> e.kind() + ": " + e.body()).toList());
		Harness.check(helper, Mods.cases().store().linksFor(id).stream()
						.anyMatch(l -> "punishment".equals(l.entityType())
								&& String.valueOf(ban.id()).equals(l.entityId())),
				"closing with the punishment did not link it to the case");
		helper.succeed();
	}
}
