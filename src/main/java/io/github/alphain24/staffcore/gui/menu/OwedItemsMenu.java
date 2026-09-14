package io.github.alphain24.staffcore.gui.menu;

import io.github.alphain24.staffcore.StaffCore;
import io.github.alphain24.staffcore.compat.Mc;
import io.github.alphain24.staffcore.config.StaffConfig;
import io.github.alphain24.staffcore.gui.Guis;
import io.github.alphain24.staffcore.gui.Icon;
import io.github.alphain24.staffcore.gui.PagedGui;
import io.github.alphain24.staffcore.gui.Sfx;
import io.github.alphain24.staffcore.gui.Theme;
import io.github.alphain24.staffcore.module.Mods;
import io.github.alphain24.staffcore.permission.Nodes;
import io.github.alphain24.staffcore.permission.Permissions;
import io.github.alphain24.staffcore.storage.PendingActions;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * Everybody a rollback left owing items, and the way to let them off.
 * <p>
 * A debt is owed to nobody. A rollback put the originals back while somebody still had the
 * copies, so what is collected on their next login is removed, not handed to anyone — it only
 * stops the repair printing items. Until this screen the only sign of a debt was a chat line
 * saying items were owed, with no name, no list and nowhere to go and look.
 */
public final class OwedItemsMenu extends PagedGui<PendingActions.Debt> {

	private static final int SLOT_FORGIVE_ALL = 51;

	public static void open(ServerPlayer viewer) {
		Guis.navigate(viewer, Theme.title("Owed Items"),
				(id, inv, v) -> new OwedItemsMenu(id, inv, v));
	}

	private static void reopen(ServerPlayer viewer) {
		Guis.silent(viewer, Theme.title("Owed Items"),
				(id, inv, v) -> new OwedItemsMenu(id, inv, v));
	}

	private OwedItemsMenu(int containerId, Inventory playerInventory, ServerPlayer viewer) {
		super(containerId, playerInventory, viewer);
		render();
	}

	@Override
	protected List<PendingActions.Debt> entries() {
		return StaffCore.pending().outstanding(1000);
	}

	@Override
	protected ItemStack header() {
		List<PendingActions.Debt> debts = entries();
		int items = debts.stream().mapToInt(PendingActions.Debt::items).sum();
		int days = StaffConfig.get().debtExpiryDays;

		return Icon.of(Items.GOLD_NUGGET)
				.name("Owed items", Theme.ACCENT)
				.field("Players", String.valueOf(debts.size()))
				.field("Items", String.valueOf(items), items > 0 ? Theme.WARN : Theme.MUTED)
				.gap()
				.lore("A rollback put these items back while somebody", Theme.MUTED)
				.lore("still had the copies. They are taken off that", Theme.MUTED)
				.lore("player the next time they log in.", Theme.MUTED)
				.gap()
				.lore("Nobody receives them: the originals are already", Theme.MUTED)
				.lore("back, so this only stops a copy being kept.", Theme.MUTED)
				.lore(days > 0 ? "Unpaid debts are written off after " + days + " days."
						: "Kept until paid or forgiven.", Theme.MUTED)
				.build();
	}

	@Override
	protected ItemStack emptyIcon() {
		return Icon.of(Mc.pane(DyeColor.LIME))
				.name("Nobody owes anything", Theme.GOOD)
				.lore("Debts appear here when a rollback puts items back")
				.lore("that the player who had them no longer has.")
				.build();
	}

	@Override
	protected ItemStack icon(PendingActions.Debt debt) {
		NameAndId who = new NameAndId(debt.ownerId(), debt.ownerName());
		MinecraftServer server = Mc.server(viewer);
		boolean online = server != null && server.getPlayerList().getPlayer(debt.ownerId()) != null;

		return Icon.head(who)
				.name(debt.ownerName(), Theme.WARN)
				.field("Owes", debt.items() + " item(s)", Theme.WARN)
				.field("Entries", String.valueOf(debt.rows()))
				.state(online, "Online", "Offline")
				.gap()
				.action("Click", "see what they owe")
				.action("Right-click", "forgive all of it")
				.build();
	}

	@Override
	protected void onPick(PendingActions.Debt debt, Click click) {
		NameAndId who = new NameAndId(debt.ownerId(), debt.ownerName());
		if (click.isRight()) {
			PlayerDebtsMenu.confirmForgiveAll(viewer, who, () -> reopen(viewer));
			return;
		}
		Sfx.page(viewer);
		PlayerDebtsMenu.open(viewer, who);
	}

	@Override
	protected void decorateFooter() {
		List<PendingActions.Debt> debts = entries();
		if (debts.isEmpty() || !Permissions.check(viewer, Nodes.ROLLBACK)) return;
		int items = debts.stream().mapToInt(PendingActions.Debt::items).sum();

		button(SLOT_FORGIVE_ALL, Icon.of(Items.MILK_BUCKET)
				.name("Forgive every debt", Theme.WARN)
				.field("Players", String.valueOf(debts.size()))
				.field("Items", String.valueOf(items))
				.gap()
				.lore("Nobody is charged for any of them any more.", Theme.MUTED)
				.action("Click", "choose, then confirm")
				.build(), click -> ConfirmMenu.open(viewer, "Forgive every debt",
				Icon.of(Items.MILK_BUCKET)
						.name("Forgive " + items + " item(s) owed by " + debts.size() + " player(s)",
								Theme.WARN)
						.gap()
						.lore("Nothing is taken off any of them on their next login.")
						.warn("Cannot be undone.")
						.build(),
				() -> {
					int written = StaffCore.pending().forgiveAll();
					viewer.sendSystemMessage(Theme.good("Forgave " + written + " owed item(s)."));
					record(viewer, "forgave every debt (" + written + " item(s))");
					Sfx.bigSuccess(viewer);
					reopen(viewer);
				},
				() -> reopen(viewer)));
	}

	/** Into the command log and staff chat, the same as the command equivalent. */
	static void record(ServerPlayer viewer, String what) {
		Mods.accountability().audit().record(viewer, Mc.name(viewer), "[panel] " + what, null);
		MinecraftServer server = Mc.server(viewer);
		if (server != null) Mods.alerts().onStaffAction(server, Mc.name(viewer) + " " + what);
	}

	@Override
	protected Runnable backTarget() {
		return () -> StaffSections.world(viewer);
	}

	@Override
	protected String backLabel() {
		return "World";
	}
}
