package io.github.alphain24.staffcore.gametest;

import io.github.alphain24.staffcore.illusion.BlockIllusions;
import io.github.alphain24.staffcore.modules.security.Canaries;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;

/**
 * The fix for the packet-persistence bug, at the one place it now lives.
 *
 * <h2>What was wrong</h2>
 * Three features showed one player a block that is not there — canary decoys, the replay
 * overlay, the rollback preview — and all three sent a {@code ClientboundBlockUpdatePacket} and
 * forgot about it. That packet is a delta against the chunk the client holds. Any resend of that
 * chunk hands the client the honest data and the lie is gone, with nothing on the server aware
 * of it.
 * <p>
 * {@link BlockIllusions} now remembers every one, indexed by chunk, and a hook on
 * {@code PlayerChunkSender.sendChunk} re-asserts them in the same call that erased them.
 *
 * <h2>Why these test the primitive and not the three features</h2>
 * Because the bug was never really about decoys. It was about a protocol fact that all three
 * had reasoned about separately and got wrong the same way. Testing it three times would be
 * three chances to fix two of them.
 */
public class IllusionPersistenceTests {

	private static BlockPos encased(GameTestHelper helper, int x, int y, int z) {
		for (int dx = -1; dx <= 1; dx++) {
			for (int dy = -1; dy <= 1; dy++) {
				for (int dz = -1; dz <= 1; dz++) {
					helper.setBlock(new BlockPos(x + dx, y + dy, z + dz), Blocks.STONE);
				}
			}
		}
		return helper.absolutePos(new BlockPos(x, y, z));
	}

	private static int chunkX(BlockPos pos) {
		return pos.getX() >> 4;
	}

	private static int chunkZ(BlockPos pos) {
		return pos.getZ() >> 4;
	}

	@GameTest
	public void aDecoyIsReAssertedWhenItsChunkIsSent(GameTestHelper helper) {
		// The regression. Before the hook, a chunk going to a player wiped their decoys and
		// the only thing that put them back was a five-second poll — so the client showed the
		// truth for up to five seconds, and the ore flickered, which teaches an x-ray user to
		// distrust exactly the blocks that would have caught them.
		ServerLevel level = helper.getLevel();
		ServerPlayer player = Harness.mockPlayer(helper);
		BlockPos pos = encased(helper, 1, 2, 1);

		Harness.check(helper, Canaries.placeAt(player, level, pos), "the decoy was not placed");
		Harness.checkEquals(helper, 1,
				BlockIllusions.countFor(player.getUUID(), BlockIllusions.Source.CANARY),
				"placing a decoy did not register an illusion, so nothing can put it back");

		int sent = BlockIllusions.onChunkSent(player, level, chunkX(pos), chunkZ(pos));

		Harness.checkEquals(helper, 1, sent,
				"a chunk holding one live decoy was sent to its owner and nothing was "
						+ "re-asserted. The client has just been handed the honest chunk, so "
						+ "the decoy is gone from their screen while the server goes on "
						+ "listing it.");
		Harness.checkEquals(helper, 0, Canaries.hitsFor(player.getUUID()),
				"re-asserting a decoy after a chunk send was recorded as the player finding it");

		helper.succeed();
	}

	@GameTest
	public void aChunkWithNothingInItCostsNothing(GameTestHelper helper) {
		// The control, and the thing that decides whether this hook is affordable at all. It
		// runs for every chunk sent to every player for the life of the server, and on almost
		// all of them there is nothing to do.
		ServerLevel level = helper.getLevel();
		ServerPlayer player = Harness.mockPlayer(helper);

		Harness.checkEquals(helper, 0, BlockIllusions.onChunkSent(player, level, 1000, 1000),
				"a chunk with nothing in it re-asserted something");

		BlockPos pos = encased(helper, 1, 2, 1);
		Canaries.placeAt(player, level, pos);

		Harness.checkEquals(helper, 0,
				BlockIllusions.onChunkSent(player, level, chunkX(pos) + 8, chunkZ(pos)),
				"an illusion in one chunk was re-sent when a different chunk went out. The "
						+ "store is meant to be indexed by chunk, so this hook does work "
						+ "proportional to what is in the chunk that moved rather than to "
						+ "everything the player has.");
		Harness.checkEquals(helper, 1, BlockIllusions.chunksFor(player.getUUID()),
				"one decoy should occupy exactly one chunk in the index");

		helper.succeed();
	}

	@GameTest
	public void aRetiredDecoyIsNotReAsserted(GameTestHelper helper) {
		// The other direction, and the one that would turn this fix into something worse than
		// the bug. A decoy retired by a neighbour break has already been resynced — the client
		// has been told the truth. If the chunk hook then put it back, an honest miner who was
		// deliberately let off would find the fake ore on their screen again.
		ServerLevel level = helper.getLevel();
		ServerPlayer player = Harness.mockPlayer(helper);
		BlockPos pos = encased(helper, 1, 2, 1);

		Canaries.placeAt(player, level, pos);
		Canaries.onBreak(level, player, pos.north());
		Harness.checkEquals(helper, 0, Canaries.liveFor(player.getUUID()), "not retired");

		Harness.checkEquals(helper, 0,
				BlockIllusions.countFor(player.getUUID(), BlockIllusions.Source.CANARY),
				"retiring a decoy left its illusion registered, so the chunk-send hook will "
						+ "draw it again on the next resend — putting a fake ore back on the "
						+ "screen of somebody the retirement rule has already cleared");
		Harness.checkEquals(helper, 0,
				BlockIllusions.onChunkSent(player, level, chunkX(pos), chunkZ(pos)),
				"a retired decoy was re-asserted when its chunk was sent");

		helper.succeed();
	}

	@GameTest
	public void oneSourceComingDownLeavesTheOthersUp(GameTestHelper helper) {
		// A staff member can hold live decoys while watching a replay. Clearing the replay
		// must not hand their client the truth about a decoy, and vice versa — which is the
		// whole reason an illusion records who owns it rather than just what it shows.
		ServerLevel level = helper.getLevel();
		ServerPlayer player = Harness.mockPlayer(helper);
		BlockPos decoy = encased(helper, 1, 2, 1);
		BlockPos overlay = encased(helper, 5, 2, 5);

		Canaries.placeAt(player, level, decoy);
		BlockIllusions.show(player, level, BlockIllusions.Source.REPLAY, overlay,
				Blocks.TINTED_GLASS.defaultBlockState());

		Harness.checkEquals(helper, 1,
				BlockIllusions.countFor(player.getUUID(), BlockIllusions.Source.CANARY), "decoy");
		Harness.checkEquals(helper, 1,
				BlockIllusions.countFor(player.getUUID(), BlockIllusions.Source.REPLAY), "overlay");

		BlockIllusions.clear(player, level, BlockIllusions.Source.REPLAY);

		Harness.checkEquals(helper, 0,
				BlockIllusions.countFor(player.getUUID(), BlockIllusions.Source.REPLAY),
				"clearing the replay overlay left it registered");
		Harness.checkEquals(helper, 1,
				BlockIllusions.countFor(player.getUUID(), BlockIllusions.Source.CANARY),
				"clearing the replay overlay also took down a decoy. The two are independent, "
						+ "and a staff member leaving a replay must not be told the truth "
						+ "about the canaries they are carrying.");

		helper.succeed();
	}

	@GameTest
	public void anIllusionDoesNotFollowTheChunkIntoAnotherWorld(GameTestHelper helper) {
		// The defect: a chunk key is x and z, and a block update packet carries no dimension
		// at all — it applies wherever the client happens to be. So an illusion registered in
		// the Overworld at chunk (4, -9) was re-asserted the instant its owner loaded chunk
		// (4, -9) in the Nether, and drawn there.
		//
		// It hit all three features at once, and it is invisible in the store because the
		// defect is in what the key was missing rather than in anything it held.
		ServerLevel here = helper.getLevel();
		ServerPlayer player = Harness.mockPlayer(helper);
		BlockPos pos = encased(helper, 1, 2, 1);

		Canaries.placeAt(player, here, pos);
		Harness.checkEquals(helper, 1,
				BlockIllusions.onChunkSent(player, here, chunkX(pos), chunkZ(pos)),
				"the decoy was not re-asserted in its own world, so this test cannot show "
						+ "that it is withheld from another one");

		ServerLevel elsewhere = null;
		for (ServerLevel level : Harness.server(helper).getAllLevels()) {
			if (level != here) {
				elsewhere = level;
				break;
			}
		}
		Harness.check(helper, elsewhere != null,
				"this server has only one world, so the cross-world case cannot be exercised");

		Harness.checkEquals(helper, 0,
				BlockIllusions.onChunkSent(player, elsewhere, chunkX(pos), chunkZ(pos)),
				"an illusion from one world was re-asserted when the same chunk coordinates "
						+ "were sent in another. A block update has no dimension, so it would "
						+ "be drawn there: a fake ore in netherrack at coordinates nobody "
						+ "chose, put back every time that chunk arrives.");

		helper.succeed();
	}

	@GameTest
	public void aDisconnectDropsEverythingWithoutSending(GameTestHelper helper) {
		// A reconnecting client is sent honest chunks from scratch, so there is nothing to
		// correct. What matters is that nothing stays registered — otherwise the chunk-send
		// hook redraws illusions for a session that has ended.
		ServerLevel level = helper.getLevel();
		ServerPlayer player = Harness.mockPlayer(helper);
		BlockPos pos = encased(helper, 1, 2, 1);

		Canaries.placeAt(player, level, pos);
		Canaries.forget(player.getUUID());

		Harness.checkEquals(helper, 0, BlockIllusions.chunksFor(player.getUUID()),
				"a disconnect left illusions registered for a player who is gone");
		Harness.checkEquals(helper, 0,
				BlockIllusions.onChunkSent(player, level, chunkX(pos), chunkZ(pos)),
				"illusions were re-asserted for a player who had disconnected");

		helper.succeed();
	}
}
