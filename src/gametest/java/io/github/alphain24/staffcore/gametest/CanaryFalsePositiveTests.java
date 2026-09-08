package io.github.alphain24.staffcore.gametest;

import io.github.alphain24.staffcore.StaffCore;
import io.github.alphain24.staffcore.modules.security.Canaries;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How often an honest miner is recorded as breaking a decoy they could not have seen.
 *
 * <h2>What is actually being measured</h2>
 * A decoy sits in fully encased rock, so reaching it means breaking one of its six neighbours
 * first — and that break retires it. The claim is that no ordinary mining sequence can produce
 * a hit, and the number worth having is not a percentage but the list of orderings that were
 * tried and did not produce one.
 * <p>
 * The interesting failure is not a miner who exposes a decoy and wanders off. It is a miner who
 * breaks the neighbour and then the decoy in immediate succession, because if retirement were
 * queued rather than done inside the neighbour's own break event, the second break would land
 * first and an honest player would be recorded as having walked to a block that was never there.
 * So the sequences below are deliberately adjacent in time, which is the worst case for that
 * ordering and the one a player with efficiency and haste actually produces.
 *
 * <h2>The control is not optional</h2>
 * A decoy layer that is completely broken — never placed, never matched, never recorded —
 * scores a false-positive rate of zero, which is the same number a perfect one scores. Every
 * scenario here is paired with a sequence that <em>must</em> register a hit, so a clean result
 * means the rule held rather than that nothing ran.
 */
public class CanaryFalsePositiveTests {

	/** One scenario and what it produced. */
	private record Trial(String name, int decoys, int hits, String expectation) {}

	private static final List<Trial> RESULTS = new ArrayList<>();

	/**
	 * A slab of stone to dig in, placed relative to the test so it cannot collide with another.
	 * <p>
	 * Written straight to the level rather than through the helper because it is larger than
	 * the test's own bounds, and taken down again at the end of each scenario — a gametest
	 * world is thrown away, but leaving a block of stone in it for the next test to trip over
	 * is the kind of thing that produces a failure nobody can reproduce.
	 */
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

	private static void clear(GameTestHelper helper, List<BlockPos> placed) {
		for (BlockPos pos : placed) {
			helper.getLevel().setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
		}
		Canaries.forgetAll();
	}

	// ------------------------------------------------------------------ scenarios

	@GameTest
	public void aTunnelPassingBesideADecoyNeverRegisters(GameTestHelper helper) {
		// The commonest shape by far: somebody digs past a decoy without ever touching it.
		Canaries.forgetAll();
		ServerLevel level = helper.getLevel();
		ServerPlayer miner = Harness.mockPlayer(helper);
		List<BlockPos> placed = slab(helper, 10, 4, 6);
		BlockPos origin = helper.absolutePos(new BlockPos(0, 1, 0));

		int decoys = 0;
		for (int x = 2; x < 8; x += 2) {
			BlockPos spot = origin.offset(x, 2, 3);
			if (Canaries.placeAt(miner, level, spot)) decoys++;
		}

		// A straight run one block away from every decoy.
		for (int x = 0; x < 10; x++) {
			Canaries.onBreak(level, miner, origin.offset(x, 2, 2));
		}

		record(helper, "tunnel passing beside decoys", decoys, miner, "no hits");
		clear(helper, placed);
		helper.succeed();
	}

	@GameTest
	public void breakingTheNeighbourThenTheDecoyImmediatelyNeverRegisters(GameTestHelper helper) {
		// The race the whole design turns on. Two breaks with nothing between them: if
		// retirement were queued behind anything at all, the second would land while the decoy
		// was still live and an honest player would be charged for it.
		Canaries.forgetAll();
		ServerLevel level = helper.getLevel();
		ServerPlayer miner = Harness.mockPlayer(helper);
		List<BlockPos> placed = slab(helper, 12, 4, 4);
		BlockPos origin = helper.absolutePos(new BlockPos(0, 1, 0));

		int decoys = 0;
		for (int x = 3; x < 11; x += 2) {
			BlockPos decoy = origin.offset(x, 2, 2);
			if (!Canaries.placeAt(miner, level, decoy)) continue;
			decoys++;

			// Neighbour, then the decoy itself, back to back — the fastest a player with
			// efficiency and haste can produce, and the ordering that would break a queue.
			Canaries.onBreak(level, miner, decoy.relative(Direction.WEST));
			Canaries.onBreak(level, miner, decoy);
		}

		record(helper, "neighbour then decoy, back to back", decoys, miner, "no hits");
		clear(helper, placed);
		helper.succeed();
	}

	@GameTest
	public void twoMinersConvergingNeverRegisterAgainstEachOther(GameTestHelper helper) {
		// Retirement is global across breakers on purpose. One player's tunnel exposes another
		// player's decoy just as thoroughly as their own would, and scoping retirement per
		// owner would leak false positives between people who never met.
		Canaries.forgetAll();
		ServerLevel level = helper.getLevel();
		ServerPlayer first = Harness.mockPlayer(helper);
		ServerPlayer second = Harness.mockPlayer(helper);
		List<BlockPos> placed = slab(helper, 12, 4, 4);
		BlockPos origin = helper.absolutePos(new BlockPos(0, 1, 0));

		int decoys = 0;
		for (int x = 4; x < 9; x += 2) {
			if (Canaries.placeAt(first, level, origin.offset(x, 2, 2))) decoys++;
		}

		// One digs east, the other west, meeting in the middle where the decoys are.
		for (int x = 0; x < 12; x++) {
			Canaries.onBreak(level, first, origin.offset(x, 2, 2));
			Canaries.onBreak(level, second, origin.offset(11 - x, 2, 2));
		}

		record(helper, "two miners converging", decoys, first, "no hits");
		Harness.checkEquals(helper, 0, Canaries.hitsFor(second.getUUID()),
				"the second miner was charged for decoys they were never told about");

		clear(helper, placed);
		helper.succeed();
	}

	@GameTest
	public void anExplosionUncoveringManyAtOnceNeverRegisters(GameTestHelper helper) {
		// TNT and bed mining destroy a volume with no break event at all. Missing this leaves
		// decoys standing in a crater, visible to anybody walking past — and the first person
		// to mine the obvious diamond in the rubble is charged for it.
		Canaries.forgetAll();
		ServerLevel level = helper.getLevel();
		ServerPlayer miner = Harness.mockPlayer(helper);
		List<BlockPos> placed = slab(helper, 8, 5, 8);
		BlockPos origin = helper.absolutePos(new BlockPos(0, 1, 0));

		int decoys = 0;
		List<BlockPos> spots = new ArrayList<>();
		for (int x = 2; x < 6; x++) {
			for (int z = 2; z < 6; z++) {
				BlockPos spot = origin.offset(x, 2, z);
				if (Canaries.placeAt(miner, level, spot)) {
					decoys++;
					spots.add(spot);
				}
			}
		}

		// One blast taking out the whole layer above them.
		List<BlockPos> blast = new ArrayList<>();
		for (BlockPos spot : spots) blast.add(spot.above());
		Canaries.onExplosion(level, blast);

		Harness.checkEquals(helper, 0, Canaries.liveFor(miner.getUUID()),
				"decoys survived the blast that uncovered them");

		// And then somebody mines the rubble, including where the decoys were.
		for (BlockPos spot : spots) Canaries.onBreak(level, miner, spot);

		record(helper, "explosion then mining the rubble", decoys, miner, "no hits");
		clear(helper, placed);
		helper.succeed();
	}

	@GameTest
	public void aBranchMineAcrossManyDecoysNeverRegisters(GameTestHelper helper) {
		// Volume, so a rate has a denominator worth quoting. A spine with ribs is the shape
		// that produces the most right-angle turns per hour of anything honest.
		Canaries.forgetAll();
		ServerLevel level = helper.getLevel();
		ServerPlayer miner = Harness.mockPlayer(helper);
		List<BlockPos> placed = slab(helper, 14, 4, 10);
		BlockPos origin = helper.absolutePos(new BlockPos(0, 1, 0));

		int decoys = 0;
		for (int x = 1; x < 13; x++) {
			for (int z = 1; z < 9; z += 3) {
				if (Canaries.placeAt(miner, level, origin.offset(x, 2, z))) decoys++;
			}
		}

		for (int x = 0; x < 14; x++) Canaries.onBreak(level, miner, origin.offset(x, 2, 0));
		for (int x = 0; x < 14; x += 2) {
			for (int z = 1; z < 10; z++) {
				Canaries.onBreak(level, miner, origin.offset(x, 2, z));
			}
		}

		record(helper, "branch mine across a decoy field", decoys, miner, "no hits");
		clear(helper, placed);
		helper.succeed();
	}

	// ------------------------------------------------------------------- the control

	@GameTest
	public void breakingADecoyColdStillRegisters(GameTestHelper helper) {
		// Without this every number above is meaningless. A decoy layer that never places,
		// never matches or never records scores a false-positive rate of zero — the same
		// number a working one scores — and only this tells the two apart.
		Canaries.forgetAll();
		ServerLevel level = helper.getLevel();
		ServerPlayer miner = Harness.mockPlayer(helper);
		List<BlockPos> placed = slab(helper, 6, 4, 6);
		BlockPos origin = helper.absolutePos(new BlockPos(0, 1, 0));

		int decoys = 0;
		int expected = 0;
		for (int x = 2; x < 5; x++) {
			BlockPos decoy = origin.offset(x, 2, 3);
			if (!Canaries.placeAt(miner, level, decoy)) continue;
			decoys++;
			expected++;
			// Straight to it, with every one of its six neighbours still standing. There is
			// no way to see this block and no reason to dig at it.
			Canaries.onBreak(level, miner, decoy);
		}

		Harness.check(helper, decoys > 0, "no decoys were placed, so nothing was under test");
		Harness.checkEquals(helper, expected, Canaries.hitsFor(miner.getUUID()),
				"breaking " + expected + " decoys cold registered "
						+ Canaries.hitsFor(miner.getUUID()) + " hits. Every false-positive "
						+ "result in this class is worthless if this one does not fire.");

		RESULTS.add(new Trial("CONTROL: decoy broken cold", decoys,
				Canaries.hitsFor(miner.getUUID()), "one hit each"));
		report();
		clear(helper, placed);
		helper.succeed();
	}

	// -------------------------------------------------------------------- reporting

	private static void record(GameTestHelper helper, String name, int decoys, ServerPlayer miner,
			String expectation) {

		int hits = Canaries.hitsFor(miner.getUUID());
		RESULTS.add(new Trial(name, decoys, hits, expectation));

		Harness.check(helper, decoys > 0,
				"\"" + name + "\" placed no decoys, so it measured nothing");
		Harness.checkEquals(helper, 0, hits,
				"\"" + name + "\" recorded " + hits + " hit(s) against an honest miner. That is "
						+ "a player being reported for x-ray for digging normally.");
	}

	/** Prints the table that goes into decisions.md. Numbers in a console session do not survive. */
	private static void report() {
		Map<String, Trial> byName = new LinkedHashMap<>();
		for (Trial trial : RESULTS) byName.put(trial.name(), trial);

		StaffCore.LOGGER.info("[CanaryFP] ---- canary false-positive corpus ----");
		for (Trial trial : byName.values()) {
			StaffCore.LOGGER.info("[CanaryFP] {} decoys, {} hits ({}) - {}",
					trial.decoys(), trial.hits(), trial.expectation(), trial.name());
		}
	}
}
