package io.github.alphain24.staffcore.compat;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which blocks count as chests, and whether they can pair.
 * <p>
 * The pairing check used to ask whether the two halves were literally the same block. Vanilla
 * does not: {@code ChestBlock.chestCanConnectTo} is {@code state.is(this)}, which is identity
 * and right for oak, while {@code CopperChestBlock} overrides it to test the
 * {@code COPPER_CHESTS} tag — so any copper chest pairs with any other, whatever its weather
 * state or waxing. An identity check reads an ordinary copper double chest as two unrelated
 * singles, and everything downstream inherits that: the log query looks at one coordinate
 * instead of two, the theft undo puts back half of what was taken, and the pairing repair
 * demotes a valid double to a single.
 * <p>
 * Minecraft 26.2 shipped copper chests in four weather states plus waxed variants, so this
 * stopped being hypothetical the moment the version landed. The fix is to ask the block rather
 * than compare it.
 */
class ChestPairingTest {

	@BeforeAll
	static void bootstrap() {
		net.minecraft.SharedConstants.tryDetectVersion();
		net.minecraft.server.Bootstrap.bootStrap();
	}

	private static List<ChestBlock> chestBlocks() {
		List<ChestBlock> out = new ArrayList<>();
		for (Block block : BuiltInRegistries.BLOCK) {
			if (block instanceof ChestBlock chest) out.add(chest);
		}
		return out;
	}

	@Test
	@DisplayName("copper chests are chests, so every chest-aware path already covers them")
	void copperChestsAreChestBlocks() {
		List<ChestBlock> chests = chestBlocks();

		assertTrue(chests.size() > 3,
				"26.2 has far more than oak and trapped — found " + chests.size());

		long copper = chests.stream()
				.filter(c -> BuiltInRegistries.BLOCK.getKey(c).getPath().contains("copper"))
				.count();
		assertTrue(copper > 0, "copper chests should be present and should extend ChestBlock");
	}

	@Test
	@DisplayName("every chest block carries the properties the pairing logic reads")
	void everyChestHasPairingProperties() {
		for (ChestBlock chest : chestBlocks()) {
			BlockState state = chest.defaultBlockState();
			String name = String.valueOf(BuiltInRegistries.BLOCK.getKey(chest));

			// getValue throws when a property is absent, so anything reading these without
			// checking would take down whatever event it was running inside.
			assertTrue(state.hasProperty(ChestBlock.TYPE), name + " should have a chest type");
			assertTrue(state.hasProperty(ChestBlock.FACING), name + " should have a facing");
		}
	}

	@Test
	@DisplayName("copper chests decide pairing by tag, not by being the same block")
	void copperOverridesTheConnectionRule() throws Exception {
		Class<?> copper = Class.forName("net.minecraft.world.level.block.CopperChestBlock");

		// ChestBlock.chestCanConnectTo is `state.is(this)` — identity, which is right for oak
		// and wrong for copper: CopperChestBlock replaces it with a check against the
		// COPPER_CHESTS tag, so any copper chest pairs with any other whatever its weather
		// state or waxing. Comparing blocks for equality reads those as unrelated singles.
		//
		// The tag behaviour itself needs a loaded datapack and cannot be exercised here — a
		// bare bootstrap has no tags, so `state.is(tag)` is false for everything. What is
		// checkable is that the rule is overridden at all, which is the whole reason asking
		// the block beats guessing.
		assertTrue(declaresConnectionRule(copper),
				"CopperChestBlock should define its own chestCanConnectTo; if it stopped "
						+ "doing so, identity would be the right test again and this fix would "
						+ "be worth revisiting");
	}

	private static boolean declaresConnectionRule(Class<?> type) {
		for (var method : type.getDeclaredMethods()) {
			if (method.getName().equals("chestCanConnectTo")) return true;
		}
		return false;
	}

	@Test
	@DisplayName("a chest never claims a partner it cannot connect to")
	void connectionIsNotUniversal() {
		List<ChestBlock> chests = chestBlocks();
		ChestBlock plain = chests.stream()
				.filter(c -> "chest".equals(BuiltInRegistries.BLOCK.getKey(c).getPath()))
				.findFirst().orElseThrow();

		// If everything connected to everything the check would be worthless in the other
		// direction, so pin that it still says no to something.
		assertFalse(plain.chestCanConnectTo(net.minecraft.world.level.block.Blocks.STONE
				.defaultBlockState()), "a chest does not pair with stone");
		assertTrue(plain.chestCanConnectTo(plain.defaultBlockState()),
				"but it does pair with another of itself");
	}
}
