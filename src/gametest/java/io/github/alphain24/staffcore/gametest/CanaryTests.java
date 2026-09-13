package io.github.alphain24.staffcore.gametest;

import io.github.alphain24.staffcore.modules.security.Canaries;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;

import java.util.List;

/**
 * Decoy veins: what counts as uncovering one, and who it counts against.
 * <p>
 * The old rule counted only a decoy broken while all six of its neighbours stood, and retired it
 * silently the moment anybody broke a neighbour. Since a decoy is sealed in rock, every route to
 * one goes through a neighbour — so the rule that protected honest miners also made a hit
 * impossible for cheaters, and breaking a decoy in-game did nothing at all. These tests hold the
 * replacement: the break that opens a face onto a vein is the find, once per vein, and only for
 * the player the vein was shown to. Whether a find is suspicious is {@link XrayScoreTests}' job.
 * <p>
 * <b>What these cannot see.</b> A mock player's resync packet is constructed and sent into an
 * embedded channel; that a real client draws and then removes the ore is one of the manual
 * checks.
 */
public class CanaryTests {

	/** A five-by-five-by-five block of stone, so a three-block vein in the middle is sealed. */
	private static BlockPos stoneBlock(GameTestHelper helper, int x, int y, int z) {
		for (int dx = -2; dx <= 2; dx++) {
			for (int dy = -2; dy <= 2; dy++) {
				for (int dz = -2; dz <= 2; dz++) {
					helper.setBlock(new BlockPos(x + dx, y + dy, z + dz), Blocks.STONE);
				}
			}
		}
		return helper.absolutePos(new BlockPos(x, y, z));
	}

	/** Breaks a block the way the break event leaves it: gone, then reported. */
	private static Canaries.Contact breakAt(ServerLevel level, ServerPlayer breaker, BlockPos pos) {
		level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
		return Canaries.onBreak(level, breaker, pos);
	}

	@GameTest
	public void uncoveringAVeinCountsOnceForTheWholeVein(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		ServerPlayer player = Harness.mockPlayer(helper);
		BlockPos centre = stoneBlock(helper, 3, 3, 3);
		List<BlockPos> vein = List.of(centre, centre.east(), centre.east().above());

		Harness.checkEquals(helper, 3, Canaries.placeVein(player, level, vein),
				"the three-block vein was not placed whole");

		var first = breakAt(level, player, centre.west());
		Harness.checkEquals(helper, 1, first.uncovered(),
				"breaking into the side of a decoy vein was not counted as uncovering it — "
						+ "this is the in-game \"I broke a canary and nothing happened\"");
		Harness.checkEquals(helper, 0, Canaries.liveFor(player.getUUID()),
				"only the touched block was retired; the rest of the vein is still on screen");
		Harness.checkEquals(helper, 1, Canaries.hitsFor(player.getUUID()), "the find was not counted");

		// Mining on along where the vein was must not count it again.
		var second = breakAt(level, player, centre);
		Harness.checkEquals(helper, 0, second.uncovered(), "one vein was counted twice");
		Harness.checkEquals(helper, 1, Canaries.hitsFor(player.getUUID()), "one vein was counted twice");
		helper.succeed();
	}

	@GameTest
	public void breakingTheDecoyBlockItselfCountsToo(GameTestHelper helper) {
		// Only a client that can target a block it cannot see manages this, but it is still the
		// vein being reached.
		ServerLevel level = helper.getLevel();
		ServerPlayer player = Harness.mockPlayer(helper);
		BlockPos centre = stoneBlock(helper, 3, 3, 3);

		Canaries.placeAt(player, level, centre);
		Harness.checkEquals(helper, 1, breakAt(level, player, centre).uncovered(),
				"breaking a sealed decoy directly was not counted");
		helper.succeed();
	}

	@GameTest
	public void somebodyElseUncoveringItCountsAgainstNobody(GameTestHelper helper) {
		// Only the owner was told the vein was there. Another player reaching it is a
		// coincidence, and the owner did nothing.
		ServerLevel level = helper.getLevel();
		ServerPlayer owner = Harness.mockPlayer(helper);
		ServerPlayer stranger = Harness.mockPlayer(helper);
		BlockPos centre = stoneBlock(helper, 3, 3, 3);

		Canaries.placeVein(owner, level, List.of(centre, centre.north()));
		var contact = breakAt(level, stranger, centre.south());

		Harness.checkEquals(helper, 0, contact.uncovered(), "a stranger was credited with a find");
		Harness.checkEquals(helper, 1, contact.retired(), "the vein was not retired");
		Harness.checkEquals(helper, 0, Canaries.hitsFor(stranger.getUUID()),
				"a player who was never told about the decoy was charged with finding it");
		Harness.checkEquals(helper, 0, Canaries.hitsFor(owner.getUUID()),
				"the owner was charged for somebody else's break");
		Harness.checkEquals(helper, 0, Canaries.liveFor(owner.getUUID()),
				"the vein is still out, though it is now open to the air");
		helper.succeed();
	}

	@GameTest
	public void anExplosionRetiresWhatItUncoversWithoutAFind(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		ServerPlayer player = Harness.mockPlayer(helper);
		BlockPos centre = stoneBlock(helper, 3, 3, 3);

		Canaries.placeAt(player, level, centre);
		Canaries.onExplosion(level, List.of(centre.relative(Direction.UP)));

		Harness.checkEquals(helper, 0, Canaries.liveFor(player.getUUID()),
				"a decoy survived the explosion that uncovered it");
		Harness.checkEquals(helper, 0, Canaries.hitsFor(player.getUUID()),
				"an explosion was recorded as somebody finding the decoy");
		helper.succeed();
	}

	@GameTest
	public void aGrownVeinIsOneConnectedClusterInSealedRock(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		ServerPlayer player = Harness.mockPlayer(helper);
		BlockPos centre = stoneBlock(helper, 3, 3, 3);

		int size = Canaries.growVein(player, level, centre, 6, new java.util.Random(7));
		List<Canaries.Canary> mine = Canaries.all().stream()
				.filter(c -> c.owner().equals(player.getUUID()))
				.toList();

		Harness.check(helper, size >= 2 && size <= 6,
				"a vein asked to grow to 6 in solid stone came out at " + size);
		Harness.checkEquals(helper, size, mine.size(), "the vein's blocks are not all live");
		Harness.checkEquals(helper, 1L, mine.stream().mapToLong(Canaries.Canary::vein).distinct().count(),
				"one grown vein was recorded as several");
		for (Canaries.Canary canary : mine) {
			Harness.check(helper, canary.shown().is(Blocks.DIAMOND_ORE),
					"a decoy in stone was shown as " + canary.shown());
			boolean touches = mine.stream().anyMatch(other -> other != canary
					&& Math.abs(other.pos().getX() - canary.pos().getX()) <= 1
					&& Math.abs(other.pos().getY() - canary.pos().getY()) <= 1
					&& Math.abs(other.pos().getZ() - canary.pos().getZ()) <= 1);
			Harness.check(helper, size == 1 || touches,
					"a vein block at " + canary.pos().toShortString() + " touches no other");
		}
		helper.succeed();
	}

	@GameTest
	public void placementRefusesRockThatIsNotSealed(GameTestHelper helper) {
		// A decoy in a wall somebody can see is not a question, it is bait — and the answer it
		// produces is meaningless because the honest explanation is "I looked at it".
		BlockPos exposed = stoneBlock(helper, 3, 3, 3);
		helper.setBlock(new BlockPos(3, 4, 3), Blocks.AIR);

		Harness.check(helper, !Canaries.wouldPlaceAt(helper.getLevel(), exposed),
				"a position with a face open to air was accepted");

		helper.setBlock(new BlockPos(3, 4, 3), Blocks.STONE);
		Harness.check(helper, Canaries.wouldPlaceAt(helper.getLevel(), exposed),
				"sealing it back up did not make it acceptable, so the check is refusing "
						+ "everything and would place nothing at all");
		helper.succeed();
	}

	@GameTest
	public void placementRefusesRockTouchingARealDiamond(GameTestHelper helper) {
		// A decoy merged into a real vein would blur which of the two a player uncovered.
		BlockPos centre = stoneBlock(helper, 3, 3, 3);
		helper.setBlock(new BlockPos(4, 3, 3), Blocks.DEEPSLATE_DIAMOND_ORE);

		Harness.check(helper, !Canaries.wouldPlaceAt(helper.getLevel(), centre),
				"a decoy would have been placed against a real diamond");
		helper.succeed();
	}

	@GameTest
	public void placementRefusesAnythingThatIsNotPlainStone(GameTestHelper helper) {
		// Overwriting a real ore would hide it; overwriting anything with a block entity
		// would show the client a chest that is not there.
		BlockPos centre = stoneBlock(helper, 3, 3, 3);

		for (var block : List.of(Blocks.DIAMOND_ORE, Blocks.CHEST, Blocks.OAK_PLANKS)) {
			helper.setBlock(new BlockPos(3, 3, 3), block);
			Harness.check(helper, !Canaries.wouldPlaceAt(helper.getLevel(), centre),
					"a decoy would have been placed over " + block.getName().getString());
		}
		helper.succeed();
	}
}
