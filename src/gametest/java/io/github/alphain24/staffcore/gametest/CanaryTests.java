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
 * Decoy ores, and the rule that stops them accusing honest miners.
 * <p>
 * The whole risk here is a false positive, and it has a specific shape: a decoy sits in solid
 * rock, an ordinary player tunnels past and breaks the block beside it, the decoy is now
 * exposed, and they mine the diamond they can suddenly see. Nothing about that is cheating and
 * everything about it looks like a canary hit.
 * <p>
 * So the retirement rule is what these mostly test. It needs a real world — encasing a block,
 * breaking its neighbour, and asking what happened is not a thing that can be done headlessly,
 * and the failure mode if it goes wrong is silent: hits accumulate against people who did
 * nothing.
 * <p>
 * <b>What these cannot see.</b> A mock player is not in the player list, so a resync that has
 * to find its target by UUID reaches nobody here. The hit path is covered, because the player
 * is passed in rather than looked up; the retirement-by-somebody-else path sends its packet
 * into a lookup that returns null in this harness. That the packet is <em>constructed</em> for
 * the right position is checked; that it arrives is one of the manual checks.
 */
public class CanaryTests {

	/** A three-by-three-by-three block of stone with one at the centre to lie about. */
	private static BlockPos encasedStone(GameTestHelper helper, int x, int y, int z) {
		for (int dx = -1; dx <= 1; dx++) {
			for (int dy = -1; dy <= 1; dy++) {
				for (int dz = -1; dz <= 1; dz++) {
					helper.setBlock(new BlockPos(x + dx, y + dy, z + dz), Blocks.STONE);
				}
			}
		}
		return helper.absolutePos(new BlockPos(x, y, z));
	}

	@GameTest
	public void breakingTheDecoyItselfIsAHit(GameTestHelper helper) {
		Canaries.forgetAll();
		ServerLevel level = helper.getLevel();
		ServerPlayer player = Harness.mockPlayer(helper);
		BlockPos centre = encasedStone(helper, 1, 2, 1);

		Canaries.placeAt(player, level, centre);
		Harness.checkEquals(helper, 1, Canaries.liveFor(player.getUUID()),
				"the decoy was not placed");

		var contact = Canaries.onBreak(level, player, centre);
		Harness.check(helper, contact == Canaries.Contact.HIT,
				"breaking a decoy while every neighbour was still standing was not recorded "
						+ "as a hit — that is the one case there is no honest route to");
		Harness.checkEquals(helper, 1, Canaries.hitsFor(player.getUUID()), "the hit was not counted");

		Canaries.forgetAll();
		helper.succeed();
	}

	@GameTest
	public void breakingANeighbourRetiresItInstead(GameTestHelper helper) {
		// The false positive this exists to prevent. An ordinary miner exposes the decoy on
		// their way past; without this rule the next swing is logged as x-ray.
		Canaries.forgetAll();
		ServerLevel level = helper.getLevel();
		ServerPlayer player = Harness.mockPlayer(helper);
		BlockPos centre = encasedStone(helper, 1, 2, 1);

		Canaries.placeAt(player, level, centre);
		var contact = Canaries.onBreak(level, player, centre.relative(Direction.NORTH));

		Harness.check(helper, contact == Canaries.Contact.RETIRED,
				"breaking the block beside a decoy did not retire it");
		Harness.checkEquals(helper, 0, Canaries.liveFor(player.getUUID()),
				"the decoy is still live after being exposed");
		Harness.checkEquals(helper, 0, Canaries.hitsFor(player.getUUID()),
				"exposing a decoy counted as a hit, which is the false positive itself");

		Canaries.forgetAll();
		helper.succeed();
	}

	@GameTest
	public void aDecoyExposedThenMinedIsNotAHit(GameTestHelper helper) {
		// The full sequence, in order, because the two rules above are only worth anything
		// together: expose, then mine. This is what an honest tunnel actually looks like.
		Canaries.forgetAll();
		ServerLevel level = helper.getLevel();
		ServerPlayer player = Harness.mockPlayer(helper);
		BlockPos centre = encasedStone(helper, 1, 2, 1);

		Canaries.placeAt(player, level, centre);
		Canaries.onBreak(level, player, centre.relative(Direction.NORTH));
		var second = Canaries.onBreak(level, player, centre);

		Harness.check(helper, second == Canaries.Contact.NOTHING,
				"mining a decoy that had already been exposed counted against the player. "
						+ "That is an ordinary miner being reported for x-ray.");
		Harness.checkEquals(helper, 0, Canaries.hitsFor(player.getUUID()), "and it was counted");

		Canaries.forgetAll();
		helper.succeed();
	}

	@GameTest
	public void somebodyElseBreakingItIsNotEvidenceAgainstThem(GameTestHelper helper) {
		// Only the owner was told the block was there. Another player reaching it is a
		// coincidence, and recording that as evidence would be the worst thing here.
		Canaries.forgetAll();
		ServerLevel level = helper.getLevel();
		ServerPlayer owner = Harness.mockPlayer(helper);
		ServerPlayer stranger = Harness.mockPlayer(helper);
		BlockPos centre = encasedStone(helper, 1, 2, 1);

		Canaries.placeAt(owner, level, centre);
		Canaries.onBreak(level, stranger, centre);

		Harness.checkEquals(helper, 0, Canaries.hitsFor(stranger.getUUID()),
				"a player who was never told about the decoy was charged with finding it");
		Harness.checkEquals(helper, 0, Canaries.hitsFor(owner.getUUID()),
				"and the owner was charged for somebody else's break");
		Harness.checkEquals(helper, 0, Canaries.liveFor(owner.getUUID()),
				"the decoy should still be gone — the block it described no longer exists");

		Canaries.forgetAll();
		helper.succeed();
	}

	@GameTest
	public void anExplosionRetiresWhatItUncovers(GameTestHelper helper) {
		// Explosions destroy blocks with no break event at all. Missing this leaves a decoy
		// standing in a crater, visible to anybody walking past.
		Canaries.forgetAll();
		ServerLevel level = helper.getLevel();
		ServerPlayer player = Harness.mockPlayer(helper);
		BlockPos centre = encasedStone(helper, 1, 2, 1);

		Canaries.placeAt(player, level, centre);
		Canaries.onExplosion(level, List.of(centre.relative(Direction.UP)));

		Harness.checkEquals(helper, 0, Canaries.liveFor(player.getUUID()),
				"a decoy survived the explosion that uncovered it");
		Harness.checkEquals(helper, 0, Canaries.hitsFor(player.getUUID()),
				"an explosion was recorded as somebody finding the decoy");

		Canaries.forgetAll();
		helper.succeed();
	}

	@GameTest
	public void placementRefusesRockThatIsNotSealed(GameTestHelper helper) {
		// A decoy in a wall somebody can see is not a question, it is bait — and the answer it
		// produces is meaningless because the honest explanation is "I looked at it".
		Canaries.forgetAll();
		ServerPlayer player = Harness.mockPlayer(helper);

		BlockPos exposed = encasedStone(helper, 1, 2, 1);
		helper.setBlock(new BlockPos(1, 3, 1), Blocks.AIR);

		Harness.check(helper, !Canaries.wouldPlaceAt(helper.getLevel(), exposed),
				"a position with a face open to air was accepted");

		helper.setBlock(new BlockPos(1, 3, 1), Blocks.STONE);
		Harness.check(helper, Canaries.wouldPlaceAt(helper.getLevel(), exposed),
				"sealing it back up did not make it acceptable, so the check is refusing "
						+ "everything and would place nothing at all");

		Canaries.forgetAll();
		helper.succeed();
	}

	@GameTest
	public void placementRefusesAnythingThatIsNotPlainStone(GameTestHelper helper) {
		// Overwriting a real ore would hide it; overwriting anything with a block entity
		// would show the client a chest that is not there. The whitelist is the safe
		// direction, so an unknown modded block is skipped rather than lied about.
		Canaries.forgetAll();
		BlockPos centre = encasedStone(helper, 1, 2, 1);

		for (var block : List.of(Blocks.DIAMOND_ORE, Blocks.CHEST, Blocks.OAK_PLANKS)) {
			helper.setBlock(new BlockPos(1, 2, 1), block);
			Harness.check(helper, !Canaries.wouldPlaceAt(helper.getLevel(), centre),
					"a decoy would have been placed over " + block.getName().getString());
		}

		Canaries.forgetAll();
		helper.succeed();
	}
}
