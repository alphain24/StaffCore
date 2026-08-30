package dev.lebron.staffcore.gui.container;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.Arrays;

/**
 * A 54-slot chest view over somebody else's inventory.
 * <p>
 * Slots are proxied to an {@link InventorySource} rather than copied, so a live view shows
 * what the player is holding at that instant and an edit lands on the real stack. Slots
 * outside the mapping are local scratch space for the menu's own buttons.
 * <p>
 * Layout, identical for live and offline views so the two are directly comparable:
 * <pre>
 *   rows 1-3  →  main storage   (inventory 9-35)
 *   row  4    →  hotbar         (inventory 0-8)
 *   row  5    →  armour + offhand, then buttons
 *   row  6    →  buttons
 * </pre>
 */
public final class InventoryViewContainer implements Container {

	public static final int SIZE = 54;

	private final InventorySource source;
	/** GUI slot → inventory slot, or -1 for scratch. */
	private final int[] map = new int[SIZE];
	private final ItemStack[] scratch = new ItemStack[SIZE];

	public InventoryViewContainer(InventorySource source) {
		this.source = source;
		Arrays.fill(map, -1);
		Arrays.fill(scratch, ItemStack.EMPTY);

		int size = source.size();

		for (int i = 0; i < 27; i++) {          // main storage
			if (9 + i < size) map[i] = 9 + i;
		}
		for (int i = 0; i < 9; i++) {           // hotbar
			if (i < size) map[27 + i] = i;
		}
		for (int i = 0; i < 5; i++) {           // armour (36-39) then offhand (40)
			if (36 + i < size) map[36 + i] = 36 + i;
		}
	}

	/** The inventory slot behind a GUI slot, or -1 when the slot is ours. */
	public int inventorySlot(int guiSlot) {
		return (guiSlot < 0 || guiSlot >= SIZE) ? -1 : map[guiSlot];
	}

	public boolean isMapped(int guiSlot) {
		return inventorySlot(guiSlot) >= 0;
	}

	public boolean isEditable() {
		return source.editable();
	}

	public InventorySource source() {
		return source;
	}

	// ------------------------------------------------------------------ Container

	@Override
	public int getContainerSize() {
		return SIZE;
	}

	@Override
	public boolean isEmpty() {
		for (int i = 0; i < SIZE; i++) {
			if (!getItem(i).isEmpty()) return false;
		}
		return true;
	}

	@Override
	public ItemStack getItem(int slot) {
		int mapped = inventorySlot(slot);
		if (mapped >= 0) return source.get(mapped);
		return (slot >= 0 && slot < SIZE) ? scratch[slot] : ItemStack.EMPTY;
	}

	@Override
	public ItemStack removeItem(int slot, int amount) {
		int mapped = inventorySlot(slot);
		if (mapped >= 0) {
			if (!source.editable()) return ItemStack.EMPTY;
			ItemStack current = source.get(mapped);
			if (current.isEmpty()) return ItemStack.EMPTY;
			ItemStack taken = current.split(amount);
			source.flush();
			return taken;
		}
		if (slot < 0 || slot >= SIZE || scratch[slot].isEmpty()) return ItemStack.EMPTY;
		return scratch[slot].split(amount);
	}

	@Override
	public ItemStack removeItemNoUpdate(int slot) {
		int mapped = inventorySlot(slot);
		if (mapped >= 0) {
			if (!source.editable()) return ItemStack.EMPTY;
			ItemStack current = source.get(mapped).copy();
			source.set(mapped, ItemStack.EMPTY);
			source.flush();
			return current;
		}
		if (slot < 0 || slot >= SIZE) return ItemStack.EMPTY;
		ItemStack removed = scratch[slot];
		scratch[slot] = ItemStack.EMPTY;
		return removed;
	}

	@Override
	public void setItem(int slot, ItemStack stack) {
		int mapped = inventorySlot(slot);
		if (mapped >= 0) {
			// A read-only viewer can never write through, whatever the client sends.
			if (!source.editable()) return;
			source.set(mapped, stack);
			source.flush();
			return;
		}
		if (slot >= 0 && slot < SIZE) scratch[slot] = stack;
	}

	@Override
	public void setChanged() {
		source.flush();
	}

	@Override
	public boolean stillValid(Player player) {
		return true;
	}

	@Override
	public void clearContent() {
		// Never wipe somebody's inventory because a menu closed. Only scratch is cleared.
		Arrays.fill(scratch, ItemStack.EMPTY);
	}
}
