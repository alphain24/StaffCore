package io.github.alphain24.staffcore.gui.menu;

import io.github.alphain24.staffcore.compat.Mc;
import io.github.alphain24.staffcore.config.StaffConfig;
import io.github.alphain24.staffcore.gui.Gui;
import io.github.alphain24.staffcore.gui.Guis;
import io.github.alphain24.staffcore.gui.Icon;
import io.github.alphain24.staffcore.gui.Sfx;
import io.github.alphain24.staffcore.gui.Theme;
import io.github.alphain24.staffcore.module.Mods;
import io.github.alphain24.staffcore.modules.grief.GriefModule;
import io.github.alphain24.staffcore.permission.Nodes;
import io.github.alphain24.staffcore.permission.Permissions;
import io.github.alphain24.staffcore.util.PlayerLookup;
import io.github.alphain24.staffcore.util.TimeFormat;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * What was broken, built and opened around where you are standing.
 * <p>
 * Paged on the server rather than in the client: the query asks for twenty-eight rows and a
 * count, runs off the main thread, and hands the result back on it. The old version pulled
 * two hundred rows synchronously on every open, which is exactly the kind of thing that
 * makes a moderation tool the cause of the lag it was opened to investigate.
 * <p>
 * Rollback works two ways. By player, when you know who did it; by area, when several
 * accounts hit one build and naming them one at a time is hopeless.
 */
public class GriefMenu extends Gui {

	private static final int PER_PAGE = 28;
	private static final int[] CONTENT = {
			10, 11, 12, 13, 14, 15, 16,
			19, 20, 21, 22, 23, 24, 25,
			28, 29, 30, 31, 32, 33, 34,
			37, 38, 39, 40, 41, 42, 43
	};

	private static final int SLOT_HEADER = 4;
	/** Top row, opposite the header — row 5 has no space left. */
	private static final int SLOT_HISTORY = 8;
	private static final int SLOT_RECENTRE = 44;
	private static final int SLOT_BACK = 45;
	private static final int SLOT_RADIUS = 46;
	private static final int SLOT_WINDOW = 47;
	private static final int SLOT_PREV = 48;
	private static final int SLOT_PAGE = 49;
	private static final int SLOT_NEXT = 50;
	private static final int SLOT_SHOW = 51;
	private static final int SLOT_ROLLBACK = 52;
	private static final int SLOT_CLOSE = 53;

	/** How far past a row's own blocks spilled items are looked for when rolling back just it. */
	static final int ROW_REACH = 3;

	/** Radius steps the control cycles through. 24 was the old hardcoded value. */
	private static final int[] RADII = { 8, 16, 24, 48, 96 };

	/**
	 * What the list is showing.
	 * <p>
	 * One control instead of two. The old screen had a mining-noise toggle and nothing else,
	 * so "show me only what was broken" — the commonest thing anybody wants from a grief log
	 * — was not expressible at all.
	 */
	private enum Show {
		INTERESTING("Everything except mining", null),
		ALL("Everything, mining included", null),
		BREAKS("Broken blocks only", "BREAK"),
		PLACES("Placed blocks only", "PLACE"),
		CONTAINERS("Containers opened only", "OPEN");

		final String label;
		final String action;

		Show(String label, String action) {
			this.label = label;
			this.action = action;
		}

		Show next() {
			return values()[(ordinal() + 1) % values().length];
		}
	}

	private BlockPos centre;
	private int windowMinutes;
	private int radius = 24;
	/** Null means "everyone in this area". */
	private String playerFilter;
	private Show show = StaffConfig.get().hideMiningNoise ? Show.INTERESTING : Show.ALL;

	private GriefModule.Page page;
	private int pageIndex;
	private boolean loading = true;
	/** The row last shift-clicked with nothing left to roll back, by its newest log id. */
	private long nothingLeftAt = -1;

	private boolean hideNoise() {
		return show == Show.INTERESTING;
	}

	// ------------------------------------------------------------------- opening

	public static void open(ServerPlayer viewer) {
		Guis.navigate(viewer, Theme.title("Grief Log"),
				(id, inv, v) -> new GriefMenu(id, inv, v, v.blockPosition(),
						StaffConfig.get().defaultRollbackMinutes, null));
	}

	/** Opened from a player's file — pre-filtered to them. */
	public static void openFor(ServerPlayer viewer, String playerName) {
		Guis.navigate(viewer, Theme.title("Grief Log", playerName),
				(id, inv, v) -> new GriefMenu(id, inv, v, v.blockPosition(),
						StaffConfig.get().defaultRollbackMinutes, playerName));
	}

	/** Centred on a specific block — used by the inspect tool. */
	public static void openAt(ServerPlayer viewer, BlockPos where) {
		Guis.navigate(viewer, Theme.title("Grief Log", "Block"),
				(id, inv, v) -> new GriefMenu(id, inv, v, where,
						StaffConfig.get().defaultRollbackMinutes, null));
	}

	/** Centred on a place and reaching back far enough — used by case evidence. */
	public static void openAround(ServerPlayer viewer, BlockPos centre, int minutes) {
		Guis.navigate(viewer, Theme.title("Grief Log", "Evidence"),
				(id, inv, v) -> new GriefMenu(id, inv, v, centre,
						Math.min(7 * 24 * 60, Math.max(5, minutes)), null));
	}

	private static void reopen(ServerPlayer viewer, BlockPos centre, int minutes, String filter) {
		Guis.silent(viewer, Theme.title("Grief Log"),
				(id, inv, v) -> new GriefMenu(id, inv, v, centre, minutes, filter));
	}

	private GriefMenu(int containerId, Inventory playerInventory, ServerPlayer viewer,
			BlockPos centre, int windowMinutes, String playerFilter) {
		super(containerId, playerInventory, viewer, 6);
		this.centre = centre;
		this.windowMinutes = windowMinutes;
		this.playerFilter = playerFilter;
		render();
		fetch();
	}

	private long windowMs() {
		return windowMinutes * 60_000L;
	}

	/**
	 * Re-reads the area every two seconds, so the log keeps up with what is happening in it.
	 * <p>
	 * Staff watch this screen during an incident rather than after one. A read per pass rather
	 * than a repaint, so slower than the inventory views &mdash; and it only repaints when the
	 * page has actually changed, because these rows are buttons and a list that reshuffles
	 * under a cursor is a way to click the wrong one.
	 */
	@Override
	protected int refreshEveryTicks() {
		return 40;
	}

	@Override
	protected void onAutoRefresh() {
		if (!loading) fetch(true);
	}

	/** Kicks off the off-thread read; the callback repaints once it lands, if anything moved. */
	private void fetch() {
		fetch(false);
	}

	/**
	 * Kicks off the off-thread read.
	 * <p>
	 * A <em>quiet</em> read is one the staff member did not ask for — the two-second refresh.
	 * It must not touch {@code loading}, because {@code build()} draws a spinner whenever that
	 * is set, and a background read has no business replacing rows somebody is reading with
	 * "Reading the log…" twice a minute. Worse, a click landing during one of those windows
	 * would repaint the spinner and then, if the rows had not changed, never repaint over it —
	 * leaving the screen stuck on a load that had already finished.
	 *
	 * @param quiet true for the automatic refresh, false when a person is waiting
	 */
	private void fetch(boolean quiet) {
		MinecraftServer server = Mc.server(viewer);
		if (server == null) return;

		boolean first = page == null;
		if (!quiet) loading = true;

		Mods.grief().nearAsync(server, viewer.level(), centre, radius, windowMs(),
				playerFilter, hideNoise(), show.action, pageIndex * PER_PAGE, PER_PAGE, result -> {
					// The staff member may have closed or navigated on while we were reading.
					if (viewer.containerMenu != this) return;

					boolean changed = first || !result.equals(this.page);
					boolean wasSpinning = this.loading;
					this.page = result;
					this.loading = false;

					// Repaint when there is something new, and always when a spinner is on
					// screen — otherwise the load that just finished stays on display.
					if (changed || wasSpinning) render();
				});
	}

	// -------------------------------------------------------------------- layout

	@Override
	protected void build() {
		frame();

		// The header is where the current scope is written, so it is also where clearing it
		// belongs — rather than on a separate button that spends most of its life saying
		// "nothing to clear".
		if (playerFilter != null) {
			button(SLOT_HEADER, header(), click -> {
				playerFilter = null;
				pageIndex = 0;
				Sfx.click(viewer);
				render();
				fetch();
			});
		} else {
			set(SLOT_HEADER, header());
		}

		if (loading || page == null) {
			set(CONTENT[10], Icon.of(Items.CLOCK)
					.name("Reading the log…", Theme.MUTED)
					.lore("Running off the main thread so the server keeps ticking.")
					.build());
		} else if (page.entries().isEmpty()) {
			set(CONTENT[10], Icon.of(Mc.pane(DyeColor.LIME))
					.name("Nothing logged here", Theme.GOOD)
					.lore("No changes in this radius and window.")
					.lore("Widen the window with the clock below.")
					.build());
		} else {
			List<Run> runs = collapse(page.entries());
			for (int i = 0; i < runs.size() && i < CONTENT.length; i++) {
				Run run = runs.get(i);
				button(CONTENT[i], icon(run), click -> onPick(run, click));
			}
		}

		buildFooter();
		fillEmpty(Theme.filler());
	}

	private void frame() {
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

	/**
	 * A run of the same thing, done by the same person, in the same place and moment.
	 *
	 * @param entries every row in it, newest first — what rolling back this one row undoes
	 */
	private record Run(GriefModule.Entry first, int count, long oldest,
			List<GriefModule.Entry> entries) {}

	/**
	 * Collapses consecutive identical actions into one row.
	 * <p>
	 * Tearing down a wall writes one row per block, so a page of the old screen could be
	 * twenty-eight lines that all said the same thing — the same player breaking the same
	 * block within the same few seconds — while whatever you were actually looking for sat on
	 * page four. Grouping them turns that into "Steve broke 28× oak planks" and leaves room
	 * for the row that matters.
	 * <p>
	 * Only <em>adjacent</em> entries are merged, and the log already arrives newest-first, so
	 * this never reorders anything or hides a gap in time. A run that straddles a page
	 * boundary shows as two, which is a fair price for not having to read the whole table to
	 * draw one screen.
	 */
	private static List<Run> collapse(List<GriefModule.Entry> entries) {
		List<Run> runs = new java.util.ArrayList<>();

		for (GriefModule.Entry entry : entries) {
			Run last = runs.isEmpty() ? null : runs.get(runs.size() - 1);
			if (last != null && continues(last, entry)) {
				List<GriefModule.Entry> members = new java.util.ArrayList<>(last.entries());
				members.add(entry);
				runs.set(runs.size() - 1, new Run(last.first(), last.count() + 1, entry.at(),
						members));
			} else {
				runs.add(new Run(entry, 1, entry.at(), List.of(entry)));
			}
		}
		return runs;
	}

	/** Same person, same action, same block, close by, and close in time. */
	private static boolean continues(Run run, GriefModule.Entry next) {
		GriefModule.Entry head = run.first();
		if (!head.player().equals(next.player())) return false;
		if (!head.action().equals(next.action())) return false;
		if (!head.block().equals(next.block())) return false;

		// Two minutes and eight blocks: wide enough to swallow a wall coming down, narrow
		// enough that two separate visits to the same spot stay separate rows.
		if (run.oldest() - next.at() > 120_000L) return false;
		return Math.abs(head.x() - next.x()) <= 8
				&& Math.abs(head.y() - next.y()) <= 8
				&& Math.abs(head.z() - next.z()) <= 8;
	}

	private ItemStack icon(Run run) {
		GriefModule.Entry entry = run.first();
		boolean broke = "BREAK".equals(entry.action());
		boolean opened = "OPEN".equals(entry.action());

		net.minecraft.world.item.Item item = opened ? Items.CHEST
				: broke ? Items.IRON_PICKAXE : Items.BRICKS;
		String verb = opened ? "Opened" : broke ? "Broke" : "Placed";
		int colour = opened ? Theme.WARN : broke ? Theme.BAD : Theme.GOOD;

		String what = run.count() > 1
				? run.count() + "× " + shortId(entry.block())
				: shortId(entry.block());

		Icon icon = Icon.of(item)
				.name(entry.player(), Theme.TEXT)
				.field(verb, what, colour)
				.field("At", "%d, %d, %d".formatted(entry.x(), entry.y(), entry.z()))
				.field("When", TimeFormat.ago(entry.at()));

		if (run.count() > 1) {
			icon.field("Over", TimeFormat.duration(Math.max(1000L, entry.at() - run.oldest())));
		}

		icon.gap()
				.action("Left-click", "teleport to the spot")
				.action("Right-click", playerFilter == null
						? "show only " + entry.player()
						: "clear the player filter");

		if (Permissions.check(viewer, Nodes.ROLLBACK)) {
			icon.action("Shift-click", opened ? "undo what they took or put in here"
					: run.count() > 1 ? "roll back these " + run.count()
					: "roll back just this");
		}
		if (entry.id() == nothingLeftAt) {
			icon.gap().warn("Nothing left to roll back here —")
					.warn("it may already have been rolled back.");
		}
		return icon.build();
	}

	private static net.minecraft.world.item.Item iconFor(Show show) {
		return switch (show) {
			case INTERESTING -> Items.SPYGLASS;
			case ALL -> Items.STONE;
			case BREAKS -> Items.IRON_PICKAXE;
			case PLACES -> Items.BRICKS;
			case CONTAINERS -> Items.CHEST;
		};
	}

	private static String shortId(String id) {
		return RollbackPreview.shortId(id);
	}

	private void onPick(Run run, Click click) {
		GriefModule.Entry entry = run.first();
		nothingLeftAt = -1;
		if (click.isRight()) {
			playerFilter = entry.player().equals(playerFilter) ? null : entry.player();
			pageIndex = 0;
			Sfx.click(viewer);
			render();
			fetch();
			return;
		}

		if (click.isShift()) {
			if (!Permissions.check(viewer, Nodes.ROLLBACK)) {
				viewer.sendSystemMessage(Theme.bad("You do not have " + Nodes.ROLLBACK + "."));
				Sfx.deny(viewer);
				return;
			}
			previewRollback(run);
			return;
		}

		Mods.teleport().toPosition(viewer, entry.x() + 0.5, entry.y() + 1.0, entry.z() + 0.5);
		viewer.sendSystemMessage(Theme.info("Teleported to %d, %d, %d."
				.formatted(entry.x(), entry.y(), entry.z())));
		viewer.closeContainer();
	}

	// -------------------------------------------------------------------- footer

	private void buildFooter() {
		if (Permissions.check(viewer, Nodes.ROLLBACK)) {
			int undoable = Mods.grief().points().undoableCount();
			button(SLOT_HISTORY, Icon.of(Items.CLOCK)
					.name("Rollback history", undoable > 0 ? Theme.ACCENT : Theme.MUTED)
					.field("Undoable", String.valueOf(undoable),
							undoable > 0 ? Theme.ACCENT : Theme.MUTED)
					.gap()
					.lore("Every rollback records what it overwrote,", Theme.MUTED)
					.lore("so it can be put back.", Theme.MUTED)
					.action("Click", "open the history")
					.build(), click -> RestorePointsMenu.open(viewer));
		}

		backButton(SLOT_BACK, "World", () -> StaffSections.world(viewer));

		button(SLOT_WINDOW, Icon.of(Items.CLOCK)
				.name("Time window", Theme.TEXT)
				.field("Currently", TimeFormat.duration(windowMs()))
				.gap()
				.action("Left-click", "double it")
				.action("Right-click", "halve it")
				.build(), click -> {
			windowMinutes = click.isRight()
					? Math.max(5, windowMinutes / 2)
					: Math.min(7 * 24 * 60, windowMinutes * 2);
			pageIndex = 0;
			Sfx.page(viewer);
			render();
			fetch();
		});

		button(SLOT_RADIUS, Icon.of(Items.SPYGLASS)
				.name("Search radius", Theme.TEXT)
				.field("Currently", radius + " blocks")
				.gap()
				.lore("The rollback button uses this too.", Theme.MUTED)
				.action("Left-click", "widen")
				.action("Right-click", "narrow")
				.build(), click -> {
			// Was hardcoded at 24, which meant a rollback could only ever be 24 blocks
			// wide — too small for a razed build and too wide for one stolen chest.
			int at = 0;
			for (int i = 0; i < RADII.length; i++) {
				if (RADII[i] == radius) at = i;
			}
			radius = RADII[Math.clamp(click.isRight() ? at - 1 : at + 1, 0, RADII.length - 1)];
			pageIndex = 0;
			Sfx.page(viewer);
			render();
			fetch();
		});

		int total = page == null ? 0 : page.total();
		int pages = Math.max(1, (total + PER_PAGE - 1) / PER_PAGE);

		if (pageIndex > 0) {
			button(SLOT_PREV, Theme.prevPage(pageIndex), click -> {
				pageIndex--;
				Sfx.page(viewer);
				render();
				fetch();
			});
		}
		if (pageIndex < pages - 1) {
			button(SLOT_NEXT, Theme.nextPage(pageIndex + 2), click -> {
				pageIndex++;
				Sfx.page(viewer);
				render();
				fetch();
			});
		}
		set(SLOT_PAGE, Theme.pageIndicator(pageIndex + 1, pages, total));

		button(SLOT_SHOW, Icon.of(iconFor(show))
				.name("Showing", Theme.ACCENT)
				.field("Rows", show.label, show == Show.INTERESTING ? Theme.TEXT : Theme.WARN)
				.gap()
				.lore("Fifty players mining buries the one broken", Theme.MUTED)
				.lore("chest you came here to find.", Theme.MUTED)
				.gap()
				.action("Click", "show " + show.next().label.toLowerCase(java.util.Locale.ROOT))
				.build(), click -> {
			show = show.next();
			pageIndex = 0;
			Sfx.click(viewer);
			render();
			fetch();
		});

		button(SLOT_RECENTRE, Icon.of(Items.COMPASS)
				.name("Re-centre here", Theme.TEXT)
				.lore("Move, then click to search around your new position.")
				.build(), click -> {
			// Moved rather than reopened, so the radius, window and filters you set on the
			// way here survive. Reopening reset all of them, which meant re-centring twice
			// cost you the search you had just built.
			centre = viewer.blockPosition().immutable();
			pageIndex = 0;
			Sfx.success(viewer);
			render();
			fetch();
		});

		if (Permissions.check(viewer, Nodes.ROLLBACK)) {
			button(SLOT_ROLLBACK, Icon.of(Items.TNT)
					.name("Roll back this area", Theme.BAD)
					.field("Scope", playerFilter == null ? "everyone" : playerFilter)
					.field("Radius", radius + " blocks")
					.field("Window", TimeFormat.duration(windowMs()))
					.gap()
					.warn("You see a preview before anything is written.")
					.build(), click -> previewRollback(playerFilter));
		}

		button(SLOT_CLOSE, Theme.closeButton(), click -> viewer.closeContainer());
	}

	// ------------------------------------------------------------------ rollback

	/**
	 * One row of the log: what that player did at exactly the spots in it, and nothing else they
	 * did nearby. A broken chest comes back with what was in it and whatever they took out of it
	 * first; a run of broken planks comes back as that run.
	 * <p>
	 * The time window is the log's own, not the row's, so the items somebody took out of a chest
	 * ten minutes before breaking it are still undone along with the break.
	 */
	private void previewRollback(Run run) {
		java.util.Set<BlockPos> spots = new java.util.LinkedHashSet<>();
		int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
		for (GriefModule.Entry each : run.entries()) {
			spots.add(new BlockPos(each.x(), each.y(), each.z()));
			minX = Math.min(minX, each.x());
			minY = Math.min(minY, each.y());
			minZ = Math.min(minZ, each.z());
			maxX = Math.max(maxX, each.x());
			maxY = Math.max(maxY, each.y());
			maxZ = Math.max(maxZ, each.z());
		}
		BlockPos middle = new BlockPos((minX + maxX) >> 1, (minY + maxY) >> 1, (minZ + maxZ) >> 1);
		// Wider than the spots themselves: this is also where spilled items are picked back up
		// from, and a chest's contents do not all land on the block it stood on.
		int reach = Math.max(maxX - minX, Math.max(maxY - minY, maxZ - minZ)) / 2 + ROW_REACH;
		// Never shorter than the row's own age, so a row right at the edge of the window does
		// not slip out of it between the screen being drawn and the click.
		long window = Math.max(windowMs(), System.currentTimeMillis() - run.oldest() + 60_000L);

		boolean opened = RollbackPreview.open(viewer,
				new RollbackPreview.Scope(viewer.level(), run.first().player(), middle, reach, window,
						List.of(), spots),
				result -> reopen(viewer, centre, windowMinutes, playerFilter),
				() -> reopen(viewer, centre, windowMinutes, playerFilter));
		if (!opened) {
			nothingLeftAt = run.first().id();
			render();
			fetch(true);
		}
	}

	/** {@code who} may be null, meaning everything in the area regardless of who did it. */
	private void previewRollback(String who) {
		RollbackPreview.open(viewer,
				new RollbackPreview.Scope(viewer.level(), who, centre, radius, windowMs(), List.of()),
				result -> reopen(viewer, centre, windowMinutes, playerFilter),
				() -> reopen(viewer, centre, windowMinutes, playerFilter));
	}

	/**
	 * Takes down any ghost preview when this screen actually goes away.
	 * <p>
	 * Not on a hop to the confirm screen, which is the one moment the preview most needs to
	 * still be there — the whole point is to look at it while deciding. Walking away with
	 * the window closed does clear it, because a preview left drawn is the tool lying about
	 * what is in the world.
	 */
	@Override
	protected void onClosed() {
		if (!isNavigating()) Mods.grief().preview().clear(viewer);
	}

	// --------------------------------------------------------------------- chrome

	private ItemStack header() {
		Icon icon = Icon.of(Items.TNT)
				.name("Grief Log", Theme.ACCENT)
				.field("Centre", "%d, %d, %d".formatted(centre.getX(), centre.getY(), centre.getZ()))
				.field("World", Mc.dimensionName(viewer.level()))
				.field("Radius", radius + " blocks")
				.field("Window", TimeFormat.duration(windowMs()))
				.field("Scope", playerFilter == null ? "everyone" : playerFilter);

		if (page != null) {
			icon.field("Matches", String.valueOf(page.total()));
		}
		icon.gap().lore("Newest first. Repeats of the same action are", Theme.MUTED)
				.lore("collapsed into one row with a count.", Theme.MUTED);

		if (playerFilter != null) {
			icon.gap().action("Click", "clear the filter and show everyone");
		}
		return icon.build();
	}
}
