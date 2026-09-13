package io.github.alphain24.staffcore.gametest;

import io.github.alphain24.staffcore.compat.Mc;
import io.github.alphain24.staffcore.config.StaffConfig;
import io.github.alphain24.staffcore.module.Mods;
import io.github.alphain24.staffcore.modules.cases.CaseCategory;
import io.github.alphain24.staffcore.modules.cases.CaseEvidence;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * A griefing burst opens a griefing case with the replay and the damage already filed.
 * <p>
 * Driven through the real break event, the real burst counter and the real case store, so what
 * it proves is the path a staff member relies on: that the case they open from the alert has
 * something in it to look at.
 */
public class CaseEvidenceTests {

	@GameTest
	public void aGriefingBurstOpensAGriefingCaseWithItsEvidence(GameTestHelper helper) {
		ServerPlayer player = Harness.mockPlayer(helper);
		ServerLevel level = helper.getLevel();
		BlockPos pos = helper.absolutePos(new BlockPos(1, 2, 1));
		BlockState stone = Blocks.STONE.defaultBlockState();

		int bar = StaffConfig.get().massGriefBlocks;
		Harness.check(helper, bar > 0 && bar <= 1000, "the mass-grief bar is off or out of reach: " + bar);
		for (int i = 0; i < bar; i++) {
			PlayerBlockBreakEvents.AFTER.invoker().afterBlockBreak(level, player, pos, stone, null);
		}

		var opened = Mods.cases().store().openCaseFor(player.getUUID(), CaseCategory.GRIEFING);
		Harness.check(helper, opened.isPresent(), "crossing the bar opened no griefing case");

		List<CaseEvidence.Item> evidence = Mods.cases().evidence().forCase(opened.get().id());
		CaseEvidence.Item replay = evidence.stream()
				.filter(item -> item.kind() == CaseEvidence.Kind.REPLAY).findFirst().orElse(null);
		CaseEvidence.Item blocks = evidence.stream()
				.filter(item -> item.kind() == CaseEvidence.Kind.BLOCKS).findFirst().orElse(null);

		Harness.check(helper, replay != null, "the case has no replay of the burst: " + evidence);
		Harness.check(helper, blocks != null, "the case has no block damage to open: " + evidence);
		Harness.checkEquals(helper, player.getUUID(), replay.subjectId(), "whose replay was filed");
		Harness.check(helper, replay.from() < System.currentTimeMillis() && replay.to() > replay.from(),
				"the replay window does not cover the burst");
		Harness.checkEquals(helper, Mc.dimensionId(level), blocks.world(), "the damage's world");
		Harness.check(helper, blocks.pos() != null, "the damage does not say where");
		helper.succeed();
	}
}
