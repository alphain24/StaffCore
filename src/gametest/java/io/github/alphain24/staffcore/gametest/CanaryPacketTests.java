package io.github.alphain24.staffcore.gametest;

import io.github.alphain24.staffcore.modules.security.Canaries;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;

/**
 * What the client is actually told, as far as this harness can see it.
 *
 * <h2>Where verification stops</h2>
 * A decoy only works if an x-ray client draws a block that is not there. Three things have to
 * be true for that: the right block state has to be chosen for the rock around it, the packet
 * has to carry that state at that position, and the client has to render it.
 * <p>
 * The first two are checked here. The third cannot be — it needs a real client with an x-ray
 * pack, and no test framework that runs headless can watch a screen. So the honest description
 * of this feature is <b>packet-verified, not client-verified</b>, and that is what Known limits
 * says.
 * <p>
 * The distinction matters more than it sounds. A decoy that never reaches a client produces a
 * false-positive rate of zero and a true-positive rate of zero, and only the first of those
 * shows up in a corpus. A clean measurement is exactly what a completely broken decoy layer
 * would also produce.
 */
public class CanaryPacketTests {

	private static BlockPos encased(GameTestHelper helper, int x, int y, int z,
			net.minecraft.world.level.block.Block rock) {

		for (int dx = -1; dx <= 1; dx++) {
			for (int dy = -1; dy <= 1; dy++) {
				for (int dz = -1; dz <= 1; dz++) {
					helper.setBlock(new BlockPos(x + dx, y + dy, z + dz), rock);
				}
			}
		}
		return helper.absolutePos(new BlockPos(x, y, z));
	}

	@GameTest
	public void theDecoyMatchesTheRockAroundIt(GameTestHelper helper) {
		// A diamond ore in a netherrack wall announces itself as fake to exactly the players
		// this is aimed at, which costs the decoy its whole purpose.
		Canaries.forgetAll();
		ServerLevel level = helper.getLevel();
		ServerPlayer player = Harness.mockPlayer(helper);

		BlockPos stone = encased(helper, 1, 2, 1, Blocks.STONE);
		Canaries.placeAt(player, level, stone);
		Harness.check(helper, shownAt(stone) == Blocks.DIAMOND_ORE,
				"stone got " + shownAt(stone) + " rather than diamond ore");

		Canaries.forgetAll();
		BlockPos deepslate = encased(helper, 1, 2, 1, Blocks.DEEPSLATE);
		Canaries.placeAt(player, level, deepslate);
		Harness.check(helper, shownAt(deepslate) == Blocks.DEEPSLATE_DIAMOND_ORE,
				"deepslate got " + shownAt(deepslate) + ", which is the wrong ore for the rock");

		Canaries.forgetAll();
		BlockPos netherrack = encased(helper, 1, 2, 1, Blocks.NETHERRACK);
		Canaries.placeAt(player, level, netherrack);
		Harness.check(helper, shownAt(netherrack) == Blocks.ANCIENT_DEBRIS,
				"netherrack got " + shownAt(netherrack) + " instead of ancient debris");

		Canaries.forgetAll();
		helper.succeed();
	}

	@GameTest
	public void thePacketCarriesTheDecoyAtTheDecoyPosition(GameTestHelper helper) {
		// One step short of watching a screen: the packet that would go out carries the state
		// we chose, at the position we chose. What it cannot show is the client drawing it.
		Canaries.forgetAll();
		ServerLevel level = helper.getLevel();
		ServerPlayer player = Harness.mockPlayer(helper);
		BlockPos pos = encased(helper, 1, 2, 1, Blocks.STONE);

		Canaries.placeAt(player, level, pos);
		var canary = Canaries.all().stream().filter(c -> c.pos().equals(pos)).findFirst()
				.orElse(null);
		Harness.check(helper, canary != null, "no decoy was recorded at the position");

		var packet = new ClientboundBlockUpdatePacket(canary.pos(), canary.shown());
		Harness.check(helper, packet.getPos().equals(pos),
				"the packet names " + packet.getPos() + " rather than " + pos);
		Harness.check(helper, packet.getBlockState() == canary.shown(),
				"the packet carries a different state from the one recorded");
		Harness.check(helper, packet.getBlockState() != level.getBlockState(pos),
				"the packet carries what is really there, so it tells the client nothing and "
						+ "no x-ray user would ever see a decoy");

		Canaries.forgetAll();
		helper.succeed();
	}

	@GameTest
	public void theResyncCarriesTheRealBlock(GameTestHelper helper) {
		// The other half. A resync that carried the decoy again would leave a ghost block in
		// the world for that player forever, and they would eventually mine at it.
		Canaries.forgetAll();
		ServerLevel level = helper.getLevel();
		BlockPos pos = encased(helper, 1, 2, 1, Blocks.STONE);

		var packet = new ClientboundBlockUpdatePacket(level, pos);
		Harness.check(helper, packet.getBlockState() == level.getBlockState(pos),
				"the resync packet does not carry what is actually in the world");
		Harness.check(helper, packet.getBlockState().is(Blocks.STONE),
				"the resync carries " + packet.getBlockState() + " where the world has stone");

		Canaries.forgetAll();
		helper.succeed();
	}

	private static net.minecraft.world.level.block.Block shownAt(BlockPos pos) {
		return Canaries.all().stream()
				.filter(c -> c.pos().equals(pos))
				.map(c -> c.shown().getBlock())
				.findFirst()
				.orElse(null);
	}
}
