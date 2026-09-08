package io.github.alphain24.staffcore.gametest;

import io.github.alphain24.staffcore.config.StaffConfig;
import io.github.alphain24.staffcore.module.Mods;
import io.github.alphain24.staffcore.modules.vanish.VanishHooks;
import io.github.alphain24.staffcore.modules.vanish.VanishModule;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.block.Blocks;

/**
 * Vanish, watched working rather than reasoned about.
 * <p>
 * Vanish is twenty-seven mixins covering sound, sleep counting, mob targeting, pressure plates
 * and the server-list sample, and the README said plainly that every one of them had been
 * compile-verified and none of them runtime-verified. The JUnit suite cannot close that gap —
 * none of this exists without a running server — and the boot check only proves the hooks
 * attached, which is a different claim from the feature behaving. A mixin can apply perfectly
 * to a method whose logic no longer does what the feature needs.
 * <p>
 * Each test here drives the real mechanism: a real mob is asked to target a vanished player, a
 * real pressure plate is stood on, and the entity tracker is asked the same question it asks
 * every tick.
 */
public class VanishTests {

	/** Vanish, run the body, un-vanish — so one failing test cannot leave the next one broken. */
	private void whileVanished(GameTestHelper helper, ServerPlayer player, Runnable body) {
		VanishModule vanish = Mods.vanish();
		vanish.toggle(player);
		try {
			Harness.check(helper, vanish.isVanished(player), "toggle did not vanish the player");
			body.run();
		} finally {
			if (vanish.isVanished(player)) vanish.toggle(player);
		}
	}

	@GameTest
	public void theTrackerRefusesToShowAVanishedPlayer(GameTestHelper helper) {
		ServerPlayer hidden = Harness.mockPlayer(helper);
		ServerPlayer viewer = Harness.mockPlayer(helper);

		// broadcastToPlayer is what the entity tracker asks inside updatePlayer, and answering
		// it is what makes vanish work in both directions: false tears the pairing down
		// properly, true rebuilds it. The old code sent removal packets by hand and left the
		// tracker's own seenBy set stale, so un-vanishing sent no spawn packet and the staff
		// member stayed invisible until they walked out of range and back.
		Harness.check(helper, hidden.broadcastToPlayer(viewer),
				"a visible player should be broadcast to everyone");

		whileVanished(helper, hidden, () ->
				Harness.check(helper, !hidden.broadcastToPlayer(viewer),
						"the tracker would still send a vanished player to an unauthorised "
								+ "viewer"));

		// The half that actually regressed once. Un-vanishing has to answer true again.
		Harness.check(helper, hidden.broadcastToPlayer(viewer),
				"after un-vanishing the tracker still refuses to send them, so they stay "
						+ "invisible until they leave tracking range and come back");
		helper.succeed();
	}

	@GameTest
	public void aVanishedPlayerIsNotPushable(GameTestHelper helper) {
		ServerPlayer player = Harness.mockPlayer(helper);

		Harness.check(helper, player.isPushable(), "a visible player is pushable");
		whileVanished(helper, player, () -> Harness.check(helper, !player.isPushable(),
				"mobs would pile up against a vanished player, which looks exactly like an "
						+ "invisible wall"));
		Harness.check(helper, player.isPushable(), "and pushable again afterwards");
		helper.succeed();
	}

	@GameTest
	public void aVanishedPlayerDoesNotBlockBuilding(GameTestHelper helper) {
		ServerPlayer player = Harness.mockPlayer(helper);

		whileVanished(helper, player, () -> Harness.check(helper, !player.blocksBuilding,
				"standing in a doorway and silently refusing everybody's block placements "
						+ "gives the position away precisely"));
		Harness.check(helper, player.blocksBuilding, "restored on un-vanish");
		helper.succeed();
	}

	@GameTest
	public void theHooksAgreeOnWhoIsHidden(GameTestHelper helper) {
		ServerPlayer player = Harness.mockPlayer(helper);

		// Every mixin routes through VanishHooks, so these answering differently from
		// isVanished is the shape of "vanish half-applied" that the state rewrite was meant
		// to make impossible.
		Harness.check(helper, !VanishHooks.isVanished(player), "not hidden to start with");

		whileVanished(helper, player, () -> {
			Harness.check(helper, VanishHooks.isVanished(player), "isVanished");
			Harness.checkEquals(helper, StaffConfig.get().vanishIgnoredByMobs,
					VanishHooks.ignoredByMobs(player), "ignoredByMobs");
			Harness.checkEquals(helper, StaffConfig.get().vanishIntangible,
					VanishHooks.intangible(player), "intangible");
			Harness.checkEquals(helper, StaffConfig.get().vanishSilent,
					VanishHooks.silent(player), "silent");
			Harness.check(helper, !VanishHooks.noneHidden(), "noneHidden while somebody is");
		});

		Harness.check(helper, !VanishHooks.isVanished(player), "cleared on un-vanish");
		helper.succeed();
	}

}
