package io.github.alphain24.staffcore.util;

import io.github.alphain24.staffcore.StaffCore;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.ItemStackWithSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.ValueInput;

import java.util.Arrays;
import java.util.Optional;

/**
 * Reads an offline player's inventory straight out of their save file.
 * <p>
 * "They logged off" is the single most common reason staff need to see an inventory, so
 * refusing to open one for an offline player made the tool useless exactly when it mattered.
 * The read is one-way on purpose: writing NBT back under a player who might reconnect
 * mid-edit is a corruption risk that no amount of care makes safe.
 */
public final class OfflineInventory {
	private OfflineInventory() {}

	/** Covers 36 main slots, 4 armour, offhand, body armour and saddle. */
	private static final int SLOTS = io.github.alphain24.staffcore.compat.Mc.PLAYER_SLOTS;

	/**
	 * Decoded contents, or null when the player has no save file — someone who has been
	 * whitelisted but never joined, or whose data was wiped.
	 */
	public static ItemStack[] load(MinecraftServer server, NameAndId target) {
		try {
			Optional<CompoundTag> data = server.getPlayerList().loadPlayerData(target);
			if (data.isEmpty()) return null;

			ValueInput input = TagValueInput.create(
					ProblemReporter.DISCARDING, server.registryAccess(), data.get());

			ItemStack[] contents = new ItemStack[SLOTS];
			Arrays.fill(contents, ItemStack.EMPTY);

			for (ItemStackWithSlot entry : input.listOrEmpty("Inventory", ItemStackWithSlot.CODEC)) {
				if (entry.slot() >= 0 && entry.slot() < SLOTS) {
					contents[entry.slot()] = entry.stack();
				}
			}
			return contents;
		} catch (RuntimeException e) {
			// A save written by a different version, or a half-written file. Report the
			// failure to the caller rather than showing staff a convincingly empty inventory.
			StaffCore.LOGGER.error("[StaffCore] Could not read offline inventory for {}",
					target.name(), e);
			return null;
		}
	}

	public static int usedSlots(ItemStack[] contents) {
		int n = 0;
		for (ItemStack stack : contents) {
			if (stack != null && !stack.isEmpty()) n++;
		}
		return n;
	}
}
