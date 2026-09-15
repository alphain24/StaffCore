package io.github.alphain24.staffcore.gametest;

import io.github.alphain24.staffcore.StaffCore;
import io.github.alphain24.staffcore.api.DiscordAccess;
import io.github.alphain24.staffcore.api.DiscordUser;
import io.github.alphain24.staffcore.api.StaffCoreApi;
import io.github.alphain24.staffcore.api.StaffCoreEvent;
import io.github.alphain24.staffcore.api.StaffCoreListener;
import io.github.alphain24.staffcore.api.internal.EventBus;
import io.github.alphain24.staffcore.module.Mods;
import io.github.alphain24.staffcore.modules.appeal.AppealModule;
import io.github.alphain24.staffcore.modules.cases.CaseCategory;
import io.github.alphain24.staffcore.modules.punish.Punishment;
import io.github.alphain24.staffcore.modules.punish.PunishmentType;
import io.github.alphain24.staffcore.permission.Actor;
import io.github.alphain24.staffcore.permission.Nodes;
import io.github.alphain24.staffcore.permission.PermissionGroups;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Phase 5.4 on a running server: appeals filed from Discord with the code off a ban screen, and
 * decided through the same path as the appeals screen.
 * <p>
 * Punished players here are offline accounts made up for each test. A ban enforced on a connected
 * player disconnects them, and nothing about an appeal needs the player to be online.
 */
public class DiscordAppealTests {

	private static String snowflake() {
		return String.valueOf(100_000_000_000_000_000L + (long) (Math.random() * 800_000_000_000_000_000L));
	}

	private static DiscordUser stranger(String name) {
		return new DiscordUser(snowflake(), name, Set.of());
	}

	private static NameAndId offlinePlayer() {
		UUID id = UUID.randomUUID();
		return new NameAndId(id, "ap" + id.toString().substring(0, 8));
	}

	private static Punishment punish(GameTestHelper helper, NameAndId player, PunishmentType type, String caseId) {
		Punishment p = Mods.punish().apply(Harness.server(helper), player, "Console", type, null,
				"gametest: " + type.name().toLowerCase(java.util.Locale.ROOT), null, caseId, Actor.console());
		Harness.check(helper, p != null && p.isAppealable(), "the " + type + " was not issued with an appeal code");
		return p;
	}

	/** Linked staff with the appeals node in game, and the given nodes mapped from Discord. */
	private static DiscordUser staff(GameTestHelper helper, ServerPlayer player, String... roleNodes) {
		DiscordUser user = new DiscordUser(snowflake(), "dc_" + Harness.name(player), Set.of(roleNodes));
		String code = Mods.discord().links().issueCode(player.getUUID(), Harness.name(player));
		Harness.check(helper, DiscordAccess.link(user, code).join().done(), "linking failed");
		return user;
	}

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

	private static AppealModule.Appeal openAppeal(GameTestHelper helper, Punishment against) {
		AppealModule.Appeal appeal = Mods.appeals().openFor(against.id());
		Harness.check(helper, appeal != null, "no open appeal against punishment #" + against.id());
		return appeal;
	}

	// ------------------------------------------------------------------ filing

	@GameTest
	public void anUnlinkedAccountCanAppealWithTheCodeAndOnlyOnce(GameTestHelper helper) {
		NameAndId player = offlinePlayer();
		Punishment ban = punish(helper, player, PunishmentType.BAN, null);
		DiscordUser filer = stranger("appellant");

		List<StaffCoreEvent> events = capture(() -> {
			var result = DiscordAccess.fileAppeal(filer, ban.appealCode().toLowerCase(java.util.Locale.ROOT),
					"it was my brother on my account").join();
			Harness.check(helper, result.done(), "a correct code was refused: " + result.message());
		});

		AppealModule.Appeal appeal = openAppeal(helper, ban);
		Harness.checkEquals(helper, "DISCORD", appeal.source(), "where it came from");
		Harness.checkEquals(helper, filer.id(), appeal.discordId(), "who filed it");

		var filed = events.stream().filter(e -> e instanceof StaffCoreEvent.AppealFiled f && f.id() == appeal.id())
				.map(e -> (StaffCoreEvent.AppealFiled) e).findFirst().orElse(null);
		Harness.check(helper, filed != null, "no AppealFiled");
		Harness.checkEquals(helper, filer.id(), filed.discordId(), "the filer in the event");
		Harness.checkEquals(helper, null, filed.discordLinkedTo(), "an unlinked filer is shown as unlinked");

		Harness.check(helper, !DiscordAccess.fileAppeal(filer, ban.appealCode(), "again").join().done(),
				"a second appeal was filed against the same punishment while the first is open");
		Harness.check(helper, !DiscordAccess.fileAppeal(filer, "AAAA-BBBB-CCCC", "guess").join().done(),
				"a wrong code filed an appeal");
		helper.succeed();
	}

	@GameTest
	public void wrongCodesCountAgainstTheAccountThatTriedThem(GameTestHelper helper) {
		NameAndId player = offlinePlayer();
		Punishment ban = punish(helper, player, PunishmentType.BAN, null);
		DiscordUser guesser = stranger("guesser");

		for (int i = 0; i < io.github.alphain24.staffcore.config.StaffConfig.get().appealAttemptsPerHour; i++) {
			DiscordAccess.fileAppeal(guesser, "ZZZZ-ZZZZ-ZZZ" + i, "guess").join();
		}
		var blocked = DiscordAccess.fileAppeal(guesser, ban.appealCode(), "now the real one").join();
		Harness.check(helper, !blocked.done() && blocked.message().contains("too many"),
				"guessing was not capped: " + blocked.message());
		Harness.check(helper, Mods.appeals().openFor(ban.id()) == null, "the capped attempt still filed");

		Harness.check(helper, DiscordAccess.fileAppeal(stranger("owner"), ban.appealCode(), "mine").join().done(),
				"another account was locked out by somebody else's guesses");
		helper.succeed();
	}

	// ------------------------------------------------------------------ deciding

	@GameTest
	public void acceptingLiftsTheAppealedPunishmentAndNothingElse(GameTestHelper helper) {
		NameAndId player = offlinePlayer();
		String caseId = Mods.cases().store().openManually(player.id(), player.name(), "Mod", "gametest", 40,
				CaseCategory.OTHER);
		Punishment mute = punish(helper, player, PunishmentType.MUTE, caseId);
		Punishment ban = punish(helper, player, PunishmentType.BAN, null);

		ServerPlayer mod = Harness.namedPlayer(helper);
		PermissionGroups groups = PermissionGroups.get();
		groups.players.put(mod.getUUID().toString(), "admin");
		try {
			Harness.check(helper, DiscordAccess.fileAppeal(stranger("p"), mute.appealCode(), "sorry").join().done(),
					"filing failed");
			AppealModule.Appeal appeal = openAppeal(helper, mute);

			DiscordUser noRole = staff(helper, mod, Nodes.HISTORY);
			Harness.check(helper, !DiscordAccess.acceptAppeal(noRole, appeal.id()).join().done(),
					"an appeal was accepted without the appeals node in the role mapping");
			Harness.check(helper, Mods.appeals().byId(appeal.id()).isOpen(), "the refused accept closed the appeal");

			DiscordUser user = new DiscordUser(noRole.id(), noRole.name(), Set.of(Nodes.APPEALS));
			List<StaffCoreEvent> events = capture(() -> {
				var result = DiscordAccess.acceptAppeal(user, appeal.id()).join();
				Harness.check(helper, result.done(), "accepting failed: " + result.message());
			});

			Punishment lifted = Mods.punish().byId(mute.id());
			Harness.check(helper, !lifted.active(), "the appealed mute is still in force");
			Harness.check(helper, lifted.revokeReason() != null && lifted.revokeReason().contains("appeal #" + appeal.id()),
					"the reversal does not say it came from the appeal: " + lifted.revokeReason());
			Harness.check(helper, Mods.punish().byId(ban.id()).active(),
					"accepting a mute appeal lifted an unrelated ban");

			Harness.check(helper, Mods.cases().store().eventsFor(caseId).stream()
							.anyMatch(e -> e.body() != null && e.body().contains("appeal #" + appeal.id() + " accepted")),
					"the case has no record of the appeal being accepted");
			Harness.check(helper, events.stream().anyMatch(e -> e instanceof StaffCoreEvent.AppealDecided d
					&& d.id() == appeal.id() && d.verdict().equals("ACCEPTED")), "no AppealDecided");
		} finally {
			groups.players.remove(mod.getUUID().toString());
			Mods.punish().revoke(Harness.server(helper), player.id(), "Console", true, "gametest");
		}
		helper.succeed();
	}

	@GameTest
	public void aRejectionMakesThePlayerWaitAndClosingDoesNot(GameTestHelper helper) {
		NameAndId player = offlinePlayer();
		Punishment ban = punish(helper, player, PunishmentType.BAN, null);
		ServerPlayer mod = Harness.namedPlayer(helper);
		PermissionGroups groups = PermissionGroups.get();
		groups.players.put(mod.getUUID().toString(), "admin");
		try {
			DiscordUser user = staff(helper, mod, Nodes.APPEALS);
			DiscordUser filer = stranger("p");

			Harness.check(helper, DiscordAccess.fileAppeal(filer, ban.appealCode(), "first").join().done(), "filing");
			long firstId = openAppeal(helper, ban).id();
			Harness.check(helper, DiscordAccess.closeAppeal(user, firstId).join().done(), "closing failed");
			Harness.checkEquals(helper, "CLOSED", Mods.appeals().byId(firstId).verdict(), "a close is not a verdict");
			Harness.check(helper, Mods.punish().byId(ban.id()).active(), "closing lifted the ban");

			Harness.check(helper, DiscordAccess.fileAppeal(filer, ban.appealCode(), "second").join().done(),
					"a closed appeal left a wait behind it");
			long secondId = openAppeal(helper, ban).id();
			List<StaffCoreEvent> events = capture(() ->
					Harness.check(helper, DiscordAccess.rejectAppeal(user, secondId).join().done(), "rejecting failed"));
			Harness.check(helper, events.stream().anyMatch(e -> e instanceof StaffCoreEvent.AppealDecided d
					&& d.id() == secondId && d.mayAppealAgainAt() != null), "the rejection did not say when they may try again");

			var third = DiscordAccess.fileAppeal(filer, ban.appealCode(), "third").join();
			Harness.check(helper, !third.done() && third.message().contains("again"),
					"a rejected punishment was appealed again straight away: " + third.message());
		} finally {
			groups.players.remove(mod.getUUID().toString());
			Mods.punish().revoke(Harness.server(helper), player.id(), "Console", true, "gametest");
		}
		helper.succeed();
	}

	@GameTest
	public void aQuestionReachesThePlayerAndOnlyTheirAnswerComesBack(GameTestHelper helper) throws Exception {
		NameAndId player = offlinePlayer();
		Punishment ban = punish(helper, player, PunishmentType.BAN, null);
		ServerPlayer mod = Harness.namedPlayer(helper);
		PermissionGroups groups = PermissionGroups.get();
		groups.players.put(mod.getUUID().toString(), "admin");
		try {
			DiscordUser user = staff(helper, mod, Nodes.APPEALS);
			DiscordUser filer = stranger("p");
			Harness.check(helper, DiscordAccess.fileAppeal(filer, ban.appealCode(), "hello").join().done(), "filing");
			long id = openAppeal(helper, ban).id();

			Harness.check(helper, !DiscordAccess.replyToAppeal(filer, "unprompted").join().done(),
					"an answer was taken before anybody asked a question");

			List<StaffCoreEvent> asked = capture(() -> Harness.check(helper,
					DiscordAccess.requestAppealInfo(user, id, "which account did you use?").join().done(), "asking failed"));
			var question = asked.stream().filter(e -> e instanceof StaffCoreEvent.AppealConversation c && c.id() == id)
					.map(e -> (StaffCoreEvent.AppealConversation) e).findFirst().orElse(null);
			Harness.check(helper, question != null && !question.fromAppellant()
					&& filer.id().equals(question.discordId()), "the question was not published for the filer: " + question);

			Harness.check(helper, !DiscordAccess.replyToAppeal(stranger("someone else"), "me too").join().done(),
					"somebody who did not file the appeal answered it");
			Harness.check(helper, DiscordAccess.replyToAppeal(filer, "my main").join().done(), "the filer's answer was refused");
			Harness.check(helper, !Mods.appeals().byId(id).waitingOnPlayer(), "answered, but still waiting on the player");
		} finally {
			groups.players.remove(mod.getUUID().toString());
			Mods.punish().revoke(Harness.server(helper), player.id(), "Console", true, "gametest");
		}
		helper.succeed();
	}

	@GameTest
	public void anAppealThePlayerStoppedAnsweringGoesStaleAndOneStaffOweIsLeftAlone(GameTestHelper helper)
			throws Exception {
		NameAndId waitingOnPlayer = offlinePlayer();
		NameAndId waitingOnStaff = offlinePlayer();
		Punishment first = punish(helper, waitingOnPlayer, PunishmentType.BAN, null);
		Punishment second = punish(helper, waitingOnStaff, PunishmentType.BAN, null);
		DiscordUser a = stranger("a");
		DiscordUser b = stranger("b");
		Harness.check(helper, DiscordAccess.fileAppeal(a, first.appealCode(), "x").join().done(), "filing");
		Harness.check(helper, DiscordAccess.fileAppeal(b, second.appealCode(), "y").join().done(), "filing");
		long stale = openAppeal(helper, first).id();
		long owed = openAppeal(helper, second).id();

		Mods.appeals().requestInfo(stale, "Mod", "anything to add?");
		long longAgo = System.currentTimeMillis() - 30L * 86_400_000L;
		try (PreparedStatement ps = StaffCore.storage().conn().prepareStatement(
				"UPDATE appeals SET info_requested_at=?, created_at=? WHERE id IN (?, ?)")) {
			ps.setLong(1, longAgo);
			ps.setLong(2, longAgo);
			ps.setLong(3, stale);
			ps.setLong(4, owed);
			ps.executeUpdate();
		}
		// The second was never asked anything; only its age was wound back.
		try (PreparedStatement ps = StaffCore.storage().conn().prepareStatement(
				"UPDATE appeals SET info_requested_at=NULL WHERE id=?")) {
			ps.setLong(1, owed);
			ps.executeUpdate();
		}

		try {
			Mods.appeals().markStale(Harness.server(helper), 7);
			Harness.checkEquals(helper, "STALE", Mods.appeals().byId(stale).status(), "the unanswered appeal");
			Harness.check(helper, Mods.appeals().byId(owed).isOpen(),
					"an old appeal nobody had asked anything was closed, punishing the player for staff's wait");
			Harness.check(helper, Mods.punish().byId(first.id()).active(), "going stale lifted the ban");
			Harness.check(helper, DiscordAccess.fileAppeal(a, first.appealCode(), "back again").join().done(),
					"a stale appeal left the player unable to appeal again");
		} finally {
			Mods.punish().revoke(Harness.server(helper), waitingOnPlayer.id(), "Console", true, "gametest");
			Mods.punish().revoke(Harness.server(helper), waitingOnStaff.id(), "Console", true, "gametest");
		}
		helper.succeed();
	}

	@GameTest
	public void theAppealButtonsReadsAreBehindTheirNodes(GameTestHelper helper) {
		NameAndId player = offlinePlayer();
		String caseId = Mods.cases().store().openManually(player.id(), player.name(), "Mod", "gametest", 40,
				CaseCategory.OTHER);
		Mods.cases().evidence().add(caseId, io.github.alphain24.staffcore.modules.cases.CaseEvidence.Draft
				.replay(player.id(), player.name(), null, null, 1L, 2L, "gametest replay"), "Mod");
		Punishment ban = punish(helper, player, PunishmentType.BAN, caseId);

		ServerPlayer mod = Harness.namedPlayer(helper);
		PermissionGroups groups = PermissionGroups.get();
		groups.players.put(mod.getUUID().toString(), "admin");
		try {
			DiscordUser historyOnly = staff(helper, mod, Nodes.HISTORY);
			var punishment = DiscordAccess.punishment(historyOnly, ban.id()).join();
			Harness.check(helper, punishment.answered() && punishment.value().id() == ban.id(), "the punishment read failed");
			Harness.check(helper, !DiscordAccess.evidence(historyOnly, ban.id()).join().answered(),
					"evidence was read without the case node");

			DiscordUser cases = new DiscordUser(historyOnly.id(), historyOnly.name(), Set.of(Nodes.STAFF_GUI));
			var evidence = DiscordAccess.evidence(cases, ban.id()).join();
			Harness.check(helper, evidence.answered() && evidence.value().size() == 1,
					"the case's evidence was not returned: " + evidence);
		} finally {
			groups.players.remove(mod.getUUID().toString());
			Mods.punish().revoke(Harness.server(helper), player.id(), "Console", true, "gametest");
		}
		helper.succeed();
	}
}
