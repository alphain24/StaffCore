package io.github.alphain24.staffcore.gametest;

import io.github.alphain24.staffcore.StaffCore;
import io.github.alphain24.staffcore.module.Mods;
import io.github.alphain24.staffcore.modules.security.ContrabandVault;
import io.github.alphain24.staffcore.storage.PendingActions;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The vault, end to end against a real database.
 * <p>
 * The vault is where confiscated items live, and its whole promise is that taking something
 * off a player is recoverable. That promise spans a disconnect: the commonest case is staff
 * confiscating from somebody who then logs off, so the return has to survive being queued and
 * come back on the next login. None of that has a shape a headless test can reach — it needs
 * a live player, a live database and the real settlement path.
 */
public class VaultTests {

	private ContrabandVault vault() {
		return Mods.security().vault();
	}

	@GameTest
	public void anItemTakenCanBeGivenBack(GameTestHelper helper) {
		ServerPlayer owner = Harness.mockPlayer(helper);
		owner.getInventory().clearContent();

		long id = vault().deposit(owner, "TestStaff", new ItemStack(Items.DIAMOND, 7),
				"gametest");
		Harness.check(helper, id > 0, "the deposit was not recorded");

		ContrabandVault.Entry entry = vault().byId(id);
		Harness.check(helper, entry != null, "the vaulted item cannot be read back");

		Harness.check(helper, vault().release(entry, owner, "TestStaff"),
				"releasing a vaulted item failed");

		// Back in their hands, not merely marked returned. A vault that records a return it
		// did not make is worse than one that refuses.
		int diamonds = 0;
		var inv = owner.getInventory();
		for (int slot = 0; slot < inv.getContainerSize(); slot++) {
			ItemStack stack = inv.getItem(slot);
			if (stack.getItem() == Items.DIAMOND) diamonds += stack.getCount();
		}
		Harness.checkEquals(helper, 7, diamonds, "the released diamonds did not arrive");
		helper.succeed();
	}

	@GameTest
	public void aReleaseToSomebodyOfflineIsQueuedAndDelivered(GameTestHelper helper) {
		ServerPlayer owner = Harness.mockPlayer(helper);
		owner.getInventory().clearContent();

		long id = vault().deposit(owner.getUUID(), Harness.name(owner), "TestStaff",
				new ItemStack(Items.EMERALD, 3), "gametest", Harness.server(helper));
		ContrabandVault.Entry entry = vault().byId(id);

		// Returning to somebody offline used to be refused outright, which meant the
		// commonest case — confiscate, they log off, staff decide it was a mistake — had no
		// answer at all.
		Harness.check(helper, vault().queueRelease(Harness.server(helper), entry, "TestStaff"),
				"queueing a release for an offline owner failed");

		PendingActions pending = StaffCore.pending();
		Harness.check(helper, pending.countFor(owner.getUUID()) > 0,
				"nothing was queued, so the item would never arrive");

		// The login path, run the way the join handler runs it.
		PendingActions.Settled settled = pending.drainFor(owner);
		Harness.check(helper, settled.given() >= 3,
				"the queued items were not handed over on login — got " + settled.given());
		Harness.checkEquals(helper, 0, pending.countFor(owner.getUUID()),
				"the queue was not cleared, so they would be given the same items again "
						+ "every time they log in");
		helper.succeed();
	}

	@GameTest
	public void aQueuedReleaseCanBeCancelled(GameTestHelper helper) {
		ServerPlayer owner = Harness.mockPlayer(helper);
		owner.getInventory().clearContent();

		long id = vault().deposit(owner.getUUID(), Harness.name(owner), "TestStaff",
				new ItemStack(Items.GOLD_INGOT, 5), "gametest", Harness.server(helper));
		ContrabandVault.Entry entry = vault().byId(id);
		vault().queueRelease(Harness.server(helper), entry, "TestStaff");

		Harness.check(helper, vault().cancelRelease(vault().byId(id)),
				"a queued release could not be cancelled");

		// The point of cancelling is that the delivery does not happen. If the queue row
		// survives, staff have changed their mind and the server has not noticed.
		PendingActions.Settled settled = StaffCore.pending().drainFor(owner);
		Harness.checkEquals(helper, 0, settled.given(),
				"a cancelled release was delivered anyway");
		helper.succeed();
	}

	@GameTest
	public void aDestroyedItemDoesNotComeBack(GameTestHelper helper) {
		ServerPlayer owner = Harness.mockPlayer(helper);
		owner.getInventory().clearContent();

		long id = vault().deposit(owner.getUUID(), Harness.name(owner), "TestStaff",
				new ItemStack(Items.NETHERITE_INGOT, 1), "gametest", Harness.server(helper));

		Harness.check(helper, vault().destroy(vault().byId(id), "TestStaff"),
				"destroying a vaulted item failed");

		PendingActions.Settled settled = StaffCore.pending().drainFor(owner);
		Harness.checkEquals(helper, 0, settled.given(),
				"an item destroyed for good was handed back, which would make the vault's "
						+ "one irreversible action reversible by accident");
		helper.succeed();
	}
}
