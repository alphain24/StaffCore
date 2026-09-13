package io.github.alphain24.staffcore.gametest;

import io.github.alphain24.staffcore.module.Mods;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * A rollback takes back what the damage actually dropped, not the blocks it put back.
 * <p>
 * The failure these guard against is the one staff saw: a TNT crater in stone rolled back, the
 * stone put back, and every piece of cobblestone the blast threw still lying there — because
 * the rollback went looking for stone. Each test makes real drops, runs a real rollback, and
 * counts what is left on the ground.
 * <p>
 * Every rollback here is for everybody in a small radius rather than for one player. Mock
 * players all share a name, so a rollback "for" one would reach into whichever of them the
 * server finds first — another test's inventory.
 */
public class RollbackDropSweepTests {

	private static final int RADIUS = 6;

	private static Vec3 cube(GameTestHelper helper, Block block) {
		for (int x = 1; x <= 3; x++) {
			for (int y = 2; y <= 4; y++) {
				for (int z = 1; z <= 3; z++) {
					helper.setBlock(new BlockPos(x, y, z), block);
				}
			}
		}
		return Vec3.atCenterOf(helper.absolutePos(new BlockPos(2, 3, 2)));
	}

	private static int onGround(ServerLevel level, Vec3 centre, Item item) {
		int count = 0;
		for (ItemEntity drop : level.getEntitiesOfClass(ItemEntity.class,
				new AABB(centre, centre).inflate(RADIUS))) {
			if (drop.getItem().is(item)) count += drop.getItem().getCount();
		}
		return count;
	}

	private static void blastThenRollBack(GameTestHelper helper, Block block, Item drop,
			Level.ExplosionInteraction interaction, String what) {

		ServerPlayer player = Harness.mockPlayer(helper);
		ServerLevel level = helper.getLevel();
		Vec3 centre = cube(helper, block);

		level.explode(null, level.damageSources().explosion(null, player), null,
				centre.x, centre.y, centre.z, 2.0F, false, interaction);

		int dropped = onGround(level, centre, drop);
		Harness.check(helper, dropped > 0,
				"the " + what + " dropped no " + drop + ", so there is nothing to sweep and "
						+ "this proves nothing");

		Mods.grief().awaitWrites();
		var result = Mods.grief().rollback(level, null, BlockPos.containing(centre), RADIUS,
				60_000L, false);

		Harness.check(helper, result.reverted() > 0, "the rollback put nothing back");
		Harness.checkEquals(helper, 0, onGround(level, centre, drop),
				drop + " still on the ground after rolling back a " + what
						+ " (it dropped " + dropped + ")");
		helper.succeed();
	}

	@GameTest
	public void aTntCraterInStoneLeavesNoCobblestone(GameTestHelper helper) {
		// Stone drops cobblestone. The rollback used to look for stone.
		blastThenRollBack(helper, Blocks.STONE, Items.COBBLESTONE,
				Level.ExplosionInteraction.TNT, "TNT blast in stone");
	}

	@GameTest
	public void aCrystalStyleBlastLeavesNothingItDropped(GameTestHelper helper) {
		// Drop decay: a random few of the blocks come out as items. Those few still have to go.
		blastThenRollBack(helper, Blocks.DIRT, Items.DIRT,
				Level.ExplosionInteraction.BLOCK, "crystal-style blast");
	}

	@GameTest
	public void aBrokenStoneBlockLeavesNoCobblestone(GameTestHelper helper) {
		ServerPlayer player = Harness.mockPlayer(helper);
		ServerLevel level = helper.getLevel();
		BlockPos pos = helper.absolutePos(new BlockPos(2, 2, 2));
		BlockState stone = Blocks.STONE.defaultBlockState();
		helper.setBlock(new BlockPos(2, 2, 2), stone);
		Vec3 centre = Vec3.atCenterOf(pos);

		// A survival break, taken apart: the game hands out the loot, the block goes, and the
		// break event fires. A mock player is stuck in creative, which drops nothing, so the
		// steps are driven directly — through the same hooks a real break goes through.
		Block.dropResources(stone, level, pos, null, player, new ItemStack(Items.DIAMOND_PICKAXE));
		level.removeBlock(pos, false);
		PlayerBlockBreakEvents.AFTER.invoker().afterBlockBreak(level, player, pos, stone, null);

		Harness.check(helper, onGround(level, centre, Items.COBBLESTONE) > 0,
				"breaking stone dropped no cobblestone, so this proves nothing");

		Mods.grief().awaitWrites();
		var result = Mods.grief().rollback(level, null, pos, RADIUS, 60_000L, false);

		Harness.check(helper, result.reverted() > 0, "the rollback put nothing back");
		Harness.check(helper, level.getBlockState(pos).is(Blocks.STONE), "the stone did not come back");
		Harness.checkEquals(helper, 0, onGround(level, centre, Items.COBBLESTONE),
				"cobblestone still on the ground after rolling back a broken stone block");
		helper.succeed();
	}
}
