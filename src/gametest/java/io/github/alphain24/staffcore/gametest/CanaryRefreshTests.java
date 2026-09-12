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
 * The maintenance pass, now that re-asserting is not its job.
 *
 * <h2>What this file used to say, and why it changed</h2>
 * It used to pin the claim that a decoy is <b>told to the client again on a timer</b>. That was
 * the first fix for the persistence bug and it worked, in the sense that decoys stopped
 * disappearing permanently. It was still the wrong shape:
 * <ul>
 *   <li>it polled a problem that has an exact event — the chunk being sent;</li>
 *   <li>it cost packets proportional to decoys times players, forever, for a picture that had
 *       almost never changed;</li>
 *   <li>it left up to five seconds in which the client saw ordinary rock; and</li>
 *   <li><b>it made the ore flicker</b>, which teaches an observant x-ray user to distrust
 *       exactly the blocks that would otherwise have caught them.</li>
 * </ul>
 * Re-asserting now happens in {@link BlockIllusions}, driven by a hook on the chunk-send path,
 * in the same call that erased it. {@code IllusionPersistenceTests} covers that.
 * <p>
 * The test asserting the old behaviour was deleted rather than adjusted to pass. It was
 * describing a design decision that has been reversed, and a test kept alive past the claim it
 * was making is worse than no test — it reads as a requirement.
 *
 * <h2>What is left for the timer</h2>
 * Validation, which genuinely has no event. A decoy describes a position that was plain stone
 * when it was placed, and blocks change without a break event — a rollback putting things back,
 * a piston, flowing water, an admin with WorldEdit.
 */
public class CanaryRefreshTests {

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

	@GameTest
	public void aDecoyOverChangedRockIsRetired(GameTestHelper helper) {
		// Blocks change without a break event: a rollback putting things back, a piston,
		// flowing water, an admin with WorldEdit. A decoy over a position that is now air is a
		// diamond floating in a tunnel, and nothing else would ever notice.
		ServerLevel level = helper.getLevel();
		ServerPlayer player = Harness.mockPlayer(helper);
		BlockPos pos = encased(helper, 1, 2, 1);

		Canaries.placeAt(player, level, pos);
		Harness.checkEquals(helper, 1, Canaries.liveFor(player.getUUID()), "not placed");

		// Changed behind the mod's back — no break event, no explosion.
		level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
		Canaries.maintain(player);

		Harness.checkEquals(helper, 0, Canaries.liveFor(player.getUUID()),
				"a decoy survived the rock underneath it being replaced, so the client is "
						+ "being shown an ore floating in open air");
		Harness.checkEquals(helper, 0,
				BlockIllusions.countFor(player.getUUID(), BlockIllusions.Source.CANARY),
				"the decoy was retired but its illusion stayed registered, so the chunk-send "
						+ "hook will draw the floating ore again on the next resend");

		helper.succeed();
	}

	@GameTest
	public void validationDoesNotCountAsFindingOne(GameTestHelper helper) {
		// The failure that would make maintenance far worse than anything it fixes. Walking
		// the decoys to check them must not touch the hit counter, or every player
		// accumulates hits simply by existing near their own decoys.
		ServerLevel level = helper.getLevel();
		ServerPlayer player = Harness.mockPlayer(helper);
		BlockPos pos = encased(helper, 1, 2, 1);

		Canaries.placeAt(player, level, pos);
		for (int i = 0; i < 5; i++) Canaries.maintain(player);

		Harness.checkEquals(helper, 0, Canaries.hitsFor(player.getUUID()),
				"the maintenance pass was recorded as the player finding a decoy");
		Harness.checkEquals(helper, 1, Canaries.liveFor(player.getUUID()),
				"repeated maintenance changed how many decoys exist");

		helper.succeed();
	}

	@GameTest
	public void aRetiredDecoyIsNeverBroughtBack(GameTestHelper helper) {
		// A decoy retired by a neighbour break has already had the truth sent to the client.
		// Nothing may put it back — not the maintenance pass, and not the chunk-send hook —
		// or a miner the retirement rule has deliberately let off finds the fake ore on their
		// screen again.
		ServerLevel level = helper.getLevel();
		ServerPlayer player = Harness.mockPlayer(helper);
		BlockPos pos = encased(helper, 1, 2, 1);

		Canaries.placeAt(player, level, pos);
		Canaries.onBreak(level, player, pos.north());
		Harness.checkEquals(helper, 0, Canaries.liveFor(player.getUUID()), "not retired");

		Canaries.maintain(player);
		boolean back = Canaries.all().stream().anyMatch(c -> c.pos().equals(pos));

		Harness.check(helper, !back,
				"a retired decoy came back on the next maintenance pass, which undoes the "
						+ "rule that protects honest miners after it has already fired");
		Harness.checkEquals(helper, 0,
				BlockIllusions.onChunkSent(player, level, pos.getX() >> 4, pos.getZ() >> 4),
				"a retired decoy was re-asserted when its chunk was sent");

		helper.succeed();
	}
}
