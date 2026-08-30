package dev.lebron.staffcore.util;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Map;

/**
 * Takes owed items back out of a player's inventory.
 * <p>
 * Rollback is a duplication exploit without this: the griefer keeps the cobblestone they
 * mined <em>and</em> the wall goes back up, so every repair quietly prints items. Ground
 * drops are cleared first; whatever is still owed after that is taken from the person who
 * owes it.
 * <p>
 * Lives here rather than inside the grief module because the debt outlives the moment it is
 * incurred — an offender who logs off before the rollback is settled the next time they log
 * in, and the join handler that settles them has no business reaching into rollback
 * internals to do it.
 */
public final class ItemDebit {
	private ItemDebit() {}

	/**
	 * Removes up to the owed amount of each item, decrementing {@code owed} as it goes so
	 * the caller can see what could not be found.
	 *
	 * @param owed item type to quantity still due; mutated in place
	 * @return how many individual items were actually taken
	 */
	public static int debit(ServerPlayer offender, Map<Item, Integer> owed) {
		int removed = 0;
		var inv = offender.getInventory();

		for (int slot = 0; slot < inv.getContainerSize(); slot++) {
			ItemStack stack = inv.getItem(slot);
			if (stack.isEmpty()) continue;

			Integer due = owed.get(stack.getItem());
			if (due == null || due <= 0) continue;

			int take = Math.min(due, stack.getCount());
			stack.shrink(take);
			owed.put(stack.getItem(), due - take);
			removed += take;
		}

		if (removed > 0) {
			inv.setChanged();
			offender.containerMenu.broadcastChanges();
		}
		return removed;
	}

	/**
	 * Puts a stack into a player's inventory, or on the floor at their feet if it will not
	 * fit.
	 * <p>
	 * Refusing to hand something back because the recipient's inventory is full would be a
	 * strange way to correct a mistake we made.
	 */
	public static void give(ServerPlayer player, ItemStack stack) {
		if (stack.isEmpty()) return;
		if (!player.getInventory().add(stack)) {
			player.drop(stack, false);
		}
		player.containerMenu.broadcastChanges();
	}
}
