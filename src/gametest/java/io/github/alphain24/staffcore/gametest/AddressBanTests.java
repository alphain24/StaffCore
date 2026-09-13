package io.github.alphain24.staffcore.gametest;

import io.github.alphain24.staffcore.StaffCore;
import io.github.alphain24.staffcore.module.Mods;
import io.github.alphain24.staffcore.modules.identity.AddressPrivacy;
import io.github.alphain24.staffcore.modules.punish.AddressBans;
import io.github.alphain24.staffcore.permission.Actor;
import io.github.alphain24.staffcore.permission.Nodes;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.players.NameAndId;

import java.net.InetSocketAddress;
import java.sql.PreparedStatement;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * IP bans, through the real login gate.
 * <p>
 * Nobody here actually connects: the gate is {@code PlayerList.canPlayerLogin}, which takes an
 * address and a profile and answers with a refusal screen or nothing, so it is asked directly
 * with the address a connection would have arrived from.
 */
public class AddressBanTests {

	/** An address from the documentation range, different every run so old rows never match. */
	private static String freshAddress() {
		ThreadLocalRandom random = ThreadLocalRandom.current();
		return "203.0." + random.nextInt(1, 255) + "." + random.nextInt(1, 255);
	}

	/** What IdentityModule records on a join, for an account that never really joined. */
	private static NameAndId joinedFrom(String address) throws Exception {
		NameAndId who = new NameAndId(UUID.randomUUID(),
				("ip" + UUID.randomUUID().toString().replace("-", "")).substring(0, 12));
		var conn = StaffCore.storage().conn();
		try (PreparedStatement ps = conn.prepareStatement("""
				INSERT INTO connections (uuid, name, ip, ip_prefix, first_seen, last_seen, joins)
				VALUES (?,?,?,?,?,?,1)
				""")) {
			long now = System.currentTimeMillis();
			ps.setString(1, who.id().toString());
			ps.setString(2, who.name());
			ps.setString(3, AddressPrivacy.store(conn, address));
			ps.setString(4, AddressPrivacy.storePrefix(conn, address));
			ps.setLong(5, now);
			ps.setLong(6, now);
			ps.executeUpdate();
		}
		return who;
	}

	/**
	 * Whether the gate refused because of an address ban. Vanilla may refuse for its own reasons
	 * after StaffCore lets somebody through — a gametest server full of mock players is full —
	 * and that is not what these tests are about.
	 */
	private static boolean refusedByAddress(net.minecraft.server.MinecraftServer server,
			String address, NameAndId who) {
		var refusal = server.getPlayerList().canPlayerLogin(new InetSocketAddress(address, 25565), who);
		return refusal != null && refusal.getString().contains("This connection is banned");
	}

	private static Actor approver() {
		return Actor.of(UUID.randomUUID(), "approver", Actor.Source.PLAYER,
				Set.of(Nodes.APPROVE, Nodes.IP_BAN));
	}

	@GameTest
	public void anApprovedIpBanRefusesAnotherAccountOnThatConnection(GameTestHelper helper)
			throws Exception {
		var server = Harness.server(helper);
		String address = freshAddress();
		NameAndId banned = joinedFrom(address);
		NameAndId sibling = new NameAndId(UUID.randomUUID(), "ipsibling");

		var outcome = Mods.punish().addressBans().request(server, Actor.console(), banned, null,
				"gametest ip ban", null);
		AddressBans.AddressBan ban;
		if (outcome.kind() == AddressBans.Outcome.Kind.STAGED) {
			Harness.check(helper, !refusedByAddress(server, address, sibling),
					"a staged IP ban refused a login before anybody approved it");
			var approved = Mods.accountability().approvals().approve(approver(),
					outcome.staged().id());
			Harness.check(helper, approved.approved(), "the approval was refused: " + approved.refusal());
			Mods.accountability().approvals().run(approved, approver());
			ban = Mods.punish().addressBans().forSource(banned.id()).stream()
					.filter(AddressBans.AddressBan::inForce).findFirst().orElse(null);
		} else {
			Harness.check(helper, outcome.kind() == AddressBans.Outcome.Kind.BANNED,
					"the IP ban was refused: " + outcome.message());
			ban = outcome.ban();
		}
		Harness.check(helper, ban != null, "approving the staged IP ban did not ban anything");

		var refusal = server.getPlayerList().canPlayerLogin(new InetSocketAddress(address, 25565),
				sibling);
		Harness.check(helper, refusal != null && refusal.getString().contains("#" + ban.id()),
				"another account on the banned connection was let in: " + refusal);
		String elsewhere = freshAddress();
		while (elsewhere.equals(address)) elsewhere = freshAddress();
		Harness.check(helper, !refusedByAddress(server, elsewhere, sibling),
				"a different connection was refused");
		Harness.check(helper, Mods.punish().activeBan(banned.id()) != null,
				"the account the IP ban was taken from is not itself banned");
		Harness.check(helper, Mods.punish().addressBans().byId(ban.id()).refused() >= 1,
				"the refused login was not counted");

		// Unbanning the person lifts the connection ban taken from them.
		Mods.punish().revoke(server, banned.id(), "gametest", true);
		Harness.check(helper, !refusedByAddress(server, address, sibling),
				"unbanning the player left their connection banned");
		helper.succeed();
	}

	@GameTest
	public void theServersOwnAddressCannotBeIpBanned(GameTestHelper helper) throws Exception {
		// Behind a proxy that does not forward addresses, everybody is 127.0.0.1.
		NameAndId local = joinedFrom("127.0.0.1");
		var check = Mods.punish().addressBans().check(Harness.server(helper), local);
		Harness.check(helper, check.refusal() != null, "a loopback address was accepted for an IP ban");
		helper.succeed();
	}

	@GameTest
	public void aConnectionManyAccountsShareCannotBeIpBanned(GameTestHelper helper) throws Exception {
		String address = freshAddress();
		NameAndId first = joinedFrom(address);
		for (int i = 0; i < 10; i++) joinedFrom(address);

		var check = Mods.punish().addressBans().check(Harness.server(helper), first);
		Harness.check(helper, check.refusal() != null,
				"an address eleven accounts joined from was accepted for an IP ban");
		helper.succeed();
	}
}
