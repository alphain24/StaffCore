package io.github.alphain24.staffcore.gametest;

import io.github.alphain24.staffcore.api.DiscordAccess;
import io.github.alphain24.staffcore.api.DiscordUser;
import io.github.alphain24.staffcore.module.Mods;
import io.github.alphain24.staffcore.modules.punish.PunishmentType;
import io.github.alphain24.staffcore.permission.Nodes;
import io.github.alphain24.staffcore.permission.PermissionGroups;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Phase 6.7 on a running server: the Discord punishment panel punishes by ladder, only for those with its
 * permission on both sides, only with punishments they may give, and never on a record that moved.
 */
public class DiscordPunishPanelTests {

	private static String snowflake() {
		return String.valueOf(100_000_000_000_000_000L + (long) (Math.random() * 800_000_000_000_000_000L));
	}

	private static String group(ServerPlayer player, String... nodes) {
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

	private static DiscordUser link(GameTestHelper helper, ServerPlayer player, String... roleNodes) {
		DiscordUser user = new DiscordUser(snowflake(), "dc_" + Harness.name(player), Set.of(roleNodes));
		String code = Mods.discord().links().issueCode(player.getUUID(), Harness.name(player));
		Harness.check(helper, DiscordAccess.link(user, code).join().done(), "linking failed");
		return user;
	}

	private static DiscordUser roles(DiscordUser user, String... roleNodes) {
		return new DiscordUser(user.id(), user.name(), Set.of(roleNodes));
	}

	@GameTest
	public void thePanelPunishesByLadderOnlyForItsPermissionHolders(GameTestHelper helper) {
		ServerPlayer admin = Harness.namedPlayer(helper);
		ServerPlayer target = Harness.namedPlayer(helper);
		String targetName = Harness.name(target);
		String group = group(admin, Nodes.PUNISH, Nodes.WARN, Nodes.MUTE, Nodes.BAN);
		try {
			// A moderator's nodes, and the panel mapped in Discord: the game says no.
			DiscordUser user = link(helper, admin, Nodes.DISCORD_PUNISH_PANEL, Nodes.WARN, Nodes.MUTE, Nodes.BAN);
			Harness.check(helper, !DiscordAccess.ladder(user, targetName).join().answered(),
					"the panel opened for an account without discord.punishpanel in game");
			Harness.check(helper, !DiscordAccess.punishByOffence(user, targetName, "griefing", 0).join().done(),
					"the panel punished for an account without discord.punishpanel in game");
			Harness.check(helper, DiscordAccess.suggestOffences(user, "").join().isEmpty(),
					"offences were offered to an account that cannot use the panel");

			// The node in game, and not in the Discord role: still no.
			PermissionGroups.get().groups.get(group).add(Nodes.DISCORD_PUNISH_PANEL);
			DiscordUser unmapped = roles(user, Nodes.WARN, Nodes.MUTE, Nodes.BAN);
			Harness.check(helper, !DiscordAccess.ladder(unmapped, targetName).join().answered(),
					"the panel opened without discord.punishpanel in the role mapping");

			// Both sides allow it.
			var ladder = DiscordAccess.ladder(user, targetName).join();
			Harness.check(helper, ladder.answered(), "the panel refused: " + ladder.refusal());
			var griefing = ladder.value().rungs().stream().filter(r -> r.offenceId().equals("griefing")).findFirst()
					.orElse(null);
			Harness.check(helper, griefing != null && griefing.priors() == 0 && "WARN".equals(griefing.type())
					&& griefing.allowed(), "griefing is not a first-time warning: " + griefing);
			Harness.check(helper, DiscordAccess.suggestOffences(user, "grief").join().stream()
					.anyMatch(s -> s.value().equals("griefing")), "griefing was not offered");

			// A ladder rung the role does not map is shown as not theirs, and refused.
			DiscordUser warnOnly = roles(user, Nodes.DISCORD_PUNISH_PANEL, Nodes.MUTE, Nodes.BAN);
			var limited = DiscordAccess.ladder(warnOnly, targetName).join();
			Harness.check(helper, limited.answered() && limited.value().rungs().stream()
					.filter(r -> r.offenceId().equals("griefing")).noneMatch(r -> r.allowed()),
					"a warning was offered to a role that does not map warnings");
			var refused = DiscordAccess.punishByOffence(warnOnly, target.getUUID().toString(), "griefing", 0).join();
			Harness.check(helper, !refused.done() && refused.message().contains(Nodes.WARN),
					"a rung the role does not map was issued: " + refused.message());

			Harness.check(helper, !DiscordAccess.punishByOffence(user, targetName, "not-an-offence", 0).join().done(),
					"an unknown offence was accepted");

			// Issued as the game would: the rung the record reaches, the offence as the reason, by id.
			var given = DiscordAccess.punishByOffence(user, target.getUUID().toString(), "griefing", 0).join();
			Harness.check(helper, given.done(), "the warning was refused: " + given.message());
			var issued = Mods.punish().history(target.getUUID()).get(0);
			Harness.checkEquals(helper, PunishmentType.WARN, issued.type(), "what was issued");
			Harness.checkEquals(helper, Harness.name(admin), issued.staffName(), "who it is recorded against");
			Harness.checkEquals(helper, 1, Mods.punish().countForOffence(target.getUUID(), "griefing"),
					"the warning was not recorded against the offence");

			// The record moved: a confirmation drawn before it is refused, and nothing is issued.
			var stale = DiscordAccess.punishByOffence(user, targetName, "griefing", 0).join();
			Harness.check(helper, !stale.done() && stale.message().contains("changed"),
					"a punishment was issued on a record that had changed: " + stale.message());
			Harness.checkEquals(helper, 1, Mods.punish().history(target.getUUID()).size(), "punishments on record");

			var next = DiscordAccess.ladder(user, targetName).join().value().rungs().stream()
					.filter(r -> r.offenceId().equals("griefing")).findFirst().orElseThrow();
			Harness.check(helper, next.priors() == 1 && "BAN".equals(next.type()),
					"the ladder did not move on to a ban: " + next);
		} finally {
			ungroup(admin, group);
		}
		helper.succeed();
	}
}
