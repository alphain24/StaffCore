package io.github.alphain24.staffcore.gui.menu;

import io.github.alphain24.staffcore.compat.Mc;
import io.github.alphain24.staffcore.gui.Gui;
import io.github.alphain24.staffcore.gui.Guis;
import io.github.alphain24.staffcore.gui.Icon;
import io.github.alphain24.staffcore.gui.Sfx;
import io.github.alphain24.staffcore.gui.Theme;
import io.github.alphain24.staffcore.module.Mods;
import io.github.alphain24.staffcore.modules.grief.GriefModule;
import io.github.alphain24.staffcore.permission.Nodes;
import io.github.alphain24.staffcore.permission.Permissions;
import io.github.alphain24.staffcore.util.TimeFormat;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;

import java.util.List;

/**
 * The full history of one block, at one coordinate.
 * <p>
 * This is the inspector's answer to "what happened <em>here</em>" — every break, place,
 * open, take and put at exactly this position, newest first, in one timeline. The old
 * behaviour opened an area log centred on the block, which answers a different question and
 * buries this one under everything that happened to the wall next to it.
 */
public class BlockHistoryMenu extends Gui {

	private static final int LIMIT = 28;
	private static final int[] CONTENT = {
			10, 11, 12, 13, 14, 15, 16,
			19, 20, 21, 22, 23, 24, 25,
			28, 29, 30, 31, 32, 33, 34,
			37, 38, 39, 40, 41, 42, 43
	};

	private static final int SLOT_HEADER = 4;
	private static final int SLOT_AREA = 48;
	private static final int SLOT_TELEPORT = 50;
	private static final int SLOT_BACK = 45;
	private static final int SLOT_CLOSE = 53;

	private final BlockPos pos;
	private final String blockName;

	private List<GriefModule.HistoryRow> rows = List.of();
	private boolean loading = true;

	public static void open(ServerPlayer viewer, BlockPos pos) {
		Guis.navigate(viewer, Theme.title("Block History"),
				(id, inv, v) -> new BlockHistoryMenu(id, inv, v, pos));
	}

	private BlockHistoryMenu(int containerId, Inventory playerInventory, ServerPlayer viewer, BlockPos pos) {
		super(containerId, playerInventory, viewer, 6);
		this.pos = pos.immutable();

		Block block = viewer.level().getBlockState(this.pos).getBlock();
		this.blockName = block.getName().getString();

		render();
		fetch();
	}

	/**
	 * Twice every three seconds, and only repainting when the history has actually changed.
	 * <p>
	 * This is the screen staff have open <em>while</em> an investigation is happening: they
	 * click a chest, and somebody else then opens it and takes something. It used to be a
	 * photograph taken when the block was clicked, which made a live theft look like it had
	 * never happened.
	 * <p>
	 * Slower than the inventory views because each pass is a database read rather than a
	 * repaint of what is already in memory. A container's takes are written when the thief
	 * closes it, so a second or two is the whole delay either way.
	 */
	@Override
	protected int refreshEveryTicks() {
		return 30;
	}

	@Override
	protected void onAutoRefresh() {
		if (!loading) fetch(true);
	}

	/**
	 * Off-thread read; the callback repaints once it lands, and only if something moved.
	 * <p>
	 * The rows are clickable, so repainting an unchanged list on a timer would reshuffle
	 * nothing and risk a misclick for no gain. Comparing first costs one list equality check
	 * against a read we had to do anyway.
	 */
	private void fetch() {
		fetch(false);
	}

	/**
	 * @param quiet true for the automatic refresh — see {@code GriefMenu#fetch(boolean)}. A
	 *              background read must not put the spinner up over rows somebody is reading.
	 */
	private void fetch(boolean quiet) {
		MinecraftServer server = Mc.server(viewer);
		if (server == null) return;

		boolean first = !loaded;
		if (!quiet) loading = true;

		Mods.grief().historyAtAsync(server, viewer.level(), pos, LIMIT, result -> {
			boolean changed = first || !result.equals(rows);
			boolean wasSpinning = loading;
			rows = result;
			loading = false;
			loaded = true;
			if (viewer.containerMenu == this && (changed || wasSpinning)) render();
		});
	}

	/** Whether the first read has landed, so the initial paint always happens. */
	private boolean loaded;

	// ---------------------------------------------------------------------- render

	@Override
	protected void build() {
		header();

		if (loading) {
			set(CONTENT[10], Icon.of(Items.CLOCK)
					.name("Reading the log…", Theme.MUTED)
					.build());
		} else if (rows.isEmpty()) {
			set(CONTENT[10], Icon.of(Items.STRUCTURE_VOID)
					.name("Nothing recorded here", Theme.MUTED)
					.gap()
					.lore("Either nobody has touched this block, or it", Theme.MUTED)
					.lore("happened before logging started.", Theme.MUTED)
					.build());
		} else {
			boolean mayRollBack = Permissions.check(viewer, Nodes.ROLLBACK);
			for (int i = 0; i < rows.size() && i < CONTENT.length; i++) {
				GriefModule.HistoryRow row = rows.get(i);
				if (mayRollBack && canRollBack(row)) {
					button(CONTENT[i], rowIcon(row), click -> {
						if (click.isShift()) rollBack(row);
						else Sfx.deny(viewer);
					});
				} else {
					set(CONTENT[i], rowIcon(row));
				}
			}
		}

		controls();
		fillEmpty(Theme.filler());
	}

	private void header() {
		Block block = viewer.level().getBlockState(pos).getBlock();
		set(SLOT_HEADER, Icon.of(new ItemStack(block))
				.name(blockName, Theme.ACCENT)
				.field("At", pos.getX() + ", " + pos.getY() + ", " + pos.getZ())
				.field("World", Mc.dimensionId(viewer.level()))
				.field("Events", loading ? "…" : String.valueOf(rows.size()))
				.gap()
				.lore("Everything that happened at this exact block.", Theme.MUTED)
				.build());
	}

	/**
	 * One event. Block changes are shown as the block, item movements as the item, so the
	 * timeline reads as pictures before it reads as words.
	 */
	private ItemStack rowIcon(GriefModule.HistoryRow row) {
		Icon icon = Icon.of(subjectStack(row));

		String verb = switch (row.action()) {
			case "BREAK" -> "broke";
			case "PLACE" -> "placed";
			case "OPEN" -> "opened";
			case "TAKE" -> "took";
			case "PUT" -> "put in";
			case "IGNITE" -> "lit";
			default -> row.action().toLowerCase(java.util.Locale.ROOT);
		};

		int colour = switch (row.action()) {
			case "BREAK", "TAKE" -> Theme.BAD;
			case "PLACE", "PUT" -> Theme.GOOD;
			default -> Theme.TEXT;
		};

		String what = row.isItemMove() && row.count() > 0
				? row.count() + "× " + shortName(row.subject())
				: shortName(row.subject());

		icon.name(row.player() + " " + verb + " " + what, colour)
				.field("When", TimeFormat.ago(row.at()))
				.field("Exact", TimeFormat.stamp(row.at()))
				.field("Player", row.player());

		if (row.rolledBack()) {
			icon.gap().lore("Already rolled back.", Theme.MUTED);
		} else if (canRollBack(row) && Permissions.check(viewer, Nodes.ROLLBACK)) {
			icon.gap().action("Shift-click", "roll back what " + row.player() + " did here");
		}
		if (row.equals(nothingLeft)) {
			icon.gap().warn("Nothing left to roll back here.");
		}
		return icon.build();
	}

	/** A block change or an item moved, that has not been undone yet. */
	private static boolean canRollBack(GriefModule.HistoryRow row) {
		return !row.rolledBack() && switch (row.action()) {
			case "BREAK", "PLACE", "TAKE", "PUT" -> true;
			default -> false;
		};
	}

	/** The row last shift-clicked with nothing left to roll back. */
	private GriefModule.HistoryRow nothingLeft;

	/**
	 * Everything that player did at this one block, through the same preview and confirmation as
	 * the grief log. Reaches back at least as far as the rollback default, so the items somebody
	 * took out of a chest before breaking it go back with the chest.
	 */
	private void rollBack(GriefModule.HistoryRow row) {
		long window = Math.max(
				io.github.alphain24.staffcore.config.StaffConfig.get().defaultRollbackMinutes * 60_000L,
				System.currentTimeMillis() - row.at() + 60_000L);

		boolean opened = RollbackPreview.open(viewer,
				new RollbackPreview.Scope(viewer.level(), row.player(), pos, GriefMenu.ROW_REACH,
						window, List.of(), java.util.Set.of(pos)),
				result -> open(viewer, pos),
				() -> open(viewer, pos));
		if (!opened) {
			nothingLeft = row;
			render();
			fetch(true);
		}
	}

	private ItemStack subjectStack(GriefModule.HistoryRow row) {
		Item item = Mc.itemFromId(row.subject(), null);
		if (item != null) return new ItemStack(item);

		// Block ids that have no item form — a nether portal, say — still need a face.
		return new ItemStack(row.isItemMove() ? Items.PAPER : Items.STONE);
	}

	private static String shortName(String id) {
		int colon = id.indexOf(':');
		String bare = colon < 0 ? id : id.substring(colon + 1);
		return bare.replace('_', ' ');
	}

	private void controls() {
		if (Permissions.check(viewer, Nodes.ROLLBACK)) {
			button(SLOT_AREA, Icon.of(Items.TNT)
					.name("Area around this block", Theme.TEXT)
					.gap()
					.lore("Widen out to everything nearby, with rollback.", Theme.MUTED)
					.action("Click", "open the grief log here")
					.build(), click -> GriefMenu.openAt(viewer, pos));
		}

		if (Permissions.check(viewer, Nodes.TP)) {
			button(SLOT_TELEPORT, Icon.of(Items.ENDER_PEARL)
					.name("Teleport here", Theme.TEXT)
					.field("To", pos.getX() + ", " + pos.getY() + ", " + pos.getZ())
					.gap()
					.action("Click", "go and look at it")
					.build(), click -> {
						viewer.closeContainer();
						Mods.teleport().toPosition(viewer,
								pos.getX() + 0.5D, pos.getY() + 1.0D, pos.getZ() + 0.5D);
						Sfx.success(viewer);
					});
		}

		backButton(SLOT_BACK, "the staff panel", () -> StaffPanelMenu.reopen(viewer));

		button(SLOT_CLOSE, Theme.closeButton(), click -> viewer.closeContainer());
	}
}
