package io.github.alphain24.staffcore.gametest;

import io.github.alphain24.staffcore.StaffCore;
import io.github.alphain24.staffcore.inventory.InventoryGateway;
import io.github.alphain24.staffcore.storage.PendingActions;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The debit half of a rollback, including the case that goes wrong quietly.
 * <p>
 * Rollback is two operations. Putting blocks back is visible and reversible. Taking items out
 * of somebody's inventory on the strength of a log query is neither, and the person it goes
 * wrong for is most often the one who was offline when it happened — which is exactly the
 * path a headless test cannot reach, because it needs a queue, a login and a database that
 * remembers in between.
 */
public class RollbackDebitTests {

	private static int count(ServerPlayer player, Item item) {
		int total = 0;
		var inv = player.getInventory();
		for (int slot = 0; slot < inv.getContainerSize(); slot++) {
			ItemStack stack = inv.getItem(slot);
			if (stack.getItem() == item) total += stack.getCount();
		}
		return total;
	}

	private static Map<Item, Integer> owed(Item item, int count) {
		Map<Item, Integer> owed = new HashMap<>();
		owed.put(item, count);
		return owed;
	}

	@GameTest
	public void anOfflineOffenderPaysOnTheirNextLogin(GameTestHelper helper) {
		ServerPlayer offender = Harness.mockPlayer(helper);
		offender.getInventory().clearContent();

		// They are not here to be charged, so the debt is booked instead. Without this an
		// offender who logs off before the rollback lands keeps the lot and the repair has
		// printed the difference.
		StaffCore.pending().queueDebit(offender.getUUID(), Harness.name(offender),
				owed(Items.COBBLESTONE, 30), "gametest rollback", "TestStaff");

		Harness.check(helper, StaffCore.pending().countFor(offender.getUUID()) > 0,
				"nothing was queued, so the debt would never be collected");

		// They come back carrying more than they owe.
		offender.getInventory().add(new ItemStack(Items.COBBLESTONE, 64));
		PendingActions.Settled settled = StaffCore.pending().drainFor(offender);

		Harness.checkEquals(helper, 30, settled.debited(), "the wrong amount was collected");
		Harness.checkEquals(helper, 34, count(offender, Items.COBBLESTONE),
				"the settlement took more or less than the debt");
		Harness.checkEquals(helper, 0, StaffCore.pending().countFor(offender.getUUID()),
				"the debt survived being paid, so they would be charged again every login");
		helper.succeed();
	}

	@GameTest
	public void adebtLargerThanTheirInventoryStaysOwed(GameTestHelper helper) {
		ServerPlayer offender = Harness.mockPlayer(helper);
		offender.getInventory().clearContent();
		offender.getInventory().add(new ItemStack(Items.COBBLESTONE, 10));

		StaffCore.pending().queueDebit(offender.getUUID(), Harness.name(offender),
				owed(Items.COBBLESTONE, 40), "gametest rollback", "TestStaff");

		PendingActions.Settled settled = StaffCore.pending().drainFor(offender);

		Harness.checkEquals(helper, 10, settled.debited(), "should take everything they had");
		Harness.checkEquals(helper, 0, count(offender, Items.COBBLESTONE), "and leave none");
		Harness.check(helper, StaffCore.pending().countFor(offender.getUUID()) > 0,
				"the rest of the debt was written off silently rather than staying owed");
		helper.succeed();
	}

	@GameTest
	public void aDebitCanBeGivenBack(GameTestHelper helper) {
		ServerPlayer offender = Harness.mockPlayer(helper);
		offender.getInventory().clearContent();
		offender.getInventory().add(new ItemStack(Items.DIAMOND, 9));

		var outcome = InventoryGateway.take(offender, InventoryGateway.Origin.ROLLBACK_DEBIT,
				"TestStaff", "gametest debit", owed(Items.DIAMOND, 4), "ROLLBACK", 4242L);

		Harness.checkEquals(helper, 4, outcome.items(), "the debit did not take what it should");
		Harness.checkEquals(helper, 5, count(offender, Items.DIAMOND), "wrong amount left");

		// The half that had no way back before. Taking items off somebody on the strength of
		// a log query is the least recoverable thing here.
		var reversal = InventoryGateway.describeReversal(outcome.auditId());
		Harness.check(helper, reversal.possible(), "the debit cannot be undone: "
				+ reversal.problem());

		var back = InventoryGateway.reverse(outcome.auditId(), offender, "TestStaff");
		Harness.check(helper, back.applied(), "the reversal failed: " + back.refused());
		Harness.checkEquals(helper, 9, count(offender, Items.DIAMOND),
				"undoing the debit did not restore what it took");

		// And it cannot be given back twice, which for something that hands out items is the
		// failure that matters.
		Harness.check(helper, !InventoryGateway.describeReversal(outcome.auditId()).possible(),
				"a debit could be refunded twice by running the command twice");
		helper.succeed();
	}

	@GameTest
	public void everyDebitIsTiedToTheRollbackThatCausedIt(GameTestHelper helper) {
		ServerPlayer offender = Harness.mockPlayer(helper);
		offender.getInventory().clearContent();
		offender.getInventory().add(new ItemStack(Items.OAK_LOG, 12));

		long rollbackId = Math.abs(UUID.randomUUID().getLeastSignificantBits() % 100000);
		InventoryGateway.take(offender, InventoryGateway.Origin.ROLLBACK_DEBIT, "TestStaff",
				"gametest", owed(Items.OAK_LOG, 5), "ROLLBACK", rollbackId);

		var debits = InventoryGateway.debitsFor("ROLLBACK", rollbackId);
		Harness.check(helper, !debits.isEmpty(),
				"a debit was not linked to its rollback, so undoing that rollback could not "
						+ "find what it had charged for");
		Harness.checkEquals(helper, 5, debits.get(0).itemCount(), "wrong amount recorded");
		helper.succeed();
	}
}
