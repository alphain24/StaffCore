package io.github.alphain24.staffcore.util;

import com.mojang.serialization.Dynamic;
import io.github.alphain24.staffcore.StaffCore;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;

/**
 * Turns an {@link ItemStack} into text and back, for the rows that have to outlive a restart.
 * <p>
 * Stacks go through the game's own {@code ItemStack.CODEC} with a registry-aware ops layer,
 * so enchantments, custom names and every other data component survive intact — writing the
 * fields out by hand would quietly drop whatever the next update adds. SNBT rather than raw
 * bytes because a stash row you can read in a database browser is worth the few extra bytes
 * when something has gone wrong at 2am.
 */
public final class ItemCodec {
	private ItemCodec() {}

	/** Encoded form of an empty slot. */
	public static final String EMPTY = "{}";

	public static String encode(MinecraftServer server, ItemStack stack) {
		if (stack == null || stack.isEmpty()) return EMPTY;
		try {
			RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, lookup(server));
			Tag tag = ItemStack.CODEC.encodeStart(ops, stack).getOrThrow();
			return tag instanceof CompoundTag compound
					? NbtUtils.structureToSnbt(compound)
					: EMPTY;
		} catch (RuntimeException e) {
			StaffCore.LOGGER.error("[StaffCore] Could not encode an item for storage", e);
			return EMPTY;
		}
	}

	/** Returns {@link ItemStack#EMPTY} for anything unreadable rather than throwing. */
	public static ItemStack decode(MinecraftServer server, String encoded) {
		if (encoded == null || encoded.isBlank() || EMPTY.equals(encoded)) return ItemStack.EMPTY;
		try {
			CompoundTag tag = NbtUtils.snbtToStructure(encoded);
			RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, lookup(server));
			return ItemStack.CODEC
					.parse(new Dynamic<>(ops, tag))
					.resultOrPartial(error -> StaffCore.LOGGER.warn(
							"[StaffCore] Partial item decode: {}", error))
					.orElse(ItemStack.EMPTY);
		} catch (Exception e) {
			// A stack written by an older version, or an item from a mod since removed.
			// One lost item beats a failed restore of everything else in the stash.
			StaffCore.LOGGER.warn("[StaffCore] Dropping an unreadable stored item: {}", e.getMessage());
			return ItemStack.EMPTY;
		}
	}

	private static HolderLookup.Provider lookup(MinecraftServer server) {
		return server.registryAccess();
	}
}
