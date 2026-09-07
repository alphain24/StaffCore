package io.github.alphain24.staffcore.gui.menu;

import io.github.alphain24.staffcore.compat.Mc;
import io.github.alphain24.staffcore.gui.Gui;
import io.github.alphain24.staffcore.gui.Guis;
import io.github.alphain24.staffcore.gui.Icon;
import io.github.alphain24.staffcore.gui.Sfx;
import io.github.alphain24.staffcore.gui.Theme;
import io.github.alphain24.staffcore.gui.container.InventorySource;
import io.github.alphain24.staffcore.gui.container.InventoryViewContainer;
import io.github.alphain24.staffcore.module.Mods;
import io.github.alphain24.staffcore.permission.Nodes;
import io.github.alphain24.staffcore.permission.Permissions;
import io.github.alphain24.staffcore.util.OfflineInventory;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * A window into what somebody is carrying — online or not.
 * <p>
 * An online target is shown <em>live</em>: the slots are their real inventory, so what you
 * see is what they hold at that instant. An offline target is decoded from their save file
 * and is always read-only, because writing NBT back under a player who might reconnect
 * mid-edit is a corruption risk no amount of care makes safe.
 * <p>
 * Editing needs {@link Nodes#INVSEE_EDIT}, and the difference is visible before you touch
 * anything: the title says so, the header says so, and the frame turns red. Opening in edit
 * mode is announced to staff chat — reaching into another player's inventory is the most
 * abusable thing in the mod, and the fix for that is daylight, not a permission node alone.
 */
public class InvseeMenu extends Gui {

	private static final int FIRST_BUTTON = 41;

	private static final int SLOT_HEAD = 44;
	private static final int SLOT_BACK = 45;
	private static final int SLOT_ENDER = 46;
	private static final int SLOT_SNAPSHOT = 47;
	private static final int SLOT_FREEZE = 48;
	private static final int SLOT_SECURITY = 49;
	private static final int SLOT_VAULT = 50;
	private static final int SLOT_PUNISH = 51;
	private static final int SLOT_CONFISCATE = 52;
	private static final int SLOT_CLOSE = 53;

	private final NameAndId target;
	private final InventoryViewContainer view;
	private final boolean offline;

	// ------------------------------------------------------------------- opening

	/** Convenience for the common case where you already hold the online player. */
	public static void open(ServerPlayer viewer, ServerPlayer target) {
		if (target != null) open(viewer, target.nameAndId());
	}

	public static void open(ServerPlayer viewer, NameAndId target) {
		MinecraftServer server = Mc.server(viewer);
		if (server == null) return;

		ServerPlayer live = server.getPlayerList().getPlayer(target.id());
		boolean canEdit = Permissions.check(viewer, Nodes.INVSEE_EDIT);

		InventorySource source;
		io.github.alphain24.staffcore.inventory.InventoryGateway.EditSession session = null;
		if (live != null) {
			source = InventorySource.live(live, canEdit);
			if (canEdit) {
				Mods.alerts().onStaffAction(server,
						Mc.name(viewer) + " opened " + target.name() + "'s inventory in edit mode");

				// Announcing the opening says it happened; a snapshot says what was there.
				// That protects the staff member as much as the player — "it was already
				// missing" needs evidence pointing one way or the other. The gateway takes
				// it and holds the before-picture until the screen closes, so what gets
				// recorded is the net change rather than every click that carried it.
				session = io.github.alphain24.staffcore.inventory.InventoryGateway.beginEdit(
						live, Mc.name(viewer), "staff edit through invsee");
			}
		} else {
			ItemStack[] stored = OfflineInventory.load(server, target);
			if (stored == null) {
				viewer.sendSystemMessage(Theme.bad(
						"No saved inventory for " + target.name() + " — they have never joined."));
				Sfx.deny(viewer);
				return;
			}
			source = InventorySource.stored(stored);
		}

		boolean isOffline = live == null;
		io.github.alphain24.staffcore.inventory.InventoryGateway.EditSession opened = session;
		Sfx.invsee(viewer);
		Guis.silent(viewer,
				Theme.title(isOffline ? "Offline" : (canEdit ? "Editing" : "Viewing"), target.name()),
				(id, inv, v) -> new InvseeMenu(id, inv, v, target,
						new InventoryViewContainer(source), isOffline, opened));
	}

	private InvseeMenu(int containerId, Inventory playerInventory, ServerPlayer viewer,
			NameAndId target, InventoryViewContainer view, boolean offline,
			io.github.alphain24.staffcore.inventory.InventoryGateway.EditSession session) {
		super(containerId, playerInventory, viewer, 6, view);
		this.target = target;
		this.view = view;
		this.offline = offline;
		this.session = session;
		render();
	}

	/**
	 * The open edit session, or null when this is a read-only or offline view.
	 * <p>
	 * Held rather than recorded per click: vanilla moves stacks one click at a time, and a
	 * row for each would bury the change under the mechanics of making it.
	 */
	private final io.github.alphain24.staffcore.inventory.InventoryGateway.EditSession session;

	/**
	 * Records what the edit actually changed.
	 * <p>
	 * Runs whether the screen was closed deliberately, by walking away, or by disconnecting —
	 * {@code Gui.removed} guarantees it — so there is no way to make a change and avoid the
	 * record by leaving abruptly.
	 */
	@Override
	protected void onClosed() {
		io.github.alphain24.staffcore.inventory.InventoryGateway.endEdit(session);
	}

	// ------------------------------------------------------- live-slot protection

	/** Only the button strip is ours to repaint; the rest is the player's real inventory. */
	@Override
	protected boolean isScratchSlot(int i) {
		return !view.isMapped(i);
	}

	/** Mapped slots go through vanilla click handling, but only when editing is allowed. */
	@Override
	protected boolean isSlotInteractive(int slotId) {
		return view.isEditable() && view.isMapped(slotId);
	}

	/** In edit mode a picked-up stack needs somewhere to go, so your own bags open up. */
	@Override
	protected boolean allowsOwnInventory() {
		return view.isEditable();
	}

	private ServerPlayer live() {
		MinecraftServer server = Mc.server(viewer);
		return server == null ? null : server.getPlayerList().getPlayer(target.id());
	}

	// -------------------------------------------------------------------- layout

	@Override
	protected void build() {
		boolean editing = view.isEditable();
		ServerPlayer live = live();
		scanCache = null;

		set(SLOT_HEAD, headerIcon(editing, live));

		button(SLOT_BACK, Theme.backButton(target.name() + "'s file"), click ->
				PlayerActionsMenu.reopen(viewer, target));

		buildEnderChestButton(live);
		buildSnapshotButton(live);
		buildFreezeButton(live);
		buildSecurityButton(live);
		buildVaultButton(live);
		buildConfiscateButton(live);

		if (Permissions.check(viewer, Nodes.PUNISH)) {
			button(SLOT_PUNISH, Icon.of(Items.NETHERITE_AXE)
					.name("Punish", Theme.BAD)
					.lore("Straight to the ladder without losing your place.")
					.build(), click -> PunishMenu.open(viewer, target));
		}

		button(SLOT_CLOSE, Theme.closeButton(), click -> viewer.closeContainer());

		// Anything left in the button strip becomes frame. Red while editing, so the mode
		// is obvious from peripheral vision rather than from reading the title.
		ItemStack frame = editing
				? Icon.of(Mc.pane(DyeColor.RED)).name(Component.empty()).build()
				: Theme.filler();
		for (int i = FIRST_BUTTON; i < size; i++) {
			if (isScratchSlot(i) && backing.getItem(i).isEmpty()) {
				set(i, frame.copy());
			}
		}
	}

	/**
	 * The other half of what somebody is carrying.
	 * <p>
	 * The ender chest is where anything worth hiding ends up, and until now getting to it
	 * meant backing out to the player's file and coming in again — while the ender chest
	 * screen had a link straight back here. The route existed in one direction only.
	 */
	private void buildEnderChestButton(ServerPlayer live) {
		if (!Permissions.check(viewer, Nodes.ENDERCHEST)) return;

		if (live == null) {
			set(SLOT_ENDER, Icon.of(Items.ENDER_CHEST)
					.name("Ender chest", Theme.MUTED)
					.gap()
					.lore("Ender chests can only be read live.", Theme.MUTED)
					.lore("This one is a saved copy.", Theme.MUTED)
					.build());
			return;
		}

		button(SLOT_ENDER, Icon.of(Items.ENDER_CHEST)
				.name("Ender chest", Theme.TEXT)
				.lore("The 27 slots that follow them everywhere.")
				.gap()
				.action("Click", "switch to it")
				.build(), click -> EnderChestMenu.open(viewer, target));
	}

	/**
	 * Snapshots.
	 * <p>
	 * Iconed as a book rather than an ender chest, which is what it used to be — sitting
	 * next to a screen that had no ender chest button at all, so the one thing on it that
	 * looked like an ender chest was the one thing that was not.
	 */
	private void buildSnapshotButton(ServerPlayer live) {
		if (live == null) {
			set(SLOT_SNAPSHOT, Icon.of(Items.WRITABLE_BOOK)
					.name("Snapshots", Theme.MUTED)
					.field("Stored", String.valueOf(Mods.inventory().snapshotCount(target.id())))
					.gap()
					.warn("Cannot capture a new one while they are offline.")
					.build());
			return;
		}

		button(SLOT_SNAPSHOT, Icon.of(Items.WRITABLE_BOOK)
				.name("Take a snapshot", Theme.TEXT)
				.lore("Freeze a copy of this inventory as evidence.")
				.field("Stored", String.valueOf(Mods.inventory().snapshotCount(target.id())))
				.gap()
				.action("Click", "capture now")
				.build(), click -> {
			ServerPlayer p = live();
			if (p == null) {
				viewer.sendSystemMessage(Theme.warn(target.name() + " just went offline."));
				Sfx.deny(viewer);
				return;
			}
			var snap = Mods.inventory().capture(p, "Manual", Mc.name(viewer));
			viewer.sendSystemMessage(Theme.good(
					"Snapshot taken — " + snap.itemCount() + " stack(s) recorded."));
			Sfx.success(viewer);
			render();
		});
	}

	private void buildFreezeButton(ServerPlayer live) {
		if (!Permissions.check(viewer, Nodes.FREEZE)) return;

		if (live == null) {
			set(SLOT_FREEZE, Icon.of(Items.PACKED_ICE)
					.name("Freeze", Theme.MUTED)
					.gap()
					.warn(target.name() + " is offline.")
					.build());
			return;
		}

		boolean frozen = Mods.freeze().isFrozen(live);
		button(SLOT_FREEZE, Icon.of(Items.PACKED_ICE)
				.name("Freeze", frozen ? 0x8FD3FF : Theme.TEXT)
				.state(frozen, "Frozen", "Free to move")
				.gap()
				.action("Click", frozen ? "release" : "freeze so they cannot dump items")
				.build(), click -> {
			ServerPlayer p = live();
			if (p == null) return;
			Mods.freeze().toggle(p);
			Sfx.success(viewer);
			render();
		});
	}

	private void buildSecurityButton(ServerPlayer live) {
		if (!Permissions.check(viewer, Nodes.SECURITY_CHECK)) return;

		if (live == null) {
			set(SLOT_SECURITY, Icon.of(Items.SPYGLASS)
					.name("Security Check", Theme.MUTED)
					.gap()
					.warn("Needs them online.")
					.build());
			return;
		}

		button(SLOT_SECURITY, Icon.of(Items.SPYGLASS)
				.name("Security Check", Theme.TEXT)
				.lore("Scan what they are carrying for the impossible.")
				.build(), click -> {
			ServerPlayer p = live();
			if (p != null) SecurityMenu.open(viewer, p);
		});
	}

	/**
	 * The vault, and the quickest way to put something in it.
	 * <p>
	 * Drop a stack on this and it is confiscated. Picking an item up out of somebody's
	 * inventory and putting it somewhere is the gesture staff already reach for; before
	 * this, the only place to put it was back, and taking one specific item meant running
	 * a scan that would take everything it disliked and nothing it did not.
	 * <p>
	 * With an empty cursor it opens the vault, so the icon means the same thing in both
	 * directions: this is where confiscated items live.
	 */
	private void buildVaultButton(ServerPlayer live) {
		boolean mayTake = Permissions.check(viewer, Nodes.CONFISCATE);
		boolean mayView = Permissions.check(viewer, Nodes.VAULT);
		if (!mayTake && !mayView) return;

		int held = Mods.security().vault().countFor(target.id());
		Icon icon = Icon.of(Items.BUNDLE)
				.name("Contraband vault", Theme.ACCENT)
				.field("Taken from them", String.valueOf(held));

		icon.gap();
		if (mayTake && live != null && view.isEditable()) {
			icon.lore("Hold one of their stacks and click here.", Theme.TEXT)
					.lore("Only items picked up from their slots —", Theme.MUTED)
					.lore("anything of yours is refused, not mis-filed.", Theme.MUTED);
		} else if (mayTake && live != null) {
			icon.lore("Open in edit mode to take items this way.", Theme.MUTED);
		}
		if (mayView) icon.action("Click with nothing held", "open the vault");

		button(SLOT_VAULT, icon.build(), click -> {
			ItemStack carried = getCarried();

			if (carried.isEmpty()) {
				if (mayView) VaultMenu.open(viewer);
				else Sfx.deny(viewer);
				return;
			}
			if (!mayTake) {
				viewer.sendSystemMessage(Theme.warn("You do not have " + Nodes.CONFISCATE + "."));
				Sfx.deny(viewer);
				return;
			}
			vaultCarried(carried);
		});
	}

	/**
	 * Takes the stack on the cursor into the vault.
	 * <p>
	 * Recorded against the target, because in an edit-mode view that is where a carried
	 * stack came from. A staff member who picked something out of their own bags first
	 * would be filing it under the wrong name — worth knowing, and the button says so
	 * rather than pretending the distinction is knowable from here.
	 */
	private void vaultCarried(ItemStack carried) {
		MinecraftServer server = Mc.server(viewer);
		if (server == null) return;

		// Refused rather than mis-filed. A vault row is a record of what was taken off
		// somebody, and one naming the wrong person is worse than one that never existed.
		if (!carriedFromTarget) {
			viewer.sendSystemMessage(Theme.warn(
					"That stack did not come out of " + target.name() + "'s inventory."));
			viewer.sendSystemMessage(Theme.info(
					"Pick it up from one of their slots and try again."));
			Sfx.deny(viewer);
			return;
		}

		ItemStack taken = carried.copy();
		long row = Mods.security().vault().deposit(target.id(), target.name(), Mc.name(viewer),
				taken, "Taken by hand during an inventory check", server);

		if (row == 0L) {
			// The item is still on the cursor, so nothing has been lost — which is the whole
			// reason the deposit happens before the removal.
			viewer.sendSystemMessage(Theme.bad("Could not record that in the vault — nothing taken."));
			Sfx.error(viewer);
			return;
		}

		setCarried(ItemStack.EMPTY);
		Mods.alerts().onStaffAction(server, "%s took %d× %s from %s".formatted(
				Mc.name(viewer), taken.getCount(), taken.getHoverName().getString(), target.name()));

		ServerPlayer live = live();
		if (live != null) {
			live.sendSystemMessage(Theme.warn(
					"Staff removed " + taken.getHoverName().getString() + " from your inventory."));
		}
		viewer.sendSystemMessage(Theme.good("Vaulted " + taken.getCount() + "× "
				+ taken.getHoverName().getString() + " — reversible from the vault."));
		Sfx.success(viewer);
		render();
	}

	/**
	 * Remembers where the stack on the cursor came from, before vanilla moves it.
	 * <p>
	 * The bundle vaults whatever is dropped on it, and it must only ever vault items that
	 * came out of the target's slots. By the time the drop lands there is no way to ask where
	 * the cursor picked it up, so the answer is recorded on the way past.
	 */
	@Override
	public void clicked(int slotId, int button, ContainerInput clickType, Player player) {
		// Middle-click reads an item rather than moving it. Vanilla only does anything with
		// it in creative, so the gesture is free here, and "look at this closely" wants to be
		// one action away from seeing something odd — not a command typed from memory.
		if (clickType == ContainerInput.CLONE && inspect(slotId)) return;

		boolean wasEmpty = getCarried().isEmpty();
		super.clicked(slotId, button, clickType, player);
		noteCarriedOrigin(wasEmpty, slotId, clickType);
	}

	/**
	 * Opens the component breakdown for whatever is in a slot.
	 *
	 * @return true when the click was handled and vanilla should not see it
	 */
	private boolean inspect(int slotId) {
		if (slotId < 0 || slotId >= slots.size()) return false;

		ItemStack stack = getSlot(slotId).getItem();
		if (stack.isEmpty()) return false;

		ItemDetailsMenu.open(viewer, stack,
				target.name() + "'s inventory, slot " + slotId, () -> open(viewer, target));
		return true;
	}

	/**
	 * Shift-click moves a stack between the player's inventory and the staff member's own.
	 * <p>
	 * This briefly meant "confiscate", which was a mistake: shift-click is the most
	 * over-learned gesture in the game and hijacking it made an editable inventory feel
	 * broken — items would vanish into the vault when somebody expected them to move. The
	 * bundle is the confiscation gesture, and it is the one that was actually asked for.
	 * <p>
	 * Only storage and hotbar are valid destinations. The armour and offhand slots are
	 * mapped too, but quick-moving an arbitrary stack into them is never what anybody meant,
	 * and the scratch slots past 40 hold this screen's buttons.
	 */
	private java.util.List<io.github.alphain24.staffcore.modules.security.SecurityModule.Flag> scanCache;

	/**
	 * The security scan for this render pass, run once.
	 * <p>
	 * Two separate parts of the footer want the findings, and both used to ask for them —
	 * so every redraw walked the inventory and the ender chest twice over. Cleared at the
	 * top of {@link #build()}, so it is a per-pass cache and never a stale one.
	 */
	private java.util.List<io.github.alphain24.staffcore.modules.security.SecurityModule.Flag> scan(
			ServerPlayer live) {

		if (live == null) return java.util.List.of();
		if (scanCache == null) scanCache = Mods.security().scanItems(live);
		return scanCache;
	}

	/**
	 * Twice a second. The point of this screen is that it is live, and it was only live at
	 * the instant it opened — staff watching a suspect saw a photograph and believed it was
	 * a window. Fast enough to follow somebody emptying a shulker, slow enough that the
	 * redraw costs nothing next to the packets the player is already generating.
	 */
	@Override
	protected int refreshEveryTicks() {
		return 10;
	}

	@Override
	public ItemStack quickMoveStack(Player player, int index) {
		if (!view.isEditable()) return ItemStack.EMPTY;

		var slot = index >= 0 && index < slots.size() ? getSlot(index) : null;
		if (slot == null || !slot.hasItem()) return ItemStack.EMPTY;

		ItemStack stack = slot.getItem();
		ItemStack before = stack.copy();

		boolean moved = index < size
				// Out of their inventory and into yours.
				? moveItemStackTo(stack, size, slots.size(), true)
				// Out of yours and into their storage and hotbar, never their armour.
				: moveItemStackTo(stack, 0, MOVABLE_SLOTS, false);

		if (!moved) return ItemStack.EMPTY;

		if (stack.isEmpty()) slot.set(ItemStack.EMPTY);
		else slot.setChanged();

		return before;
	}

	/** Storage plus hotbar — slots 36-40 are armour and offhand, 41+ are our buttons. */
	private static final int MOVABLE_SLOTS = 36;

	/**
	 * Whether the stack currently on the cursor came out of the target's inventory.
	 * <p>
	 * Without this the vault has no way to tell a confiscation from a staff member handing
	 * over something of their own, and would file both under the target's name.
	 */
	private boolean carriedFromTarget;

	/**
	 * Remembers where a picked-up stack came from.
	 * <p>
	 * Only a plain pickup out of a mapped slot counts. Everything else — a drag, a
	 * double-click gather, a hotbar swap, anything out of the staff member's own bags —
	 * leaves the flag false, so an unrecognised gesture is refused rather than filed
	 * against the wrong person. Being wrong in that direction costs a click; being wrong in
	 * the other direction puts somebody else's name on a confiscation record.
	 */
	private void noteCarriedOrigin(boolean wasEmpty, int slotId, ContainerInput clickType) {
		if (getCarried().isEmpty()) {
			carriedFromTarget = false;
			return;
		}
		if (!wasEmpty) return;   // already carrying; the origin has not changed

		carriedFromTarget = clickType == ContainerInput.PICKUP && view.isMapped(slotId);
	}

	/**
	 * Strips everything the security scan calls impossible.
	 * <p>
	 * Scoped to <em>flagged</em> items rather than "everything selected", because a button
	 * that empties a player's inventory wholesale is one misclick away from an incident of
	 * its own. A snapshot is taken first either way, so a wrong call is reversible.
	 */
	private void buildConfiscateButton(ServerPlayer live) {
		if (!Permissions.check(viewer, Nodes.CONFISCATE)) return;

		if (live == null) {
			set(SLOT_CONFISCATE, Icon.of(Items.HOPPER)
					.name("Confiscate", Theme.MUTED)
					.gap()
					.warn(target.name() + " must be online.")
					.build());
			return;
		}

		int flagged = scan(live).size();
		button(SLOT_CONFISCATE, Icon.of(Items.HOPPER)
				.name("Confiscate", flagged > 0 ? Theme.BAD : Theme.MUTED)
				.field("Flagged", String.valueOf(flagged), flagged > 0 ? Theme.BAD : Theme.MUTED)
				.lore("Sweeps their inventory and ender chest.")
				.gap()
				.action("Left-click", flagged > 0 ? "take the flagged items" : "nothing flagged")
				.action("Right-click", "take everything they are carrying")
				.gap()
				.lore("A snapshot is taken first, so either is reversible.", Theme.MUTED)
				.lore("Everything goes to the vault, never deleted.", Theme.MUTED)
				.build(), click -> {
			ServerPlayer p = live();
			if (p == null) return;

			if (click.isRight()) {
				confirmConfiscateAll(p);
				return;
			}
			if (Mods.security().scanItems(p).isEmpty()) {
				viewer.sendSystemMessage(Theme.info("Nothing impossible to take."));
				Sfx.deny(viewer);
				return;
			}
			confirmConfiscate(p);
		});
	}

	/**
	 * Takes everything, not just what the scan disliked.
	 * <p>
	 * The narrow version exists because a button that empties somebody's inventory is one
	 * misclick from an incident of its own — but "only what a heuristic flagged" is the
	 * wrong tool when staff have already worked out what is going on and simply need it all
	 * held while they do. The guard is the same one that makes the narrow version safe: a
	 * snapshot first, a confirm that says what is going, and the vault rather than deletion.
	 */
	private void confirmConfiscateAll(ServerPlayer live) {
		int stacks = Mods.inventory().usedSlots(live);
		if (stacks == 0) {
			viewer.sendSystemMessage(Theme.info(target.name() + " is carrying nothing."));
			Sfx.deny(viewer);
			return;
		}

		Icon summary = Icon.head(target)
				.name("Take everything from " + target.name(), Theme.BAD)
				.field("Stacks", String.valueOf(stacks))
				.gap()
				.warn("This empties their inventory and ender chest.")
				.gap()
				.lore("A snapshot is taken first, and every stack goes", Theme.MUTED)
				.lore("to the vault — so all of it can be handed back.", Theme.MUTED);

		ConfirmMenu.open(viewer, "Take all", summary.build(),
				() -> {
					ServerPlayer p = live();
					if (p == null) return;
					applyConfiscation(p, stack -> true, "everything they were carrying");
				},
				() -> open(viewer, target));
	}

	private void confirmConfiscate(ServerPlayer live) {
		var flags = scan(live);

		Icon summary = Icon.head(target)
				.name("Confiscate from " + target.name(), Theme.BAD)
				.field("Findings", String.valueOf(flags.size()))
				.gap();
		flags.stream().limit(6).forEach(f -> summary.lore("  " + f.detail(), f.color()));
		if (flags.size() > 6) summary.lore("  … and " + (flags.size() - 6) + " more");
		summary.gap().warn("A snapshot is taken first — you can put it all back.");
		summary.lore("Taken items go to the contraband vault.", Theme.MUTED);

		ConfirmMenu.open(viewer, "Confiscate", summary.build(),
				() -> {
					ServerPlayer p = live();
					if (p == null) return;
					applyConfiscation(p, stack -> Mods.security().isIllegal(p, stack),
							"flagged items");
				},
				() -> open(viewer, target));
	}

	/** Shared by both confiscation modes, so they cannot drift on the safety parts. */
	private void applyConfiscation(ServerPlayer p, java.util.function.Predicate<ItemStack> doomed,
			String what) {

		var taken = Mods.inventory().confiscate(p, Mc.name(viewer), doomed);

		// Vaulted rather than destroyed. Confiscation acts on a judgement — sometimes a
		// heuristic, sometimes a staff member's — and either kind that deletes property on a
		// wrong call leaves nothing to hand back and nothing to show for the decision.
		for (ItemStack stack : taken.taken()) {
			Mods.security().vault().deposit(p, Mc.name(viewer), stack,
					"Confiscated during an inventory check");
		}

		MinecraftServer server = Mc.server(viewer);
		if (server != null && !taken.isEmpty()) {
			Mods.alerts().onStaffAction(server, "%s confiscated %s from %s (%d stack(s)): %s"
					.formatted(Mc.name(viewer), what, target.name(), taken.stacks(), taken.summary()));
		}
		viewer.sendSystemMessage(taken.isEmpty()
				? Theme.info("Nothing was taken.")
				: Theme.good("Took " + taken.stacks() + " stack(s), "
						+ taken.items() + " item(s) total — held in the vault."));
		Sfx.bigSuccess(viewer);
		open(viewer, target);
	}

	private ItemStack headerIcon(boolean editing, ServerPlayer live) {
		Icon icon = Icon.head(target)
				.name(target.name(), editing ? Theme.BAD : Theme.ACCENT);

		if (live != null) {
			icon.field("Slots in use", String.valueOf(Mods.inventory().usedSlots(live)))
					.field("Health", "%.0f / %.0f".formatted(live.getHealth(), live.getMaxHealth()))
					.field("World", Mc.dimensionName(live.level()));
		} else {
			icon.field("Slots in use", String.valueOf(usedSlots()))
					.lore("Read from their save file.");
		}

		icon.gap();
		if (offline) {
			icon.lore("○ Offline — read-only", Theme.MUTED)
					.lore("Saved inventories cannot be edited.");
		} else {
			icon.state(!editing, "Read-only", "Edit mode — changes are real");
		}
		return icon.build();
	}

	private int usedSlots() {
		int n = 0;
		for (int i = 0; i < view.getContainerSize(); i++) {
			if (view.isMapped(i) && !view.getItem(i).isEmpty()) n++;
		}
		return n;
	}
}
