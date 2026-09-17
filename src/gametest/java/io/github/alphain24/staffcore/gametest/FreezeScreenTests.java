package io.github.alphain24.staffcore.gametest;

import io.github.alphain24.staffcore.module.Mods;
import io.github.alphain24.staffcore.modules.freeze.FreezeScreen;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ClientboundClearTitlesPacket;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;

import java.util.List;
import java.util.Locale;

/**
 * A frozen player's screen: dark, with the freeze written across it, and nothing about it in chat.
 */
public class FreezeScreenTests {

	private static boolean titled(List<Object> sent, String text) {
		return sent.stream().anyMatch(p -> p instanceof ClientboundSetTitleTextPacket title
				&& title.text().getString().equals(text));
	}

	private static boolean chatMentionsFreezing(List<Object> sent) {
		return sent.stream().anyMatch(p -> p instanceof ClientboundSystemChatPacket chat
				&& chat.content().getString().toLowerCase(Locale.ROOT).contains("frozen"));
	}

	@GameTest
	public void freezingDarkensTheScreenAndWritesAcrossIt(GameTestHelper helper) {
		ServerPlayer player = Harness.namedPlayer(helper);
		Harness.sent(player);
		try {
			Harness.check(helper, Mods.freeze().toggle(player), "the player was not frozen");
			List<Object> sent = Harness.sent(player);
			Harness.check(helper, player.hasEffect(MobEffects.BLINDNESS), "a frozen player can still see");
			Harness.check(helper, titled(sent, "YOU ARE FROZEN"), "no title was sent: " + sent);
			Harness.check(helper, sent.stream().anyMatch(p -> p instanceof ClientboundSetSubtitleTextPacket sub
					&& sub.text().getString().contains("staff")), "the subtitle does not say to contact staff");
			Harness.check(helper, !chatMentionsFreezing(sent), "the old freeze text is still sent to chat");

			Mods.freeze().toggle(player);
			sent = Harness.sent(player);
			Harness.check(helper, !player.hasEffect(MobEffects.BLINDNESS), "the blindness outlived the freeze");
			Harness.check(helper, sent.stream().anyMatch(p -> p instanceof ClientboundClearTitlesPacket),
					"the title was left on the screen after unfreezing");
		} finally {
			if (Mods.freeze().isFrozen(player)) Mods.freeze().toggle(player);
		}
		helper.succeed();
	}

	@GameTest(maxTicks = 200)
	public void theScreenStaysWhileFrozenAndCarriesTheInvite(GameTestHelper helper) {
		ServerPlayer player = Harness.namedPlayer(helper);
		Harness.sent(player);
		Mods.freeze().toggle(player);
		Harness.check(helper, !chatMentionsFreezing(Harness.sent(player)), "the old freeze text is still sent to chat");

		// The configured invite is not changed here, since every test reads it at once; the screen is
		// given one directly instead.
		String invite = "discord.gg/staffcore-test";
		FreezeScreen.show(player, invite);
		List<Object> shown = Harness.sent(player);
		Harness.check(helper, shown.stream().anyMatch(p -> p instanceof ClientboundSetActionBarTextPacket bar
				&& bar.text().getString().contains(invite)), "the invite is not above the hotbar");
		Harness.check(helper, shown.stream().anyMatch(p -> p instanceof ClientboundSetSubtitleTextPacket sub
				&& sub.text().getString().contains("Discord")), "the subtitle does not say to join the Discord");
		var line = FreezeScreen.inviteLine(invite);
		Harness.check(helper, line != null && line.getString().contains(invite), "there is no invite line to click");
		Harness.check(helper, FreezeScreen.inviteLine("") == null, "an empty invite still produced a chat line");

		// Longer than a blindness lasts and longer than a title stays: both have to have been sent again.
		helper.runAfterDelay(90, () -> {
			try {
				Harness.check(helper, player.hasEffect(MobEffects.BLINDNESS), "the blindness ran out while frozen");
				Harness.check(helper, titled(Harness.sent(player), "YOU ARE FROZEN"),
						"the title was not sent again while frozen");
			} finally {
				Mods.freeze().toggle(player);
			}
			helper.succeed();
		});
	}

	@GameTest
	public void blindnessThePlayerHadOfTheirOwnIsLeftAlone(GameTestHelper helper) {
		ServerPlayer player = Harness.namedPlayer(helper);
		player.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 2400));
		Mods.freeze().toggle(player);
		Mods.freeze().toggle(player);
		Harness.check(helper, player.hasEffect(MobEffects.BLINDNESS),
				"unfreezing took away blindness that did not come from the freeze");
		player.removeEffect(MobEffects.BLINDNESS);
		helper.succeed();
	}
}
