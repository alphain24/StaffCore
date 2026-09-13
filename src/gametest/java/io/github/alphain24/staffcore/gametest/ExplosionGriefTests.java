package io.github.alphain24.staffcore.gametest;

import io.github.alphain24.staffcore.compat.Mc;
import io.github.alphain24.staffcore.module.Mods;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.TntBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Explosions counted toward mass grief, through real blasts on a real server.
 * <p>
 * The counting is read back through {@code /staff grief test}'s progress, which is per player
 * — so each test watches its own mock player's count and nothing a neighbouring test blows up
 * can move it. The detector itself cannot be checked the same way without lowering its bar,
 * and gametests do not write the config.
 */
public class ExplosionGriefTests {

	/** A 3×3×3 cube of dirt, weak enough that a small blast is guaranteed to take some. */
	private static Vec3 dirtCube(GameTestHelper helper) {
		for (int x = 1; x <= 3; x++) {
			for (int y = 2; y <= 4; y++) {
				for (int z = 1; z <= 3; z++) {
					helper.setBlock(new BlockPos(x, y, z), Blocks.DIRT);
				}
			}
		}
		return Vec3.atCenterOf(helper.absolutePos(new BlockPos(2, 3, 2)));
	}

	@GameTest
	public void aBlastCountsForThePlayerInItsDamageSource(GameTestHelper helper) {
		// The end-crystal shape: nothing is the "source" a player can be read from, and the
		// player who hit the crystal is only in the damage source. Before, this blast was on
		// "#end_crystal" and counted toward nobody.
		ServerPlayer player = Harness.mockPlayer(helper);
		ServerLevel level = helper.getLevel();
		Vec3 centre = dirtCube(helper);
		Mods.grief().rehearse(player, 100);

		level.explode(null, level.damageSources().explosion(null, player), null,
				centre.x, centre.y, centre.z, 2.0F, false, Level.ExplosionInteraction.BLOCK);

		int counted = Mods.grief().rehearsalProgress(player.getUUID());
		Harness.check(helper, counted > 0,
				"the blast destroyed dirt and none of it was counted for the player who caused it"
						+ " (progress " + counted + ")");
		helper.succeed();
	}

	@GameTest
	public void aWindChargeBlastCountsNothing(GameTestHelper helper) {
		// Same cube, same power, the player as the direct source — everything that would get
		// it counted — except the interaction is TRIGGER, which is what a wind charge is.
		// It destroys nothing, so it must count nothing and log nothing.
		ServerPlayer player = Harness.mockPlayer(helper);
		ServerLevel level = helper.getLevel();
		Vec3 centre = dirtCube(helper);
		Mods.grief().rehearse(player, 100);

		level.explode(player, null, null, centre.x, centre.y, centre.z, 2.0F, false,
				Level.ExplosionInteraction.TRIGGER);

		Harness.checkEquals(helper, 0, Mods.grief().rehearsalProgress(player.getUUID()),
				"blocks a wind charge only pushed against were counted as destroyed");
		helper.succeed();
	}

	@GameTest
	public void redstoneLitTntIsOnWhoeverPlacedIt(GameTestHelper helper) {
		ServerPlayer player = Harness.mockPlayer(helper);
		ServerLevel level = helper.getLevel();
		BlockPos pos = helper.absolutePos(new BlockPos(2, 2, 2));

		// What the block-place hook reports when a player puts TNT down.
		Mods.grief().logPlace(player, pos, Blocks.TNT.defaultBlockState(), Mc.dimensionId(level));
		// Primed with no igniter, which is what redstone and fire do.
		Harness.check(helper, TntBlock.prime(level, pos), "the TNT did not prime");

		List<PrimedTnt> primed = level.getEntitiesOfClass(PrimedTnt.class, new AABB(pos).inflate(1));
		Harness.checkEquals(helper, 1, primed.size(), "primed TNT entities at the block");
		PrimedTnt tnt = primed.get(0);

		// Read before discarding, and discarded before it can go off: a real TNT blast in a
		// shared gametest world would reach whatever the next test area is building.
		String blamed = Mods.grief().primedTntBlame(tnt.getUUID());
		tnt.discard();

		Harness.check(helper, tnt.getOwner() == null,
				"the TNT was given an owner, which would change vanilla kill credit");
		Harness.checkEquals(helper, Harness.name(player), blamed,
				"who the redstone-lit TNT was attributed to");
		helper.succeed();
	}
}
