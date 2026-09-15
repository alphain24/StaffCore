package io.github.alphain24.staffcore.gametest;

import io.github.alphain24.staffcore.StaffCore;
import io.github.alphain24.staffcore.api.DiscordAccess;
import io.github.alphain24.staffcore.api.DiscordOperation;
import io.github.alphain24.staffcore.api.DiscordUser;
import io.github.alphain24.staffcore.api.internal.DiscordGate;
import io.github.alphain24.staffcore.inventory.InventoryGateway;
import io.github.alphain24.staffcore.module.Mods;
import io.github.alphain24.staffcore.modules.punish.AddressBans;
import io.github.alphain24.staffcore.modules.punish.PunishmentType;
import io.github.alphain24.staffcore.permission.Actor;
import io.github.alphain24.staffcore.permission.Nodes;
import io.github.alphain24.staffcore.permission.PermissionGroups;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Gate 5's first condition on a running server: Discord cannot do anything the linked account
 * could not do in game — tried the ways somebody would actually try it.
 * <p>
 * Every call here goes through the published API or the real services. The API runs its work on
 * the server thread and a gametest already is the server thread, so each future is complete by the
 * time it is returned.
 */
public class DiscordAccessTests {

	private static String snowflake() {
		return String.valueOf(100_000_000_000_000_000L + (long) (Math.random() * 800_000_000_000_000_000L));
	}

	/** Links a Discord id to this account through the real code path. */
	private static DiscordUser link(GameTestHelper helper, UUID player, String name, String... roleNodes) {
		DiscordUser user = new DiscordUser(snowflake(), "dc_" + name, Set.of(roleNodes));
		String code = Mods.discord().links().issueCode(player, name);
		var result = DiscordAccess.link(user, code).join();
		Harness.check(helper, result.done(), "linking failed: " + result.message());
		return user;
	}

	@GameTest
	public void whatDiscordAllowsIsTheSmallerOfRolesAndTheGame(GameTestHelper helper) {
		ServerPlayer staff = Harness.namedPlayer(helper);
		PermissionGroups groups = PermissionGroups.get();
		Harness.check(helper, groups != null, "no permission groups in the test server");
		groups.players.put(staff.getUUID().toString(), "helper");
		try {
			// The role mapping says ban, mute and history. In game this account is a helper: history
			// yes, bans and mutes no.
			DiscordUser user = link(helper, staff.getUUID(), Harness.name(staff),
					Nodes.BAN, Nodes.MUTE, Nodes.HISTORY);

			var standing = DiscordAccess.standing(user).join();
			Harness.checkEquals(helper, Set.of(Nodes.HISTORY), standing.nodes(), "what Discord allows");
			Harness.check(helper, DiscordAccess.check(user, DiscordOperation.VIEW_HISTORY).join().allowed(),
					"a node held on both sides was refused");
			Harness.check(helper, !DiscordAccess.check(user, DiscordOperation.BAN).join().allowed(),
					"a Discord role added a permission the account does not hold in game");

			// Freeze is held in game but not mapped: the owner has said it stays in game.
			Harness.check(helper, !DiscordAccess.check(user, DiscordOperation.FREEZE).join().allowed(),
					"a node the role mapping never named was allowed");

			// Promoted in game, and the next request sees it — nothing was cached.
			groups.players.put(staff.getUUID().toString(), "moderator");
			Harness.check(helper, DiscordAccess.check(user, DiscordOperation.BAN).join().allowed(),
					"a promotion in game did not reach Discord on the next request");

			// Demoted again, and the next request sees that too.
			groups.players.remove(staff.getUUID().toString());
			Harness.check(helper, !DiscordAccess.check(user, DiscordOperation.VIEW_HISTORY).join().allowed(),
					"a demotion in game did not reach Discord on the next request");
		} finally {
			groups.players.remove(staff.getUUID().toString());
		}
		helper.succeed();
	}

	@GameTest
	public void anOfflineAccountIsAskedTheSameQuestionTheGameWouldAsk(GameTestHelper helper) {
		PermissionGroups groups = PermissionGroups.get();
		UUID offline = UUID.randomUUID();
		String name = "off" + offline.toString().substring(0, 8);
		DiscordUser user = link(helper, offline, name, Nodes.HISTORY, Nodes.BAN);

		Harness.check(helper, !DiscordAccess.check(user, DiscordOperation.VIEW_HISTORY).join().allowed(),
				"an offline account with no group was allowed something");

		groups.players.put(offline.toString(), "helper");
		try {
			Harness.check(helper, DiscordAccess.check(user, DiscordOperation.VIEW_HISTORY).join().allowed(),
					"an offline helper was refused a node their group grants");
			Harness.check(helper, !DiscordAccess.check(user, DiscordOperation.BAN).join().allowed(),
					"an offline helper was allowed a ban");
		} finally {
			groups.players.remove(offline.toString());
		}
		helper.succeed();
	}

	@GameTest
	public void aBannedAccountCanDoNothingFromDiscord(GameTestHelper helper) {
		PermissionGroups groups = PermissionGroups.get();
		UUID rogue = UUID.randomUUID();
		String name = "rog" + rogue.toString().substring(0, 8);
		DiscordUser user = link(helper, rogue, name, Nodes.HISTORY, Nodes.UNPUNISH);
		groups.players.put(rogue.toString(), "admin");
		try {
			Harness.check(helper, DiscordAccess.check(user, DiscordOperation.UNBAN).join().allowed(),
					"the account could not act before the ban, so the test proves nothing");

			Mods.punish().apply(Harness.server(helper), new NameAndId(rogue, name), "Console",
					PunishmentType.BAN, null, "gametest: rogue staff", null, null, Actor.console());

			var decision = DiscordAccess.check(user, DiscordOperation.UNBAN).join();
			Harness.check(helper, !decision.allowed(),
					"a banned staff member could still act from Discord — including unbanning themselves");
			Harness.check(helper, decision.refusal().contains("banned"), decision.refusal());
		} finally {
			groups.players.remove(rogue.toString());
			Mods.punish().revoke(Harness.server(helper), rogue, "Console", true, "gametest");
		}
		helper.succeed();
	}

	@GameTest
	public void anUnlinkedAccountHoldsNothingWhateverItsRoles(GameTestHelper helper) {
		DiscordUser stranger = new DiscordUser(snowflake(), "stranger", Actor.all());
		for (DiscordOperation operation : DiscordOperation.values()) {
			Harness.check(helper, !DiscordAccess.check(stranger, operation).join().allowed(),
					"an unlinked Discord account was allowed " + operation);
		}
		Harness.check(helper, !DiscordAccess.standing(stranger).join().linked(), "reported as linked");
		helper.succeed();
	}

	@GameTest
	public void ipBansRollbacksAndInventoryEditsRefuseDiscordWhateverItHolds(GameTestHelper helper) {
		ServerPlayer admin = Harness.namedPlayer(helper);
		ServerPlayer target = Harness.namedPlayer(helper);
		PermissionGroups groups = PermissionGroups.get();
		groups.players.put(admin.getUUID().toString(), "admin");
		try {
			DiscordUser user = link(helper, admin.getUUID(), Harness.name(admin),
					Actor.all().toArray(String[]::new));
			Actor fromDiscord = DiscordGate.resolve(Harness.server(helper), user).actor();
			Harness.check(helper, fromDiscord.has(Nodes.IP_BAN) && fromDiscord.has(Nodes.ROLLBACK)
							&& fromDiscord.has(Nodes.INVSEE_EDIT),
					"the Discord actor should hold every node here, so the refusals below are about the "
							+ "channel and nothing else: " + fromDiscord.nodes());

			AddressBans.Outcome ipBan = Mods.punish().addressBans().request(Harness.server(helper), fromDiscord,
					new NameAndId(target.getUUID(), Harness.name(target)), null, "gametest", null);
			Harness.checkEquals(helper, AddressBans.Outcome.Kind.REFUSED, ipBan.kind(), "an IP ban from Discord");

			BlockPos centre = helper.absolutePos(new BlockPos(1, 2, 1));
			var rollback = Mods.grief().rollback(helper.getLevel(), null, centre, 3, 60_000L, false, fromDiscord);
			Harness.check(helper, rollback.reverted() == 0 && rollback.proposed().isEmpty(),
					"a rollback ran from Discord");

			var edit = InventoryGateway.give(target, InventoryGateway.Origin.INVSEE_EDIT, fromDiscord,
					"gametest", List.of(new ItemStack(Items.DIAMOND)));
			Harness.check(helper, !edit.applied() && edit.refused().contains("Discord"),
					"an inventory edit ran from Discord: " + edit);
			Harness.check(helper, !target.getInventory().contains(new ItemStack(Items.DIAMOND)),
					"the refused edit still put the item in the inventory");
		} finally {
			groups.players.remove(admin.getUUID().toString());
		}
		helper.succeed();
	}

	@GameTest
	public void aLinkIsAttributedToTheAccountAndNeverRecordsAnAddress(GameTestHelper helper) throws Exception {
		ServerPlayer staff = Harness.namedPlayer(helper);
		link(helper, staff.getUUID(), Harness.name(staff));

		try (PreparedStatement ps = StaffCore.storage().conn().prepareStatement(
				"SELECT staff_uuid, staff_ip, command FROM command_log WHERE staff_name=? "
						+ "AND command LIKE 'discord link%' ORDER BY id DESC LIMIT 1")) {
			ps.setString(1, Harness.name(staff));
			try (ResultSet rs = ps.executeQuery()) {
				Harness.check(helper, rs.next(), "linking left no audit row");
				Harness.checkEquals(helper, staff.getUUID().toString(), rs.getString("staff_uuid"),
						"the account the link is attributed to");
				// Online at the time, with a connection that has an address — and still none recorded,
				// because the request did not come from that connection.
				Harness.check(helper, rs.getString("staff_ip") == null,
						"an address was recorded against a Discord action");
				Harness.check(helper, rs.getString("command").contains("dc_"),
						"the Discord user is not named in the row: " + rs.getString("command"));
			}
		}
		helper.succeed();
	}
}
