package io.github.alphain24.staffcore.gametest;

import io.github.alphain24.staffcore.config.StaffConfig;
import io.github.alphain24.staffcore.modules.security.Canaries;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;

/**
 * A decoy is a packet, not a fact, and it has to be said again.
 *
 * <h2>The bug these pin</h2>
 * A {@code ClientboundBlockUpdatePacket} is a delta against the chunk the client is holding at
 * that moment. The first implementation sent one per decoy at placement and never again, and
 * {@code maintain} returned early once a player had their full complement — so no decoy packet
 * ever went out a second time.
 * <p>
 * The moment the client reloaded that chunk — view distance, a relog, a dimension change, any
 * resend the server does for its own reasons — it received the honest chunk and the decoy
 * vanished from the screen. The server carried on listing it in {@code /staff canary}, because
 * from the server's side nothing had changed.
 * <p>
 * It was found by a person testing with an x-ray pack and reporting that decoys were "hit or
 * miss". No test caught it, because every test asked the server what it believed.
 *
 * <h2>Why these are worth having in this shape</h2>
 * Forcing a real chunk resend inside a gametest is not practical. What is testable is the
 * property that makes the feature survive one: that the client is told again, on a timer,
 * whether or not the server thinks anything has changed.
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

	private static long refreshedAt(BlockPos pos) {
		return Canaries.all().stream()
				.filter(c -> c.pos().equals(pos))
				.mapToLong(Canaries.Canary::refreshedAt)
				.findFirst()
				.orElse(-1);
	}

	@GameTest
	public void anExistingDecoyIsToldToTheClientAgain(GameTestHelper helper) throws Exception {
		// The regression. Before this, maintain returned early once the player was at full
		// density and the client was never told anything again — so a chunk reload silently
		// ended the decoy while the server went on counting it.
		Canaries.forgetAll();
		ServerLevel level = helper.getLevel();
		ServerPlayer player = Harness.mockPlayer(helper);
		BlockPos pos = encased(helper, 1, 2, 1);

		int density = StaffConfig.get().canaryDensity;
		StaffConfig.get().canaryDensity = 1;
		try {
			Canaries.placeAt(player, level, pos);
			long first = refreshedAt(pos);
			Harness.check(helper, first > 0, "the decoy was not placed");

			Thread.sleep(3);
			Canaries.maintain(player);

			Harness.check(helper, refreshedAt(pos) > first,
					"a player already at full density was not told about their decoy again. "
							+ "The client loses it on the next chunk resend and the server "
							+ "never notices.");
		} finally {
			StaffConfig.get().canaryDensity = density;
			Canaries.forgetAll();
		}
		helper.succeed();
	}

	@GameTest
	public void aDecoyOverChangedRockIsRetired(GameTestHelper helper) {
		// Blocks change without a break event: a rollback putting things back, a piston,
		// flowing water, an admin with WorldEdit. A decoy over a position that is now air is a
		// diamond floating in a tunnel, and nothing else would ever notice.
		Canaries.forgetAll();
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

		Canaries.forgetAll();
		helper.succeed();
	}

	@GameTest
	public void refreshingDoesNotCountAsFindingOne(GameTestHelper helper) {
		// The failure that would turn this fix into something far worse than the bug. Re-sending
		// must not touch the hit counter, or every player accumulates hits just by standing
		// near their own decoys.
		Canaries.forgetAll();
		ServerLevel level = helper.getLevel();
		ServerPlayer player = Harness.mockPlayer(helper);
		BlockPos pos = encased(helper, 1, 2, 1);

		Canaries.placeAt(player, level, pos);
		for (int i = 0; i < 5; i++) Canaries.maintain(player);

		Harness.checkEquals(helper, 0, Canaries.hitsFor(player.getUUID()),
				"re-sending a decoy was recorded as the player finding it");
		Harness.checkEquals(helper, 1, Canaries.liveFor(player.getUUID()),
				"repeated refreshes changed how many decoys exist");

		Canaries.forgetAll();
		helper.succeed();
	}

	@GameTest
	public void aRetiredDecoyIsNeverResent(GameTestHelper helper) {
		// The other direction. A decoy retired by a neighbour break must not come back on the
		// next refresh — that would undo the retirement rule the whole false-positive
		// measurement rests on.
		Canaries.forgetAll();
		ServerLevel level = helper.getLevel();
		ServerPlayer player = Harness.mockPlayer(helper);
		BlockPos pos = encased(helper, 1, 2, 1);

		Canaries.placeAt(player, level, pos);
		Canaries.onBreak(level, player, pos.north());
		Harness.checkEquals(helper, 0, Canaries.liveFor(player.getUUID()), "not retired");

		Canaries.maintain(player);
		boolean back = Canaries.all().stream().anyMatch(c -> c.pos().equals(pos));

		Harness.check(helper, !back,
				"a retired decoy was re-sent, which puts the ore back on the client's screen "
						+ "after the rule that protects honest miners has already fired");

		Canaries.forgetAll();
		helper.succeed();
	}
}
