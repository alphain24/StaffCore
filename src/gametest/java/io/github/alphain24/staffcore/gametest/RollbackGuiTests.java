package io.github.alphain24.staffcore.gametest;

import io.github.alphain24.staffcore.compat.Mc;
import io.github.alphain24.staffcore.gui.Gui;
import io.github.alphain24.staffcore.gui.menu.ConfirmMenu;
import io.github.alphain24.staffcore.gui.menu.RollbackPreview;
import io.github.alphain24.staffcore.module.Mods;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Rolling a broken chest back through the screens, the way staff do it: the preview drawn in the
 * world, the confirmation, the click on it.
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
}
