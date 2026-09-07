package io.github.alphain24.staffcore.gui.menu;

import io.github.alphain24.staffcore.compat.Mc;
import io.github.alphain24.staffcore.config.StaffConfig;
import io.github.alphain24.staffcore.gui.Guis;
import io.github.alphain24.staffcore.gui.Icon;
import io.github.alphain24.staffcore.gui.PagedGui;
import io.github.alphain24.staffcore.gui.Sfx;
import io.github.alphain24.staffcore.gui.Theme;
import io.github.alphain24.staffcore.module.Mods;
import io.github.alphain24.staffcore.modules.grief.ContainerWatch;
import io.github.alphain24.staffcore.permission.Nodes;
import io.github.alphain24.staffcore.permission.Permissions;
import io.github.alphain24.staffcore.util.ItemCodec;
import io.github.alphain24.staffcore.util.TimeFormat;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * Who took what out of this exact container, and who put things in.
 * <p>
 * This is the screen the inspect stick opens on a chest, because "who broke this" is almost
 * never the question when a chest is involved — the chest is usually still standing and the
 * diamonds are not. Each row is a real stack with its own icon, so the answer reads at a
 * glance rather than as a wall of item ids.
 */
public class ContainerLogMenu extends PagedGui<ContainerWatch.Move> {

	private static final int SLOT_BLOCKS = 47;
	private static final int SLOT_UNDO_THEFT = 52;
	private static final int LIMIT = 200;

	private final BlockPos pos;
	private final String world;
	private int windowMinutes;

	public static void open(ServerPlayer viewer, BlockPos pos) {
		Guis.navigate(viewer, Theme.title("Container", "%d, %d, %d"
						.formatted(pos.getX(), pos.getY(), pos.getZ())),
				(id, inv, v) -> new ContainerLogMenu(id, inv, v, pos,
						StaffConfig.get().defaultRollbackMinutes));
	}

	private ContainerLogMenu(int containerId, Inventory playerInventory, ServerPlayer viewer,
			BlockPos pos, int windowMinutes) {
		super(containerId, playerInventory, viewer);
		this.pos = pos;
		this.world = Mc.dimensionId(viewer.level());
		this.windowMinutes = windowMinutes;
		render();
	}

	/**
	 * Once a second, and only when something changed.
	 * <p>
	 * This screen is watched live during an investigation &mdash; a second account is opening
	 * the chest while staff have the log open &mdash; and it used to be a photograph taken
	 * when it opened. A take is written when the thief closes the container, so the row
	 * appears within a second of them doing it.
	 */
	@Override
	protected int refreshEveryTicks() {
		return 20;
	}

	@Override
	protected List<ContainerWatch.Move> entries() {
		return Mods.grief().containers().at(viewer.level(), pos, windowMinutes * 60_000L, LIMIT);
	}

	@Override
	protected ItemStack icon(ContainerWatch.Move move) {
		MinecraftServer server = Mc.server(viewer);
		ItemStack moved = server == null ? ItemStack.EMPTY : ItemCodec.decode(server, move.item());
		boolean took = "TAKE".equals(move.action());

		// The row shows the actual item that moved, so the answer is legible at a glance.
		Icon icon = (moved.isEmpty() ? Icon.of(Items.BARRIER) : Icon.of(moved))
				.name(move.player(), took ? Theme.BAD : Theme.GOOD)
				.field("Action", took ? "Took" : "Put in", took ? Theme.BAD : Theme.GOOD)
				.field("Item", moved.isEmpty() ? "unknown" : moved.getHoverName().getString())
				.field("Amount", String.valueOf(move.count()))
				.field("When", TimeFormat.ago(move.at()))
				.field("Exact", TimeFormat.stamp(move.at()))
				.gap()
				.action("Left-click", "see everything this player did here");

		if (took && Permissions.check(viewer, Nodes.ROLLBACK)) {
			// It undoes everything this player took from this container in the window, not
			// only the clicked row — a theft is a visit, and putting half of one back is not
			// an outcome anybody wants.
			icon.action("Shift-click", "put back everything they took from here");
		}

		icon.count(Math.max(1, Math.min(64, move.count())));
		if (took) icon.glow();
		return icon.build();
	}

	@Override
	protected void onPick(ContainerWatch.Move move, Click click) {
		if (!Permissions.check(viewer, Nodes.ROLLBACK)) {
			Sfx.deny(viewer);
			return;
		}
		if (click.isShift() && "TAKE".equals(move.action())) {
			undoTheft(move.player());
			return;
		}
		GriefMenu.openFor(viewer, move.player());
	}

	/**
	 * Puts back what was taken from this container, and debits whoever took it.
	 * <p>
	 * Scoped to this one chest rather than a radius, because "somebody emptied my chest" is
	 * a question about a container, and answering it should not mean reverting every block
	 * change around it as well.
	 *
	 * @param thief one player, or null for everyone who took from here
	 */
	private void undoTheft(String thief) {
		var result = Mods.grief().containers().undoTheftAt(
				viewer.level(), pos, windowMinutes * 60_000L, thief);

		if (result.didNothing()) {
			viewer.sendSystemMessage(Theme.warn("Nothing left to put back here."));
			Sfx.deny(viewer);
			render();
			return;
		}

		viewer.sendSystemMessage(Theme.good("Put back " + result.restored() + " stack(s) into this container."));
		if (result.debited() > 0) {
			viewer.sendSystemMessage(Theme.info("Took " + result.debited() + " item(s) back off them."));
		}
		if (result.queued() > 0) {
			viewer.sendSystemMessage(Theme.info(result.queued()
					+ " item(s) still owed — collected the next time they log in with some."));
		}
		if (result.deferred() > 0) {
			viewer.sendSystemMessage(Theme.warn(result.deferred()
					+ " stack(s) would not fit — clear space and try again."));
		}

		MinecraftServer server = Mc.server(viewer);
		if (server != null) {
			Mods.alerts().onStaffAction(server, "%s put %d stack(s) back into a container at %d, %d, %d"
					.formatted(Mc.name(viewer), result.restored(), pos.getX(), pos.getY(), pos.getZ()));
		}
		Sfx.bigSuccess(viewer);
		render();
	}

	@Override
	protected ItemStack header() {
		List<ContainerWatch.Move> all = entries();
		long taken = all.stream().filter(m -> "TAKE".equals(m.action())).count();

		return Icon.of(Items.CHEST)
				.name("Container history", Theme.ACCENT)
				.field("At", "%d, %d, %d".formatted(pos.getX(), pos.getY(), pos.getZ()))
				.field("World", Mc.dimensionName(viewer.level()))
				.field("Window", TimeFormat.duration(windowMinutes * 60_000L))
				.gap()
				.field("Taken out", String.valueOf(taken), taken > 0 ? Theme.BAD : Theme.MUTED)
				.field("Put in", String.valueOf(all.size() - taken))
				.gap()
				.lore("Newest first.")
				.build();
	}

	@Override
	protected ItemStack emptyIcon() {
		return Icon.of(Mc.pane(DyeColor.LIME))
				.name("Nothing moved", Theme.GOOD)
				.lore("Nobody has taken from or added to this container")
				.lore("within the last " + TimeFormat.duration(windowMinutes * 60_000L) + ".")
				.build();
	}

	@Override
	protected Runnable backTarget() {
		return () -> StaffPanelMenu.reopen(viewer);
	}

	@Override
	protected void decorateFooter() {
		button(SLOT_BLOCKS, Icon.of(Items.IRON_PICKAXE)
				.name("Block history instead", Theme.TEXT)
				.lore("Who broke, placed and opened things around here.")
				.build(), click -> GriefMenu.openAt(viewer, pos));

		if (Permissions.check(viewer, Nodes.ROLLBACK)) {
			long thefts = entries().stream().filter(m -> "TAKE".equals(m.action())).count();

			button(SLOT_UNDO_THEFT, Icon.of(Items.HOPPER)
					.name("Put everything back", thefts > 0 ? Theme.ACCENT : Theme.MUTED)
					.field("Stacks taken", String.valueOf(thefts),
							thefts > 0 ? Theme.BAD : Theme.MUTED)
					.gap()
					.lore("Restores everything taken out of this container", Theme.MUTED)
					.lore("in the window above, and debits whoever took it.", Theme.MUTED)
					.gap()
					.lore("Only removals are undone — items somebody added", Theme.MUTED)
					.lore("are left alone.", Theme.MUTED)
					.action("Click", thefts > 0 ? "put it all back" : "nothing was taken")
					.build(), click -> {
				if (thefts == 0) {
					Sfx.deny(viewer);
					return;
				}
				undoTheft(null);
			});
		}

		button(46, Icon.of(Items.CLOCK)
				.name("Time window", Theme.TEXT)
				.field("Currently", TimeFormat.duration(windowMinutes * 60_000L))
				.gap()
				.action("Left-click", "double it")
				.action("Right-click", "halve it")
				.build(), click -> {
			windowMinutes = click.isRight()
					? Math.max(5, windowMinutes / 2)
					: Math.min(7 * 24 * 60, windowMinutes * 2);
			resetPage();
			Sfx.page(viewer);
			render();
		});
	}
}
