package io.github.alphain24.staffcore.gui.menu;

import net.minecraft.server.players.NameAndId;
import io.github.alphain24.staffcore.compat.Mc;
import io.github.alphain24.staffcore.gui.Gui;
import io.github.alphain24.staffcore.gui.Guis;
import io.github.alphain24.staffcore.gui.Icon;
import io.github.alphain24.staffcore.gui.PagedGui;
import io.github.alphain24.staffcore.gui.Sfx;
import io.github.alphain24.staffcore.gui.Theme;
import io.github.alphain24.staffcore.module.Mods;
import io.github.alphain24.staffcore.modules.inventory.InventoryModule;
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
 * Frozen copies of a player's inventory, newest first.
 * <p>
 * Snapshots settle the arguments that live views cannot: what someone had ten minutes ago,
 * before they emptied a shulker into lava. They are in-memory and capped, so treat them as
 * evidence for the length of an incident rather than as an archive.
 */
public class SnapshotsMenu extends PagedGui<InventoryModule.Snapshot> {

	private static final int SLOT_CLEAR = 47;

	private final NameAndId target;

	public static void open(ServerPlayer viewer, NameAndId target) {
		Guis.navigate(viewer, Theme.title("Snapshots", target.name()),
				(id, inv, v) -> new SnapshotsMenu(id, inv, v, target));
	}

	static void reopen(ServerPlayer viewer, NameAndId target) {
		Guis.goBack(viewer, Theme.title("Snapshots", target.name()),
				(id, inv, v) -> new SnapshotsMenu(id, inv, v, target));
	}

	private SnapshotsMenu(int containerId, Inventory playerInventory, ServerPlayer viewer, NameAndId target) {
		super(containerId, playerInventory, viewer);
		this.target = target;
		render();
	}

	@Override
	protected List<InventoryModule.Snapshot> entries() {
		return Mods.inventory().snapshotsFor(target.id());
	}

	// Paged in the query rather than in memory: maxSnapshotsPerPlayer can be set to 0, which
	// means "keep every one", and that turns an unbounded table into an unbounded read.
	@Override
	protected int totalEntries() {
		return Mods.inventory().snapshotCount(target.id());
	}

	@Override
	protected List<InventoryModule.Snapshot> window(int offset, int limit) {
		return Mods.inventory().snapshotPage(target.id(), offset, limit);
	}

	@Override
	protected ItemStack icon(InventoryModule.Snapshot snap) {
		// Routine captures are dimmer, because the list is now a mix of "somebody decided
		// this mattered" and "this happens on every logout" — and reading a wall of
		// identical rows to find the one that was deliberate is the wrong kind of work.
		Icon icon = Icon.of(Items.ENDER_CHEST)
				.name(snap.label(), snap.isRoutine() ? Theme.MUTED : Theme.TEXT)
				.field("Taken", TimeFormat.ago(snap.takenAt()))
				.field("Exact", TimeFormat.stamp(snap.takenAt()))
				.field("By", snap.takenBy())
				.field("Stacks", String.valueOf(snap.itemCount()))
				.field("Kept", snap.isRoutine() ? "routine — discarded first" : "evidence",
						snap.isRoutine() ? Theme.MUTED : Theme.ACCENT)
				.gap()
				.action("Left-click", "look inside");

		if (Permissions.check(viewer, Nodes.INVSEE_EDIT)) {
			icon.action("Shift-click", "write it back onto them");
		}
		if (Permissions.check(viewer, Nodes.SNAPSHOT_REMOVE)) {
			icon.action("Right-click", "delete this snapshot");
		}
		return icon.build();
	}

	@Override
	protected void onPick(InventoryModule.Snapshot snap, Click click) {
		if (click.isRight()) {
			deleteSnapshot(snap);
			return;
		}
		if (!click.isShift()) {
			SnapshotViewMenu.open(viewer, target, snap);
			return;
		}

		if (!Permissions.check(viewer, Nodes.INVSEE_EDIT)) {
			viewer.sendSystemMessage(Theme.bad("You do not have " + Nodes.INVSEE_EDIT + "."));
			Sfx.deny(viewer);
			return;
		}

		MinecraftServer server = Mc.server(viewer);
		ServerPlayer live = server == null ? null : server.getPlayerList().getPlayer(target.id());
		if (live == null) {
			viewer.sendSystemMessage(Theme.warn(target.name() + " must be online to restore onto."));
			Sfx.deny(viewer);
			return;
		}

		ConfirmMenu.open(viewer, "Restore inventory",
				Icon.of(Items.ENDER_CHEST)
						.name("Overwrite " + target.name() + "'s inventory", Theme.BAD)
						.field("Snapshot", snap.label())
						.field("Taken", TimeFormat.ago(snap.takenAt()))
						.field("Stacks", String.valueOf(snap.itemCount()))
						.gap()
						.warn("Everything they are carrying right now is destroyed.")
						.build(),
				() -> {
					// Capture what they have first, so an accidental restore is itself undoable.
					Mods.inventory().capture(live, "Before restore", Mc.name(viewer));
					int cleared = Mods.inventory().restore(live, snap);
					if (server != null) {
						Mods.alerts().onStaffAction(server, Mc.name(viewer)
								+ " restored " + target.name() + "'s inventory from a snapshot");
					}
					viewer.sendSystemMessage(Theme.good("Restored " + target.name() + "'s inventory."));
					if (cleared > 0) {
						// Said out loud, because removing somebody's dropped items is a real
						// action and staff should not discover it happened by accident.
						viewer.sendSystemMessage(Theme.info("Cleared " + cleared
								+ " matching item(s) still lying where the snapshot was taken,"
								+ " so nothing was duplicated."));
					}
					Sfx.bigSuccess(viewer);
					reopen(viewer, target);
				},
				() -> reopen(viewer, target));
	}

	private void deleteSnapshot(InventoryModule.Snapshot snap) {
		if (!Permissions.check(viewer, Nodes.SNAPSHOT_REMOVE)) {
			viewer.sendSystemMessage(Theme.bad("You do not have " + Nodes.SNAPSHOT_REMOVE + "."));
			Sfx.deny(viewer);
			return;
		}

		ConfirmMenu.open(viewer, "Delete snapshot",
				Icon.of(Items.ENDER_CHEST)
						.name("Delete this snapshot", Theme.BAD)
						.field("Snapshot", snap.label())
						.field("Taken", TimeFormat.ago(snap.takenAt()))
						.field("Stacks", String.valueOf(snap.itemCount()))
						.gap()
						.warn("Snapshots are evidence. Deleting one cannot be undone.")
						.build(),
				() -> {
					if (Mods.inventory().removeSnapshot(target.id(), snap)) {
						viewer.sendSystemMessage(Theme.info("Snapshot deleted."));
						Sfx.success(viewer);
					} else {
						viewer.sendSystemMessage(Theme.warn("That snapshot was already gone."));
						Sfx.deny(viewer);
					}
					reopen(viewer, target);
				},
				() -> reopen(viewer, target));
	}

	@Override
	protected void decorateFooter() {
		if (!Permissions.check(viewer, Nodes.SNAPSHOT_REMOVE)) return;
		int count = entries().size();
		if (count == 0) return;

		button(SLOT_CLEAR, Icon.of(Items.LAVA_BUCKET)
				.name("Delete all snapshots", Theme.BAD)
				.field("Stored", String.valueOf(count))
				.gap()
				.warn("Clears every snapshot held for " + target.name() + ".")
				.build(), click -> ConfirmMenu.open(viewer, "Delete all",
				Icon.head(target)
						.name("Delete all " + count + " snapshot(s)", Theme.BAD)
						.lore("For " + target.name() + ".")
						.gap()
						.warn("This cannot be undone.")
						.build(),
				() -> {
					int gone = Mods.inventory().clearSnapshots(target.id());
					viewer.sendSystemMessage(Theme.warn("Deleted " + gone + " snapshot(s)."));
					Sfx.bigSuccess(viewer);
					reopen(viewer, target);
				},
				() -> reopen(viewer, target)));
	}

	@Override
	protected ItemStack header() {
		return Icon.head(target)
				.name(target.name() + "'s snapshots", Theme.ACCENT)
				.field("Stored", String.valueOf(entries().size()))
				.gap()
				.lore("Kept in memory. A restart clears them.")
				.build();
	}

	@Override
	protected ItemStack emptyIcon() {
		return Icon.of(Items.ENDER_CHEST)
				.name("No snapshots", Theme.MUTED)
				.lore("Take one from their inventory view.")
				.build();
	}

	@Override
	protected Runnable backTarget() {
		return () -> PlayerActionsMenu.reopen(viewer, target);
	}

	@Override
	protected String backLabel() {
		return target.name() + "'s file";
	}

	// ------------------------------------------------------------ the view itself

	/** A read-only chest showing one snapshot's contents. */
	private static final class SnapshotViewMenu extends Gui {

		private final NameAndId target;
		private final InventoryModule.Snapshot snapshot;

		static void open(ServerPlayer viewer, NameAndId target, InventoryModule.Snapshot snap) {
			Sfx.invsee(viewer);
			Guis.silent(viewer, Theme.title("Snapshot", target.name(), snap.label()),
					(id, inv, v) -> new SnapshotViewMenu(id, inv, v, target, snap));
		}

		private SnapshotViewMenu(int containerId, Inventory playerInventory, ServerPlayer viewer,
				NameAndId target, InventoryModule.Snapshot snapshot) {
			super(containerId, playerInventory, viewer, 6);
			this.target = target;
			this.snapshot = snapshot;
			render();
		}

		@Override
		protected void build() {
			ItemStack[] contents = Mods.inventory().contentsOf(snapshot);

			// Same geometry as the live view, so the two are directly comparable.
			for (int i = 0; i < 27 && 9 + i < contents.length; i++) {
				set(i, contents[9 + i].copy());
			}
			for (int i = 0; i < 9 && i < contents.length; i++) {
				set(27 + i, contents[i].copy());
			}
			for (int i = 0; i < 5 && 36 + i < contents.length; i++) {
				set(36 + i, contents[36 + i].copy());
			}

			set(44, Icon.head(target)
					.name(snapshot.label(), Theme.ACCENT)
					.field("Taken", TimeFormat.ago(snapshot.takenAt()))
					.field("By", snapshot.takenBy())
					.field("Stacks", String.valueOf(snapshot.itemCount()))
					.gap()
					.lore("Read-only. This is a copy.")
					.build());

			backButton(45, "the snapshot list", () -> reopen(viewer, target));
			button(53, Theme.closeButton(), click -> viewer.closeContainer());

			for (int i = 41; i < size; i++) {
				if (backing.getItem(i).isEmpty()) set(i, Theme.filler());
			}
		}
	}
}
