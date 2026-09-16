package io.github.alphain24.staffcore.gametest;

import io.github.alphain24.staffcore.api.DiscordAccess;
import io.github.alphain24.staffcore.api.DiscordOperation;
import io.github.alphain24.staffcore.api.DiscordUser;
import io.github.alphain24.staffcore.api.StaffCoreApi;
import io.github.alphain24.staffcore.api.StaffCoreEvent;
import io.github.alphain24.staffcore.api.StaffCoreListener;
import io.github.alphain24.staffcore.api.internal.EventBus;
import io.github.alphain24.staffcore.module.Mods;
import io.github.alphain24.staffcore.modules.punish.PunishmentType;
import io.github.alphain24.staffcore.permission.Actor;
import io.github.alphain24.staffcore.permission.Nodes;
import io.github.alphain24.staffcore.permission.PermissionGroups;
import io.github.alphain24.staffcore.permission.Permissions;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.ServerOpListEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Gate 5: Discord cannot do anything the same account could not do in game, tried the ways somebody
 * would try it; and a bot that dies mid-punishment leaves the punishment applied.
 * <p>
 * The rest of the sweep lives beside what it guards: {@code DiscordAccessTests} (roles only narrow,
 * unlinked and banned accounts hold nothing, the in-game-only actions refuse Discord),
 * {@code DiscordCommandTests} (the same rate limit and rank guard), {@code BypassAttemptTest} (a Discord
 * account cannot approve), {@code ActorBoundaryTest} (an operator the settings refused holds nothing
 * extra), and in the companion {@code DiscordBotQueueTest} (the post waits for the bot to come back).
 */
public class DiscordBypassTests {

	private static String snowflake() {
		return String.valueOf(100_000_000_000_000_000L + (long) (Math.random() * 800_000_000_000_000_000L));
	}

	private static DiscordUser link(GameTestHelper helper, UUID player, String name, String... roleNodes) {
		DiscordUser user = new DiscordUser(snowflake(), "dc_" + name, Set.of(roleNodes));
		String code = Mods.discord().links().issueCode(player, name);
		var linked = DiscordAccess.link(user, code).join();
		Harness.check(helper, linked.done(), "linking failed: " + linked.message());
		return user;
	}

	private static String group(UUID player, String... nodes) {
		String name = "gametest-" + player.toString().substring(0, 8);
		PermissionGroups groups = PermissionGroups.get();
		groups.groups.put(name, new ArrayList<>(List.of(nodes)));
		groups.players.put(player.toString(), name);
		return name;
	}

	private static void ungroup(UUID player, String name) {
		PermissionGroups groups = PermissionGroups.get();
		groups.players.remove(player.toString());
		groups.groups.remove(name);
	}

	@GameTest
	public void aPermissionsModsRefusalHoldsFromDiscordEvenForAnOperator(GameTestHelper helper) {
		PermissionApiTests.standInPermissionsMod();
		MinecraftServer server = Harness.server(helper);
		ServerPlayer admin = Harness.namedPlayer(helper);
		server.getPlayerList().getOps().add(new ServerOpListEntry(admin.nameAndId(),
				LevelBasedPermissionSet.OWNER, false));
		PermissionApiTests.DENIED.add(admin.getUUID());
		try {
			// The role mapping names both; the permissions mod refuses staff.gui and says nothing about
			// history, which being an operator then grants.
			DiscordUser user = link(helper, admin.getUUID(), Harness.name(admin), Nodes.STAFF_GUI, Nodes.HISTORY);

			Harness.check(helper, !Permissions.check(admin, Nodes.STAFF_GUI),
					"in game, the operator kept a node the permissions mod refused");
			Harness.check(helper, !DiscordAccess.check(user, DiscordOperation.VIEW_CASE).join().allowed(),
					"from Discord, an operator used a node the permissions mod refused them in game");
			Harness.check(helper, DiscordAccess.check(user, DiscordOperation.VIEW_HISTORY).join().allowed(),
					"a node the operator holds on both sides was refused, so the refusal above proves nothing");
		} finally {
			PermissionApiTests.DENIED.remove(admin.getUUID());
			server.getPlayerList().getOps().remove(admin.nameAndId());
		}
		helper.succeed();
	}

	@GameTest
	public void anAccountThePermissionsModHasNotLoadedCanDoNothingFromDiscord(GameTestHelper helper) {
		PermissionApiTests.standInPermissionsMod();
		UUID offline = UUID.randomUUID();
		String name = "off" + offline.toString().substring(0, 8);
		String group = group(offline, Nodes.HISTORY);
		PermissionApiTests.LOADING.add(offline);
		try {
			DiscordUser user = link(helper, offline, name, Nodes.HISTORY);

			var loading = DiscordAccess.check(user, DiscordOperation.VIEW_HISTORY).join();
			Harness.check(helper, !loading.allowed(),
					"an account whose permissions were not known yet was allowed a node from Discord");
			Harness.check(helper, loading.refusal().contains("Try again"), loading.refusal());

			PermissionApiTests.LOADING.remove(offline);
			Harness.check(helper, DiscordAccess.check(user, DiscordOperation.VIEW_HISTORY).join().allowed(),
					"once loaded, the node its group grants was still refused");
		} finally {
			PermissionApiTests.LOADING.remove(offline);
			ungroup(offline, group);
		}
		helper.succeed();
	}

	@GameTest
	public void aBotThatDiesMidPunishmentLeavesThePunishmentApplied(GameTestHelper helper) {
		MinecraftServer server = Harness.server(helper);
		UUID target = UUID.randomUUID();
		String name = "vic" + target.toString().substring(0, 8);

		CountDownLatch release = new CountDownLatch(1);
		List<Long> toldAfter = new CopyOnWriteArrayList<>();
		// A bot stuck writing to Discord, one that has died, and a listener after both.
		StaffCoreListener stuck = event -> {
			if (event instanceof StaffCoreEvent.PunishmentIssued issued && issued.targetId().equals(target)) {
				try {
					release.await(1, TimeUnit.SECONDS);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}
		};
		StaffCoreListener dead = event -> {
			if (event instanceof StaffCoreEvent.PunishmentIssued issued && issued.targetId().equals(target)) {
				throw new IllegalStateException("the bot died mid-write");
			}
		};
		StaffCoreListener after = event -> {
			if (event instanceof StaffCoreEvent.PunishmentIssued issued && issued.targetId().equals(target)) {
				toldAfter.add(issued.id());
			}
		};
		StaffCoreApi.addListener(stuck);
		StaffCoreApi.addListener(dead);
		StaffCoreApi.addListener(after);
		try {
			long started = System.nanoTime();
			var ban = Mods.punish().apply(server, new NameAndId(target, name), "Console", PunishmentType.BAN, null,
					"gametest: the bot died", null, null, Actor.console());
			long millis = (System.nanoTime() - started) / 1_000_000;

			Harness.check(helper, ban != null && Mods.punish().activeBan(target) != null,
					"the punishment was not applied while the bot was stuck and dead");
			Harness.check(helper, millis < 750, "the punishment waited " + millis + " ms on a stuck bot");

			release.countDown();
			Harness.check(helper, EventBus.drain(5000), "the event thread did not recover from a dead listener");
			Harness.check(helper, toldAfter.contains(ban.id()),
					"a listener after the dead one was never told about the punishment");
		} finally {
			release.countDown();
			StaffCoreApi.removeListener(stuck);
			StaffCoreApi.removeListener(dead);
			StaffCoreApi.removeListener(after);
			Mods.punish().revoke(server, target, "Console", true, "gametest");
		}
		helper.succeed();
	}
}
