package io.github.alphain24.staffcore.gametest;

import io.github.alphain24.staffcore.module.Mods;
import io.github.alphain24.staffcore.modules.appeal.AppealModule;
import io.github.alphain24.staffcore.modules.punish.PunishmentType;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;

/**
 * An appeal is an appeal of something.
 */
public class AppealTests {

	@GameTest
	public void nobodyCanAppealWithNothingAgainstThem(GameTestHelper helper) {
		ServerPlayer player = Harness.mockPlayer(helper);

		Harness.checkEquals(helper, AppealModule.Result.NOTHING_TO_APPEAL,
				Mods.appeals().file(player.getUUID(), Harness.name(player), "unban me pls"),
				"an appeal from a player with no punishment");
		helper.succeed();
	}

	@GameTest
	public void aMutedPlayerCanAppealTheirMuteOnce(GameTestHelper helper) {
		ServerPlayer player = Harness.mockPlayer(helper);
		var mute = Mods.punish().apply(Harness.server(helper), player.nameAndId(), "CONSOLE",
				PunishmentType.MUTE, 600_000L, "gametest mute", null, null,
				io.github.alphain24.staffcore.permission.Actor.console());
		Harness.check(helper, mute != null, "the test mute was not stored");

		Harness.checkEquals(helper, AppealModule.Result.OK,
				Mods.appeals().file(player.getUUID(), Harness.name(player), "I was only joking"),
				"an appeal against an active mute");
		Harness.checkEquals(helper, AppealModule.Result.ALREADY_OPEN,
				Mods.appeals().file(player.getUUID(), Harness.name(player), "again"),
				"a second appeal while the first is open");
		helper.succeed();
	}
}
