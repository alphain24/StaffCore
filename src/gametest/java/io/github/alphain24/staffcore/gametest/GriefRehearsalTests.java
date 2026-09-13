package io.github.alphain24.staffcore.gametest;

import io.github.alphain24.staffcore.module.Mods;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;

/**
 * {@code /staff grief test}, driven by real block breaks.
 * <p>
 * The window arithmetic is tested headless. What only a server can show is the part a staff
 * member is actually relying on when they run it: that a block broken through the game's own
 * break path reaches the counter. A test fed its breaks directly would pass with the break
 * hook detached, which is the one failure worth catching.
 */
public class GriefRehearsalTests {

	@GameTest
	public void realBreaksCarryTheTestToTheBar(GameTestHelper helper) {
		ServerPlayer staff = Harness.mockPlayer(helper);
		BlockPos[] stones = {new BlockPos(1, 2, 1), new BlockPos(2, 2, 1), new BlockPos(3, 2, 1)};
		for (BlockPos stone : stones) helper.setBlock(stone, Blocks.STONE);

		Mods.grief().rehearse(staff, 3);
		Harness.checkEquals(helper, 0, Mods.grief().rehearsalProgress(staff.getUUID()),
				"a freshly started test");

		for (int i = 0; i < 2; i++) {
			staff.gameMode.destroyBlock(helper.absolutePos(stones[i]));
			// Without this the counter could be right about a break that never happened.
			Harness.check(helper, helper.getBlockState(stones[i]).isAir(),
					"the stone is still there, so the break did not go through the game");
			Harness.checkEquals(helper, i + 1, Mods.grief().rehearsalProgress(staff.getUUID()),
					"breaks counted after break " + (i + 1));
		}

		staff.gameMode.destroyBlock(helper.absolutePos(stones[2]));
		Harness.checkEquals(helper, -1, Mods.grief().rehearsalProgress(staff.getUUID()),
				"the test should be over once the third break reached the bar");
		helper.succeed();
	}
}
