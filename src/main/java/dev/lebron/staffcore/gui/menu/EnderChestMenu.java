package dev.lebron.staffcore.gui.menu;

import dev.lebron.staffcore.compat.Mc;
import dev.lebron.staffcore.gui.Gui;
import dev.lebron.staffcore.gui.Guis;
import dev.lebron.staffcore.gui.Icon;
import dev.lebron.staffcore.gui.Sfx;
import dev.lebron.staffcore.gui.Theme;
import dev.lebron.staffcore.module.Mods;
import dev.lebron.staffcore.permission.Nodes;
import dev.lebron.staffcore.permission.Permissions;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * A live view of a player's ender chest.
 * <p>
 * Worth its own screen because the ender chest is where anything worth hiding ends up —
 * it follows the player, survives death, and is invisible to a normal inventory check. The
 * 27 slots sit in the top three rows so the geometry matches the real thing.
 * <p>
 * Online players only: an ender chest lives in player data that is not safe to rewrite
 * underneath somebody who might reconnect mid-edit.
 */
public class EnderChestMenu extends Gui {

	private static final int SLOT_BACK = 27;
	private static final int SLOT_INVENTORY = 28;
	private static final int SLOT_VAULT = 29;
	private static final int SLOT_HEAD = 31;
	private static final int SLOT_SECURITY = 33;
	private static final int SLOT_CLOSE = 35;

	private final NameAndId target;
	private final Container chest;
	private final boolean editable;

	public static void open(ServerPlayer viewer, NameAndId target) {
		MinecraftServer server = Mc.server(viewer);
		if (server == null) return;

		ServerPlayer live = server.getPlayerList().getPlayer(target.id());
		if (live == null) {
			viewer.sendSystemMessage(Theme.warn(target.name() + " must be online to open their ender chest."));
			Sfx.deny(viewer);
			return;
		}

		boolean canEdit = Permissions.check(viewer, Nodes.INVSEE_EDIT);
		if (canEdit) {
			Mods.alerts().onStaffAction(server,
					Mc.name(viewer) + " opened " + target.name() + "'s ender chest in edit mode");
		}

		Sfx.invsee(viewer);
		Guis.silent(viewer, Theme.title("Ender Chest", target.name()),
				(id, inv, v) -> new EnderChestMenu(id, inv, v, target,
						live.getEnderChestInventory(), canEdit));
	}

	private EnderChestMenu(int containerId, Inventory playerInventory, ServerPlayer viewer,
			NameAndId target, Container chest, boolean editable) {
		super(containerId, playerInventory, viewer, 4, new Backing(chest, editable));
		this.target = target;
		this.chest = chest;
		this.editable = editable;
		render();
	}

	/** Rows 1-3 are the real ender chest; row 4 is ours. */
	@Override
	protected boolean isScratchSlot(int i) {
		return i >= 27;
	}

	@Override
	protected boolean isSlotInteractive(int slotId) {
		return editable && slotId < 27;
	}

	@Override
	protected boolean allowsOwnInventory() {
		return editable;
	}

	@Override
	protected void build() {
		set(SLOT_HEAD, Icon.head(target)
				.name(target.name() + "'s ender chest", editable ? Theme.BAD : Theme.ACCENT)
				.field("Slots in use", String.valueOf(used()))
				.gap()
				.state(!editable, "Read-only", "Edit mode — changes are real")
				.build());

		button(SLOT_BACK, Theme.backButton(target.name() + "'s file"), click ->
				PlayerActionsMenu.reopen(viewer, target));

		button(SLOT_INVENTORY, Icon.of(Items.CHEST)
				.name("Main inventory", Theme.TEXT)
				.lore("Switch to what they are carrying.")
				.build(), click -> InvseeMenu.open(viewer, target));

		buildVaultButton();
		buildSecurityButton();

		button(SLOT_CLOSE, Theme.closeButton(), click -> viewer.closeContainer());

		ItemStack frame = editable
				? Icon.of(Mc.pane(DyeColor.RED)).name(Component.empty()).build()
				: Theme.filler();
		for (int i = 27; i < size; i++) {
			if (backing.getItem(i).isEmpty()) set(i, frame.copy());
		}
	}

	/**
	 * The same bundle, meaning the same thing, in the other half of somebody's storage.
	 * <p>
	 * An ender chest is where contraband ends up precisely because it used to be harder to
	 * act on. Having the route here and not there would have kept that true.
	 */
	private void buildVaultButton() {
		boolean mayTake = Permissions.check(viewer, Nodes.CONFISCATE);
		boolean mayView = Permissions.check(viewer, Nodes.VAULT);
		if (!mayTake && !mayView) return;

		Icon icon = Icon.of(Items.BUNDLE)
				.name("Contraband vault", Theme.ACCENT)
				.gap();

		if (mayTake && editable) {
			icon.lore("Hold a stack and click here to take it.", Theme.TEXT)
					.lore("Only items from their slots — anything of", Theme.MUTED)
					.lore("yours is refused, not mis-filed.", Theme.MUTED);
		} else if (mayTake) {
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

	private void buildSecurityButton() {
		if (!Permissions.check(viewer, Nodes.SECURITY_CHECK)) return;

		MinecraftServer server = Mc.server(viewer);
		ServerPlayer live = server == null ? null : server.getPlayerList().getPlayer(target.id());
		if (live == null) return;

		button(SLOT_SECURITY, Icon.of(Items.SPYGLASS)
				.name("Security check", Theme.TEXT)
				.lore("Scan everything they hold, here and in their bags.")
				.build(), click -> SecurityMenu.open(viewer, live));
	}

	/** Takes the stack on the cursor into the vault. Deposit first, so nothing is lost. */
	private void vaultCarried(ItemStack carried) {
		MinecraftServer server = Mc.server(viewer);
		if (server == null) return;

		// Refused rather than mis-filed. A vault row records what was taken off somebody,
		// and one naming the wrong person is worse than one that never existed.
		if (!carriedFromTarget) {
			viewer.sendSystemMessage(Theme.warn(
					"That stack did not come out of " + target.name() + "'s ender chest."));
			viewer.sendSystemMessage(Theme.info("Pick it up from one of their slots and try again."));
			Sfx.deny(viewer);
			return;
		}

		ItemStack taken = carried.copy();
		long row = Mods.security().vault().deposit(target.id(), target.name(), Mc.name(viewer),
				taken, "Taken by hand from their ender chest", server);

		if (row == 0L) {
			viewer.sendSystemMessage(Theme.bad("Could not record that in the vault — nothing taken."));
			Sfx.error(viewer);
			return;
		}

		setCarried(ItemStack.EMPTY);
		Mods.alerts().onStaffAction(server, "%s took %d× %s from %s's ender chest".formatted(
				Mc.name(viewer), taken.getCount(), taken.getHoverName().getString(), target.name()));

		ServerPlayer live = server.getPlayerList().getPlayer(target.id());
		if (live != null) {
			live.sendSystemMessage(Theme.warn("Staff removed "
					+ taken.getHoverName().getString() + " from your ender chest."));
		}
		viewer.sendSystemMessage(Theme.good("Vaulted " + taken.getCount() + "× "
				+ taken.getHoverName().getString() + " — reversible from the vault."));
		Sfx.success(viewer);
		render();
	}

	@Override
	public void clicked(int slotId, int button, ContainerInput clickType, Player player) {
		// See InvseeMenu — middle-click reads an item instead of moving it.
		if (clickType == ContainerInput.CLONE && inspect(slotId)) return;

		boolean wasEmpty = getCarried().isEmpty();
		super.clicked(slotId, button, clickType, player);

		// Only a plain pickup out of the ender chest itself counts as theirs. Anything else
		// leaves this false, so an unrecognised gesture is refused rather than recorded
		// against the wrong person.
		if (getCarried().isEmpty()) carriedFromTarget = false;
		else if (wasEmpty) carriedFromTarget = clickType == ContainerInput.PICKUP
				&& slotId >= 0 && slotId < 27;
	}

	/** Whether the stack on the cursor came out of the target's ender chest. */
	private boolean carriedFromTarget;

	/**
	 * Shift-click moves a stack between their ender chest and your own inventory.
	 * <p>
	 * The ordinary meaning of the gesture. It briefly meant "confiscate", which made an
	 * editable view feel broken — the bundle is the confiscation gesture and always was.
	 */
	/** Opens the component breakdown for whatever is in a slot. */
	private boolean inspect(int slotId) {
		if (slotId < 0 || slotId >= slots.size()) return false;

		ItemStack stack = getSlot(slotId).getItem();
		if (stack.isEmpty()) return false;

		ItemDetailsMenu.open(viewer, stack,
				target.name() + "'s ender chest, slot " + slotId, () -> open(viewer, target));
		return true;
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
		if (!editable) return ItemStack.EMPTY;

		var slot = index >= 0 && index < slots.size() ? getSlot(index) : null;
		if (slot == null || !slot.hasItem()) return ItemStack.EMPTY;

		ItemStack stack = slot.getItem();
		ItemStack before = stack.copy();

		// Never into slots 27+ — those are this screen's buttons, not storage.
		boolean moved = index < 27
				? moveItemStackTo(stack, size, slots.size(), true)
				: moveItemStackTo(stack, 0, 27, false);

		if (!moved) return ItemStack.EMPTY;

		if (stack.isEmpty()) slot.set(ItemStack.EMPTY);
		else slot.setChanged();

		return before;
	}

	private int used() {
		int n = 0;
		for (int i = 0; i < Math.min(27, chest.getContainerSize()); i++) {
			if (!chest.getItem(i).isEmpty()) n++;
		}
		return n;
	}

	/**
	 * A 36-slot container whose first 27 slots are the real ender chest and whose last 9
	 * are scratch space for this menu's buttons.
	 */
	private static final class Backing implements Container {
		private final Container chest;
		private final boolean editable;
		private final ItemStack[] scratch = new ItemStack[9];

		Backing(Container chest, boolean editable) {
			this.chest = chest;
			this.editable = editable;
			java.util.Arrays.fill(scratch, ItemStack.EMPTY);
		}

		private boolean mapped(int slot) {
			return slot >= 0 && slot < 27 && slot < chest.getContainerSize();
		}

		@Override
		public int getContainerSize() {
			return 36;
		}

		@Override
		public boolean isEmpty() {
			for (int i = 0; i < 36; i++) {
				if (!getItem(i).isEmpty()) return false;
			}
			return true;
		}

		@Override
		public ItemStack getItem(int slot) {
			if (mapped(slot)) return chest.getItem(slot);
			int local = slot - 27;
			return (local >= 0 && local < scratch.length) ? scratch[local] : ItemStack.EMPTY;
		}

		@Override
		public ItemStack removeItem(int slot, int amount) {
			if (mapped(slot)) return editable ? chest.removeItem(slot, amount) : ItemStack.EMPTY;
			int local = slot - 27;
			if (local < 0 || local >= scratch.length || scratch[local].isEmpty()) return ItemStack.EMPTY;
			return scratch[local].split(amount);
		}

		@Override
		public ItemStack removeItemNoUpdate(int slot) {
			if (mapped(slot)) return editable ? chest.removeItemNoUpdate(slot) : ItemStack.EMPTY;
			int local = slot - 27;
			if (local < 0 || local >= scratch.length) return ItemStack.EMPTY;
			ItemStack out = scratch[local];
			scratch[local] = ItemStack.EMPTY;
			return out;
		}

		@Override
		public void setItem(int slot, ItemStack stack) {
			if (mapped(slot)) {
				if (editable) chest.setItem(slot, stack);
				return;
			}
			int local = slot - 27;
			if (local >= 0 && local < scratch.length) scratch[local] = stack;
		}

		@Override
		public void setChanged() {
			chest.setChanged();
		}

		@Override
		public boolean stillValid(Player player) {
			return true;
		}

		@Override
		public void clearContent() {
			java.util.Arrays.fill(scratch, ItemStack.EMPTY);
		}
	}
}
