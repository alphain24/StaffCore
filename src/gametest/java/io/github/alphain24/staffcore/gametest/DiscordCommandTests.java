package io.github.alphain24.staffcore.gametest;

import io.github.alphain24.staffcore.StaffCore;
import io.github.alphain24.staffcore.api.DiscordAccess;
import io.github.alphain24.staffcore.api.DiscordUser;
import io.github.alphain24.staffcore.config.StaffConfig;
import io.github.alphain24.staffcore.module.Mods;
import io.github.alphain24.staffcore.modules.cases.CaseCategory;
import io.github.alphain24.staffcore.modules.punish.PunishmentType;
import io.github.alphain24.staffcore.permission.Nodes;
import io.github.alphain24.staffcore.permission.PermissionGroups;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Phase 5.5 and Gate 5 on a running server: the {@code /staff} commands from Discord, tried the ways
 * somebody would try to get more out of them than their account can do in game.
 * <p>
 * Every call here is what the companion's slash command handler makes, straight into the published API.
 */
public class DiscordCommandTests {

	private static String snowflake() {
		return String.valueOf(100_000_000_000_000_000L + (long) (Math.random() * 800_000_000_000_000_000L));
	}

	private static DiscordUser link(GameTestHelper helper, ServerPlayer player, String... roleNodes) {
		DiscordUser user = new DiscordUser(snowflake(), "dc_" + Harness.name(player), Set.of(roleNodes));
		String code = Mods.discord().links().issueCode(player.getUUID(), Harness.name(player));
		Harness.check(helper, DiscordAccess.link(user, code).join().done(), "linking failed");
		return user;
	}

	private static DiscordUser withRoles(DiscordUser user, String... roleNodes) {
		return new DiscordUser(user.id(), user.name(), Set.of(roleNodes));
	}

	/** A permission group made for one test, so a test can hold exactly the nodes it is about. */
	private static String group(GameTestHelper helper, ServerPlayer player, String... nodes) {
		String name = "gametest-" + player.getUUID().toString().substring(0, 8);
		PermissionGroups groups = PermissionGroups.get();
		groups.groups.put(name, new ArrayList<>(List.of(nodes)));
		groups.players.put(player.getUUID().toString(), name);
		return name;
	}

	private static void ungroup(ServerPlayer player, String name) {
		PermissionGroups groups = PermissionGroups.get();
		groups.players.remove(player.getUUID().toString());
		groups.groups.remove(name);
	}

	// ------------------------------------------------------------------ the bypass attempts

	@GameTest
	public void aPunishmentFromDiscordNeedsThePermissionInGameAndInTheRoleMapping(GameTestHelper helper) throws Exception {
		ServerPlayer staff = Harness.namedPlayer(helper);
		ServerPlayer target = Harness.namedPlayer(helper);
		String group = group(helper, staff, Nodes.HISTORY);
		try {
			// A role that maps every punishment, on an account that can punish nothing in game.
			DiscordUser user = link(helper, staff, Nodes.WARN, Nodes.MUTE, Nodes.BAN);
			var refused = DiscordAccess.warn(user, Harness.name(target), "gametest").join();
			Harness.check(helper, !refused.done(), "a role granted a warning the account cannot give in game");

			// An unlinked account whose roles map everything.
			DiscordUser stranger = new DiscordUser(snowflake(), "stranger", Set.of(Nodes.WARN, Nodes.MUTE, Nodes.BAN));
			Harness.check(helper, !DiscordAccess.warn(stranger, Harness.name(target), "gametest").join().done(),
					"an unlinked account gave a warning");

			// The account can warn in game, but the owner has not mapped warnings to Discord.
			PermissionGroups.get().groups.get(group).add(Nodes.WARN);
			Harness.check(helper, !DiscordAccess.warn(withRoles(user, Nodes.HISTORY), Harness.name(target), "gametest")
					.join().done(), "a warning was given without the role mapping allowing it");

			// Both sides allow it.
			var given = DiscordAccess.warn(withRoles(user, Nodes.WARN), Harness.name(target), "gametest: rude").join();
			Harness.check(helper, given.done(), "the warning was refused: " + given.message());

			var warning = Mods.punish().history(target.getUUID()).get(0);
			Harness.checkEquals(helper, PunishmentType.WARN, warning.type(), "what was given");
			Harness.checkEquals(helper, Harness.name(staff), warning.staffName(), "who it is recorded against");
			Harness.check(helper, Mods.punish().history(target.getUUID()).size() == 1,
					"a refused attempt still wrote a punishment");

			try (PreparedStatement ps = StaffCore.storage().conn().prepareStatement(
					"SELECT command FROM command_log WHERE staff_name=? AND command LIKE '[discord] warn %'")) {
				ps.setString(1, Harness.name(staff));
				try (ResultSet rs = ps.executeQuery()) {
					Harness.check(helper, rs.next() && rs.getString(1).contains(user.id()),
							"the warning was not audited against the Discord account");
				}
			}
		} finally {
			ungroup(staff, group);
		}
		helper.succeed();
	}

	@GameTest
	public void theRateLimitFromDiscordIsTheOneInGame(GameTestHelper helper) {
		ServerPlayer staff = Harness.namedPlayer(helper);
		ServerPlayer target = Harness.namedPlayer(helper);
		String group = group(helper, staff, Nodes.WARN);
		try {
			DiscordUser user = link(helper, staff, Nodes.WARN);
			int allowed = StaffConfig.get().maxPunishmentsPerMinute;
			for (int i = 0; i < allowed; i++) {
				var result = DiscordAccess.warn(user, Harness.name(target), "gametest " + i).join();
				Harness.check(helper, result.done(), "warning " + (i + 1) + " of " + allowed + " was refused: " + result.message());
			}
			var over = DiscordAccess.warn(user, Harness.name(target), "one too many").join();
			Harness.check(helper, !over.done() && over.message().startsWith("Rate limit"),
					"the punishment rate limit did not hold from Discord: " + over.message());
			Harness.checkEquals(helper, allowed, Mods.punish().history(target.getUUID()).size(), "warnings written");
		} finally {
			ungroup(staff, group);
		}
		helper.succeed();
	}

	@GameTest
	public void theRankGuardHoldsFromDiscord(GameTestHelper helper) {
		ServerPlayer staff = Harness.namedPlayer(helper);
		ServerPlayer senior = Harness.namedPlayer(helper);
		String staffGroup = group(helper, staff, Nodes.MUTE);
		String seniorGroup = group(helper, senior, Nodes.MUTE, Nodes.BAN, Nodes.WARN);
		try {
			DiscordUser user = link(helper, staff, Nodes.MUTE);
			var result = DiscordAccess.mute(user, Harness.name(senior), "1h", "gametest").join();
			Harness.check(helper, !result.done(), "a staff member muted somebody who outranks them, from Discord");
			Harness.check(helper, result.message().contains("holds at least as much"),
					"the refusal did not come from the rank guard: " + result.message());
			Harness.check(helper, Mods.punish().activeMute(senior.getUUID()) == null, "the refused mute was applied");
		} finally {
			ungroup(staff, staffGroup);
			ungroup(senior, seniorGroup);
		}
		helper.succeed();
	}

	@GameTest
	public void everyDiscordWriteCountsAgainstTheDiscordActionLimit(GameTestHelper helper) {
		ServerPlayer staff = Harness.namedPlayer(helper);
		ServerPlayer target = Harness.namedPlayer(helper);
		String group = group(helper, staff, Nodes.NOTES);
		try {
			DiscordUser user = link(helper, staff, Nodes.NOTES);
			int allowed = StaffConfig.get().discordActionsPerMinute;
			for (int i = 0; i < allowed; i++) {
				var result = DiscordAccess.addNote(user, Harness.name(target), "note " + i).join();
				Harness.check(helper, result.done(), "note " + (i + 1) + " was refused: " + result.message());
			}
			var over = DiscordAccess.addNote(user, Harness.name(target), "one too many").join();
			Harness.check(helper, !over.done() && over.message().contains("Discord action"),
					"notes, which have no limit in game, were not limited from Discord: " + over.message());
		} finally {
			ungroup(staff, group);
		}
		helper.succeed();
	}

	// ------------------------------------------------------------------ the commands

	@GameTest
	public void muteUnmuteFreezeAndUnfreezeByName(GameTestHelper helper) {
		ServerPlayer staff = Harness.namedPlayer(helper);
		ServerPlayer target = Harness.namedPlayer(helper);
		String group = group(helper, staff, Nodes.MUTE, Nodes.UNPUNISH, Nodes.FREEZE);
		try {
			DiscordUser user = link(helper, staff, Nodes.MUTE, Nodes.UNPUNISH, Nodes.FREEZE);

			Harness.check(helper, !DiscordAccess.mute(user, Harness.name(target), "forever-ish", "x").join().done(),
					"a duration that is not one was accepted");
			var muted = DiscordAccess.mute(user, Harness.name(target), "1h", "gametest: spam").join();
			Harness.check(helper, muted.done(), "the mute failed: " + muted.message());
			var mute = Mods.punish().activeMute(target.getUUID());
			Harness.check(helper, mute != null && mute.type() == PunishmentType.TEMPMUTE && mute.expiresAt() != null,
					"the mute was not a one-hour mute: " + mute);

			Harness.check(helper, DiscordAccess.unmute(user, Harness.name(target), "gametest").join().done(), "unmute failed");
			Harness.check(helper, Mods.punish().activeMute(target.getUUID()) == null, "still muted");
			Harness.check(helper, !DiscordAccess.unmute(user, Harness.name(target), "").join().done(),
					"lifting a mute that is not there said it did");

			Harness.check(helper, !DiscordAccess.unfreeze(user, Harness.name(target)).join().done(),
					"releasing somebody not frozen froze them instead");
			Harness.check(helper, DiscordAccess.freeze(user, Harness.name(target)).join().done(), "freeze failed");
			Harness.check(helper, Mods.freeze().isFrozen(target), "not frozen");
			Harness.check(helper, DiscordAccess.unfreeze(user, Harness.name(target)).join().done(), "unfreeze failed");
			Harness.check(helper, !Mods.freeze().isFrozen(target), "still frozen");
		} finally {
			if (Mods.freeze().isFrozen(target)) Mods.freeze().toggle(target);
			ungroup(staff, group);
		}
		helper.succeed();
	}

	@GameTest
	public void aBanFromDiscordIsTheSameBanAsInGame(GameTestHelper helper) {
		ServerPlayer staff = Harness.namedPlayer(helper);
		ServerPlayer target = Harness.namedPlayer(helper);
		UUID targetId = target.getUUID();
		String group = group(helper, staff, Nodes.BAN, Nodes.UNPUNISH);
		try {
			DiscordUser user = link(helper, staff, Nodes.BAN);
			var result = DiscordAccess.ban(user, Harness.name(target), "1d", "gametest: x-ray").join();
			Harness.check(helper, result.done(), "the ban failed: " + result.message());
			var ban = Mods.punish().activeBan(targetId);
			Harness.check(helper, ban != null && ban.type() == PunishmentType.TEMPBAN, "not a temporary ban: " + ban);
			Harness.check(helper, ban.isAppealable(), "a ban from Discord has no appeal code");
		} finally {
			Mods.punish().revoke(Harness.server(helper), targetId, "Console", true, "gametest");
			ungroup(staff, group);
		}
		helper.succeed();
	}

	@GameTest
	public void theReadCommandsAnswerBehindTheirNodes(GameTestHelper helper) {
		ServerPlayer staff = Harness.namedPlayer(helper);
		ServerPlayer target = Harness.namedPlayer(helper);
		String group = group(helper, staff, Nodes.HISTORY, Nodes.NOTES_VIEW, Nodes.NOTES, Nodes.STAFF_GUI, Nodes.AUDIT,
				Nodes.ANALYTICS);
		try {
			Mods.notes().add(target.getUUID(), "Console", "gametest note");
			String caseId = Mods.cases().store().openManually(target.getUUID(), Harness.name(target), "Console",
					"gametest case", 40, CaseCategory.OTHER);

			DiscordUser nothing = link(helper, staff, Nodes.CHAT);
			Harness.check(helper, !DiscordAccess.notes(nothing, Harness.name(target)).join().answered(), "notes without the node");
			Harness.check(helper, !DiscordAccess.caseView(nothing, caseId).join().answered(), "a case without the node");
			Harness.check(helper, !DiscordAccess.staffHistory(nothing, Harness.name(staff), 7).join().answered(),
					"staff history without the node");
			Harness.check(helper, !DiscordAccess.analytics(nothing, "").join().answered(), "analytics without the node");

			DiscordUser user = withRoles(nothing, Nodes.HISTORY, Nodes.NOTES_VIEW, Nodes.STAFF_GUI, Nodes.AUDIT,
					Nodes.ANALYTICS);
			var notes = DiscordAccess.notes(user, Harness.name(target)).join();
			Harness.check(helper, notes.answered() && notes.value().stream().anyMatch(n -> n.text().equals("gametest note")),
					"the note was not listed");
			var view = DiscordAccess.caseView(user, caseId.toLowerCase(java.util.Locale.ROOT)).join();
			Harness.check(helper, view.answered() && view.value().id().equals(caseId), "the case was not found: " + view);
			Harness.check(helper, DiscordAccess.caseEvidence(user, caseId).join().answered(), "case evidence");
			Harness.check(helper, DiscordAccess.profile(user, Harness.name(target)).join().answered(), "profile");
			Harness.check(helper, DiscordAccess.history(user, Harness.name(target)).join().answered(), "history");
			Harness.check(helper, DiscordAccess.staffHistory(user, Harness.name(staff), 7).join().answered(), "staff history");
			Harness.check(helper, DiscordAccess.analytics(user, "").join().answered(), "analytics");
			Harness.check(helper, !DiscordAccess.caseView(user, "NOPE0000").join().answered(), "a case that does not exist");

			List<String> suggested = DiscordAccess.suggestPlayers(user, Harness.name(target).substring(0, 8)).join();
			Harness.check(helper, suggested.contains(Harness.name(target)), "autocomplete did not offer the player: " + suggested);
			Harness.check(helper, DiscordAccess.suggestPlayers(new DiscordUser(snowflake(), "x", Set.of()),
					Harness.name(target).substring(0, 8)).join().isEmpty(), "autocomplete listed players to an unlinked account");
		} finally {
			ungroup(staff, group);
		}
		helper.succeed();
	}
}
