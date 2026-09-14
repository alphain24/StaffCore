package io.github.alphain24.staffcore.gametest;

import io.github.alphain24.staffcore.compat.Mc;
import io.github.alphain24.staffcore.gui.Gui;
import io.github.alphain24.staffcore.gui.menu.BlockHistoryMenu;
import io.github.alphain24.staffcore.gui.menu.ConfirmMenu;
import io.github.alphain24.staffcore.gui.menu.GriefMenu;
import io.github.alphain24.staffcore.gui.menu.PlayerActionsMenu;
import io.github.alphain24.staffcore.gui.menu.RollbackPreview;
import io.github.alphain24.staffcore.module.Mods;
import io.github.alphain24.staffcore.permission.Actor;
import io.github.alphain24.staffcore.permission.PermissionGroups;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Rolling a broken chest back through the screens, the way staff do it: the preview drawn in the
 * world, the confirmation, the click on it — and from a single row of the grief log or of a
 * block's history, which rolls back that row and not the whole area around it.
 */
public class RollbackGuiTests {

	private static final int CONFIRM = 11;

	private static void click(ServerPlayer staff, int slot) {
		((Gui) staff.containerMenu).clicked(slot, 0, ContainerInput.PICKUP, staff);
	}

	private static String screen(ServerPlayer staff) {
		return staff.containerMenu.getClass().getSimpleName();
	}

	// Radius 2 in both: a preview holds its region lock until it is confirmed, and gametests in
	// a batch run side by side, so a wider one would be refused by the test next door.
	@GameTest(maxTicks = 200)
	public void confirmingARollbackOnScreenBringsABrokenChestBack(GameTestHelper helper) {
		ServerPlayer staff = Harness.namedPlayer(helper);
		ServerPlayer griefer = Harness.namedPlayer(helper);
		ServerLevel level = helper.getLevel();
		BlockPos rel = new BlockPos(2, 2, 2);
		BlockPos pos = helper.absolutePos(rel);

		helper.setBlock(rel, Blocks.CHEST);
		Container chest = Mc.containerAt(level, pos);
		chest.setItem(0, new ItemStack(Items.DIAMOND, 5));
		griefer.snapTo(Vec3.atBottomCenterOf(pos.north()));
		Harness.check(helper, griefer.gameMode.destroyBlock(pos), "the game refused the break");
		Mods.grief().awaitWrites();
		staff.snapTo(Vec3.atBottomCenterOf(pos.south().south()));

		helper.startSequence()
				.thenExecute(() -> RollbackPreview.open(staff,
						new RollbackPreview.Scope(level, null, pos, 2, 60 * 60_000L, List.of()),
						result -> { }, () -> { }))
				.thenExecuteAfter(3, () -> {
					Harness.check(helper, staff.containerMenu instanceof ConfirmMenu,
							"the preview did not open the confirmation: " + screen(staff));
					click(staff, CONFIRM);
				})
				.thenExecuteAfter(3, () -> {
					Container back = Mc.containerAt(level, pos);
					Harness.check(helper, level.getBlockState(pos).is(Blocks.CHEST),
							"confirming did not bring the chest back: " + level.getBlockState(pos));
					Harness.check(helper, back != null && back.getItem(0).is(Items.DIAMOND)
									&& back.getItem(0).getCount() == 5,
							"the chest came back without its diamonds");
				})
				.thenSucceed();
	}

	@GameTest(maxTicks = 300)
	public void aChestTheyPlacedAndBrokeComesBackWithOneConfirm(GameTestHelper helper) {
		ServerPlayer staff = Harness.namedPlayer(helper);
		ServerLevel level = helper.getLevel();
		BlockPos rel = new BlockPos(2, 2, 2);
		BlockPos pos = helper.absolutePos(rel);
		String world = Mc.dimensionId(level);

		helper.setBlock(rel, Blocks.CHEST);
		Mods.grief().logPlace(staff, pos, Blocks.CHEST.defaultBlockState(), world);
		Mods.grief().awaitWrites();
		long start = System.currentTimeMillis();
		while (System.currentTimeMillis() == start) Thread.onSpinWait();
		Container chest = Mc.containerAt(level, pos);
		Mods.grief().containers().onOpen(staff, pos, chest, world);
		chest.setItem(0, new ItemStack(Items.DIAMOND, 5));
		Mods.grief().onContainerClosed(staff);
		Mods.grief().awaitWrites();
		staff.snapTo(Vec3.atBottomCenterOf(pos.north()));
		Harness.check(helper, staff.gameMode.destroyBlock(pos), "the game refused the break");
		Mods.grief().awaitWrites();

		helper.startSequence()
				.thenExecute(() -> RollbackPreview.open(staff,
						new RollbackPreview.Scope(level, Harness.name(staff), pos, 2, 60 * 60_000L,
								List.of()),
						result -> { }, () -> { }))
				.thenExecuteAfter(3, () -> {
					Harness.check(helper, staff.containerMenu instanceof ConfirmMenu,
							"the preview did not open the confirmation: " + screen(staff));
					click(staff, CONFIRM);
				})
				.thenExecuteAfter(3, () -> {
					Container back = Mc.containerAt(level, pos);
					Harness.check(helper, level.getBlockState(pos).is(Blocks.CHEST) && back != null
									&& back.getItem(0).is(Items.DIAMOND) && back.getItem(0).getCount() == 5,
							"the chest did not come back full: " + level.getBlockState(pos));
				})
				.thenSucceed();
	}

	/**
	 * Staff hold grief.rollback for the length of a test. Op does not give it here: the test
	 * players have no moderator level, so the permissions file is the way in.
	 */
	private static Runnable grantRollback(GameTestHelper helper, ServerPlayer staff) {
		PermissionGroups groups = PermissionGroups.get();
		Harness.check(helper, groups != null, "no permission groups in the test server");
		groups.players.put(staff.getUUID().toString(), "admin");
		return () -> groups.players.remove(staff.getUUID().toString());
	}

	private static String text(ItemStack stack) {
		StringBuilder out = new StringBuilder(stack.getHoverName().getString());
		ItemLore lore = stack.get(DataComponents.LORE);
		if (lore != null) {
			for (Component line : lore.lines()) out.append('\n').append(line.getString());
		}
		return out.toString();
	}

	/** The first row on screen whose name and lore say all of these, or -1. */
	private static int rowSaying(ServerPlayer staff, String... needles) {
		for (int slot = 9; slot < 45; slot++) {
			String said = text(staff.containerMenu.getSlot(slot).getItem());
			boolean all = true;
			for (String needle : needles) all &= said.contains(needle);
			if (all) return slot;
		}
		return -1;
	}

	private static String at(BlockPos pos) {
		return "%d, %d, %d".formatted(pos.getX(), pos.getY(), pos.getZ());
	}

	private static long diamondsOnTheGround(ServerLevel level, BlockPos pos) {
		return level.getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(4)).stream()
				.filter(e -> e.getItem().is(Items.DIAMOND)).mapToLong(e -> e.getItem().getCount()).sum();
	}

	/** Planks broken beside it first, then a chest of diamonds: two rows in the log. */
	private static void breakPlanksThenChest(GameTestHelper helper, ServerPlayer griefer,
			BlockPos chestRel, BlockPos planksRel) {
		ServerLevel level = helper.getLevel();
		BlockPos pos = helper.absolutePos(chestRel);
		helper.setBlock(planksRel, Blocks.OAK_PLANKS);
		helper.setBlock(chestRel, Blocks.CHEST);
		Mc.containerAt(level, pos).setItem(0, new ItemStack(Items.DIAMOND, 5));

		griefer.snapTo(Vec3.atBottomCenterOf(pos.north()));
		Harness.check(helper, griefer.gameMode.destroyBlock(helper.absolutePos(planksRel)),
				"the game refused to break the planks");
		Harness.check(helper, griefer.gameMode.destroyBlock(pos), "the game refused to break the chest");
		Mods.grief().awaitWrites();
	}

	@GameTest(maxTicks = 300)
	public void shiftClickingAChestRowRollsBackThatChestAndNothingElse(GameTestHelper helper) {
		ServerPlayer staff = Harness.namedPlayer(helper);
		ServerPlayer griefer = Harness.namedPlayer(helper);
		ServerLevel level = helper.getLevel();
		BlockPos rel = new BlockPos(2, 2, 2);
		BlockPos pos = helper.absolutePos(rel);
		BlockPos planks = helper.absolutePos(new BlockPos(4, 2, 2));
		Runnable revoke = grantRollback(helper, staff);

		breakPlanksThenChest(helper, griefer, rel, new BlockPos(4, 2, 2));
		staff.snapTo(Vec3.atBottomCenterOf(pos.south().south()));

		helper.startSequence()
				.thenExecute(() -> GriefMenu.openAt(staff, pos))
				.thenExecuteAfter(10, () -> {
					int row = rowSaying(staff, "chest", at(pos));
					Harness.check(helper, row >= 0, "no row for the broken chest in the grief log");
					Harness.check(helper, text(staff.containerMenu.getSlot(row).getItem())
							.contains("roll back just this"), "the row does not say what shift-click does");
					((Gui) staff.containerMenu).clicked(row, 0, ContainerInput.QUICK_MOVE, staff);
				})
				.thenExecuteAfter(3, () -> {
					Harness.check(helper, staff.containerMenu instanceof ConfirmMenu,
							"shift-click did not open the rollback confirmation: " + screen(staff));
					click(staff, CONFIRM);
				})
				.thenExecuteAfter(3, () -> {
					revoke.run();
					Container back = Mc.containerAt(level, pos);
					Harness.check(helper, level.getBlockState(pos).is(Blocks.CHEST) && back != null
									&& back.getItem(0).is(Items.DIAMOND) && back.getItem(0).getCount() == 5,
							"the chest did not come back with its diamonds: " + level.getBlockState(pos));
					Harness.check(helper, level.getBlockState(planks).isAir(),
							"the planks next to it were rolled back too, and that row was not clicked");
					Harness.checkEquals(helper, 0L, diamondsOnTheGround(level, pos),
							"the spilled diamonds are still on the ground as well, a duplication");
				})
				.thenSucceed();
	}

	@GameTest(maxTicks = 300)
	public void aRowWithNothingLeftToRollBackSaysSoOnTheRow(GameTestHelper helper) {
		ServerPlayer staff = Harness.namedPlayer(helper);
		ServerPlayer griefer = Harness.namedPlayer(helper);
		ServerLevel level = helper.getLevel();
		BlockPos rel = new BlockPos(2, 2, 2);
		BlockPos pos = helper.absolutePos(rel);
		Runnable revoke = grantRollback(helper, staff);

		breakPlanksThenChest(helper, griefer, rel, new BlockPos(4, 2, 2));
		staff.snapTo(Vec3.atBottomCenterOf(pos.south().south()));

		helper.startSequence()
				.thenExecute(() -> GriefMenu.openAt(staff, pos))
				.thenExecuteAfter(10, () -> {
					int row = rowSaying(staff, "chest", at(pos));
					Harness.check(helper, row >= 0, "no row for the broken chest in the grief log");
					// Somebody else rolls it back while this screen is still showing the row.
					Mods.grief().rollback(level, Harness.name(griefer), pos, 1, 60_000L, false,
							Actor.console(), java.util.Set.of(pos));
					Mods.grief().awaitWrites();

					((Gui) staff.containerMenu).clicked(row, 0, ContainerInput.QUICK_MOVE, staff);
					revoke.run();
					Harness.check(helper, staff.containerMenu instanceof GriefMenu,
							"the log closed on a click that did nothing: " + screen(staff));
					Harness.check(helper, text(staff.containerMenu.getSlot(row).getItem())
									.contains("Nothing left to roll back"),
							"the row did not say there was nothing left: "
									+ text(staff.containerMenu.getSlot(row).getItem()));
				})
				.thenSucceed();
	}

	@GameTest(maxTicks = 100)
	public void aPlayersFileIsLaidOutInLabelledRows(GameTestHelper helper) {
		ServerPlayer staff = Harness.namedPlayer(helper);
		ServerPlayer target = Harness.namedPlayer(helper);
		Runnable revoke = grantRollback(helper, staff);

		PlayerActionsMenu.open(staff, new net.minecraft.server.players.NameAndId(
				target.getUUID(), Harness.name(target)));
		revoke.run();

		Harness.check(helper, staff.containerMenu instanceof PlayerActionsMenu,
				"the file did not open: " + screen(staff));
		String[][] expected = {
				{"4", Harness.name(target)},
				{"9", "Moderation"}, {"10", "Punish"}, {"13", "Appeals"}, {"14", "Lift Ban"},
				{"18", "Items"}, {"19", "Inventory"}, {"20", "Ender Chest"}, {"22", "Owed Items"},
				{"27", "Movement"}, {"28", "Teleport To"}, {"30", "Freeze"},
				{"36", "Investigation"}, {"37", "Logs"}, {"41", "Risk Profile"},
		};
		for (String[] want : expected) {
			String said = text(staff.containerMenu.getSlot(Integer.parseInt(want[0])).getItem());
			Harness.check(helper, said.contains(want[1]),
					"slot " + want[0] + " should be " + want[1] + " but says: " + said);
		}
		helper.succeed();
	}

	@GameTest(maxTicks = 300)
	public void shiftClickingABreakInTheBlockHistoryRollsItBack(GameTestHelper helper) {
		ServerPlayer staff = Harness.namedPlayer(helper);
		ServerPlayer griefer = Harness.namedPlayer(helper);
		ServerLevel level = helper.getLevel();
		BlockPos rel = new BlockPos(2, 2, 2);
		BlockPos pos = helper.absolutePos(rel);
		Runnable revoke = grantRollback(helper, staff);

		breakPlanksThenChest(helper, griefer, rel, new BlockPos(4, 2, 2));
		staff.snapTo(Vec3.atBottomCenterOf(pos.south().south()));

		helper.startSequence()
				.thenExecute(() -> BlockHistoryMenu.open(staff, pos))
				.thenExecuteAfter(10, () -> {
					int row = rowSaying(staff, Harness.name(griefer) + " broke chest");
					Harness.check(helper, row >= 0, "no row for the break in the block history");
					((Gui) staff.containerMenu).clicked(row, 0, ContainerInput.QUICK_MOVE, staff);
				})
				.thenExecuteAfter(3, () -> {
					Harness.check(helper, staff.containerMenu instanceof ConfirmMenu,
							"shift-click did not open the rollback confirmation: " + screen(staff));
					click(staff, CONFIRM);
				})
				.thenExecuteAfter(3, () -> {
					revoke.run();
					Container back = Mc.containerAt(level, pos);
					Harness.check(helper, level.getBlockState(pos).is(Blocks.CHEST) && back != null
									&& back.getItem(0).is(Items.DIAMOND) && back.getItem(0).getCount() == 5,
							"the chest did not come back with its diamonds: " + level.getBlockState(pos));
				})
				.thenSucceed();
	}
}
