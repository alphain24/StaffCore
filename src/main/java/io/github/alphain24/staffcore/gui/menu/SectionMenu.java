package io.github.alphain24.staffcore.gui.menu;

import io.github.alphain24.staffcore.gui.Gui;
import io.github.alphain24.staffcore.gui.Guis;
import io.github.alphain24.staffcore.gui.Icon;
import io.github.alphain24.staffcore.gui.Sfx;
import io.github.alphain24.staffcore.gui.Theme;
import io.github.alphain24.staffcore.permission.Permissions;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.function.Consumer;

/**
 * One page of one section of the staff panel.
 *
 * <h2>Why a section is data rather than a class</h2>
 * The panel used to be twenty-five buttons on a single screen in four unlabelled bands, which
 * is a list rather than a structure: everything was one click away and nothing was findable,
 * because "is the container log under people or under server?" has no answer when the grouping
 * is implicit.
 * <p>
 * Splitting it needs one screen per section, and eight near-identical classes is how that ends
 * up drifting — one gets a back button in a different slot, another forgets the permission
 * gate, and a year later no two look alike. So the screen is written once and a section is a
 * {@link List} of {@link Entry}. Adding one is a line in {@link StaffSections}, and every
 * section is laid out, gated and navigated identically because there is only one of it.
 *
 * <h2>Locked entries stay visible</h2>
 * An entry the viewer cannot use is shown as a locked bar naming the node, rather than hidden.
 * A menu that silently omits what you lack is a menu where you cannot tell "this server does
 * not have that" from "you are not allowed that", and the second is a conversation with an
 * admin while the first is a bug report.
 */
public final class SectionMenu extends Gui {

	/**
	 * One thing a section can open.
	 *
	 * @param node the permission required, or null for something everybody with panel access
	 *             may use
	 * @param open what clicking it does. Takes the viewer so an entry can open a menu, run a
	 *             sweep, or toggle something without this class knowing which.
	 */
	public record Entry(String node, ItemStack icon, Consumer<ServerPlayer> open) {

		public static Entry of(String node, ItemStack icon, Consumer<ServerPlayer> open) {
			return new Entry(node, icon, open);
		}
	}

	/** Where entries go: the middle two rows, leaving the border for title and navigation. */
	private static final int[] SLOTS = {
			10, 11, 12, 13, 14, 15, 16,
			19, 20, 21, 22, 23, 24, 25,
			28, 29, 30, 31, 32, 33, 34};

	private static final int TITLE = 4;
	private static final int BACK = 45;
	private static final int CLOSE = 49;

	private final String section;
	private final String blurb;
	private final List<Entry> entries;

	public static void open(ServerPlayer viewer, String section, String blurb,
			List<Entry> entries) {

		Guis.navigate(viewer, Theme.title(section),
				(id, inv, player) -> new SectionMenu(id, inv, player, section, blurb, entries));
	}

	private SectionMenu(int containerId, Inventory playerInventory, ServerPlayer viewer,
			String section, String blurb, List<Entry> entries) {

		super(containerId, playerInventory, viewer, 6);
		this.section = section;
		this.blurb = blurb;
		this.entries = entries;
		render();
	}

	@Override
	protected void build() {
		set(TITLE, Icon.of(Items.NETHER_STAR)
				.name(section, Theme.ACCENT)
				.lore(blurb)
				.build());

		for (int i = 0; i < entries.size() && i < SLOTS.length; i++) {
			place(SLOTS[i], entries.get(i));
		}

		button(BACK, Icon.of(Items.ARROW)
				.name("Back", Theme.MUTED)
				.lore("Return to the staff panel.")
				.build(), click -> StaffPanelMenu.reopen(viewer));

		button(CLOSE, Theme.closeButton(), click -> viewer.closeContainer());
		fillEmpty(Theme.filler());
	}

	private void place(int slot, Entry entry) {
		if (entry.node() != null && !Permissions.check(viewer, entry.node())) {
			set(slot, Theme.lockedButton("Needs " + entry.node()));
			return;
		}
		button(slot, entry.icon(), click -> {
			Sfx.click(viewer);
			entry.open().accept(viewer);
		});
	}

	/** The section title, styled like every other screen's. */
	private static Component title(String section) {
		return Theme.title(section);
	}
}
