package io.github.alphain24.staffcore.gametest;

import io.github.alphain24.staffcore.api.StaffCoreApi;
import io.github.alphain24.staffcore.api.StaffCoreEvent;
import io.github.alphain24.staffcore.api.StaffCoreListener;
import io.github.alphain24.staffcore.api.internal.EventBus;
import io.github.alphain24.staffcore.module.Mods;
import io.github.alphain24.staffcore.modules.punish.Punishment;
import io.github.alphain24.staffcore.modules.punish.PunishmentType;
import io.github.alphain24.staffcore.modules.punish.ReturnWatch;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Staff are told once when somebody joins for the first time after a ban of theirs ended.
 * <p>
 * The bans are recorded rather than enforced, because enforcing one on a connected player
 * disconnects them, and the join is called directly because a test player's join is not the
 * network's.
 */
public class ReturnWatchTests {

	private static List<StaffCoreEvent.PlayerReturned> returns(Runnable work) {
		List<StaffCoreEvent.PlayerReturned> seen = new CopyOnWriteArrayList<>();
		StaffCoreListener listener = e -> {
			if (e instanceof StaffCoreEvent.PlayerReturned r) seen.add(r);
		};
		StaffCoreApi.addListener(listener);
		try {
			work.run();
			EventBus.drain(5000);
		} finally {
			StaffCoreApi.removeListener(listener);
		}
		return seen;
	}

	@GameTest
	public void aBanThatRanOutIsAnnouncedOnTheFirstJoinOnly(GameTestHelper helper) {
		ServerPlayer player = Harness.namedPlayer(helper);
		String name = Harness.name(player);
		long hourAgo = System.currentTimeMillis() - 3_600_000L;
		Punishment ban = Mods.punish().record(player.getUUID(), name, "Mod", PunishmentType.TEMPBAN, "x-ray",
				hourAgo, null);

		ReturnWatch.Ended[] first = new ReturnWatch.Ended[1];
		var seen = returns(() -> first[0] = ReturnWatch.onJoin(Harness.server(helper), player));
		Harness.check(helper, first[0] != null && first[0].ban().id() == ban.id() && !first[0].lifted(),
				"the ban that ran out was not announced: " + first[0]);
		Harness.check(helper, first[0].describe().startsWith("expired"), "the line does not say it ran out: "
				+ first[0].describe());
		Harness.check(helper, seen.size() == 1 && "EXPIRED".equals(seen.get(0).howEnded())
				&& seen.get(0).punishmentId() == ban.id() && name.equals(seen.get(0).playerName()),
				"no PlayerReturned for the companion: " + seen);

		var again = returns(() -> Harness.check(helper, ReturnWatch.onJoin(Harness.server(helper), player) == null,
				"the second join announced the same ban again"));
		Harness.check(helper, again.isEmpty(), "the second join published again");
		helper.succeed();
	}

	@GameTest
	public void aLiftedBanSaysWhoLiftedItAndWhy(GameTestHelper helper) {
		ServerPlayer player = Harness.namedPlayer(helper);
		Punishment ban = Mods.punish().record(player.getUUID(), Harness.name(player), "Mod", PunishmentType.BAN,
				"griefing", null, null);
		Mods.punish().revoke(Harness.server(helper), player.getUUID(), "Admin", true, "appeal #3 accepted");

		ReturnWatch.Ended ended = ReturnWatch.onJoin(Harness.server(helper), player);
		Harness.check(helper, ended != null && ended.lifted() && ended.ban().id() == ban.id(),
				"the lifted ban was not announced as lifted: " + ended);
		Harness.check(helper, ended.describe().contains("lifted by Admin") && ended.describe().contains("appeal #3"),
				"the line does not say who lifted it and why: " + ended.describe());
		helper.succeed();
	}

	@GameTest
	public void nothingIsSaidForABanInForceOrForAMute(GameTestHelper helper) {
		ServerPlayer player = Harness.namedPlayer(helper);
		String name = Harness.name(player);
		// A mute that ran out is not a return: a muted player was never kept out.
		Mods.punish().record(player.getUUID(), name, "Mod", PunishmentType.MUTE, "spam",
				System.currentTimeMillis() - 1000, null);
		// A ban still in force is not over. (Recorded without enforcement; the player stays connected.)
		Punishment standing = Mods.punish().record(player.getUUID(), name, "Mod", PunishmentType.TEMPBAN, "grief",
				System.currentTimeMillis() + 3_600_000L, null);
		try {
			Harness.check(helper, ReturnWatch.onJoin(Harness.server(helper), player) == null,
					"a join was announced with no ban over");
		} finally {
			Mods.punish().revoke(Harness.server(helper), player.getUUID(), "Console", true, "gametest");
		}
		// Lifting it now makes it a return, once.
		ReturnWatch.Ended ended = ReturnWatch.onJoin(Harness.server(helper), player);
		Harness.check(helper, ended != null && ended.ban().id() == standing.id(),
				"lifting the ban did not make the next join a return: " + ended);
		helper.succeed();
	}
}
