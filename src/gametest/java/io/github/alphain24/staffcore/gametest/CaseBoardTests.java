package io.github.alphain24.staffcore.gametest;

import io.github.alphain24.staffcore.compat.Mc;
import io.github.alphain24.staffcore.gui.menu.CaseMenu;
import io.github.alphain24.staffcore.module.Mods;
import io.github.alphain24.staffcore.modules.cases.Case;
import io.github.alphain24.staffcore.modules.cases.CaseCategory;
import io.github.alphain24.staffcore.modules.cases.CaseEvidence;
import io.github.alphain24.staffcore.modules.cases.CaseStore;
import io.github.alphain24.staffcore.modules.cases.Resolution;
import io.github.alphain24.staffcore.permission.Actor;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;

import java.util.List;
import java.util.UUID;

/**
 * The case board and what a case screen does with its evidence.
 */
public class CaseBoardTests {

	private static String open(UUID subject, String name, CaseCategory kind) {
		return Mods.cases().store().openManually(subject, name, "gametest", "board test", 10, kind);
	}

	@GameTest
	public void theBoardSplitsOpenFromSolvedNewestFirst(GameTestHelper helper) {
		UUID subject = UUID.randomUUID();
		String older = open(subject, "boardA", CaseCategory.CHAT);
		sleepAMillisecond();
		String newer = open(subject, "boardA", CaseCategory.OTHER);
		sleepAMillisecond();
		String closed = open(subject, "boardA", CaseCategory.GRIEFING);
		Mods.cases().store().setStatus(closed, Case.Status.CLEARED, "gametest", "nothing in it",
				Resolution.values()[0]);

		List<String> open = Mods.cases().store().board(CaseStore.Board.OPEN, null, null, 0, 1000)
				.stream().map(Case::id).filter(id -> id.equals(older) || id.equals(newer)
						|| id.equals(closed)).toList();
		Harness.checkEquals(helper, List.of(newer, older), open,
				"the open tab, newest first, without the cleared case");

		boolean solved = Mods.cases().store().board(CaseStore.Board.SOLVED, null, null, 0, 1000)
				.stream().anyMatch(c -> c.id().equals(closed));
		Harness.check(helper, solved, "the cleared case is not on the solved tab");
		helper.succeed();
	}

	@GameTest
	public void automaticAssignmentNeverTakesACaseOffSomebody(GameTestHelper helper) {
		UUID subject = UUID.randomUUID();
		String id = open(subject, "boardB", CaseCategory.CHAT);

		Mods.cases().store().assign(id, "Claimer", "Claimer");
		Harness.check(helper, !Mods.cases().store().assignIfUnassigned(id, "Somebody", "test"),
				"an automatic assignment replaced somebody's claim");
		Harness.checkEquals(helper, "Claimer",
				Mods.cases().store().byId(id).orElseThrow().assignedTo(), "who has the case");

		Mods.cases().store().assign(id, null, "Claimer");
		Harness.check(helper, Mods.cases().store().assignIfUnassigned(id, "Somebody", "test"),
				"an unassigned case was not picked up");
		helper.succeed();
	}

	@GameTest
	public void theSceneIsTheMostDeliberatePlaceOnFile(GameTestHelper helper) {
		UUID subject = UUID.randomUUID();
		String id = open(subject, "boardC", CaseCategory.GRIEFING);
		String world = Mc.dimensionId(helper.getLevel());
		BlockPos damage = new BlockPos(10, 60, 10);
		BlockPos filed = new BlockPos(-5, 70, 3);

		Mods.cases().evidence().add(id, CaseEvidence.Draft.replay(subject, "boardC", world,
				new BlockPos(0, 0, 0), 0, 1, "replay"), "gametest");
		Mods.cases().evidence().add(id, CaseEvidence.Draft.blocks(subject, "boardC", world, damage,
				16, 0, 1, "damage"), "gametest");
		Harness.checkEquals(helper, damage,
				CaseMenu.sceneOf(Mods.cases().evidence().forCase(id)).orElseThrow().pos(),
				"with damage and a replay on file, the scene");

		Mods.cases().evidence().add(id, CaseEvidence.Draft.location(subject, "boardC", world, filed,
				"here"), "gametest");
		Harness.checkEquals(helper, filed,
				CaseMenu.sceneOf(Mods.cases().evidence().forCase(id)).orElseThrow().pos(),
				"once a place is filed on purpose, the scene");
		helper.succeed();
	}

	@GameTest
	public void aCaseRollbackUndoesTheFiledDamage(GameTestHelper helper) {
		ServerPlayer griefer = Harness.namedPlayer(helper);
		ServerLevel level = helper.getLevel();
		BlockPos rel = new BlockPos(2, 2, 2);
		BlockPos pos = helper.absolutePos(rel);
		long before = System.currentTimeMillis();

		helper.setBlock(rel, Blocks.OAK_PLANKS);
		net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.AFTER.invoker()
				.afterBlockBreak(level, griefer, pos, Blocks.OAK_PLANKS.defaultBlockState(), null);
		helper.setBlock(rel, Blocks.AIR);
		Mods.grief().awaitWrites();

		String id = open(griefer.getUUID(), Harness.name(griefer), CaseCategory.GRIEFING);
		Mods.cases().evidence().add(id, CaseEvidence.Draft.blocks(griefer.getUUID(),
				Harness.name(griefer), Mc.dimensionId(level), pos, 4, before, before + 60_000L,
				"the damage"), "gametest");
		CaseEvidence.Item area = Mods.cases().evidence().forCase(id).stream()
				.filter(item -> item.kind() == CaseEvidence.Kind.BLOCKS).findFirst().orElseThrow();

		var scope = CaseMenu.scopeFor(level, area, Harness.name(griefer));
		var result = Mods.grief().rollback(scope.level(), scope.who(), scope.centre(),
				scope.radius(), scope.windowMs(), false, Actor.console());
		Harness.check(helper, result.reverted() > 0 && level.getBlockState(pos).is(Blocks.OAK_PLANKS),
				"rolling back from the case's filed damage did not put the plank back");
		helper.succeed();
	}

	private static void sleepAMillisecond() {
		long start = System.currentTimeMillis();
		while (System.currentTimeMillis() == start) Thread.onSpinWait();
	}

	@GameTest(maxTicks = 100)
	public void aCaseScreenIsTheSameGridWithSubsectionsForEvidence(GameTestHelper helper) {
		ServerPlayer staff = Harness.namedPlayer(helper);
		var groups = io.github.alphain24.staffcore.permission.PermissionGroups.get();
		Harness.check(helper, groups != null, "no permission groups in the test server");
		groups.players.put(staff.getUUID().toString(), "admin");

		UUID subject = UUID.randomUUID();
		String id = Mods.cases().store().openManually(subject, "gridsubject", "gametest", "grid",
				10, CaseCategory.OTHER);
		io.github.alphain24.staffcore.gui.menu.CaseMenu.open(staff, id);
		groups.players.remove(staff.getUUID().toString());

		String[] expected = new String[45];
		expected[4] = id;
		String[][] rows = {
				{"Case", "Claim", "Mark investigating", "Close the case", "Assign to", "Kind"},
				{"Look into it", "Evidence", "Dig info", "Detector signals", "History", "File evidence"},
				{"Act on it", "Go to the scene", "Roll back the damage", "Punish", "file", "inventory"},
		};
		for (int row = 0; row < rows.length; row++) {
			int start = (row + 1) * 9;
			expected[start] = rows[row][0];
			expected[start + 8] = rows[row][0];
			for (int column = 0; column < 5; column++) expected[start + 2 + column] = rows[row][column + 1];
		}
		expected[36] = "Back";
		expected[44] = "Close";

		for (int slot = 0; slot < 45; slot++) {
			var stack = staff.containerMenu.getSlot(slot).getItem();
			String name = stack.getHoverName().getString();
			if (expected[slot] == null) {
				Harness.check(helper, name.isBlank(), "slot " + slot + " should be frame, holds " + name);
			} else {
				Harness.check(helper, name.contains(expected[slot]),
						"slot " + slot + " should be " + expected[slot] + " but is " + name);
			}
		}
		helper.succeed();
	}

}
