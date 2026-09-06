package io.github.alphain24.staffcore.gui.menu;

import io.github.alphain24.staffcore.compat.Mc;
import io.github.alphain24.staffcore.config.StaffConfig;
import io.github.alphain24.staffcore.gui.Guis;
import io.github.alphain24.staffcore.gui.Icon;
import io.github.alphain24.staffcore.gui.PagedGui;
import io.github.alphain24.staffcore.gui.Sfx;
import io.github.alphain24.staffcore.gui.Theme;
import io.github.alphain24.staffcore.module.Mods;
import io.github.alphain24.staffcore.modules.grief.RollbackPoints;
import io.github.alphain24.staffcore.permission.Nodes;
import io.github.alphain24.staffcore.permission.Permissions;
import io.github.alphain24.staffcore.util.TimeFormat;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * Every rollback that can still be taken back.
 * <p>
 * Undo exists as a command too, but a command is the wrong shape for this. The moment
 * somebody needs it they do not know the id, they know "the one I just ran" or "the one
 * that ate the spawn build" — which is a list to look down, not an argument to remember.
 * <p>
 * Rows that have already been undone stay on the list rather than disappearing. "Did
 * somebody already put this back?" is the question staff ask second, and a list that
 * silently drops the answer sends them to check the world instead.
 */
public class RestorePointsMenu extends PagedGui<RollbackPoints.Point> {

	private static final int SLOT_HELP = 47;

	public static void open(ServerPlayer viewer) {
		Guis.navigate(viewer, Theme.title("Rollback History"),
				(id, inv, v) -> new RestorePointsMenu(id, inv, v));
	}

	static void reopen(ServerPlayer viewer) {
		Guis.silent(viewer, Theme.title("Rollback History"),
				(id, inv, v) -> new RestorePointsMenu(id, inv, v));
	}

	private RestorePointsMenu(int containerId, Inventory playerInventory, ServerPlayer viewer) {
		super(containerId, playerInventory, viewer);
		render();
	}

	@Override
	protected List<RollbackPoints.Point> entries() {
		return Mods.grief().points().recent(140);
	}

	@Override
	protected ItemStack header() {
		int days = StaffConfig.get().rollbackPointRetentionDays;
		return Icon.of(Items.CLOCK)
				.name("Rollback History", Theme.ACCENT)
				.field("Kept for", days <= 0 ? "undo is disabled" : days + " days")
				.gap()
				.lore("Every rollback records what it was about to", Theme.MUTED)
				.lore("overwrite, so it can be put back.", Theme.MUTED)
				.build();
	}

	@Override
	protected ItemStack emptyIcon() {
		int days = StaffConfig.get().rollbackPointRetentionDays;
		Icon icon = Icon.of(Items.STRUCTURE_VOID).name("No rollbacks on record", Theme.MUTED);

		if (days <= 0) {
			icon.gap().warn("rollbackPointRetentionDays is 0, so nothing")
					.warn("is being recorded and undo is unavailable.");
		} else {
			icon.lore("Rollbacks appear here for " + days + " days.", Theme.MUTED);
		}
		return icon.build();
	}

	@Override
	protected ItemStack icon(RollbackPoints.Point point) {
		Icon icon = Icon.of(point.isUndone() ? Mc.dye(net.minecraft.world.item.DyeColor.GRAY) : Items.TNT)
				.name("#" + point.id() + " — " + point.changes() + " change(s)",
						point.isUndone() ? Theme.MUTED : Theme.TEXT)
				.field("By", point.staff())
				.field("Scope", point.scope() == null ? "everyone" : point.scope())
				.field("Where", "%d, %d, %d".formatted(
						point.centre().getX(), point.centre().getY(), point.centre().getZ()))
				.field("Radius", point.radius() + " blocks")
				.field("Window", TimeFormat.duration(point.windowMs()))
				.field("When", TimeFormat.ago(point.createdAt()));

		icon.gap();
		if (point.isUndone()) {
			icon.lore("Already undone by " + point.undoneBy(), Theme.MUTED)
					.lore("on " + TimeFormat.ago(point.undoneAt()) + ".", Theme.MUTED)
					.gap()
					.action("Left-click", "go to where it happened");
		} else {
			icon.action("Left-click", "go to where it happened");
			if (Permissions.check(viewer, Nodes.ROLLBACK)) {
				icon.action("Shift-click", "undo this rollback");
			}
		}
		return icon.build();
	}

	@Override
	protected void onPick(RollbackPoints.Point point, Click click) {
		if (click.isShift()) {
			undo(point);
			return;
		}

		// Going there is the other thing staff want from this list, and it is what turns a
		// row of coordinates into a decision they can actually make.
		Mods.teleport().toPosition(viewer,
				point.centre().getX() + 0.5, point.centre().getY() + 1.0, point.centre().getZ() + 0.5);
		viewer.sendSystemMessage(Theme.info("Teleported to rollback #" + point.id() + "."));
		viewer.closeContainer();
	}

	private void undo(RollbackPoints.Point point) {
		if (!Permissions.check(viewer, Nodes.ROLLBACK)) {
			viewer.sendSystemMessage(Theme.bad("You do not have " + Nodes.ROLLBACK + "."));
			Sfx.deny(viewer);
			return;
		}
		if (point.isUndone()) {
			viewer.sendSystemMessage(Theme.warn("That one was already undone by " + point.undoneBy() + "."));
			Sfx.deny(viewer);
			return;
		}

		ConfirmMenu.open(viewer, "Undo rollback",
				Icon.of(Items.CLOCK)
						.name("Undo rollback #" + point.id(), Theme.WARN)
						.field("Originally by", point.staff())
						.field("Changes", String.valueOf(point.changes()))
						.field("When", TimeFormat.ago(point.createdAt()))
						.gap()
						.lore("Puts back exactly what that rollback overwrote,", Theme.MUTED)
						.lore("including anything that was in a container.", Theme.MUTED)
						.gap()
						.warn("Anything built there since is overwritten in turn.")
						.build(),
				() -> {
					var result = Mods.grief().points().undo(viewer.level(), point, Mc.name(viewer));

					if (result.didNothing()) {
						viewer.sendSystemMessage(Theme.warn(
								"Nothing was restored — the record may have been purged."));
						Sfx.error(viewer);
					} else {
						viewer.sendSystemMessage(Theme.good("Undid rollback #" + point.id()
								+ " — restored " + result.restored() + " block(s)."));
						if (result.skipped() > 0) {
							viewer.sendSystemMessage(Theme.warn("  " + result.skipped()
									+ " block(s) could not be put back — their type no longer exists."));
						}
						MinecraftServer server = Mc.server(viewer);
						if (server != null) {
							Mods.alerts().onStaffAction(server, "%s undid rollback #%d (%s)"
									.formatted(Mc.name(viewer), point.id(), point.describe()));
						}
						Sfx.bigSuccess(viewer);
					}
					reopen(viewer);
				},
				() -> reopen(viewer));
	}

	@Override
	protected void decorateFooter() {
		set(SLOT_HELP, Icon.of(Items.KNOWLEDGE_BOOK)
				.name("How undo works", Theme.ACCENT)
				.gap()
				.lore("Before a rollback writes anything, it records", Theme.TEXT)
				.lore("the blocks it is about to overwrite and what", Theme.TEXT)
				.lore("was inside any container it replaces.", Theme.TEXT)
				.gap()
				.lore("Undo replays that backwards. It puts back what", Theme.MUTED)
				.lore("was recorded — it does not try to merge with", Theme.MUTED)
				.lore("anything built there since.", Theme.MUTED)
				.gap()
				.lore("Also available as /staff rollback undo.", Theme.MUTED)
				.build());
	}

	@Override
	protected Runnable backTarget() {
		return () -> GriefMenu.open(viewer);
	}

	@Override
	protected String backLabel() {
		return "the grief log";
	}
}
