package io.github.alphain24.staffcore.gametest;

import io.github.alphain24.staffcore.api.DiscordAccess;
import io.github.alphain24.staffcore.api.DiscordUser;
import io.github.alphain24.staffcore.api.StaffCoreApi;
import io.github.alphain24.staffcore.api.StaffCoreEvent;
import io.github.alphain24.staffcore.api.StaffCoreListener;
import io.github.alphain24.staffcore.api.internal.EventBus;
import io.github.alphain24.staffcore.module.Mods;
import io.github.alphain24.staffcore.modules.cases.CaseCategory;
import io.github.alphain24.staffcore.modules.cases.CaseEvidence;
import io.github.alphain24.staffcore.modules.cases.Signal;
import io.github.alphain24.staffcore.permission.Nodes;
import io.github.alphain24.staffcore.permission.PermissionGroups;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The cases channel on a running server: StaffCore says how a case looks every time it changes, and the
 * card's buttons run through the same gate as everything else from Discord.
 */
public class DiscordCaseCardTests {

	private static String snowflake() {
		return String.valueOf(100_000_000_000_000_000L + (long) (Math.random() * 800_000_000_000_000_000L));
	}

	private static DiscordUser link(GameTestHelper helper, UUID player, String name, String... roleNodes) {
		DiscordUser user = new DiscordUser(snowflake(), "dc_" + name, Set.of(roleNodes));
		String code = Mods.discord().links().issueCode(player, name);
		Harness.check(helper, DiscordAccess.link(user, code).join().done(), "linking failed");
		return user;
	}

	private static String group(ServerPlayer player, String... nodes) {
		String name = "gametest-" + player.getUUID().toString().substring(0, 8);
		PermissionGroups.get().groups.put(name, new ArrayList<>(List.of(nodes)));
		PermissionGroups.get().players.put(player.getUUID().toString(), name);
		return name;
	}

	private static void ungroup(ServerPlayer player, String name) {
		PermissionGroups.get().players.remove(player.getUUID().toString());
		PermissionGroups.get().groups.remove(name);
	}

	/** The snapshots sent about one case while the work ran, in order. */
	private static List<StaffCoreEvent.CaseUpdated> updates(String[] caseId, Runnable work) {
		List<StaffCoreEvent.CaseUpdated> seen = new CopyOnWriteArrayList<>();
		StaffCoreListener listener = event -> {
			if (event instanceof StaffCoreEvent.CaseUpdated update) seen.add(update);
		};
		StaffCoreApi.addListener(listener);
		try {
			work.run();
			EventBus.drain(5000);
		} finally {
			StaffCoreApi.removeListener(listener);
		}
		return seen.stream().filter(u -> u.snapshot().id().equals(caseId[0])).toList();
	}

	@GameTest
	public void everyChangeToACaseSendsHowItLooksNow(GameTestHelper helper) {
		ServerPlayer suspect = Harness.namedPlayer(helper);
		UUID id = suspect.getUUID();
		String name = Harness.name(suspect);
		String[] caseId = { null };

		var opened = updates(caseId, () -> caseId[0] = Mods.cases().store().openManually(id, name, "Mod",
				"gametest: fly hacks", 40, CaseCategory.CHEATING));
		Harness.check(helper, opened.size() == 1 && opened.get(0).opened(), "opening the case sent " + opened);
		Harness.checkEquals(helper, name, opened.get(0).snapshot().subjectName(), "who the card is about");
		Harness.checkEquals(helper, "open", opened.get(0).snapshot().status(), "the card's status");

		var noted = updates(caseId, () -> Mods.cases().store().note(caseId[0], "Mod", "watching them"));
		Harness.check(helper, noted.size() == 1 && !noted.get(0).opened(), "a note sent " + noted);
		Harness.check(helper, noted.get(0).snapshot().events().get(0).body().contains("watching them"),
				"the card's latest line is not the note");

		var filed = updates(caseId, () -> Mods.cases().evidence().add(caseId[0], CaseEvidence.Draft.location(id, name,
				"overworld", net.minecraft.core.BlockPos.ZERO, "gametest"), "Mod"));
		Harness.check(helper, filed.size() == 1 && filed.get(0).snapshot().evidence() == 1,
				"filing evidence did not update the card's count: " + filed);

		var signalled = updates(caseId, () -> Mods.cases().store().ingest(Signal.of(Signal.Type.XRAY, id, name, 40,
				"dug straight to diamonds", "gametest")));
		Harness.check(helper, signalled.size() == 1 && signalled.get(0).snapshot().signals() == 1,
				"a signal joining the case did not update the card: " + signalled);

		var closed = updates(caseId, () -> Mods.cases().store().setStatus(caseId[0],
				io.github.alphain24.staffcore.modules.cases.Case.Status.CLEARED, "Mod", "nothing found"));
		Harness.check(helper, !closed.isEmpty() && closed.get(closed.size() - 1).snapshot().closedAt() != null,
				"closing the case did not update the card: " + closed);
		helper.succeed();
	}

	@GameTest
	public void theCardsButtonsGoThroughTheGate(GameTestHelper helper) {
		ServerPlayer staff = Harness.namedPlayer(helper);
		ServerPlayer suspect = Harness.namedPlayer(helper);
		String group = group(staff, Nodes.NOTES_VIEW, Nodes.FREEZE);
		try {
			String caseId = Mods.cases().store().openManually(suspect.getUUID(), Harness.name(suspect), "Mod",
					"gametest: griefing", 40, CaseCategory.GRIEFING);
			DiscordUser user = link(helper, staff.getUUID(), Harness.name(staff), Nodes.STAFF_GUI, Nodes.NOTES_VIEW,
					Nodes.FREEZE);
			String player = suspect.getUUID().toString();

			var refused = DiscordAccess.caseNote(user, caseId, "seen at spawn").join();
			Harness.check(helper, !refused.done() && refused.message().contains(Nodes.STAFF_GUI),
					"a case note was written without the case node in game: " + refused.message());

			PermissionGroups.get().groups.get(group).add(Nodes.STAFF_GUI);
			var noted = DiscordAccess.caseNote(user, caseId, "seen at spawn").join();
			Harness.check(helper, noted.done(), "the case note failed: " + noted.message());
			Harness.check(helper, Mods.cases().store().eventsFor(caseId).stream()
					.anyMatch(e -> "note".equals(e.kind()) && e.body().contains("seen at spawn")), "the note is not on the case");
			Harness.check(helper, !DiscordAccess.caseNote(user, "NOSUCHCASE", "x").join().done(), "a note went on no case");

			Harness.check(helper, DiscordAccess.notes(user, player).join().answered(), "notes by player id were refused");

			Harness.check(helper, DiscordAccess.freeze(user, suspect.getUUID()).join().done(), "the freeze failed");
			var released = DiscordAccess.unfreeze(user, player).join();
			Harness.check(helper, released.done() && !Mods.freeze().isFrozen(suspect),
					"releasing by player id failed: " + released.message());
		} finally {
			ungroup(staff, group);
			if (Mods.freeze().isFrozen(suspect)) Mods.freeze().toggle(suspect);
		}
		helper.succeed();
	}
}
