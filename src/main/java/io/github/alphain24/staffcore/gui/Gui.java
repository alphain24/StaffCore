package io.github.alphain24.staffcore.gui;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

import java.util.Arrays;

/**
 * Base class for every StaffCore screen.
 * <p>
 * A {@code Gui} is a chest menu whose slots are buttons rather than storage. Item
 * movement is refused at the {@link #clicked} level instead of relying on the client to
 * behave, so a modified client cannot walk items out of a menu. Concrete menus describe
 * themselves in {@link #build()} and call {@link #render()} at the end of their
 * constructor; calling {@code render()} again later redraws in place.
 */
public abstract class Gui extends ChestMenu {

	/** Everything a button handler needs to know about the press that triggered it. */
	public record Click(ServerPlayer player, ContainerInput type, int button) {
		public boolean isRight() {
			return type == ContainerInput.PICKUP && button == 1;
		}

		public boolean isShift() {
			return type == ContainerInput.QUICK_MOVE;
		}

		public boolean isMiddle() {
			return type == ContainerInput.CLONE;
		}
	}

	@FunctionalInterface
	public interface Action {
		void run(Click click);
	}

	protected final ServerPlayer viewer;
	protected final Container backing;
	protected final int rows;
	protected final int size;

	private final Action[] buttons;
	private boolean navigating;

	/** The slot {@link #backButton} painted, or -1 for a screen without one. */
	private int backSlot = -1;
	private String backFallbackLabel;
	private Runnable backFallback;
	/** Where the history says back leads; wins over the fallback label once known. */
	private String backLabel;

	protected Gui(int containerId, Inventory playerInventory, ServerPlayer viewer, int rows, Container backing) {
		super(typeForRows(rows), containerId, playerInventory, backing, rows);
		this.viewer = viewer;
		this.rows = rows;
		this.size = rows * 9;
		this.backing = backing;
		this.buttons = new Action[this.size];
	}

	protected Gui(int containerId, Inventory playerInventory, ServerPlayer viewer, int rows) {
		this(containerId, playerInventory, viewer, rows, new SimpleContainer(rows * 9));
	}

	private static MenuType<?> typeForRows(int rows) {
		return switch (rows) {
			case 1 -> MenuType.GENERIC_9x1;
			case 2 -> MenuType.GENERIC_9x2;
			case 3 -> MenuType.GENERIC_9x3;
			case 4 -> MenuType.GENERIC_9x4;
			case 5 -> MenuType.GENERIC_9x5;
			case 6 -> MenuType.GENERIC_9x6;
			default -> throw new IllegalArgumentException("A chest menu has 1-6 rows, not " + rows);
		};
	}

	// ------------------------------------------------------------------ rendering

	/** Lay out this screen. Called by {@link #render()}; never call it directly. */
	protected abstract void build();

	/**
	 * Wipes the scratch slots, re-runs {@link #build()} and pushes the result to the
	 * client. Safe to call from inside a button handler.
	 */
	protected final void render() {
		Arrays.fill(buttons, null);
		for (int i = 0; i < size; i++) {
			if (isScratchSlot(i)) backing.setItem(i, ItemStack.EMPTY);
		}
		build();
		if (viewer.containerMenu == this) {
			sendAllDataToRemote();
		}
	}

	// --------------------------------------------------------------- auto refresh

	/**
	 * How often to redraw this screen on its own, in ticks. Zero, the default, never does.
	 * <p>
	 * A chest menu is not a live view of anything: the server sends its contents when the
	 * screen opens and then says nothing until somebody clicks. For a static list that is
	 * correct and cheap. For a screen showing what a player is holding <em>right now</em> it
	 * is a lie that gets worse the longer staff look at it — they watch a suspect empty their
	 * inventory and the screen shows the old contents until they click something.
	 * <p>
	 * Screens that claim to be live say how live they are here.
	 */
	protected int refreshEveryTicks() {
		return 0;
	}

	/**
	 * Redraws if this screen asked to be redrawn and now is the moment.
	 * <p>
	 * Skipped while the viewer is carrying something, because repainting mid-drag pulls the
	 * item out from under them — and a staff member moving an item is precisely when they
	 * least want the screen rearranged.
	 */
	public final void autoRefresh(int serverTick) {
		int every = refreshEveryTicks();
		if (every <= 0 || serverTick % every != 0) return;
		if (viewer.containerMenu != this || !getCarried().isEmpty()) return;

		try {
			onAutoRefresh();
		} catch (RuntimeException e) {
			// A screen that throws while redrawing itself must not take the tick loop with
			// it — every other player's menus are on the same thread.
			io.github.alphain24.staffcore.StaffCore.LOGGER.error(
					"[GUI] {} failed to refresh", getClass().getSimpleName(), e);
		}
	}

	/**
	 * What an automatic refresh actually does. A redraw, unless a screen needs otherwise.
	 * <p>
	 * A screen reading straight from memory can simply repaint. One reading from the database
	 * cannot: a log view has to fetch first, and fetching on the server thread twice a second
	 * is not something to do casually. Those override this to kick off their own read and
	 * repaint when it lands.
	 */
	protected void onAutoRefresh() {
		render();
	}

	/**
	 * True when slot {@code i} is ours to clear and repaint. Menus that surface real
	 * inventory contents (invsee) override this so a redraw never touches live items.
	 */
	protected boolean isScratchSlot(int i) {
		return true;
	}

	// ------------------------------------------------------------------- placement

	/** A decorative item with no behaviour. */
	protected void set(int slot, ItemStack icon) {
		if (slot < 0 || slot >= size) return;
		backing.setItem(slot, icon);
	}

	/** An item that runs {@code action} when clicked. */
	protected void button(int slot, ItemStack icon, Action action) {
		if (slot < 0 || slot >= size) return;
		backing.setItem(slot, icon);
		buttons[slot] = action;
	}

	/** Paints {@code icon} into every empty scratch slot. Call last in {@link #build()}. */
	protected void fillEmpty(ItemStack icon) {
		for (int i = 0; i < size; i++) {
			if (isScratchSlot(i) && backing.getItem(i).isEmpty()) {
				backing.setItem(i, icon.copy());
			}
		}
	}

	/** Paints a full row. */
	protected void fillRow(int row, ItemStack icon) {
		for (int c = 0; c < 9; c++) {
			set(row * 9 + c, icon.copy());
		}
	}

	/** Convenience for {@code row * 9 + column}. */
	protected static int slot(int row, int column) {
		return row * 9 + column;
	}

	// --------------------------------------------------------------- interaction

	@Override
	public void clicked(int slotId, int button, ContainerInput clickType, Player player) {
		// Below our container sits the viewer's own inventory. Normally that is inert too —
		// a staff menu is a control surface, not a place where items change hands — but an
		// editable inventory view has to allow it, or a stack you have picked up has
		// nowhere to go.
		if (slotId < 0 || slotId >= size) {
			if (slotId >= size && allowsOwnInventory()) {
				super.clicked(slotId, button, clickType, player);
				return;
			}
			resync();
			return;
		}

		Action action = buttons[slotId];
		if (action != null) {
			try {
				action.run(new Click(viewer, clickType, button));
			} catch (Exception e) {
				io.github.alphain24.staffcore.StaffCore.LOGGER.error(
						"[StaffCore] GUI action failed in {} slot {}", getClass().getSimpleName(), slotId, e);
				Sfx.error(viewer);
			}
			resync();
			return;
		}

		if (isSlotInteractive(slotId)) {
			super.clicked(slotId, button, clickType, player);
			return;
		}

		Sfx.deny(viewer);
		resync();
	}

	/**
	 * True when vanilla click handling should run for this slot. Only the editable
	 * invsee grid says yes; everywhere else a bare slot is inert decoration.
	 */
	protected boolean isSlotInteractive(int slotId) {
		return false;
	}

	/**
	 * True when the viewer may handle their own inventory while this menu is open. Only
	 * the editable inventory view says yes.
	 */
	protected boolean allowsOwnInventory() {
		return false;
	}

	/** Shift-clicking would try to move items between halves. Nothing to move. */
	@Override
	public ItemStack quickMoveStack(Player player, int index) {
		return ItemStack.EMPTY;
	}

	@Override
	public boolean stillValid(Player player) {
		return true;
	}

	/**
	 * Re-sends the whole window, killing any client-side desync.
	 * <p>
	 * This deliberately leaves the carried stack alone. An earlier version cleared it to
	 * "reset" the cursor, which silently destroyed items: in an editable inventory view,
	 * picking a stack up and then clicking anywhere inert — the frame, a button, your own
	 * inventory, outside the window — deleted it. Vanilla's {@code removed} already returns
	 * a carried stack to the player when the menu closes, so nothing here needs to.
	 */
	protected void resync() {
		if (viewer.containerMenu != this) return;
		sendAllDataToRemote();
	}

	// ------------------------------------------------------------------ going back

	/**
	 * The back arrow. Click for one level back along the path actually taken; shift-click for
	 * the staff panel.
	 * <p>
	 * One helper rather than a button per menu, because the per-menu version is what went
	 * wrong: every screen named its own destination, most of them named the panel, and the
	 * sections made each of those guesses wrong at once.
	 *
	 * @param fallbackLabel what to call {@code fallback} when there is no path to follow
	 * @param fallback      where back goes when the screen was opened by a command, or the
	 *                      path was lost when the menus closed; {@code null} closes it
	 */
	protected void backButton(int slot, String fallbackLabel, Runnable fallback) {
		this.backSlot = slot;
		this.backFallbackLabel = fallbackLabel;
		this.backFallback = fallback;
		button(slot, Theme.backButton(backLabel != null ? backLabel : fallbackLabel), click -> {
			if (click.isShift()) {
				home();
			} else {
				Guis.back(viewer, backFallback);
			}
		});
	}

	/**
	 * Shift-click on back. The panel is gated on {@code staff.gui} at the command and at the
	 * toolset item, and a sub-screen can be opened without it — so the gate is checked here
	 * too rather than assumed from having got this far.
	 */
	private void home() {
		if (!io.github.alphain24.staffcore.permission.Permissions.check(viewer,
				io.github.alphain24.staffcore.permission.Nodes.STAFF_GUI)) {
			viewer.sendSystemMessage(Theme.bad("You do not have "
					+ io.github.alphain24.staffcore.permission.Nodes.STAFF_GUI + "."));
			Sfx.deny(viewer);
			return;
		}
		io.github.alphain24.staffcore.gui.menu.StaffPanelMenu.home(viewer);
	}

	/**
	 * Told by {@link Guis} once this screen is in the history, so the arrow can name the
	 * screen it really returns to instead of the one its author assumed.
	 */
	void arrivedFrom(String label) {
		this.backLabel = label;
		if (backSlot < 0 || buttons[backSlot] == null) return;
		backing.setItem(backSlot, Theme.backButton(label));
		broadcastChanges();
	}

	/**
	 * False for a screen that must never be rebuilt from history. A confirmation is the case:
	 * its button runs the action, so stepping back into one offers to do it again.
	 */
	protected boolean returnable() {
		return true;
	}

	/**
	 * How back re-enters this screen, or {@code null} — the default — to rebuild it from the
	 * factory that first made it.
	 * <p>
	 * A screen whose opener does real work should return that opener: one that starts an edit
	 * session, or reads a live-or-stored source, or checks something that could have changed
	 * while staff were a level deeper.
	 */
	protected Runnable reentry() {
		return null;
	}

	// -------------------------------------------------------------------- lifecycle

	/** Marks that the next close is a hop to another menu, not the player leaving. */
	public void markNavigating() {
		this.navigating = true;
	}

	/**
	 * True when this screen is closing because another StaffCore menu is opening.
	 * <p>
	 * {@link #onClosed()} runs either way, so a screen holding something that should outlive
	 * a hop between menus — a rollback preview drawn in the world, say, which exists
	 * precisely so it can be looked at while the confirm screen is up — has to be able to
	 * tell the two apart.
	 */
	protected boolean isNavigating() {
		return navigating;
	}

	@Override
	public void removed(Player player) {
		super.removed(player);
		if (!navigating) {
			Sfx.close(viewer);
			// Closed for real. A path back through screens that are no longer open would
			// return the next command-opened screen to wherever staff were an hour ago.
			Guis.forget(viewer.getUUID());
		}
		onClosed();
	}

	/** Hook for menus that hold resources (live inventory views, tick listeners). */
	protected void onClosed() {}

	public ServerPlayer viewer() {
		return viewer;
	}
}
