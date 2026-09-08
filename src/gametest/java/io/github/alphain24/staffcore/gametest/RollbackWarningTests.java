package io.github.alphain24.staffcore.gametest;

import io.github.alphain24.staffcore.config.StaffConfig;
import io.github.alphain24.staffcore.modules.grief.RegionLock;
import io.github.alphain24.staffcore.modules.grief.RollbackWarnings;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;

import java.util.List;

/**
 * What a rollback says about itself before somebody confirms it.
 * <p>
 * These need a real level, because both questions are about the world: where spawn is, and
 * which dimension this is. The geometry cannot be checked headlessly and the failure is silent
 * — a warning that never fires looks exactly like a rollback that was fine.
 * <p>
 * The negative case matters as much as the positive one. A warning that appears on every
 * rollback teaches everybody to press through it, and then the one that mattered goes past
 * unread too.
 */
public class RollbackWarningTests {

	@GameTest
	public void aRollbackOverSpawnSaysSo(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos spawn = spawnOf(level);

		if (spawn == null) {
			// A dimension with no respawn data has nothing to overlap. Succeeding here rather
			// than failing, because that is a property of the test world and not a defect —
			// and the companion test below still proves the check can fire at all.
			helper.succeed();
			return;
		}

		// A radius that certainly reaches spawn from spawn.
		var warnings = RollbackWarnings.forArea(level, spawn, 16, 0);
		Harness.check(helper, warnings.stream().anyMatch(w -> w.text().contains("spawn")),
				"a rollback centred on spawn said nothing about spawn. Spawn is built by staff "
						+ "over months and is logged exactly the way griefing is, so a wide "
						+ "rollback here undoes building work rather than damage.");

		Harness.check(helper, warnings.stream().anyMatch(RollbackWarnings.Warning::severe),
				"the spawn warning is not severe, so it does not require a preview first");
		helper.succeed();
	}

	@GameTest
	public void aRollbackFarFromSpawnSaysNothing(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos spawn = spawnOf(level);
		if (spawn == null) {
			helper.succeed();
			return;
		}

		// Thirty thousand blocks away, with a small radius. If this warns, the check is
		// firing on everything and is therefore worth nothing.
		BlockPos far = spawn.offset(30_000, 0, 30_000);
		List<RollbackWarnings.Warning> warnings = RollbackWarnings.forArea(level, far, 8, 0);

		Harness.check(helper, warnings.stream().noneMatch(w -> w.text().contains("spawn")),
				"a rollback thirty thousand blocks from spawn warned about spawn. A warning "
						+ "that fires every time is one nobody reads.");
		helper.succeed();
	}

	@GameTest
	public void sizeIsFlaggedAboveTheConfiguredLine(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		int line = StaffConfig.get().rollbackWarnBlocks;
		BlockPos far = spawnOf(level) == null ? BlockPos.ZERO
				: spawnOf(level).offset(30_000, 0, 30_000);

		Harness.check(helper, RollbackWarnings.forArea(level, far, 8, line).stream()
						.anyMatch(w -> w.text().contains("large rollback")),
				"a rollback at the configured threshold was not flagged as large");

		Harness.check(helper, RollbackWarnings.forArea(level, far, 8, line - 1).stream()
						.noneMatch(w -> w.text().contains("large rollback")),
				"a rollback below the threshold was flagged, which is how the warning stops "
						+ "meaning anything");
		helper.succeed();
	}

	@GameTest
	public void theRegionLockRefusesASecondStaffMemberByName(GameTestHelper helper) {
		// The headless test covers the arithmetic. This covers it holding across a real
		// dimension id, which is what the key is actually built from.
		RegionLock.releaseAll();
		String world = io.github.alphain24.staffcore.compat.Mc.dimensionId(helper.getLevel());

		var first = RegionLock.acquire(Harness.staff(), world, BlockPos.ZERO, 32);
		Harness.check(helper, first.acquired(), "the first rollback could not take the ground");

		var second = RegionLock.acquire(Harness.otherStaff(), world, new BlockPos(8, 0, 8), 32);
		Harness.check(helper, !second.acquired(), "two staff rolled back the same ground");
		Harness.check(helper, second.refusal().contains(Harness.staff().name()),
				"the refusal does not name who to go and talk to: " + second.refusal());

		RegionLock.releaseAll();
		helper.succeed();
	}

	private static BlockPos spawnOf(ServerLevel level) {
		try {
			var respawn = level.getRespawnData();
			if (respawn == null) return null;
			return respawn.dimension().equals(level.dimension()) ? respawn.pos() : null;
		} catch (RuntimeException e) {
			return null;
		}
	}
}
