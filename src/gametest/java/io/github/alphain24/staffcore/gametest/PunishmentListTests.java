package io.github.alphain24.staffcore.gametest;

import io.github.alphain24.staffcore.module.Mods;
import io.github.alphain24.staffcore.modules.punish.Punishment;
import io.github.alphain24.staffcore.modules.punish.PunishmentType;
import io.github.alphain24.staffcore.permission.Actor;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.players.NameAndId;

import java.util.List;
import java.util.UUID;

/**
 * The lists behind "who punished whom" and "who is banned".
 */
public class PunishmentListTests {

	private static NameAndId somebody() {
		return new NameAndId(UUID.randomUUID(),
				("pl" + UUID.randomUUID().toString().replace("-", "")).substring(0, 12));
	}

	private static Punishment issue(GameTestHelper helper, String staff, NameAndId target,
			PunishmentType type, Long durationMs) {
		Punishment p = Mods.punish().apply(Harness.server(helper), target, staff, type, durationMs,
				"list test", null, null, Actor.console());
		Harness.check(helper, p != null, "the test " + type + " was not stored");
		return p;
	}

	@GameTest
	public void issuedByListsOneStaffMembersPunishmentsNewestFirst(GameTestHelper helper) {
		String staff = ("st" + UUID.randomUUID().toString().replace("-", "")).substring(0, 12);
		Punishment warn = issue(helper, staff, somebody(), PunishmentType.WARN, null);
		sleepAMillisecond();
		Punishment ban = issue(helper, staff, somebody(), PunishmentType.BAN, null);
		issue(helper, "someoneElse", somebody(), PunishmentType.WARN, null);

		List<Long> ids = Mods.punish().issuedBy(staff, 50).stream().map(Punishment::id).toList();
		Harness.checkEquals(helper, List.of(ban.id(), warn.id()), ids, "what " + staff + " issued");

		var issuer = Mods.punish().issuers().stream()
				.filter(i -> i.staffName().equalsIgnoreCase(staff)).findFirst().orElse(null);
		Harness.check(helper, issuer != null && issuer.issued() == 2 && issuer.inForce() == 1,
				"the issuer summary is wrong: " + issuer);
		helper.succeed();
	}

	@GameTest
	public void bansInForceDropLiftedAndExpiredBans(GameTestHelper helper) {
		String staff = ("sb" + UUID.randomUUID().toString().replace("-", "")).substring(0, 12);
		NameAndId standing = somebody();
		NameAndId lifted = somebody();
		NameAndId expired = somebody();

		Punishment stands = issue(helper, staff, standing, PunishmentType.BAN, null);
		issue(helper, staff, lifted, PunishmentType.BAN, null);
		issue(helper, staff, expired, PunishmentType.BAN, 1L);
		issue(helper, staff, somebody(), PunishmentType.MUTE, null);
		Mods.punish().revoke(Harness.server(helper), lifted.id(), "gametest", true);
		sleepAMillisecond();
		sleepAMillisecond();

		List<Long> bans = Mods.punish().bansInForce(staff, 50).stream().map(Punishment::id).toList();
		Harness.checkEquals(helper, List.of(stands.id()), bans,
				"bans in force by " + staff + " (not the lifted one, the expired one or the mute)");
		helper.succeed();
	}

	private static void sleepAMillisecond() {
		long start = System.currentTimeMillis();
		while (System.currentTimeMillis() == start) Thread.onSpinWait();
	}
}
