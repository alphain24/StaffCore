package io.github.alphain24.staffcore.gametest;

import io.github.alphain24.staffcore.StaffCore;
import io.github.alphain24.staffcore.api.DiscordAccess;
import io.github.alphain24.staffcore.api.DiscordUser;
import io.github.alphain24.staffcore.api.StaffCoreApi;
import io.github.alphain24.staffcore.api.StaffCoreEvent;
import io.github.alphain24.staffcore.api.StaffCoreListener;
import io.github.alphain24.staffcore.api.internal.EventBus;
import io.github.alphain24.staffcore.module.Mods;
import io.github.alphain24.staffcore.modules.cases.Case;
import io.github.alphain24.staffcore.modules.cases.CaseCategory;
import io.github.alphain24.staffcore.modules.punish.PunishmentType;
import io.github.alphain24.staffcore.permission.Actor;
import io.github.alphain24.staffcore.permission.Nodes;
import io.github.alphain24.staffcore.permission.PermissionGroups;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Phase 5.3 on a running server: what StaffCore tells the Discord companion, and the actions behind
 * a report post's buttons — each through the same gate and the same services as in game.
 * <p>
 * Events are captured by a listener and filtered by this test's own players, because gametests in
 * a batch run at the same time and every one of them publishes.
 */
public class DiscordChannelTests {

	private static String snowflake() {
		return String.valueOf(100_000_000_000_000_000L + (long) (Math.random() * 800_000_000_000_000_000L));
	}

	/** Links a Discord account to this player through the real path. */
	private static DiscordUser link(GameTestHelper helper, UUID player, String name, String... roleNodes) {
		DiscordUser user = new DiscordUser(snowflake(), "dc_" + name, Set.of(roleNodes));
		String code = Mods.discord().links().issueCode(player, name);
		Harness.check(helper, DiscordAccess.link(user, code).join().done(), "linking failed");
		return user;
	}

	/** Runs the work with a listener attached and returns every event it caused, once delivered. */
	private static List<StaffCoreEvent> capture(Runnable work) {
		List<StaffCoreEvent> seen = new CopyOnWriteArrayList<>();
		StaffCoreListener listener = seen::add;
		StaffCoreApi.addListener(listener);
		try {
			work.run();
			EventBus.drain(5000);
		} finally {
			StaffCoreApi.removeListener(listener);
		}
		return seen;
	}

	private static <T extends StaffCoreEvent> List<T> of(List<StaffCoreEvent> events, Class<T> type) {
		return events.stream().filter(type::isInstance).map(type::cast).toList();
	}

	/** A report against a fresh player, by a fresh reporter, so no cooldown or open report gets in the way. */
	private static long fileReport(GameTestHelper helper, ServerPlayer target) {
		ServerPlayer reporter = Harness.namedPlayer(helper);
		var result = Mods.reports().file(reporter.getUUID(), Harness.name(reporter), target.getUUID(),
				Harness.name(target), "gametest: flying over spawn");
		Harness.checkEquals(helper, io.github.alphain24.staffcore.modules.report.ReportModule.Result.OK, result,
				"filing the report");
		return Mods.reports().queue().stream().filter(r -> r.targetUuid().equals(target.getUUID()))
				.findFirst().orElseThrow().id();
	}

	// ------------------------------------------------------------------ events

	@GameTest
	public void aReportTellsTheCompanionWhichCaseItOpened(GameTestHelper helper) {
		ServerPlayer target = Harness.namedPlayer(helper);
		List<StaffCoreEvent> events = capture(() -> fileReport(helper, target));

		var filed = of(events, StaffCoreEvent.ReportFiled.class).stream()
				.filter(e -> e.targetId().equals(target.getUUID())).findFirst().orElse(null);
		var signal = of(events, StaffCoreEvent.SignalRaised.class).stream()
				.filter(e -> e.subjectId().equals(target.getUUID()) && "REPORT".equals(e.type()))
				.findFirst().orElse(null);
		Harness.check(helper, filed != null && signal != null, "the report or its signal was not published");
		Harness.check(helper, filed.caseId() != null && filed.caseId().equals(signal.caseId()),
				"the report does not name the case its signal landed in: " + filed.caseId() + " / " + signal.caseId());
		Harness.check(helper, events.indexOf(signal) < events.indexOf(filed),
				"the report arrived before its signal, so the companion cannot know the case yet");
		helper.succeed();
	}

	@GameTest
	public void casesTellTheCompanionWhatPeopleWroteInThem(GameTestHelper helper) {
		UUID subject = UUID.randomUUID();
		String[] id = new String[1];
		List<StaffCoreEvent> events = capture(() -> {
			var store = Mods.cases().store();
			id[0] = store.openManually(subject, "subj" + subject.toString().substring(0, 6), "Mod", "gametest",
					40, CaseCategory.OTHER);
			store.note(id[0], "Mod", "looked at the logs");
			store.setStatus(id[0], Case.Status.ACTIONED, "Mod", "done");
		});

		Harness.check(helper, of(events, StaffCoreEvent.CaseOpened.class).stream()
				.anyMatch(e -> e.caseId().equals(id[0]) && e.subjectId().equals(subject)), "no CaseOpened");
		var changes = of(events, StaffCoreEvent.CaseChanged.class).stream()
				.filter(e -> e.caseId().equals(id[0])).toList();
		Harness.check(helper, changes.stream().anyMatch(e -> e.kind().equals("note") && e.body().contains("logs")),
				"the note was not published: " + changes);
		Harness.check(helper, changes.stream().anyMatch(e -> e.kind().equals("actioned") && e.closed()),
				"closing the case was not published as closing: " + changes);
		helper.succeed();
	}

	@GameTest
	public void anAppealIsAnnouncedWithThePunishmentItIsAgainst(GameTestHelper helper) {
		ServerPlayer player = Harness.namedPlayer(helper);
		var server = Harness.server(helper);
		var mute = Mods.punish().apply(server, new NameAndId(player.getUUID(), Harness.name(player)), "Console",
				PunishmentType.MUTE, null, "gametest: spam", null, null, Actor.console());
		Harness.check(helper, mute != null, "the mute was not issued");
		try {
			List<StaffCoreEvent> events = capture(() -> {
				Mods.appeals().file(player.getUUID(), Harness.name(player), "it was my brother");
				var appeal = Mods.appeals().forPlayer(player.getUUID()).get(0);
				Mods.appeals().accept(appeal.id(), "Admin");
			});
			var filed = of(events, StaffCoreEvent.AppealFiled.class).stream()
					.filter(e -> e.playerId().equals(player.getUUID())).findFirst().orElse(null);
			Harness.check(helper, filed != null, "no AppealFiled");
			Harness.checkEquals(helper, mute.id(), filed.punishmentId(), "the punishment appealed");
			Harness.checkEquals(helper, "gametest: spam", filed.punishmentReason(), "the original reason");
			Harness.check(helper, of(events, StaffCoreEvent.AppealDecided.class).stream()
					.anyMatch(e -> e.id() == filed.id() && e.verdict().equals("ACCEPTED")), "no AppealDecided");
		} finally {
			Mods.punish().revoke(server, player.getUUID(), "Console", false, "gametest");
		}
		helper.succeed();
	}

	@GameTest
	public void aStaffActionNamesThePlayerItWasAbout(GameTestHelper helper) {
		ServerPlayer target = Harness.namedPlayer(helper);
		String command = "/staff history " + Harness.name(target) + " gametest";
		List<StaffCoreEvent> events = capture(() ->
				Mods.accountability().audit().record(Actor.console(), null, "Console", command, null));
		var action = of(events, StaffCoreEvent.StaffAction.class).stream()
				.filter(e -> e.action().equals(command)).findFirst().orElse(null);
		Harness.check(helper, action != null, "the action was not published");
		Harness.checkEquals(helper, Harness.name(target), action.target(), "the player it names");
		helper.succeed();
	}

	// ------------------------------------------------------------------ report buttons

	@GameTest
	public void claimAndResolveGoThroughTheGateAndTheQueueRules(GameTestHelper helper) throws Exception {
		ServerPlayer target = Harness.namedPlayer(helper);
		ServerPlayer mod = Harness.namedPlayer(helper);
		ServerPlayer other = Harness.namedPlayer(helper);
		PermissionGroups groups = PermissionGroups.get();
		groups.players.put(mod.getUUID().toString(), "helper");
		groups.players.put(other.getUUID().toString(), "helper");
		try {
			long report = fileReport(helper, target);
			DiscordUser modUser = link(helper, mod.getUUID(), Harness.name(mod), Nodes.REPORT_VIEW);
			DiscordUser otherUser = link(helper, other.getUUID(), Harness.name(other), Nodes.REPORT_VIEW);
			DiscordUser stranger = new DiscordUser(snowflake(), "stranger", Set.of(Nodes.REPORT_VIEW));

			Harness.check(helper, !DiscordAccess.claimReport(stranger, report).join().done(),
					"an unlinked account claimed a report");
			Harness.checkEquals(helper, "OPEN", Mods.reports().byId(report).status(), "after a refused claim");

			Harness.check(helper, DiscordAccess.claimReport(modUser, report).join().done(), "the claim failed");
			Harness.checkEquals(helper, Harness.name(mod), Mods.reports().byId(report).claimedBy(), "claimed by");

			// Clicking a report somebody else holds takes it over, as it does in the queue screen.
			Harness.check(helper, DiscordAccess.claimReport(otherUser, report).join().done(), "the takeover failed");
			Harness.checkEquals(helper, Harness.name(other), Mods.reports().byId(report).claimedBy(), "taken over by");

			Harness.check(helper, DiscordAccess.resolveReport(modUser, report).join().done(), "resolving failed");
			Harness.checkEquals(helper, "RESOLVED", Mods.reports().byId(report).status(), "after resolving");
			Harness.check(helper, !DiscordAccess.resolveReport(modUser, report).join().done(),
					"a resolved report was resolved again");

			try (PreparedStatement ps = StaffCore.storage().conn().prepareStatement(
					"SELECT COUNT(*) FROM command_log WHERE staff_name=? AND command LIKE '[discord] claim %'")) {
				ps.setString(1, Harness.name(mod));
				try (ResultSet rs = ps.executeQuery()) {
					Harness.check(helper, rs.next() && rs.getInt(1) == 1, "the claim from Discord was not audited");
				}
			}
		} finally {
			groups.players.remove(mod.getUUID().toString());
			groups.players.remove(other.getUUID().toString());
		}
		helper.succeed();
	}

	@GameTest
	public void escalatingPutsTheReportIntoAnInvestigation(GameTestHelper helper) {
		ServerPlayer target = Harness.namedPlayer(helper);
		ServerPlayer mod = Harness.namedPlayer(helper);
		PermissionGroups groups = PermissionGroups.get();
		groups.players.put(mod.getUUID().toString(), "helper");
		try {
			long report = fileReport(helper, target);
			DiscordUser needsNode = link(helper, mod.getUUID(), Harness.name(mod), Nodes.REPORT_VIEW);
			Harness.check(helper, !DiscordAccess.escalateReport(needsNode, report).join().done(),
					"escalated without the case node in the role mapping");

			DiscordUser user = new DiscordUser(needsNode.id(), needsNode.name(), Set.of(Nodes.STAFF_GUI));
			var result = DiscordAccess.escalateReport(user, report).join();
			Harness.check(helper, result.done(), "escalating failed: " + result.message());

			var open = Mods.cases().store().openCasesFor(target.getUUID());
			Harness.check(helper, !open.isEmpty(), "no case for the reported player");
			Case investigated = open.get(0);
			Harness.checkEquals(helper, Case.Status.INVESTIGATING, investigated.status(), "the case status");
			Harness.check(helper, Mods.cases().store().linksFor(investigated.id()).stream()
							.anyMatch(l -> l.entityType().equals("report") && l.entityId().equals(String.valueOf(report))),
					"the report was not linked to the case");
		} finally {
			groups.players.remove(mod.getUUID().toString());
		}
		helper.succeed();
	}

	@GameTest
	public void aNoteFromDiscordLandsOnTheRecordAndItsCase(GameTestHelper helper) {
		ServerPlayer target = Harness.namedPlayer(helper);
		ServerPlayer mod = Harness.namedPlayer(helper);
		PermissionGroups groups = PermissionGroups.get();
		groups.players.put(mod.getUUID().toString(), "helper");
		String group = "gametest-notes-" + mod.getUUID().toString().substring(0, 8);
		try {
			fileReport(helper, target);   // opens a case the note should attach to
			DiscordUser user = link(helper, mod.getUUID(), Harness.name(mod), Nodes.NOTES);

			// The starter helper group's staff.notes.* covers the nodes under staff.notes and not
			// staff.notes itself, so a helper cannot write a note with /staff note either — and
			// Discord gives them nothing more.
			Harness.check(helper, !DiscordAccess.addNote(user, target.getUUID(), "x").join().done(),
					"Discord allowed a note the same account could not write in game");

			groups.groups.put(group, new java.util.ArrayList<>(List.of(Nodes.NOTES)));
			groups.players.put(mod.getUUID().toString(), group);
			var result = DiscordAccess.addNote(user, target.getUUID(), "seen§c near the base\nat night").join();
			Harness.check(helper, result.done(), "the note failed: " + result.message());

			var note = Mods.notes().list(target.getUUID()).get(0);
			Harness.checkEquals(helper, "seenc near the base at night", note.text(), "the note as saved");
			Harness.checkEquals(helper, Harness.name(mod), note.author(), "the author");
			Harness.check(helper, note.caseId() != null, "the note was not attached to the open case");
		} finally {
			groups.players.remove(mod.getUUID().toString());
			groups.groups.remove(group);
		}
		helper.succeed();
	}

	@GameTest
	public void freezeFromDiscordHoldsSomebodyWhoIsHere(GameTestHelper helper) {
		ServerPlayer target = Harness.namedPlayer(helper);
		ServerPlayer mod = Harness.namedPlayer(helper);
		PermissionGroups groups = PermissionGroups.get();
		groups.players.put(mod.getUUID().toString(), "helper");
		try {
			DiscordUser user = link(helper, mod.getUUID(), Harness.name(mod), Nodes.FREEZE);
			Harness.check(helper, DiscordAccess.freeze(user, target.getUUID()).join().done(), "the freeze failed");
			Harness.check(helper, Mods.freeze().isFrozen(target), "the player is not frozen");
			Harness.check(helper, !DiscordAccess.freeze(user, target.getUUID()).join().done(),
					"a second freeze was accepted, which would have unfrozen them");
			Harness.check(helper, Mods.freeze().isFrozen(target), "the second click unfroze them");
			Harness.check(helper, !DiscordAccess.freeze(user, UUID.randomUUID()).join().done(),
					"somebody who is not online was frozen");
		} finally {
			if (Mods.freeze().isFrozen(target)) Mods.freeze().toggle(target);
			groups.players.remove(mod.getUUID().toString());
		}
		helper.succeed();
	}

	@GameTest
	public void staffChatFromDiscordIsMarkedAndNotSentBack(GameTestHelper helper) {
		ServerPlayer mod = Harness.namedPlayer(helper);
		PermissionGroups groups = PermissionGroups.get();
		groups.players.put(mod.getUUID().toString(), "helper");
		try {
			DiscordUser noChat = link(helper, mod.getUUID(), Harness.name(mod), Nodes.HISTORY);
			Harness.check(helper, !DiscordAccess.staffChat(noChat, "hello").join().done(),
					"staff chat was sent without staff.chat in the role mapping");

			DiscordUser user = new DiscordUser(noChat.id(), noChat.name(), Set.of(Nodes.CHAT));
			String line = "gametest line " + UUID.randomUUID();
			List<StaffCoreEvent> events = capture(() -> DiscordAccess.staffChat(user, line).join());
			var chat = of(events, StaffCoreEvent.StaffChat.class).stream()
					.filter(e -> e.message().equals(line)).findFirst().orElse(null);
			Harness.check(helper, chat != null, "the line did not reach staff chat");
			Harness.check(helper, chat.fromDiscord(), "the line is not marked as from Discord, so it would echo");
			Harness.checkEquals(helper, Harness.name(mod), chat.senderName(), "sent as the linked account");
		} finally {
			groups.players.remove(mod.getUUID().toString());
		}
		helper.succeed();
	}

	@GameTest
	public void profileAndHistoryAreReadsBehindTheirNodes(GameTestHelper helper) {
		ServerPlayer target = Harness.namedPlayer(helper);
		ServerPlayer mod = Harness.namedPlayer(helper);
		PermissionGroups groups = PermissionGroups.get();
		groups.players.put(mod.getUUID().toString(), "helper");
		var server = Harness.server(helper);
		Mods.punish().apply(server, new NameAndId(target.getUUID(), Harness.name(target)), "Console",
				PunishmentType.WARN, null, "gametest: rude", null, null, Actor.console());
		try {
			DiscordUser historyOnly = link(helper, mod.getUUID(), Harness.name(mod), Nodes.HISTORY);
			Harness.check(helper, !DiscordAccess.profile(historyOnly, target.getUUID()).join().answered(),
					"a profile was read without the profile node");
			var history = DiscordAccess.history(historyOnly, target.getUUID()).join();
			Harness.check(helper, history.answered() && history.value().stream()
					.anyMatch(p -> p.reason().equals("gametest: rude")), "the history did not include the warning");

			DiscordUser both = new DiscordUser(historyOnly.id(), historyOnly.name(), Set.of(Nodes.STAFF_GUI));
			var profile = DiscordAccess.profile(both, target.getUUID()).join();
			Harness.check(helper, profile.answered(), "the profile was refused: " + profile.refusal());
			Harness.check(helper, profile.value().online() && profile.value().punishments() >= 1,
					"the profile is wrong: " + profile.value());
		} finally {
			groups.players.remove(mod.getUUID().toString());
		}
		helper.succeed();
	}
}
