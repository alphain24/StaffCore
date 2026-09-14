package io.github.alphain24.staffcore.gametest;

import io.github.alphain24.staffcore.compat.Mc;
import io.github.alphain24.staffcore.module.Mods;
import io.github.alphain24.staffcore.permission.Actor;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * A container destroyed and rolled back comes back, with what was in it — broken by hand through
 * the game's own block breaking, and blown up.
 */
public class ContainerBreakRollbackTests {

	private static int count(Container container, Item item) {
		int total = 0;
		for (int slot = 0; slot < container.getContainerSize(); slot++) {
			ItemStack stack = container.getItem(slot);
			if (stack.is(item)) total += stack.getCount();
		}
		return total;
	}

	private static Container filled(GameTestHelper helper, BlockPos rel, Block block) {
		helper.setBlock(rel, block);
		Container container = Mc.containerAt(helper.getLevel(), helper.absolutePos(rel));
		container.setItem(0, new ItemStack(Items.DIAMOND, 5));
		container.setItem(1, new ItemStack(Items.COBBLESTONE, 32));
		container.setChanged();
		return container;
	}

	private static String describe(ServerLevel level, BlockPos pos) {
		Container c = Mc.containerAt(level, pos);
		return level.getBlockState(pos) + (c == null ? " (no container)"
				: " diamonds=" + count(c, Items.DIAMOND) + " cobble=" + count(c, Items.COBBLESTONE));
	}

	private void breakByHandAndRollBack(GameTestHelper helper, Block block) {
		ServerPlayer griefer = Harness.namedPlayer(helper);
		ServerLevel level = helper.getLevel();
		BlockPos rel = new BlockPos(2, 2, 2);
		BlockPos pos = helper.absolutePos(rel);
		filled(helper, rel, block);

		griefer.snapTo(Vec3.atBottomCenterOf(pos.north()));
		Harness.check(helper, griefer.gameMode.destroyBlock(pos), "the game refused the break");
		Harness.check(helper, level.getBlockState(pos).isAir(), "the container is still there");
		Mods.grief().awaitWrites();

		var result = Mods.grief().rollback(level, Harness.name(griefer), pos, 6, 60_000L, false,
				Actor.console());
		Mods.grief().awaitWrites();

		Container back = Mc.containerAt(level, pos);
		Harness.check(helper, level.getBlockState(pos).is(block),
				"the " + block.getName().getString() + " did not come back: " + describe(level, pos)
						+ ", result " + result);
		Harness.check(helper, back != null && count(back, Items.DIAMOND) == 5
						&& count(back, Items.COBBLESTONE) == 32,
				"the contents did not come back: " + describe(level, pos));
		long loose = level.getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(4)).stream()
				.filter(e -> e.getItem().is(Items.DIAMOND)).count();
		Harness.check(helper, loose == 0, "the dropped diamonds are still on the ground, so they were duplicated");
		helper.succeed();
	}

	@GameTest(maxTicks = 100)
	public void aChestBrokenByHandComesBackWithItsContents(GameTestHelper helper) {
		breakByHandAndRollBack(helper, Blocks.CHEST);
	}

	@GameTest(maxTicks = 100)
	public void aBarrelBrokenByHandComesBackWithItsContents(GameTestHelper helper) {
		breakByHandAndRollBack(helper, Blocks.BARREL);
	}

	@GameTest(maxTicks = 100)
	public void aShulkerBoxBrokenByHandComesBackWithItsContents(GameTestHelper helper) {
		breakByHandAndRollBack(helper, Blocks.SHULKER_BOX);
	}

	@GameTest(maxTicks = 100)
	public void aChestBlownUpComesBackWithItsContents(GameTestHelper helper) {
		ServerPlayer griefer = Harness.namedPlayer(helper);
		ServerLevel level = helper.getLevel();
		BlockPos rel = new BlockPos(2, 2, 2);
		BlockPos pos = helper.absolutePos(rel);
		filled(helper, rel, Blocks.CHEST);

		Vec3 c = Vec3.atCenterOf(pos.east());
		level.explode(null, level.damageSources().explosion(null, griefer), null,
				c.x, c.y, c.z, 3.0F, false, Level.ExplosionInteraction.TNT);
		Harness.check(helper, level.getBlockState(pos).isAir(), "the blast did not destroy the chest");
		Mods.grief().awaitWrites();

		var result = Mods.grief().rollback(level, null, pos, 6, 60_000L, false, Actor.console());
		Mods.grief().awaitWrites();

		Container back = Mc.containerAt(level, pos);
		Harness.check(helper, level.getBlockState(pos).is(Blocks.CHEST),
				"the chest did not come back after the blast: " + describe(level, pos) + ", result " + result);
		Harness.check(helper, back != null && count(back, Items.DIAMOND) == 5,
				"the chest came back without its contents: " + describe(level, pos));
		helper.succeed();
	}

	@GameTest(maxTicks = 100)
	public void takingSomeThenBreakingTheChestPutsEverythingBack(GameTestHelper helper) {
		// The commonest grief there is: help yourself, then break the chest so nobody can see
		// what was taken. The chest used to come back with only what was left in it.
		ServerPlayer griefer = Harness.namedPlayer(helper);
		ServerLevel level = helper.getLevel();
		BlockPos rel = new BlockPos(2, 2, 2);
		BlockPos pos = helper.absolutePos(rel);
		Container chest = filled(helper, rel, Blocks.CHEST);

		Mods.grief().containers().onOpen(griefer, pos, chest, Mc.dimensionId(level));
		chest.setItem(0, ItemStack.EMPTY);
		griefer.getInventory().add(new ItemStack(Items.DIAMOND, 5));
		Mods.grief().onContainerClosed(griefer);
		Mods.grief().awaitWrites();
		sleepAMillisecond();

		griefer.snapTo(Vec3.atBottomCenterOf(pos.north()));
		Harness.check(helper, griefer.gameMode.destroyBlock(pos), "the game refused the break");
		Mods.grief().awaitWrites();

		Mods.grief().rollback(level, Harness.name(griefer), pos, 6, 60_000L, false, Actor.console());
		Mods.grief().awaitWrites();

		Container back = Mc.containerAt(level, pos);
		Harness.check(helper, back != null && count(back, Items.DIAMOND) == 5
						&& count(back, Items.COBBLESTONE) == 32,
				"the chest did not come back as it was before the theft: " + describe(level, pos));
		Harness.checkEquals(helper, 0, count(griefer.getInventory(), Items.DIAMOND),
				"diamonds the griefer still holds after the chest got them back");
		helper.succeed();
	}

	@GameTest(maxTicks = 100)
	public void halfADoubleChestBrokenComesBackWithTheRightHalfsItems(GameTestHelper helper) {
		ServerPlayer griefer = Harness.namedPlayer(helper);
		ServerLevel level = helper.getLevel();
		BlockPos leftRel = new BlockPos(2, 2, 2);
		BlockPos rightRel = new BlockPos(3, 2, 2);
		var south = Blocks.CHEST.defaultBlockState()
				.setValue(net.minecraft.world.level.block.ChestBlock.FACING, net.minecraft.core.Direction.SOUTH);
		helper.setBlock(leftRel, south.setValue(net.minecraft.world.level.block.ChestBlock.TYPE,
				net.minecraft.world.level.block.state.properties.ChestType.LEFT));
		helper.setBlock(rightRel, south.setValue(net.minecraft.world.level.block.ChestBlock.TYPE,
				net.minecraft.world.level.block.state.properties.ChestType.RIGHT));
		BlockPos left = helper.absolutePos(leftRel);
		BlockPos right = helper.absolutePos(rightRel);
		((Container) level.getBlockEntity(left)).setItem(0, new ItemStack(Items.DIAMOND, 5));
		((Container) level.getBlockEntity(right)).setItem(0, new ItemStack(Items.EMERALD, 7));

		griefer.snapTo(Vec3.atBottomCenterOf(right.north()));
		Harness.check(helper, griefer.gameMode.destroyBlock(right), "the game refused the break");
		Mods.grief().awaitWrites();

		Mods.grief().rollback(level, Harness.name(griefer), right, 6, 60_000L, false, Actor.console());
		Mods.grief().awaitWrites();

		Harness.check(helper, level.getBlockState(right).is(Blocks.CHEST),
				"the broken half did not come back: " + level.getBlockState(right));
		Container leftHalf = (Container) level.getBlockEntity(left);
		Container rightHalf = level.getBlockEntity(right) instanceof Container c ? c : null;
		Harness.check(helper, rightHalf != null && count(rightHalf, Items.EMERALD) == 7,
				"the broken half came back without its own emeralds: " + describe(level, right));
		Harness.check(helper, count(leftHalf, Items.DIAMOND) == 5 && count(leftHalf, Items.EMERALD) == 0,
				"the half that was never broken changed: " + describe(level, left));
		helper.succeed();
	}

	@GameTest(maxTicks = 100)
	public void rollingBackAChestTheyPlacedDoesNotSpillWhatIsInIt(GameTestHelper helper) {
		// A griefer's own chest full of what they stole: rolling back the placement removes
		// the chest. Removing a container spills its contents in this version, so without
		// care the loot lands on the floor for anybody — including the griefer — to pick up.
		ServerPlayer griefer = Harness.namedPlayer(helper);
		ServerLevel level = helper.getLevel();
		BlockPos rel = new BlockPos(2, 2, 2);
		BlockPos pos = helper.absolutePos(rel);

		helper.setBlock(rel, Blocks.CHEST);
		Mods.grief().logPlace(griefer, pos, Blocks.CHEST.defaultBlockState(), Mc.dimensionId(level));
		Mods.grief().awaitWrites();
		sleepAMillisecond();
		Mc.containerAt(level, pos).setItem(0, new ItemStack(Items.DIAMOND, 9));

		Mods.grief().rollback(level, Harness.name(griefer), pos, 6, 60_000L, false, Actor.console());
		Mods.grief().awaitWrites();

		Harness.check(helper, level.getBlockState(pos).isAir(), "the placed chest was not removed");
		long spilled = level.getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(3)).stream()
				.filter(e -> e.getItem().is(Items.DIAMOND)).mapToLong(e -> e.getItem().getCount()).sum();
		Harness.checkEquals(helper, 0L, spilled, "diamonds spilled from the removed chest");
		helper.succeed();
	}

	@GameTest(maxTicks = 100)
	public void lootStashedInTheirOwnChestGoesBackToTheVictim(GameTestHelper helper) {
		ServerPlayer griefer = Harness.namedPlayer(helper);
		ServerLevel level = helper.getLevel();
		BlockPos victimRel = new BlockPos(1, 2, 2);
		BlockPos stashRel = new BlockPos(4, 2, 2);
		BlockPos victimPos = helper.absolutePos(victimRel);
		BlockPos stashPos = helper.absolutePos(stashRel);
		String world = Mc.dimensionId(level);

		Container victim = filled(helper, victimRel, Blocks.CHEST);
		Mods.grief().containers().onOpen(griefer, victimPos, victim, world);
		victim.setItem(0, ItemStack.EMPTY);
		Mods.grief().onContainerClosed(griefer);
		Mods.grief().awaitWrites();
		sleepAMillisecond();

		helper.setBlock(stashRel, Blocks.CHEST);
		Mods.grief().logPlace(griefer, stashPos, Blocks.CHEST.defaultBlockState(), world);
		Mods.grief().awaitWrites();
		sleepAMillisecond();

		Container stash = Mc.containerAt(level, stashPos);
		Mods.grief().containers().onOpen(griefer, stashPos, stash, world);
		stash.setItem(0, new ItemStack(Items.DIAMOND, 5));
		Mods.grief().onContainerClosed(griefer);
		Mods.grief().awaitWrites();

		Mods.grief().rollback(level, Harness.name(griefer), victimPos, 8, 60_000L, false, Actor.console());
		Mods.grief().awaitWrites();

		Harness.checkEquals(helper, 5, count(victim, Items.DIAMOND), "diamonds back in the victim's chest");
		Harness.check(helper, level.getBlockState(stashPos).isAir(), "the griefer's chest was not removed");
		long loose = level.getEntitiesOfClass(ItemEntity.class, new AABB(stashPos).inflate(4)).stream()
				.filter(e -> e.getItem().is(Items.DIAMOND)).mapToLong(e -> e.getItem().getCount()).sum();
		Harness.checkEquals(helper, 0L, loose, "diamonds spilled from the removed stash");
		Harness.checkEquals(helper, 0, count(griefer.getInventory(), Items.DIAMOND),
				"diamonds handed to the griefer");
		helper.succeed();
	}

	/**
	 * The sequence somebody testing on their own chest produces: place it, put diamonds in, break
	 * it. The diamonds spill and lie on the ground. Returns the chest's position.
	 */
	private static BlockPos placeFillAndBreak(GameTestHelper helper, ServerPlayer player) {
		ServerLevel level = helper.getLevel();
		BlockPos rel = new BlockPos(2, 2, 2);
		BlockPos pos = helper.absolutePos(rel);
		String world = Mc.dimensionId(level);

		helper.setBlock(rel, Blocks.CHEST);
		Mods.grief().logPlace(player, pos, Blocks.CHEST.defaultBlockState(), world);
		Mods.grief().awaitWrites();
		sleepAMillisecond();

		Container chest = Mc.containerAt(level, pos);
		Mods.grief().containers().onOpen(player, pos, chest, world);
		chest.setItem(0, new ItemStack(Items.DIAMOND, 5));
		Mods.grief().onContainerClosed(player);
		Mods.grief().awaitWrites();
		sleepAMillisecond();

		player.snapTo(Vec3.atBottomCenterOf(pos.north()));
		Harness.check(helper, player.gameMode.destroyBlock(pos), "the game refused the break");
		Mods.grief().awaitWrites();
		return pos;
	}

	private static long diamondsOnTheGround(ServerLevel level, BlockPos pos) {
		return level.getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(4)).stream()
				.filter(e -> e.getItem().is(Items.DIAMOND)).mapToLong(e -> e.getItem().getCount()).sum();
	}

	@GameTest(maxTicks = 100)
	public void aChestYouPlacedFilledAndBrokeComesBackFull(GameTestHelper helper) {
		// Going strictly back to before the placement would leave no chest and lose what was
		// in it. A chest broken with items in it always comes back with them.
		ServerPlayer player = Harness.namedPlayer(helper);
		ServerLevel level = helper.getLevel();
		BlockPos pos = placeFillAndBreak(helper, player);
		int vaultBefore = Mods.security().vault().countFor(player.getUUID());

		Mods.grief().rollback(level, Harness.name(player), pos, 6, 60_000L, false, Actor.console());
		Mods.grief().awaitWrites();

		Container back = Mc.containerAt(level, pos);
		Harness.check(helper, level.getBlockState(pos).is(Blocks.CHEST) && back != null
						&& count(back, Items.DIAMOND) == 5,
				"the chest did not come back where it was, with its diamonds: " + describe(level, pos));
		Harness.checkEquals(helper, vaultBefore, Mods.security().vault().countFor(player.getUUID()),
				"items went into the vault");
		Harness.checkEquals(helper, 0L, diamondsOnTheGround(level, pos),
				"the spilled diamonds are still on the ground as well, a duplication");
		helper.succeed();
	}

	@GameTest(maxTicks = 100)
	public void puttingItemsInThenBreakingTheChestBringsItBackWithThem(GameTestHelper helper) {
		// How anybody tests this: a chest already there, put some diamonds in, break it, roll
		// back. The chest has to come back with the diamonds that were in it when it broke.
		ServerPlayer player = Harness.namedPlayer(helper);
		ServerLevel level = helper.getLevel();
		BlockPos rel = new BlockPos(2, 2, 2);
		BlockPos pos = helper.absolutePos(rel);
		String world = Mc.dimensionId(level);

		helper.setBlock(rel, Blocks.CHEST);
		Container chest = Mc.containerAt(level, pos);
		Mods.grief().containers().onOpen(player, pos, chest, world);
		chest.setItem(0, new ItemStack(Items.DIAMOND, 5));
		Mods.grief().onContainerClosed(player);
		Mods.grief().awaitWrites();
		sleepAMillisecond();

		player.snapTo(Vec3.atBottomCenterOf(pos.north()));
		Harness.check(helper, player.gameMode.destroyBlock(pos), "the game refused the break");
		Mods.grief().awaitWrites();

		Mods.grief().rollback(level, null, pos, 6, 60_000L, false, Actor.console());
		Mods.grief().awaitWrites();

		Container back = Mc.containerAt(level, pos);
		Harness.check(helper, level.getBlockState(pos).is(Blocks.CHEST) && back != null
						&& count(back, Items.DIAMOND) == 5,
				"the chest did not come back with the diamonds it held when it broke: "
						+ describe(level, pos));
		Harness.checkEquals(helper, 0L, diamondsOnTheGround(level, pos),
				"the spilled diamonds are still on the ground as well, a duplication");
		helper.succeed();
	}

	@GameTest(maxTicks = 100)
	public void aWholeBaseGriefComesBackWithOneRollback(GameTestHelper helper) {
		// Walls broken, a full chest broken, another chest robbed, rubbish placed: one area
		// rollback of that player puts every part of it back, containers and contents included.
		ServerPlayer griefer = Harness.namedPlayer(helper);
		ServerLevel level = helper.getLevel();
		String world = Mc.dimensionId(level);
		BlockPos centre = helper.absolutePos(new BlockPos(2, 2, 2));
		List<BlockPos> wall = List.of(helper.absolutePos(new BlockPos(1, 2, 4)),
				helper.absolutePos(new BlockPos(2, 2, 4)), helper.absolutePos(new BlockPos(3, 2, 4)));
		BlockPos brokenChest = centre;
		BlockPos robbedChest = helper.absolutePos(new BlockPos(4, 2, 2));
		BlockPos rubbish = helper.absolutePos(new BlockPos(0, 2, 2));

		for (BlockPos plank : wall) level.setBlockAndUpdate(plank, Blocks.OAK_PLANKS.defaultBlockState());
		filled(helper, new BlockPos(2, 2, 2), Blocks.CHEST);
		helper.setBlock(new BlockPos(4, 2, 2), Blocks.CHEST);
		Container robbed = Mc.containerAt(level, robbedChest);
		robbed.setItem(0, new ItemStack(Items.GOLD_INGOT, 10));

		griefer.snapTo(Vec3.atBottomCenterOf(centre.north()));
		for (BlockPos plank : wall) {
			Harness.check(helper, griefer.gameMode.destroyBlock(plank), "the game refused a plank");
		}
		Harness.check(helper, griefer.gameMode.destroyBlock(brokenChest), "the game refused the chest");

		Mods.grief().containers().onOpen(griefer, robbedChest, robbed, world);
		robbed.getItem(0).shrink(3);
		griefer.getInventory().add(new ItemStack(Items.GOLD_INGOT, 3));
		Mods.grief().onContainerClosed(griefer);

		level.setBlockAndUpdate(rubbish, Blocks.COBBLESTONE.defaultBlockState());
		Mods.grief().logPlace(griefer, rubbish, Blocks.COBBLESTONE.defaultBlockState(), world);
		Mods.grief().awaitWrites();

		Mods.grief().rollback(level, Harness.name(griefer), centre, 6, 60_000L, false, Actor.console());
		Mods.grief().awaitWrites();

		for (BlockPos plank : wall) {
			Harness.check(helper, level.getBlockState(plank).is(Blocks.OAK_PLANKS),
					"a wall block did not come back: " + plank);
		}
		Container back = Mc.containerAt(level, brokenChest);
		Harness.check(helper, level.getBlockState(brokenChest).is(Blocks.CHEST) && back != null
						&& count(back, Items.DIAMOND) == 5 && count(back, Items.COBBLESTONE) == 32,
				"the broken chest did not come back full: " + describe(level, brokenChest));
		Harness.checkEquals(helper, 10, count(robbed, Items.GOLD_INGOT),
				"the robbed chest did not get its gold back");
		Harness.checkEquals(helper, 0, count(griefer.getInventory(), Items.GOLD_INGOT),
				"the griefer kept the gold as well");
		Harness.check(helper, level.getBlockState(rubbish).isAir(), "the placed cobblestone is still there");
		Harness.checkEquals(helper, 0L, diamondsOnTheGround(level, brokenChest),
				"the spilled diamonds are still on the ground as well, a duplication");
		helper.succeed();
	}

	private static void sleepAMillisecond() {
		long start = System.currentTimeMillis();
		while (System.currentTimeMillis() == start) Thread.onSpinWait();
	}
}
