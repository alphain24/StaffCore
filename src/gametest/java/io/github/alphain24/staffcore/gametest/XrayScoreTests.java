package io.github.alphain24.staffcore.gametest;

import io.github.alphain24.staffcore.module.Mods;
import io.github.alphain24.staffcore.modules.cases.CaseCategory;
import io.github.alphain24.staffcore.modules.cases.CaseEvidence;
import io.github.alphain24.staffcore.modules.security.Canaries;
import io.github.alphain24.staffcore.modules.security.OreSense;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The live x-ray score, against real blocks: what a break uncovers, and what a pattern of
 * uncovering turns into.
 * <p>
 * The statistics — how often honest branch mining and x-ray mining cross the line over whole
 * sessions — are measured headlessly in {@code OreSenseSimulationTest}, where thousands of
 * sessions run in a second. These check the part a simulation cannot: that the world is read
 * the way the simulation assumes, and that a score over the line becomes a cheating case a
 * staff member can open, with the replay attached.
 */
public class XrayScoreTests {

	/**
	 * A slab of deepslate written straight to the level, larger than the test's own bounds,
	 * with a skin of one block that is never mined so everything inside is sealed.
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

	private static BlockPos origin(GameTestHelper helper) {
		return helper.absolutePos(new BlockPos(0, 1, 0));
	}

	private static void clear(GameTestHelper helper, List<BlockPos> placed) {
		for (BlockPos pos : placed) helper.getLevel().setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
	}

	/** Breaks a block as the break event leaves it, then asks what that uncovered. */
	private static OreSense.Observation dig(ServerLevel level, ServerPlayer player, BlockPos pos) {
		level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
		Canaries.Contact contact = Canaries.onBreak(level, player, pos);
		return Mods.security().oreSense().observe(level, player, pos, contact);
	}

	private static OreSense.Report digAndScore(ServerLevel level, ServerPlayer player, BlockPos pos) {
		OreSense.Observation observation = dig(level, player, pos);
		return observation == null ? null : Mods.security().oreSense().score(observation);
	}

	@GameTest
	public void aSealedVeinIsFoundOnceAndAnOpenOneNever(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		ServerPlayer player = Harness.namedPlayer(helper);
		List<BlockPos> placed = slab(helper, 9, 7, 9);
		BlockPos o = origin(helper);

		// A sealed three-block vein.
		BlockPos vein = o.offset(3, 3, 3);
		for (BlockPos pos : List.of(vein, vein.east(), vein.east().north())) {
			level.setBlock(pos, Blocks.DEEPSLATE_DIAMOND_ORE.defaultBlockState(), 2);
		}
		var first = dig(level, player, vein.west());
		Harness.check(helper, first != null && first.hiddenVeins() == 1,
				"breaking into a sealed vein was not a find: " + first);
		Harness.check(helper, first.faces() >= 4,
				"a break inside solid rock opened only " + first.faces() + " sealed faces");

		var again = dig(level, player, vein.east().east());
		Harness.check(helper, again == null || again.hiddenVeins() == 0,
				"the same vein was found a second time from its other end");

		// A vein already open to a cave.
		BlockPos cave = o.offset(6, 3, 6);
		level.setBlock(cave, Blocks.DIAMOND_ORE.defaultBlockState(), 2);
		level.setBlock(cave.above(), Blocks.AIR.defaultBlockState(), 2);
		var open = dig(level, player, cave.west());
		Harness.check(helper, open == null || open.hiddenVeins() == 0,
				"a diamond with a face already open to air counted as hidden");

		clear(helper, placed);
		helper.succeed();
	}

	@GameTest
	public void anHonestTunnelPastAVeinAndADecoyStaysQuiet(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		ServerPlayer miner = Harness.namedPlayer(helper);
		List<BlockPos> placed = slab(helper, 30, 5, 5);
		BlockPos o = origin(helper);

		level.setBlock(o.offset(10, 2, 3), Blocks.DEEPSLATE_DIAMOND_ORE.defaultBlockState(), 2);
		Canaries.placeAt(miner, level, o.offset(20, 2, 1));

		// A straight one-by-two tunnel down the middle, touching both.
		List<OreSense.Report> reports = new ArrayList<>();
		for (int x = 1; x < 29; x++) {
			for (int y = 1; y <= 2; y++) {
				OreSense.Report report = digAndScore(level, miner, o.offset(x, y, 2));
				if (report != null) reports.add(report);
			}
		}

		OreSense.Session session = Mods.security().oreSense().sessionFor(miner.getUUID());
		Harness.check(helper, session != null && session.finds() == 2,
				"the tunnel should have found the vein and the decoy, found "
						+ (session == null ? "nothing" : session.finds()));
		Harness.check(helper, reports.isEmpty(),
				"an honest tunnel that met one vein and one decoy was reported: " + reports);
		clear(helper, placed);
		helper.succeed();
	}

	@GameTest(maxTicks = 200)
	public void diggingStraightToHiddenVeinsOpensACheatingCase(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		ServerPlayer cheat = Harness.namedPlayer(helper);
		List<BlockPos> placed = slab(helper, 25, 5, 7);
		BlockPos o = origin(helper);

		// Eight sealed veins in a row, each reached with a two-block approach — what walking
		// to ore you can see through the rock looks like.
		OreSense.Report last = null;
		for (int i = 0; i < 8; i++) {
			BlockPos ore = o.offset(2 + i * 3, 2, 4);
			level.setBlock(ore, Blocks.DEEPSLATE_DIAMOND_ORE.defaultBlockState(), 2);
			for (BlockPos step : List.of(ore.north().north(), ore.north())) {
				OreSense.Report report = digAndScore(level, cheat, step);
				if (report != null) last = report;
			}
		}

		Harness.check(helper, last != null && last.loudness() == OreSense.Loudness.ALERT,
				"eight hidden veins in sixteen blocks was not an alert: " + last + ", session "
						+ Mods.security().oreSense().sessionFor(cheat.getUUID()));

		OreSense.Report report = last;
		Mods.security().oreSense().announce(level.getServer(), report);
		var opened = Mods.cases().store().openCaseFor(cheat.getUUID(), CaseCategory.CHEATING);
		Harness.check(helper, opened.isPresent(),
				"a score of " + report.confidence() + " opened no cheating case");

		Set<CaseEvidence.Kind> kinds = Mods.cases().evidence().forCase(opened.get().id()).stream()
				.map(CaseEvidence.Item::kind).collect(Collectors.toSet());
		Harness.check(helper, kinds.contains(CaseEvidence.Kind.REPLAY)
						&& kinds.contains(CaseEvidence.Kind.XRAY_DIG)
						&& kinds.contains(CaseEvidence.Kind.LOCATION),
				"the case is missing its evidence; it has " + kinds);

		clear(helper, placed);
		helper.succeed();
	}

	@GameTest
	public void creativeIsScoredSoTestingInCreativeWorks(GameTestHelper helper) {
		// Skipping creative made every operator's test of the decoys look like a broken
		// detector, because that is where operators test from.
		ServerLevel level = helper.getLevel();
		ServerPlayer creative = Harness.mockPlayer(helper);
		List<BlockPos> placed = slab(helper, 5, 5, 5);
		BlockPos o = origin(helper);

		level.setBlock(o.offset(2, 2, 3), Blocks.DEEPSLATE_DIAMOND_ORE.defaultBlockState(), 2);
		var observed = dig(level, creative, o.offset(2, 2, 2));
		Harness.check(helper, observed != null && observed.hiddenVeins() == 1,
				"a creative player's break was not scored: " + observed);
		clear(helper, placed);
		helper.succeed();
	}

	@GameTest(maxTicks = 200)
	public void aRealBreakReachesTheScore(GameTestHelper helper) {
		// The wiring, end to end: the game's own block breaking, the break event, the worker.
		ServerLevel level = helper.getLevel();
		ServerPlayer miner = Harness.namedPlayer(helper);
		List<BlockPos> placed = slab(helper, 7, 5, 7);
		BlockPos o = origin(helper);

		BlockPos ore = o.offset(3, 2, 4);
		level.setBlock(ore, Blocks.DEEPSLATE_DIAMOND_ORE.defaultBlockState(), 2);
		BlockPos target = ore.north();
		miner.snapTo(net.minecraft.world.phys.Vec3.atCenterOf(target.north()));
		miner.gameMode.destroyBlock(target);

		helper.succeedWhen(() -> {
			OreSense.Session session = Mods.security().oreSense().sessionFor(miner.getUUID());
			Harness.check(helper, session != null && session.hiddenVeins() == 1,
					"breaking a block with the game's own break did not reach the score: " + session);
			clear(helper, placed);
		});
	}
}
