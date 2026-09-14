package io.github.alphain24.staffcore.gui.menu;

import io.github.alphain24.staffcore.compat.Mc;
import io.github.alphain24.staffcore.gui.Guis;
import io.github.alphain24.staffcore.gui.Icon;
import io.github.alphain24.staffcore.gui.PagedGui;
import io.github.alphain24.staffcore.gui.Sfx;
import io.github.alphain24.staffcore.gui.Theme;
import io.github.alphain24.staffcore.module.Mods;
import io.github.alphain24.staffcore.modules.security.ContrabandVault;
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
 * Everything confiscated, and whatever became of it.
 * <p>
 * The vault exists because destroying an item on suspicion makes every mistake permanent
 * and unprovable. Each row shows the real stack, who it came off, who took it and why, and
 * offers exactly two endings: give it back, or destroy it deliberately.
 * <p>
 * Rows are read a page at a time rather than in one capped block. The table grows for the
 * life of a server, and the old five-hundred-row ceiling meant the screen would tell you a
 * true total it could not actually show you.
 */
public class VaultMenu extends PagedGui<ContrabandVault.Entry> {

	private static final int SLOT_FILTER = 47;
	private static final int SLOT_DESTROY_ALL = 51;
	private static final int SLOT_DELETE_HISTORY = 52;

	/**
	 * Which rows are on screen. {@code null} is everything, which is what makes the
	 * returned-and-destroyed history the table has always kept reachable at all.
	 */
	private ContrabandVault.State filter;

	public static void open(ServerPlayer viewer) {
		Guis.navigate(viewer, Theme.title("Contraband Vault"),
				(id, inv, v) -> new VaultMenu(id, inv, v, ContrabandVault.State.HELD));
	}

	static void reopen(ServerPlayer viewer) {
		reopen(viewer, ContrabandVault.State.HELD);
	}

	private static void reopen(ServerPlayer viewer, ContrabandVault.State filter) {
		Guis.silent(viewer, Theme.title("Contraband Vault"),
				(id, inv, v) -> new VaultMenu(id, inv, v, filter));
	}

	private VaultMenu(int containerId, Inventory playerInventory, ServerPlayer viewer,
			ContrabandVault.State filter) {
		super(containerId, playerInventory, viewer);
		this.filter = filter;
		render();
	}

	// -------------------------------------------------------------------- paging

	@Override
	protected int totalEntries() {
		return Mods.security().vault().count(filter);
	}

	@Override
	protected List<ContrabandVault.Entry> window(int offset, int limit) {
		return Mods.security().vault().page(filter, offset, limit);
	}

	@Override
	protected List<ContrabandVault.Entry> entries() {
		return List.of();   // unused: this screen is windowed
	}

	// --------------------------------------------------------------------- chrome

	@Override
	protected ItemStack header() {
		ContrabandVault vault = Mods.security().vault();
		return Icon.of(Items.BUNDLE)
				.name("Contraband Vault", Theme.ACCENT)
				.field("Held", String.valueOf(vault.count(ContrabandVault.State.HELD)))
				.field("Awaiting return",
						String.valueOf(vault.count(ContrabandVault.State.PENDING_RETURN)))
				.field("Showing", label(filter))
				.gap()
				.lore("Items taken off players and kept rather than destroyed,", Theme.MUTED)
				.lore("so a wrong call costs a click instead of an apology.", Theme.MUTED)
				.build();
	}

	@Override
	protected ItemStack emptyIcon() {
		return Icon.of(Items.STRUCTURE_VOID)
				.name(filter == ContrabandVault.State.HELD ? "Nothing held" : "Nothing here", Theme.MUTED)
				.lore(filter == ContrabandVault.State.HELD
						? "Confiscated items will appear here."
						: "No rows in this state.", Theme.MUTED)
				.build();
	}

	/** Cycles the state filter, so every row the table keeps can actually be looked at. */
	@Override
	protected void decorateFooter() {
		button(SLOT_FILTER, Icon.of(Items.HOPPER)
				.name("Showing: " + label(filter), Theme.ACCENT)
				.gap()
				.lore("Held — taken and still here.", Theme.MUTED)
				.lore("Awaiting return — owner is offline.", Theme.MUTED)
				.lore("Returned and Destroyed are kept as record.", Theme.MUTED)
				.gap()
				.action("Click", "show " + label(next(filter)))
				.build(), click -> {
			filter = next(filter);
			resetPage();
			Sfx.page(viewer);
			render();
		});

		if (!Permissions.check(viewer, Nodes.VAULT_CLEAR)) return;
		ContrabandVault vault = Mods.security().vault();
		int held = vault.count(ContrabandVault.State.HELD);
		int history = vault.count(ContrabandVault.State.RETURNED)
				+ vault.count(ContrabandVault.State.DESTROYED);

		button(SLOT_DESTROY_ALL, Icon.of(Items.LAVA_BUCKET)
				.name("Destroy everything held", held > 0 ? Theme.BAD : Theme.MUTED)
				.field("Held", String.valueOf(held))
				.gap()
				.lore("Every item in the vault, gone for good.", Theme.MUTED)
				.lore("Returns waiting for a login are kept.", Theme.MUTED)
				.action("Click", "choose, then confirm")
				.build(), click -> destroyAll(held));

		button(SLOT_DELETE_HISTORY, Icon.of(Items.WRITABLE_BOOK)
				.name("Delete the history", history > 0 ? Theme.BAD : Theme.MUTED)
				.field("Records", String.valueOf(history))
				.gap()
				.lore("The record of every returned and destroyed item.", Theme.MUTED)
				.lore("Held items and waiting returns are kept.", Theme.MUTED)
				.action("Click", "choose, then confirm")
				.build(), click -> deleteHistory(history));
	}

	private void destroyAll(int held) {
		if (held == 0) {
			viewer.sendSystemMessage(Theme.info("Nothing is held in the vault."));
			Sfx.deny(viewer);
			return;
		}
		int waiting = Mods.security().vault().count(ContrabandVault.State.PENDING_RETURN);
		Icon summary = Icon.of(Items.LAVA_BUCKET)
				.name("Destroy all " + held + " held item(s)", Theme.BAD)
				.gap()
				.lore("None of them can be given back afterwards.");
		if (waiting > 0) {
			summary.lore(waiting + " waiting to go back to their owner are kept.", Theme.MUTED);
		}
		summary.lore("Each keeps a Destroyed record until the history is deleted.", Theme.MUTED)
				.gap()
				.warn("Cannot be undone.");

		ConfirmMenu.open(viewer, "Destroy all", summary.build(),
				() -> {
					if (!Permissions.check(viewer, Nodes.VAULT_CLEAR)) {
						Sfx.deny(viewer);
						reopen(viewer, filter);
						return;
					}
					int destroyed = Mods.security().vault().destroyAllHeld(Mc.name(viewer));
					viewer.sendSystemMessage(Theme.warn("Destroyed " + destroyed + " held item(s)."));
					record("destroyed every held item in the vault (" + destroyed + ")");
					Sfx.bigSuccess(viewer);
					reopen(viewer, filter);
				},
				() -> reopen(viewer, filter));
	}

	private void deleteHistory(int history) {
		if (history == 0) {
			viewer.sendSystemMessage(Theme.info("There is no returned or destroyed history to delete."));
			Sfx.deny(viewer);
			return;
		}
		ConfirmMenu.open(viewer, "Delete history",
				Icon.of(Items.WRITABLE_BOOK)
						.name("Delete " + history + " vault record(s)", Theme.BAD)
						.gap()
						.lore("Every returned and destroyed item's record goes:")
						.lore("what it was, who it came off, who took it, and why.")
						.lore("Held items and returns waiting for a login stay.", Theme.MUTED)
						.gap()
						.warn("Cannot be undone.")
						.build(),
				() -> {
					if (!Permissions.check(viewer, Nodes.VAULT_CLEAR)) {
						Sfx.deny(viewer);
						reopen(viewer, filter);
						return;
					}
					int deleted = Mods.security().vault().deleteHistory();
					viewer.sendSystemMessage(Theme.warn("Deleted " + deleted + " vault record(s)."));
					record("deleted the vault history (" + deleted + " record(s))");
					Sfx.bigSuccess(viewer);
					reopen(viewer, filter);
				},
				() -> reopen(viewer, filter));
	}

	/** A mass action goes into the command log as well as staff chat. */
	private void record(String what) {
		Mods.accountability().audit().record(viewer, Mc.name(viewer), "[panel] vault: " + what, null);
		audit(what);
	}

	private static ContrabandVault.State next(ContrabandVault.State state) {
		if (state == null) return ContrabandVault.State.HELD;
		return switch (state) {
			case HELD -> ContrabandVault.State.PENDING_RETURN;
			case PENDING_RETURN -> ContrabandVault.State.RETURNED;
			case RETURNED -> ContrabandVault.State.DESTROYED;
			case DESTROYED -> null;   // everything
		};
	}

	private static String label(ContrabandVault.State state) {
		if (state == null) return "Everything";
		return switch (state) {
			case HELD -> "Held";
			case PENDING_RETURN -> "Awaiting return";
			case RETURNED -> "Returned";
			case DESTROYED -> "Destroyed";
		};
	}

	// ----------------------------------------------------------------------- rows

	@Override
	protected ItemStack icon(ContrabandVault.Entry entry) {
		MinecraftServer server = Mc.server(viewer);
		ItemStack real = entry.stack(server);

		// The genuine stack is shown, so staff judge the thing itself — enchantments,
		// custom name, stack size — rather than a description of it. A row whose item no
		// longer decodes still lists under its recorded name instead of vanishing.
		Icon icon = real.isEmpty()
				? Icon.of(Items.BARRIER).name(entry.display() + " (unreadable)", Theme.BAD)
				: Icon.of(real).name(entry.display(), colorFor(entry));

		icon.field("From", entry.ownerName())
				.field("Taken by", entry.takenBy())
				.field("Count", String.valueOf(entry.count()))
				.field("When", TimeFormat.ago(entry.createdAt()));

		if (entry.reason() != null && !entry.reason().isBlank()) {
			icon.field("Reason", entry.reason());
		}
		if (entry.state() != ContrabandVault.State.HELD) {
			icon.field("Status", label(entry.state()),
					entry.state() == ContrabandVault.State.DESTROYED ? Theme.BAD : Theme.WARN);
		}
		if (entry.resolvedBy() != null) {
			icon.field("By", entry.resolvedBy());
		}

		icon.gap();
		switch (entry.state()) {
			case HELD -> {
				if (Permissions.check(viewer, Nodes.VAULT)) {
					icon.action("Left-click", "give it back to " + entry.ownerName());
				}
				if (Permissions.check(viewer, Nodes.VAULT_DESTROY)) {
					icon.action("Right-click", "destroy it permanently");
				}
			}
			case PENDING_RETURN -> {
				icon.lore("Queued — goes back when they next log in.", Theme.MUTED);
				if (Permissions.check(viewer, Nodes.VAULT)) {
					icon.action("Click", "cancel and keep it in the vault");
				}
			}
			default -> icon.lore("Kept as a record. Nothing left to do.", Theme.MUTED);
		}
		return icon.build();
	}

	private static int colorFor(ContrabandVault.Entry entry) {
		return switch (entry.state()) {
			case HELD -> Theme.TEXT;
			case PENDING_RETURN -> Theme.WARN;
			case RETURNED -> Theme.GOOD;
			case DESTROYED -> Theme.MUTED;
		};
	}

	@Override
	protected void onPick(ContrabandVault.Entry entry, Click click) {
		// Reading a confiscated item is the point of keeping it. Middle-click opens the
		// breakdown from any state, including rows already returned or destroyed — the
		// stored copy is what makes an old decision reviewable at all.
		if (click.isMiddle()) {
			ItemDetailsMenu.open(viewer, entry.stack(Mc.server(viewer)),
					"the vault — taken from " + entry.ownerName() + " by " + entry.takenBy(),
					() -> reopen(viewer));
			return;
		}

		switch (entry.state()) {
			case HELD -> {
				if (click.isRight()) destroy(entry);
				else release(entry);
			}
			case PENDING_RETURN -> cancelReturn(entry);
			default -> Sfx.deny(viewer);
		}
	}

	// -------------------------------------------------------------------- actions

	/**
	 * Hands an item back, whether or not its owner is online.
	 * <p>
	 * An offline owner used to be a refusal — staff were told to come back later, which
	 * meant remembering to. The item still cannot be pushed into a save file, but the
	 * decision no longer has to wait for the delivery: it is booked now and settled the
	 * moment they log in.
	 */
	private void release(ContrabandVault.Entry entry) {
		if (!Permissions.check(viewer, Nodes.VAULT)) {
			Sfx.deny(viewer);
			return;
		}

		MinecraftServer server = Mc.server(viewer);
		if (server == null) {
			Sfx.error(viewer);
			return;
		}
		ServerPlayer owner = server.getPlayerList().getPlayer(entry.ownerId());
		boolean online = owner != null;

		Icon summary = Icon.of(Items.CHEST)
				.name("Return " + entry.display(), Theme.GOOD)
				.field("To", entry.ownerName())
				.field("Taken by", entry.takenBy())
				.gap();

		if (online) {
			summary.lore("The item goes straight into their inventory.", Theme.MUTED);
		} else {
			summary.lore("They are offline — it is handed over the", Theme.MUTED)
					.lore("next time they log in.", Theme.MUTED);
		}

		ConfirmMenu.open(viewer, "Give back", summary.build(),
				() -> {
					boolean done = online
							? Mods.security().vault().release(entry, owner, Mc.name(viewer))
							: Mods.security().vault().queueRelease(server, entry, Mc.name(viewer));

					if (done && online) {
						viewer.sendSystemMessage(Theme.good(
								"Returned " + entry.display() + " to " + entry.ownerName() + "."));
						owner.sendSystemMessage(Theme.good(
								"Staff returned your " + entry.display() + "."));
						audit("returned " + entry.display() + " to " + entry.ownerName());
						Sfx.bigSuccess(viewer);
					} else if (done) {
						viewer.sendSystemMessage(Theme.good("Queued " + entry.display() + " for "
								+ entry.ownerName() + " — they get it on their next login."));
						audit("queued " + entry.display() + " for return to " + entry.ownerName());
						Sfx.bigSuccess(viewer);
					} else {
						viewer.sendSystemMessage(Theme.warn("That item is no longer held."));
						Sfx.error(viewer);
					}
					reopen(viewer, filter);
				},
				() -> reopen(viewer, filter));
	}

	private void cancelReturn(ContrabandVault.Entry entry) {
		if (!Permissions.check(viewer, Nodes.VAULT)) {
			Sfx.deny(viewer);
			return;
		}

		if (Mods.security().vault().cancelRelease(entry)) {
			viewer.sendSystemMessage(Theme.info(
					"Cancelled — " + entry.display() + " stays in the vault."));
			audit("cancelled the return of " + entry.display() + " to " + entry.ownerName());
			Sfx.toggleOff(viewer);
		} else {
			viewer.sendSystemMessage(Theme.warn("That return has already gone through."));
			Sfx.error(viewer);
		}
		reopen(viewer, filter);
	}

	private void destroy(ContrabandVault.Entry entry) {
		if (!Permissions.check(viewer, Nodes.VAULT_DESTROY)) {
			Sfx.deny(viewer);
			return;
		}

		ConfirmMenu.open(viewer, "Destroy",
				Icon.of(Items.LAVA_BUCKET)
						.name("Destroy " + entry.display(), Theme.BAD)
						.field("From", entry.ownerName())
						.field("Count", String.valueOf(entry.count()))
						.gap()
						.warn("This cannot be undone. The record stays.")
						.build(),
				() -> {
					if (Mods.security().vault().destroy(entry, Mc.name(viewer))) {
						viewer.sendSystemMessage(Theme.warn("Destroyed " + entry.display() + "."));
						audit("destroyed " + entry.display() + " taken from " + entry.ownerName());
						Sfx.bigSuccess(viewer);
					} else {
						viewer.sendSystemMessage(Theme.warn("That item is no longer held."));
						Sfx.error(viewer);
					}
					reopen(viewer, filter);
				},
				() -> reopen(viewer, filter));
	}

	private void audit(String what) {
		MinecraftServer server = Mc.server(viewer);
		if (server != null) {
			Mods.alerts().onStaffAction(server, Mc.name(viewer) + " " + what);
		}
	}

	@Override
	protected Runnable backTarget() {
		return () -> StaffSections.security(viewer);
	}

	@Override
	protected String backLabel() {
		return "Security";
	}
}
