package io.github.alphain24.staffcore.util;

import io.github.alphain24.staffcore.compat.Mc;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.TypedDataComponent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Reads out everything an item is actually carrying.
 * <p>
 * Two items that look identical in a chest menu can be very different things: one dropped by
 * a zombie, one built by a command with an attribute modifier nobody notices until a player
 * is hitting for forty. The hover tooltip shows what the game chooses to show; this shows
 * what is <em>there</em>.
 * <p>
 * In 26.x that means data components rather than an NBT tag soup, which is a better thing to
 * read: each entry has a registered id and a codec, so every value can be rendered without
 * this class knowing anything about what any particular component means. New components from
 * a future version, or from another mod, list themselves.
 */
public final class ItemInspector {
	private ItemInspector() {}

	/** One component, ready to print. */
	public record Entry(String id, String value, boolean fromDefaults) {

		/** Values are frequently longer than a chat line or a lore row. */
		public List<String> wrapped(int width) {
			List<String> out = new ArrayList<>();
			String remaining = value;
			while (remaining.length() > width) {
				int cut = remaining.lastIndexOf(',', width);
				if (cut <= 0) cut = width;
				out.add(remaining.substring(0, cut + (remaining.charAt(cut) == ',' ? 1 : 0)));
				remaining = remaining.substring(cut + 1).stripLeading();
			}
			if (!remaining.isEmpty()) out.add(remaining);
			return out;
		}
	}

	/**
	 * Every component on a stack, sorted by id.
	 * <p>
	 * Components an item type carries by default — every pickaxe has a max damage — are
	 * marked rather than hidden. What makes an item suspicious is usually the difference
	 * from its defaults, and you cannot see a difference if one side is not shown.
	 */
	public static List<Entry> components(MinecraftServer server, ItemStack stack) {
		List<Entry> out = new ArrayList<>();
		if (stack == null || stack.isEmpty() || server == null) return out;

		RegistryOps<Tag> ops = registryOps(server);
		var defaults = stack.getItem().components();

		for (TypedDataComponent<?> component : stack.getComponents()) {
			DataComponentType<?> type = component.type();
			String id = String.valueOf(BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(type));

			String rendered;
			try {
				// The component's own codec, so this works for anything registered without
				// a line of code here per component type.
				rendered = component.encodeValue(ops)
						.result()
						.map(Tag::toString)
						.orElseGet(() -> String.valueOf(component.value()));
			} catch (RuntimeException e) {
				// A component whose codec cannot handle a server-side-only context still has
				// a toString, and a partial reading beats a screen that will not open.
				rendered = String.valueOf(component.value());
			}
			out.add(new Entry(id, rendered, defaults.has(type)
					&& java.util.Objects.equals(defaults.get(type), component.value())));
		}
		out.sort(Comparator.comparing(Entry::id));
		return out;
	}

	/** The whole stack as SNBT — what the database stores, and what a bug report wants. */
	public static String raw(MinecraftServer server, ItemStack stack) {
		return ItemCodec.encode(server, stack);
	}

	/** A short headline: what it is and how many. */
	public static String summary(ItemStack stack) {
		if (stack == null || stack.isEmpty()) return "empty";
		return stack.getCount() + "× " + Mc.itemId(stack.getItem());
	}

	private static RegistryOps<Tag> registryOps(MinecraftServer server) {
		return RegistryOps.create(NbtOps.INSTANCE, server.registryAccess());
	}
}
