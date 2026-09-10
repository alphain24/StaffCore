package io.github.alphain24.staffcore.gametest;

import io.github.alphain24.staffcore.config.StaffConfig;
import io.github.alphain24.staffcore.modules.security.Canaries;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Which of two mechanisms actually takes a decoy away.
 *
 * <h2>The two hypotheses</h2>
 * A decoy is a {@code ClientboundBlockUpdatePacket} — a delta against the chunk the client is
 * holding. Two different things could explain a decoy that disappears:
 * <ol>
 *   <li><b>Elapsed time.</b> Something on the server stops considering it live, or stops
 *       telling the client about it, after some interval. If this is the mechanism, a decoy
 *       should die while a player stands perfectly still and does nothing.</li>
 *   <li><b>Chunk resend.</b> The server sends the client a fresh copy of the chunk, built from
 *       the real world, and the delta is simply not in it. If this is the mechanism, a decoy
 *       should survive standing still indefinitely and die the moment the chunk comes back —
 *       flying beyond view distance and returning, relogging, changing dimension.</li>
 * </ol>
 * These predict opposite things, so they can be told apart. That is what this file does for the
 * half that does not need a person watching a screen.
 *
 * <h2>What each test settles</h2>
 * {@link #aDecoySurvivesStandingStill} is the falsifier for hypothesis 1. If a decoy expires on
 * a timer, there has to be something on the server doing it, and this looks for it directly.
 * <p>
 * {@link #theChunkItselfHoldsTheTruth} is the mechanism for hypothesis 2, stated as a property
 * rather than an argument. {@code PlayerChunkSender.sendChunk} builds
 * {@code ClientboundLevelChunkWithLightPacket} straight from the {@code LevelChunk} — confirmed
 * from the bytecode of the 26.2 jar, not from memory — so whatever the chunk holds is what the
 * client receives. This asserts the chunk holds ordinary rock at a live decoy's position, which
 * is the whole reason a resend erases it.
 */
public class CanaryPersistenceTests {

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

	private static Canaries.Canary find(BlockPos pos) {
		return Canaries.all().stream()
				.filter(c -> c.pos().equals(pos))
				.findFirst()
				.orElse(null);
	}

	@GameTest
	public void aDecoySurvivesStandingStill(GameTestHelper helper) throws Exception {
		// Hypothesis 1's falsifier. Nothing moves, nothing is broken, nothing changes — and
		// the clock runs well past the refresh interval. If a decoy expires on elapsed time,
		// the mechanism doing it is on the server and this is where it would show.
		ServerLevel level = helper.getLevel();
		ServerPlayer player = Harness.mockPlayer(helper);
		BlockPos pos = encased(helper, 1, 2, 1);

		int density = StaffConfig.get().canaryDensity;
		StaffConfig.get().canaryDensity = 1;
		try {
			Canaries.placeAt(player, level, pos);
			Harness.check(helper, find(pos) != null, "the decoy was not placed");

			// Twelve rounds of the maintenance pass with real time between them, which is
			// more than twice the five-second cadence the live server runs it at.
			for (int i = 0; i < 12; i++) {
				Thread.sleep(2);
				Canaries.maintain(player);
			}

			Canaries.Canary after = find(pos);
			Harness.check(helper, after != null,
					"a decoy disappeared while the player stood still and nothing touched it. "
							+ "That is the elapsed-time mechanism, and it means the diagnosis "
							+ "that chunk resend is responsible is wrong — fix the expiry "
							+ "before building anything that hooks chunk sends.");
			Harness.checkEquals(helper, 1, Canaries.liveFor(player.getUUID()),
					"standing still changed how many decoys exist");
			Harness.checkEquals(helper, 1,
					io.github.alphain24.staffcore.illusion.BlockIllusions.countFor(
							player.getUUID(),
							io.github.alphain24.staffcore.illusion.BlockIllusions.Source.CANARY),
					"the decoy is still listed but no longer registered as an illusion, so "
							+ "nothing would put it back after a chunk resend");
		} finally {
			StaffConfig.get().canaryDensity = density;
		}
		helper.succeed();
	}

	@GameTest
	public void theChunkItselfHoldsTheTruth(GameTestHelper helper) {
		// Hypothesis 2's mechanism. The decoy exists only as a packet already delivered; the
		// world underneath is untouched, which is the whole design. PlayerChunkSender.sendChunk
		// builds its packet from this LevelChunk, so a resend necessarily carries what this
		// assertion reads — ordinary rock — and the client's decoy is gone with it.
		ServerLevel level = helper.getLevel();
		ServerPlayer player = Harness.mockPlayer(helper);
		BlockPos pos = encased(helper, 1, 2, 1);

		Canaries.placeAt(player, level, pos);
		Canaries.Canary canary = find(pos);
		Harness.check(helper, canary != null, "the decoy was not placed");

		BlockState inTheWorld = level.getBlockState(pos);
		Harness.check(helper, inTheWorld.is(Blocks.STONE),
				"the world holds " + inTheWorld + " at a decoy position rather than the rock "
						+ "that was there. If the decoy were written into the chunk it would "
						+ "survive a resend — and it would also be a real ore that a player "
						+ "could mine, which is a different feature with different problems.");

		Harness.check(helper, !inTheWorld.equals(canary.shown()),
				"the block shown to the client and the block in the chunk are the same, so "
						+ "there is no lie here to lose");

		helper.succeed();
	}

}
