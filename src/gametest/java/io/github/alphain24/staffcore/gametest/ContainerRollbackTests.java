package io.github.alphain24.staffcore.gametest;

import io.github.alphain24.staffcore.compat.Mc;
import io.github.alphain24.staffcore.module.Mods;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/**
 * Rolling back what somebody did to a chest, and nobody ending up with a copy.
 * <p>
 * Each test drives the real container watch — open, change, close — so what is rolled back is
 * what the log actually wrote, and each uses a player with a name of its own so the rollback
 * charges that player rather than whichever test player the server finds first.
 */
public class ContainerRollbackTests {

	private static int count(Container container, Item item) {
		int total = 0;
		for (int slot = 0; slot < container.getContainerSize(); slot++) {
			ItemStack stack = container.getItem(slot);
			if (stack.is(item)) total += stack.getCount();
		}
		return total;
	}

	private static int held(ServerPlayer player, Item item) {
		return count(player.getInventory(), item);
	}

	private static Container chest(GameTestHelper helper, BlockPos relative) {
		helper.setBlock(relative, Blocks.CHEST);
		return Mc.containerAt(helper.getLevel(), helper.absolutePos(relative));
	}

	@GameTest
	public void rollingBackATheftTakesTheItemsOffTheThief(GameTestHelper helper) {
		ServerPlayer thief = Harness.namedPlayer(helper);
		ServerLevel level = helper.getLevel();
		BlockPos rel = new BlockPos(2, 2, 2);
		Container chest = chest(helper, rel);
		BlockPos pos = helper.absolutePos(rel);
		chest.setItem(0, new ItemStack(Items.DIAMOND, 10));

		// Opened, five taken into their inventory, closed — exactly what a theft logs.
		Mods.grief().containers().onOpen(thief, pos, chest, Mc.dimensionId(level));
		chest.getItem(0).shrink(5);
		thief.getInventory().add(new ItemStack(Items.DIAMOND, 5));
		Mods.grief().onContainerClosed(thief);
		Mods.grief().awaitWrites();

		Mods.grief().rollback(level, null, pos, 4, 60_000L, false);

		Harness.checkEquals(helper, 10, count(chest, Items.DIAMOND), "diamonds back in the chest");
		Harness.checkEquals(helper, 0, held(thief, Items.DIAMOND),
				"diamonds the thief still holds after the chest got them back — a duplication");
		helper.succeed();
	}

	@GameTest
	public void rollingBackAPutGivesTheItemsBackToWhoeverPutThem(GameTestHelper helper) {
		ServerPlayer player = Harness.namedPlayer(helper);
		ServerLevel level = helper.getLevel();
		BlockPos rel = new BlockPos(2, 2, 2);
		Container chest = chest(helper, rel);
		BlockPos pos = helper.absolutePos(rel);

		Mods.grief().containers().onOpen(player, pos, chest, Mc.dimensionId(level));
		chest.setItem(3, new ItemStack(Items.COBBLESTONE, 32));
		Mods.grief().onContainerClosed(player);
		Mods.grief().awaitWrites();

		Mods.grief().rollback(level, null, pos, 4, 60_000L, false);

		Harness.checkEquals(helper, 0, count(chest, Items.COBBLESTONE), "the put was not undone");
		Harness.checkEquals(helper, 32, held(player, Items.COBBLESTONE),
				"the items taken back out of the chest were deleted rather than returned");
		helper.succeed();
	}

	@GameTest
	public void takingAndPuttingTheSameItemsBackChargesNothing(GameTestHelper helper) {
		ServerPlayer player = Harness.namedPlayer(helper);
		ServerLevel level = helper.getLevel();
		BlockPos relA = new BlockPos(1, 2, 1);
		BlockPos relB = new BlockPos(3, 2, 3);
		Container a = chest(helper, relA);
		Container b = chest(helper, relB);
		BlockPos posA = helper.absolutePos(relA);
		BlockPos posB = helper.absolutePos(relB);
		a.setItem(0, new ItemStack(Items.GOLD_INGOT, 8));
		String world = Mc.dimensionId(level);

		// Moved from one chest to the other, through their hands, ending with nothing held.
		Mods.grief().containers().onOpen(player, posA, a, world);
		a.setItem(0, ItemStack.EMPTY);
		Mods.grief().onContainerClosed(player);
		Mods.grief().containers().onOpen(player, posB, b, world);
		b.setItem(0, new ItemStack(Items.GOLD_INGOT, 8));
		Mods.grief().onContainerClosed(player);
		Mods.grief().awaitWrites();
		player.getInventory().add(new ItemStack(Items.GOLD_INGOT, 3)); // their own, unrelated

		Mods.grief().rollback(level, null, posA, 6, 60_000L, false);

		Harness.checkEquals(helper, 8, count(a, Items.GOLD_INGOT), "gold back where it started");
		Harness.checkEquals(helper, 0, count(b, Items.GOLD_INGOT), "gold still in the second chest");
		Harness.checkEquals(helper, 3, held(player, Items.GOLD_INGOT),
				"the player was charged for items the rollback had already found in the other chest");
		helper.succeed();
	}
}
