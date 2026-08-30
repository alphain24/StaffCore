package dev.lebron.staffcore.gui.container;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * Where an inventory view reads its slots from.
 * <p>
 * Two implementations, so one container class serves both cases: a live player, where
 * every read hits their real inventory and a write lands on it immediately; and a stored
 * snapshot decoded from an offline player's save file, which is always read-only because
 * writing it back would mean re-encoding NBT under the player's feet.
 */
public interface InventorySource {

	int size();

	ItemStack get(int slot);

	/** Writes a slot. Read-only sources ignore this. */
	void set(int slot, ItemStack stack);

	boolean editable();

	/** Called after a write so the owner sees the change. */
	void flush();

	// ------------------------------------------------------------------ factories

	static InventorySource live(ServerPlayer player, boolean editable) {
		return new Live(player, editable);
	}

	/** A read-only view over already-decoded contents. */
	static InventorySource stored(ItemStack[] contents) {
		return new Stored(contents);
	}

	// ------------------------------------------------------------- implementations

	final class Live implements InventorySource {
		private final ServerPlayer player;
		private final boolean editable;

		Live(ServerPlayer player, boolean editable) {
			this.player = player;
			this.editable = editable;
		}

		@Override
		public int size() {
			return player.getInventory().getContainerSize();
		}

		@Override
		public ItemStack get(int slot) {
			Inventory inv = player.getInventory();
			return (slot >= 0 && slot < inv.getContainerSize()) ? inv.getItem(slot) : ItemStack.EMPTY;
		}

		@Override
		public void set(int slot, ItemStack stack) {
			if (!editable) return;
			Inventory inv = player.getInventory();
			if (slot >= 0 && slot < inv.getContainerSize()) inv.setItem(slot, stack);
		}

		@Override
		public boolean editable() {
			return editable;
		}

		@Override
		public void flush() {
			player.getInventory().setChanged();
		}

		public ServerPlayer player() {
			return player;
		}
	}

	final class Stored implements InventorySource {
		private final ItemStack[] contents;

		Stored(ItemStack[] contents) {
			this.contents = contents;
		}

		@Override
		public int size() {
			return contents.length;
		}

		@Override
		public ItemStack get(int slot) {
			return (slot >= 0 && slot < contents.length) ? contents[slot] : ItemStack.EMPTY;
		}

		@Override
		public void set(int slot, ItemStack stack) {
			// Read-only by design.
		}

		@Override
		public boolean editable() {
			return false;
		}

		@Override
		public void flush() {
			// Nothing to write back to.
		}
	}
}
