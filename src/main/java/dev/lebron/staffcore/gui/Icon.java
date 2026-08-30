package dev.lebron.staffcore.gui;

import net.minecraft.server.players.NameAndId;
import dev.lebron.staffcore.compat.Mc;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.ItemLike;

import java.util.ArrayList;
import java.util.List;

/**
 * Fluent builder for menu icons.
 * <p>
 * Item names and lore are italic by default in vanilla, which looks like a bug in a
 * purpose-built menu — every line produced here has italics explicitly switched off.
 */
public final class Icon {

	private final ItemStack stack;
	private final List<Component> lore = new ArrayList<>();

	private Icon(ItemStack stack) {
		this.stack = stack;
	}

	public static Icon of(ItemLike item) {
		return new Icon(new ItemStack(item));
	}

	/**
	 * Wraps an already-built stack for further editing. Existing lore is read back into
	 * the builder so appending a line adds to it rather than replacing it — without this,
	 * decorating a finished icon would silently wipe its description.
	 */
	public static Icon of(ItemStack stack) {
		Icon icon = new Icon(stack.copy());
		ItemLore existing = stack.get(DataComponents.LORE);
		if (existing != null) {
			icon.lore.addAll(existing.lines());
		}
		return icon;
	}

	/** A player head wearing {@code profile}'s skin. */
	public static Icon head(NameAndId profile) {
		return new Icon(Mc.head(profile));
	}

	// ------------------------------------------------------------------- styling

	public Icon name(String text, int rgb) {
		return name(text(text, rgb).withStyle(Style.EMPTY.withBold(true)));
	}

	public Icon name(Component component) {
		stack.set(DataComponents.CUSTOM_NAME, component);
		return this;
	}

	/** A plain muted line. */
	public Icon lore(String line) {
		lore.add(text(line, Theme.MUTED));
		return this;
	}

	public Icon lore(String line, int rgb) {
		lore.add(text(line, rgb));
		return this;
	}

	public Icon lore(Component line) {
		lore.add(line);
		return this;
	}

	/** {@code Label: value} with the label muted and the value emphasised. */
	public Icon field(String label, String value) {
		return field(label, value, Theme.TEXT);
	}

	public Icon field(String label, String value, int valueRgb) {
		lore.add(text(label + ": ", Theme.MUTED).append(text(value, valueRgb)));
		return this;
	}

	/** Blank spacer line. */
	public Icon gap() {
		lore.add(Component.empty());
		return this;
	}

	/** A call-to-action line, e.g. {@code action("Left-click", "open the punish menu")}. */
	public Icon action(String input, String result) {
		lore.add(text("▶ ", Theme.ACCENT)
				.append(text(input, Theme.ACCENT))
				.append(text(" — " + result, Theme.MUTED)));
		return this;
	}

	/** A green/red state line, e.g. {@code state(true, "Enabled", "Disabled")}. */
	public Icon state(boolean on, String whenOn, String whenOff) {
		lore.add(text(on ? "● " + whenOn : "○ " + whenOff, on ? Theme.GOOD : Theme.BAD));
		return this;
	}

	public Icon warn(String line) {
		lore.add(text("⚠ " + line, Theme.WARN));
		return this;
	}

	/** Enchantment shimmer without an actual enchantment. */
	public Icon glow() {
		stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
		return this;
	}

	public Icon count(int count) {
		stack.setCount(Math.max(1, Math.min(count, 64)));
		return this;
	}

	// ------------------------------------------------------------------- building

	public ItemStack build() {
		if (!lore.isEmpty()) {
			stack.set(DataComponents.LORE, new ItemLore(List.copyOf(lore)));
		}
		return stack;
	}

	// -------------------------------------------------------------------- helpers

	/** Non-italic coloured text — the base every line in this mod is built from. */
	public static MutableComponent text(String s, int rgb) {
		return Component.literal(s).setStyle(Style.EMPTY
				.withColor(TextColor.fromRgb(rgb))
				.withItalic(false));
	}

	/** Wraps a long paragraph onto lore-sized lines. */
	public Icon paragraph(String body, int rgb) {
		if (body == null || body.isBlank()) return this;
		StringBuilder line = new StringBuilder();
		for (String word : body.split("\\s+")) {
			if (line.length() + word.length() + 1 > 38) {
				lore.add(text(line.toString(), rgb));
				line.setLength(0);
			}
			if (!line.isEmpty()) line.append(' ');
			line.append(word);
		}
		if (!line.isEmpty()) lore.add(text(line.toString(), rgb));
		return this;
	}
}
