package io.github.alphain24.staffcore.gametest;

import io.github.alphain24.staffcore.compat.Mc;
import io.github.alphain24.staffcore.module.Mods;
import io.github.alphain24.staffcore.modules.teleport.TeleportLog;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * Teleports are recorded whatever moved the player, and StaffCore's own say who did it.
 */
public class TeleportHistoryTests {

	private static List<TeleportLog.Entry> historyOf(ServerPlayer player) {
		Mods.grief().awaitWrites();
		return Mods.teleport().log().forPlayer(player.getUUID(), 20);
	}

	@GameTest
	public void aLongJumpIsRecordedAndAShortOneIsNot(GameTestHelper helper) {
		ServerPlayer player = Harness.namedPlayer(helper);
		var server = Harness.server(helper);
		TeleportLog log = Mods.teleport().log();

		log.tick(server);
		Mc.teleport(player, helper.getLevel(), player.getX() + 5, player.getY(), player.getZ(), 0, 0);
		log.tick(server);
		Harness.check(helper, historyOf(player).isEmpty(), "a five-block step was recorded as a teleport");

		// A plain vanilla teleport, the way /tp or another mod would do it.
		player.teleportTo(helper.getLevel(), player.getX() + 100, player.getY(), player.getZ(),
				java.util.Set.of(), 0, 0, true);
		log.tick(server);
		List<TeleportLog.Entry> history = historyOf(player);
		Harness.check(helper, history.size() == 1, "a hundred-block jump was not recorded: " + history);
		TeleportLog.Entry jump = history.get(0);
		Harness.checkEquals(helper, TeleportLog.Cause.TELEPORT, jump.cause(), "the cause of an unexplained jump");
		Harness.check(helper, jump.distance() > 90 && jump.distance() < 110,
				"the recorded distance was " + jump.distance());
		helper.succeed();
	}

	@GameTest
	public void bringingSomebodyNamesTheStaffMemberOnBothHistories(GameTestHelper helper) {
		ServerPlayer staff = Harness.namedPlayer(helper);
		ServerPlayer target = Harness.namedPlayer(helper);
		var server = Harness.server(helper);
		TeleportLog log = Mods.teleport().log();

		Mc.teleport(staff, helper.getLevel(), staff.getX() + 60, staff.getY(), staff.getZ(), 0, 0);
		log.tick(server);
		Mods.teleport().bringHere(staff, target);
		log.tick(server);

		TeleportLog.Entry brought = historyOf(target).stream()
				.filter(e -> e.cause() == TeleportLog.Cause.STAFF_BRING).findFirst().orElse(null);
		Harness.check(helper, brought != null, "being brought by staff was not recorded as such: "
				+ historyOf(target));
		Harness.checkEquals(helper, Harness.name(staff), brought.actor(), "who brought them");
		Harness.check(helper, historyOf(staff).stream().anyMatch(e -> e.id() == brought.id()),
				"the staff member's own history does not show who they brought");
		helper.succeed();
	}
}
