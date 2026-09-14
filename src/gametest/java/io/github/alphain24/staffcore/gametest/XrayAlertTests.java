package io.github.alphain24.staffcore.gametest;

import io.github.alphain24.staffcore.module.Mods;
import io.github.alphain24.staffcore.modules.cases.CaseCategory;
import io.github.alphain24.staffcore.modules.security.Canaries;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * The whole path, as a player on the server takes it: the game's own block breaking, the break
 * event, the scoring on the background worker, and the case at the end. Nothing here calls the
 * scoring directly.
 */
public class XrayAlertTests {

	private static List<BlockPos> slab(GameTestHelper helper, int width, int height, int depth) {
		ServerLevel level = helper.getLevel();
		BlockPos origin = helper.absolutePos(new BlockPos(0, 1, 0));
		List<BlockPos> placed = new ArrayList<>();
		for (int x = 0; x < width; x++) {
			for (int y = 0; y < height; y++) {
				for (int z = 0; z < depth; z++) {
					BlockPos pos = origin.offset(x, y, z);
					level.setBlock(pos, Blocks.DEEPSLATE.defaultBlockState(), 2);
					placed.add(pos);
				}
			}
		}
		return placed;
	}

	/** Breaks a block the way a player does: standing next to it, through the game mode. */
	private static void mine(ServerPlayer player, BlockPos pos) {
		player.snapTo(Vec3.atBottomCenterOf(pos.north()));
		player.gameMode.destroyBlock(pos);
	}

	@GameTest(maxTicks = 200)
	public void diggingToThreeDecoyVeinsOpensACheatingCase(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		ServerPlayer cheat = Harness.namedPlayer(helper);
		List<BlockPos> placed = slab(helper, 20, 5, 7);
		BlockPos o = helper.absolutePos(new BlockPos(0, 1, 0));

		for (int i = 0; i < 3; i++) {
			BlockPos decoy = o.offset(3 + i * 6, 2, 4);
			Harness.check(helper, Canaries.placeVein(cheat, level, List.of(decoy, decoy.east())) == 2,
					"a decoy vein could not be placed");
			mine(cheat, decoy.north().north());
			mine(cheat, decoy.north());
		}

		helper.succeedWhen(() -> {
			Harness.check(helper, Canaries.hitsFor(cheat.getUUID()) == 3,
					"decoy veins uncovered: " + Canaries.hitsFor(cheat.getUUID()));
			var opened = Mods.cases().store().openCaseFor(cheat.getUUID(), CaseCategory.CHEATING);
			Harness.check(helper, opened.isPresent(), "no cheating case yet; session "
					+ Mods.security().oreSense().sessionFor(cheat.getUUID()));
			for (BlockPos pos : placed) level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
		});
	}

	@GameTest(maxTicks = 200)
	public void diggingStraightToHiddenDiamondsOpensACheatingCase(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		ServerPlayer cheat = Harness.namedPlayer(helper);
		List<BlockPos> placed = slab(helper, 26, 5, 7);
		BlockPos o = helper.absolutePos(new BlockPos(0, 1, 0));

		for (int i = 0; i < 8; i++) {
			BlockPos ore = o.offset(2 + i * 3, 2, 4);
			level.setBlock(ore, Blocks.DEEPSLATE_DIAMOND_ORE.defaultBlockState(), 2);
			mine(cheat, ore.north().north());
			mine(cheat, ore.north());
		}

		helper.succeedWhen(() -> {
			var opened = Mods.cases().store().openCaseFor(cheat.getUUID(), CaseCategory.CHEATING);
			Harness.check(helper, opened.isPresent(), "no cheating case yet; session "
					+ Mods.security().oreSense().sessionFor(cheat.getUUID()));
			for (BlockPos pos : placed) level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
		});
	}
}
