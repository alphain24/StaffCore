package io.github.alphain24.staffcore.gametest;

import io.github.alphain24.staffcore.inventory.InventoryGateway;
import io.github.alphain24.staffcore.module.Mods;
import io.github.alphain24.staffcore.modules.inventory.InventoryModule;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Staff editing somebody else's inventory, and the record it leaves.
 * <p>
 * Reaching into another player's inventory is the most abusable thing in the mod, and every
 * control on it — the snapshot, the audit row, the transaction — only exists at runtime. A
 * headless test can check that {@code InventoryGateway} refuses when it should; only a live
 * server can check that the items actually moved and that a row describing the move exists
 * afterwards.
 */
public class InvseeTests {

	private static int count(ServerPlayer player, net.minecraft.world.item.Item item) {
		int total = 0;
		var inv = player.getInventory();
		for (int slot = 0; slot < inv.getContainerSize(); slot++) {
			ItemStack stack = inv.getItem(slot);
			if (stack.getItem() == item) total += stack.getCount();
		}
		return total;
	}

	@GameTest
	public void anEditWritesThroughAndIsRecorded(GameTestHelper helper) {
		ServerPlayer target = Harness.mockPlayer(helper);
		target.getInventory().clearContent();

		var before = InventoryGateway.historyFor(target.getUUID(), 50).size();

		var outcome = InventoryGateway.give(target, InventoryGateway.Origin.INVSEE_EDIT,
				"TestStaff", "gametest edit", java.util.List.of(new ItemStack(Items.DIAMOND, 4)));

		Harness.check(helper, outcome.applied(), "the write was refused: " + outcome.refused());
		Harness.checkEquals(helper, 4, count(target, Items.DIAMOND),
				"the items did not reach the target's inventory");

		var after = InventoryGateway.historyFor(target.getUUID(), 50);
		Harness.check(helper, after.size() > before,
				"the items moved and nothing recorded it, which is the state the gateway "
						+ "exists to make impossible");
		Harness.check(helper, after.get(0).actor().equals("TestStaff"),
				"the audit row does not name who did it");
		Harness.check(helper, after.get(0).items().contains("diamond"),
				"the audit row does not say what moved: " + after.get(0).items());
		helper.succeed();
	}

	@GameTest
	public void anEditSessionRecordsTheNetChangeAndNothingElse(GameTestHelper helper) {
		ServerPlayer target = Harness.mockPlayer(helper);
		target.getInventory().clearContent();
		target.getInventory().add(new ItemStack(Items.IRON_INGOT, 10));

		// Opening a screen and changing nothing is what staff do most of the time. A row for
		// every one of those is a log nobody reads, which is the same as no log.
		var quiet = InventoryGateway.beginEdit(target, "TestStaff", "looked, changed nothing");
		var quietOutcome = InventoryGateway.endEdit(quiet);
		Harness.checkEquals(helper, 0, quietOutcome.items(),
				"a session that changed nothing wrote a row");

		// A session that does change something records the net effect, not the clicks.
		var session = InventoryGateway.beginEdit(target, "TestStaff", "took some iron");
		target.getInventory().clearContent();
		target.getInventory().add(new ItemStack(Items.IRON_INGOT, 3));
		var outcome = InventoryGateway.endEdit(session);

		Harness.checkEquals(helper, 7, outcome.items(),
				"the recorded change does not match what actually moved");
		helper.succeed();
	}

	@GameTest
	public void aSnapshotRestorePutsBackExactlyWhatWasRecorded(GameTestHelper helper) {
		ServerPlayer target = Harness.mockPlayer(helper);
		target.getInventory().clearContent();
		target.getInventory().add(new ItemStack(Items.DIAMOND, 12));
		target.getInventory().add(new ItemStack(Items.OAK_LOG, 5));

		InventoryModule inventory = Mods.inventory();
		InventoryModule.Snapshot snapshot =
				inventory.capture(target, "gametest", "TestStaff");
		Harness.check(helper, snapshot != null, "the snapshot was not taken");

		// Whatever happens next, the restore has to undo it — including things acquired
		// since, because a restore is a return to a recorded moment rather than a top-up.
		target.getInventory().clearContent();
		target.getInventory().add(new ItemStack(Items.NETHERITE_INGOT, 2));

		inventory.restore(target, snapshot);

		Harness.checkEquals(helper, 12, count(target, Items.DIAMOND), "diamonds not restored");
		Harness.checkEquals(helper, 5, count(target, Items.OAK_LOG), "logs not restored");
		Harness.checkEquals(helper, 0, count(target, Items.NETHERITE_INGOT),
				"something picked up after the snapshot survived the restore, so a restore "
						+ "adds to an inventory instead of replacing it");
		helper.succeed();
	}

	@GameTest
	public void takingRemovesOnlyWhatIsOwed(GameTestHelper helper) {
		ServerPlayer target = Harness.mockPlayer(helper);
		target.getInventory().clearContent();
		target.getInventory().add(new ItemStack(Items.DIAMOND, 20));

		var owed = new java.util.HashMap<net.minecraft.world.item.Item, Integer>();
		owed.put(Items.DIAMOND, 6);

		var outcome = InventoryGateway.take(target, InventoryGateway.Origin.ROLLBACK_DEBIT,
				"TestStaff", "gametest debit", owed);

		Harness.checkEquals(helper, 6, outcome.items(), "took the wrong amount");
		Harness.checkEquals(helper, 14, count(target, Items.DIAMOND),
				"the debit took more than was owed, which is how a rollback deletes items "
						+ "belonging to somebody who was not there to argue");
		helper.succeed();
	}
}
