package io.github.alphain24.staffcore.gametest;

import io.github.alphain24.staffcore.compat.Mc;
import io.github.alphain24.staffcore.module.Mods;
import io.github.alphain24.staffcore.modules.replay.PathEvents;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * A replay of grief shows the damage, even after the grief was rolled back.
 * <p>
 * The order staff actually do things in: something blows up, somebody rolls it back, and then
 * somebody opens the replay to see what happened. The replay used to show the blast and then
 * the restored wall, so the damage was the one thing it could not show.
 */
public class ReplayDamageTests {

	@GameTest
	public void aRolledBackBlastIsStillAHoleUntilTheRollback(GameTestHelper helper) {
		ServerPlayer player = Harness.mockPlayer(helper);
		ServerLevel level = helper.getLevel();
		BlockPos cell = new BlockPos(2, 3, 2);
		for (int x = 1; x <= 3; x++) {
			for (int y = 2; y <= 4; y++) {
				for (int z = 1; z <= 3; z++) helper.setBlock(new BlockPos(x, y, z), Blocks.DIRT);
			}
		}
		BlockPos centre = helper.absolutePos(cell);
		long before = System.currentTimeMillis() - 1;

		Vec3 c = Vec3.atCenterOf(centre);
		level.explode(null, level.damageSources().explosion(null, player), null,
				c.x, c.y, c.z, 2.0F, false, Level.ExplosionInteraction.TNT);
		Harness.check(helper, level.getBlockState(centre).isAir(),
				"the blast did not take the centre block, so there is no damage to look for");
		Mods.grief().awaitWrites();

		// As the console, so a restore point is written — that is what records when the
		// block came back — and no staff rate limit another test is spending can refuse it.
		var result = Mods.grief().rollback(level, null, centre, 6, 60_000L, false,
				io.github.alphain24.staffcore.permission.Actor.console());
		Harness.check(helper, result.reverted() > 0 && level.getBlockState(centre).is(Blocks.DIRT),
				"the rollback did not put the centre block back");
		long after = System.currentTimeMillis() + 1;

		List<PathEvents.Change> changes = PathEvents.inWindow(level.getServer(),
				Harness.name(player), Mc.dimensionId(level), before, after);
		PathEvents.Change blast = changes.stream()
				.filter(change -> change.isBreak() && change.pos().equals(centre))
				.findFirst().orElse(null);
		Harness.check(helper, blast != null, "the replay did not load the blast at all");
		Harness.check(helper, blast.releaseAt() > blast.at() && blast.releaseAt() != PathEvents.NEVER,
				"the replay does not know the rollback filled the hole: release "
						+ blast.releaseAt() + ", blast " + blast.at());

		long midway = blast.at() + (blast.releaseAt() - blast.at()) / 2;
		Harness.check(helper, PathEvents.at(changes, midway).get(centre) != null
						&& PathEvents.at(changes, midway).get(centre).isAir(),
				"between the blast and the rollback the replay shows the block standing, "
						+ "so the damage is invisible");
		Harness.check(helper, !PathEvents.at(changes, blast.releaseAt()).containsKey(centre),
				"after the rollback the replay still draws a hole where the block is back");
		helper.succeed();
	}
}
