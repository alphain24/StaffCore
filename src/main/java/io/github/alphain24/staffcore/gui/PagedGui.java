package io.github.alphain24.staffcore.gui;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * A six-row list screen: a framed 7×4 content area holding 28 entries, with page
 * controls along the bottom. Player lists, punishment history, notes, the report queue
 * and the grief log are all this class with a different {@link #icon} and {@link #onPick}.
 */
public abstract class PagedGui<T> extends Gui {

	/** The inner 7×4 window, reading left-to-right, top-to-bottom. */
	protected static final int[] CONTENT = {
			10, 11, 12, 13, 14, 15, 16,
			19, 20, 21, 22, 23, 24, 25,
			28, 29, 30, 31, 32, 33, 34,
			37, 38, 39, 40, 41, 42, 43
	};

	protected static final int SLOT_HEADER = 4;
	protected static final int SLOT_BACK = 45;
	protected static final int SLOT_PREV = 48;
	protected static final int SLOT_PAGE = 49;
	protected static final int SLOT_NEXT = 50;
	protected static final int SLOT_CLOSE = 53;

	private int page;

	protected PagedGui(int containerId, Inventory playerInventory, ServerPlayer viewer) {
		super(containerId, playerInventory, viewer, 6);
	}

	// ------------------------------------------------------------- what subclasses give us

	/** Re-read every time the screen renders, so edits show up without reopening. */
	protected abstract List<T> entries();

	/**
	 * Total row count when this screen reads a page at a time, or {@code -1} — the default —
	 * when {@link #entries()} already returns everything.
	 * <p>
	 * Most lists here are short enough that loading them whole is simpler and cheaper than
	 * paging. A few are not: the contraband vault grows for the life of a server, and
	 * pulling it into memory to draw twenty-eight rows meant capping it and telling staff
	 * the rest was unreachable. Overriding this and {@link #window} moves the paging into
	 * the query, which the underlying tables were already indexed for.
	 */
	protected int totalEntries() {
		return -1;
	}

	/** One page of rows. Only called when {@link #totalEntries()} is non-negative. */
	protected List<T> window(int offset, int limit) {
		return List.of();
	}

	protected abstract ItemStack icon(T entry);

	protected abstract void onPick(T entry, Click click);

	/** The item shown in the middle of the top row. */
	protected abstract ItemStack header();

	/** Where the back arrow goes, or {@code null} for a root screen. */
	protected Runnable backTarget() {
		return null;
	}

	protected String backLabel() {
		return "the staff panel";
	}

	/** Shown in the middle of the content area when there is nothing to list. */
	protected ItemStack emptyIcon() {
		return Icon.of(Items.STRUCTURE_VOID)
				.name("Nothing here", Theme.MUTED)
				.lore("The list is empty.")
				.build();
	}

	/** Hook for extra controls in the bottom row (slots 46, 47, 51, 52). */
	protected void decorateFooter() {}

	/** The rows this screen last drew, so an automatic refresh can tell if anything moved. */
	private List<T> lastSeen;

	/**
	 * Redraws only when the list has actually changed.
	 * <p>
	 * Repainting a list of rows on a timer is not free the way repainting a static screen is:
	 * these rows are <em>buttons</em>, and on the container log shift-clicking one undoes a
	 * theft. A list that quietly reshuffles under somebody's cursor between the decision to
	 * click and the click itself is a way to undo the wrong thing. Newest-first ordering
	 * makes it worse — one new row pushes every existing one down a place.
	 * <p>
	 * So the screen holds still while nothing is happening, and moves only when there is
	 * genuinely something new to show, which is the moment staff want it to move anyway.
	 */
	@Override
	protected void onAutoRefresh() {
		// A windowed screen reads a page at a time and cannot be compared without fetching
		// the whole table, which is the thing windowing exists to avoid. Those repaint every
		// pass; nothing currently enables refresh on one, and this is here so that turning it
		// on later gets a working screen rather than one that silently never updates.
		if (totalEntries() >= 0) {
			render();
			return;
		}

		List<T> fresh = entries();
		if (fresh.equals(lastSeen)) return;

		lastSeen = fresh;
		render();
	}

	// ------------------------------------------------------------------------- layout

	@Override
	protected void build() {
		// A windowed screen knows its size without loading itself; everything else hands
		// over the whole list and is sliced here, exactly as before.
		int windowed = totalEntries();
		int total = windowed >= 0 ? windowed : entries().size();

		int pages = Math.max(1, (total + CONTENT.length - 1) / CONTENT.length);
		page = Math.clamp(page, 0, pages - 1);

		int start = page * CONTENT.length;
		List<T> visible = windowed >= 0
				? window(start, CONTENT.length)
				: entries().subList(Math.min(start, total), Math.min(start + CONTENT.length, total));

		frame();
		set(SLOT_HEADER, header());

		for (int i = 0; i < CONTENT.length && i < visible.size(); i++) {
			T entry = visible.get(i);
			button(CONTENT[i], icon(entry), click -> onPick(entry, click));
		}

		if (total == 0) {
			set(CONTENT[10], emptyIcon()); // dead centre of the window
		}

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
		set(SLOT_PAGE, Theme.pageIndicator(page + 1, pages, total));

		Runnable back = backTarget();
		if (back != null) {
			button(SLOT_BACK, Theme.backButton(backLabel()), click -> back.run());
		}
		button(SLOT_CLOSE, Theme.closeButton(), click -> viewer.closeContainer());

		decorateFooter();
	}

	/** Border: full top and bottom rows plus the outer columns. */
	protected void frame() {
		ItemStack filler = Theme.filler();
		for (int column = 0; column < 9; column++) {
			set(column, filler.copy());
			set(45 + column, filler.copy());
		}
		for (int row = 1; row < 5; row++) {
			set(row * 9, filler.copy());
			set(row * 9 + 8, filler.copy());
		}
	}

	/** Jump back to the first page — used after an action shortens the list. */
	protected void resetPage() {
		page = 0;
	}
}
