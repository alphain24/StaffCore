package io.github.alphain24.staffcore.gui;

import io.github.alphain24.staffcore.compat.Mc;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Items;

/**
 * The look of StaffCore: one palette, one set of chrome pieces, one title format.
 * <p>
 * Every menu borrows from here rather than picking its own colours, which is what keeps
 * fifteen separate screens reading as one tool.
 */
public final class Theme {
	private Theme() {}

	// ------------------------------------------------------------------- palette

	/**
	 * Brand purple — headers, interactive hints, the accent on every title.
	 * <p>
	 * Purple rather than red on purpose: {@link #BAD} is already red and carries a specific
	 * meaning everywhere in this mod — banned, destructive, offline, refused. Making the
	 * brand colour red too would put "this is a heading" and "this will hurt someone" in the
	 * same ink, which is exactly the distinction staff need at a glance.
	 */
	public static final int ACCENT = 0xB15CFF;
	/** Body text. */
	public static final int TEXT   = 0xE8EAED;
	/** Secondary text, labels, disabled things. */
	public static final int MUTED  = 0x9099A2;
	/** Enabled, safe, resolved. */
	public static final int GOOD   = 0x5BE87A;
	/** Destructive, banned, offline. */
	public static final int BAD    = 0xFF5C57;
	/** Needs attention, temporary, pending. */
	public static final int WARN   = 0xFFC93C;
	/** Punishment ladder accents. */
	public static final int PUNISH = 0xE0563C;

	// --------------------------------------------------------------------- chrome

	/** Dark filler for the border of every menu. */
	public static ItemStack filler() {
		return Icon.of(Mc.pane(DyeColor.BLACK)).name(Component.empty()).build();
	}

	/** Lighter filler for the inside of a menu, used sparingly. */
	public static ItemStack softFiller() {
		return Icon.of(Mc.pane(DyeColor.GRAY)).name(Component.empty()).build();
	}

	/** Accent filler used to underline a section header. */
	public static ItemStack accentFiller() {
		return Icon.of(Mc.pane(DyeColor.PURPLE)).name(Component.empty()).build();
	}

	// -------------------------------------------------------------- common buttons

	public static ItemStack backButton(String to) {
		return Icon.of(Items.ARROW)
				.name("Back", ACCENT)
				.lore("Return to " + to + ".")
				.build();
	}

	public static ItemStack closeButton() {
		return Icon.of(Items.BARRIER)
				.name("Close", BAD)
				.lore("Shut the menu.")
				.build();
	}

	public static ItemStack prevPage(int page) {
		return Icon.of(Items.SPECTRAL_ARROW)
				.name("Previous page", TEXT)
				.field("Going to", "page " + page)
				.build();
	}

	public static ItemStack nextPage(int page) {
		return Icon.of(Items.SPECTRAL_ARROW)
				.name("Next page", TEXT)
				.field("Going to", "page " + page)
				.build();
	}

	public static ItemStack pageIndicator(int page, int total, int entries) {
		return Icon.of(Items.PAPER)
				.name("Page " + page + " / " + total, ACCENT)
				.field("Entries", String.valueOf(entries))
				.build();
	}

	public static ItemStack confirmButton(String what) {
		return Icon.of(Mc.concrete(DyeColor.LIME))
				.name("Confirm", GOOD)
				.lore(what)
				.glow()
				.build();
	}

	public static ItemStack cancelButton() {
		return Icon.of(Mc.concrete(DyeColor.RED))
				.name("Cancel", BAD)
				.lore("Nothing happens.")
				.build();
	}

	public static ItemStack lockedButton(String reason) {
		return Icon.of(Items.IRON_BARS)
				.name("Locked", MUTED)
				.lore(reason)
				.build();
	}

	// ---------------------------------------------------------------------- titles

	/** {@code StaffCore › Punish › Notch} — the breadcrumb style used by every screen. */
	public static MutableComponent title(String... crumbs) {
		// Bold throughout: a chest title bar renders small and thin, and the breadcrumb is
		// the only thing telling staff which of sixteen near-identical screens they are on.
		MutableComponent out = Icon.text("StaffCore", ACCENT).withStyle(s -> s.withBold(true));
		for (String crumb : crumbs) {
			out = out.append(Icon.text(" › ", MUTED))
					.append(Icon.text(crumb, TEXT).withStyle(s -> s.withBold(true)));
		}
		return out;
	}

	/** Prefix for every chat line StaffCore sends. */
	public static MutableComponent prefix() {
		return Icon.text("[", MUTED)
				.append(Icon.text("StaffCore", ACCENT).withStyle(s -> s.withBold(true)))
				.append(Icon.text("] ", MUTED));
	}

	public static MutableComponent info(String message) {
		return prefix().append(Icon.text(message, TEXT));
	}

	public static MutableComponent good(String message) {
		return prefix().append(Icon.text(message, GOOD));
	}

	public static MutableComponent bad(String message) {
		return prefix().append(Icon.text(message, BAD));
	}

	public static MutableComponent warn(String message) {
		return prefix().append(Icon.text(message, WARN));
	}
}
