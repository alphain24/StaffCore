package io.github.alphain24.staffcore.gui.menu;

import io.github.alphain24.staffcore.compat.Mc;
import io.github.alphain24.staffcore.config.StaffConfig;
import io.github.alphain24.staffcore.gui.Gui;
import io.github.alphain24.staffcore.gui.Guis;
import io.github.alphain24.staffcore.gui.Icon;
import io.github.alphain24.staffcore.gui.Sfx;
import io.github.alphain24.staffcore.gui.Theme;
import io.github.alphain24.staffcore.permission.Nodes;
import io.github.alphain24.staffcore.permission.Permissions;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * The contraband rule list, edited by pointing at items rather than typing their ids.
 * <p>
 * The lists themselves are plain config, and always were — but editing them meant knowing
 * that a spawner is {@code minecraft:spawner} and that the trial variant is a different id
 * again. Here the item <em>is</em> the rule: click one in your inventory to ban it, click
 * it in the grid to allow it again.
 * <p>
 * Nothing is ever consumed. Clicking reads the item's type and puts it straight back —
 * a rules screen that ate a stack of shulker boxes to learn what a shulker box is would
 * be a poor trade.
 */
public class ContrabandMenu extends Gui {

	/** Rows 0–4 hold rules; row 5 is controls. */
	private static final int GRID = 45;

	private static final int SLOT_TAB_ILLEGAL = 45;
	private static final int SLOT_TAB_OPERATOR = 46;
	private static final int SLOT_PREV = 47;
	private static final int SLOT_PAGE = 48;
	private static final int SLOT_HELP = 49;
	private static final int SLOT_NEXT = 50;
	private static final int SLOT_VAULT = 51;
	private static final int SLOT_BACK = 52;
	private static final int SLOT_CLOSE = 53;

	/** Which list is being edited. */
	private boolean operatorTab;
	/** Which page of that list is on screen. */
	private int page;

	public static void open(ServerPlayer viewer) {
		Guis.navigate(viewer, Theme.title("Contraband Rules"),
				(id, inv, v) -> new ContrabandMenu(id, inv, v, false, 0));
	}

	private static void reopen(ServerPlayer viewer, boolean operatorTab, int page) {
		Guis.silent(viewer, Theme.title("Contraband Rules"),
				(id, inv, v) -> new ContrabandMenu(id, inv, v, operatorTab, page));
	}

	private ContrabandMenu(int containerId, Inventory playerInventory, ServerPlayer viewer,
			boolean operatorTab, int page) {
		super(containerId, playerInventory, viewer, 6);
		this.operatorTab = operatorTab;
		this.page = page;
		render();
	}

	private List<String> list() {
		StaffConfig cfg = StaffConfig.get();
		return operatorTab ? cfg.operatorItems : cfg.illegalItems;
	}

	private boolean mayEdit() {
		return Permissions.check(viewer, Nodes.CONTRABAND_EDIT);
	}

	// ---------------------------------------------------------------------- render

	@Override
	protected void build() {
		List<String> ids = list();
		int pages = Math.max(1, (ids.size() + GRID - 1) / GRID);
		page = Math.clamp(page, 0, pages - 1);

		int start = page * GRID;
		for (int i = 0; i < GRID && start + i < ids.size(); i++) {
			final String id = ids.get(start + i);
			Item item = Mc.itemFromId(id, null);

			ItemStack shown = item == null ? ItemStack.EMPTY : new ItemStack(item);
			Icon icon = item == null
					? Icon.of(Items.BARRIER).name(id, Theme.BAD)
							.lore("Unknown item — this id matches nothing.", Theme.BAD)
					: Icon.of(shown).name(shown.getHoverName().getString(), Theme.TEXT)
							.field("Id", id);

			if (mayEdit()) {
				icon.gap().action("Click", "stop treating this as contraband");
			}
			button(i, icon.build(), click -> removeRule(id));
		}

		tabs();
		pager(pages, ids.size());
		controls();
		fillEmpty(Theme.filler());
	}

	/**
	 * Page controls, in the three slots row 5 has left over.
	 * <p>
	 * This screen used to refuse to edit a list longer than one gridful, because saving
	 * would have written back only what was on screen and silently dropped the rest. That
	 * was the right call given a screen that could only show 45 things — but the fix for
	 * "we can only show 45 things" is to show the other ones, not to send people to a JSON
	 * file to do what this screen exists for.
	 */
	private void pager(int pages, int total) {
		if (page > 0) {
			button(SLOT_PREV, Theme.prevPage(page), click -> {
				page--;
				Sfx.page(viewer);
				render();
			});
		}
		if (page < pages - 1) {
			button(SLOT_NEXT, Theme.nextPage(page + 2), click -> {
				page++;
				Sfx.page(viewer);
				render();
			});
		}
		if (pages > 1) {
			set(SLOT_PAGE, Theme.pageIndicator(page + 1, pages, total));
		}
	}

	private void tabs() {
		StaffConfig cfg = StaffConfig.get();

		button(SLOT_TAB_ILLEGAL, Icon.of(Items.BEDROCK)
				.name("Impossible in survival", operatorTab ? Theme.MUTED : Theme.ACCENT)
				.field("Entries", String.valueOf(cfg.illegalItems.size()))
				.gap()
				.lore("Items no legitimate player can obtain.", Theme.MUTED)
				.action("Click", operatorTab ? "edit this list" : "already editing")
				.build(), click -> switchTab(false));

		button(SLOT_TAB_OPERATOR, Icon.of(Items.COMMAND_BLOCK)
				.name("Operator tooling", operatorTab ? Theme.ACCENT : Theme.MUTED)
				.field("Entries", String.valueOf(cfg.operatorItems.size()))
				.gap()
				.lore("Reported separately — finding one of these", Theme.MUTED)
				.lore("on a player is a different kind of problem.", Theme.MUTED)
				.action("Click", operatorTab ? "already editing" : "edit this list")
				.build(), click -> switchTab(true));
	}

	private void controls() {
		set(SLOT_HELP, Icon.of(Items.KNOWLEDGE_BOOK)
				.name("How this works", Theme.ACCENT)
				.gap()
				.lore("Click an item in your own inventory below", Theme.TEXT)
				.lore("to add it to the list above.", Theme.TEXT)
				.gap()
				.lore("Click an item in the grid to remove it.", Theme.TEXT)
				.gap()
				.lore("Your items are never taken — only the item", Theme.MUTED)
				.lore("type is read.", Theme.MUTED)
				.build());

		if (Permissions.check(viewer, Nodes.VAULT)) {
			button(SLOT_VAULT, Icon.of(Items.BUNDLE)
					.name("Contraband Vault", Theme.TEXT)
					.gap()
					.lore("What has actually been taken off players.", Theme.MUTED)
					.action("Click", "open the vault")
					.build(), click -> VaultMenu.open(viewer));
		}

		backButton(SLOT_BACK, "Security", () -> StaffSections.security(viewer));

		button(SLOT_CLOSE, Icon.of(Items.BARRIER)
				.name("Close", Theme.MUTED)
				.build(), click -> viewer.closeContainer());
	}

	// --------------------------------------------------------------------- editing

	/**
	 * A click anywhere in the viewer's own inventory adds that item type to the list.
	 * <p>
	 * Reading the stack rather than moving it is the whole trick: the player keeps their
	 * item, and the screen never has to distinguish "this is a rule" from "this is
	 * somebody's actual shulker box", because it only ever holds the former.
	 */
	@Override
	public void clicked(int slotId, int button, ContainerInput clickType, Player player) {
		if (slotId >= size && slotId < slots.size()) {
			ItemStack clicked = getSlot(slotId).getItem();
			if (!clicked.isEmpty()) {
				addRule(clicked.getItem());
				return;
			}
		}
		super.clicked(slotId, button, clickType, player);
	}

	private void addRule(Item item) {
		if (!mayEdit()) {
			viewer.sendSystemMessage(Theme.warn("You do not have " + Nodes.CONTRABAND_EDIT + "."));
			Sfx.deny(viewer);
			resync();
			return;
		}

		String id = Mc.itemId(item);
		List<String> ids = list();

		if (ids.contains(id)) {
			viewer.sendSystemMessage(Theme.info(id + " is already on this list."));
			Sfx.deny(viewer);
			resync();
			return;
		}

		ids.add(id);
		StaffConfig.save();
		viewer.sendSystemMessage(Theme.good("Added " + id + " to the "
				+ (operatorTab ? "operator" : "impossible") + " list."));
		Sfx.success(viewer);

		// Land on the page the new entry is actually on, rather than leaving somebody
		// looking at a screen that did not visibly change.
		reopen(viewer, operatorTab, (ids.size() - 1) / GRID);
	}

	private void removeRule(String id) {
		if (!mayEdit()) {
			viewer.sendSystemMessage(Theme.warn("You do not have " + Nodes.CONTRABAND_EDIT + "."));
			Sfx.deny(viewer);
			return;
		}

		if (list().remove(id)) {
			StaffConfig.save();
			viewer.sendSystemMessage(Theme.info("Removed " + id + " from the list."));
			Sfx.toggleOff(viewer);
		}
		// Removing the last entry on the final page would otherwise leave the viewer
		// looking at a page that no longer exists.
		int pages = Math.max(1, (list().size() + GRID - 1) / GRID);
		reopen(viewer, operatorTab, Math.min(page, pages - 1));
	}

	private void switchTab(boolean operator) {
		if (operator == operatorTab) {
			Sfx.deny(viewer);
			return;
		}
		Sfx.page(viewer);
		reopen(viewer, operator, 0);
	}
}
