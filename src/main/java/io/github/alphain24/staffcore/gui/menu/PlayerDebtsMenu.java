package io.github.alphain24.staffcore.gui.menu;

import io.github.alphain24.staffcore.StaffCore;
import io.github.alphain24.staffcore.compat.Mc;
import io.github.alphain24.staffcore.gui.Guis;
import io.github.alphain24.staffcore.gui.Icon;
import io.github.alphain24.staffcore.gui.PagedGui;
import io.github.alphain24.staffcore.gui.Sfx;
import io.github.alphain24.staffcore.gui.Theme;
import io.github.alphain24.staffcore.modules.grief.OwedReport;
import io.github.alphain24.staffcore.permission.Nodes;
import io.github.alphain24.staffcore.permission.Permissions;
import io.github.alphain24.staffcore.storage.PendingActions;
import io.github.alphain24.staffcore.util.TimeFormat;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * What one player owes, item by item, and why.
 * <p>
 * Reached from their file and from the owed items list. Each row is one kind of item a rollback
 * put back that they did not have on them; forgiving it means nothing is taken off them for it.
 */
public final class PlayerDebtsMenu extends PagedGui<PendingActions.Entry> {

	private static final int SLOT_FORGIVE_ALL = 51;

	private final NameAndId target;

	public static void open(ServerPlayer viewer, NameAndId target) {
		Guis.navigate(viewer, Theme.title("Owed Items", target.name()),
				(id, inv, v) -> new PlayerDebtsMenu(id, inv, v, target));
	}

	private static void reopen(ServerPlayer viewer, NameAndId target) {
		Guis.silent(viewer, Theme.title("Owed Items", target.name()),
				(id, inv, v) -> new PlayerDebtsMenu(id, inv, v, target));
	}

	private PlayerDebtsMenu(int containerId, Inventory playerInventory, ServerPlayer viewer,
			NameAndId target) {
		super(containerId, playerInventory, viewer);
		this.target = target;
		render();
	}

	@Override
	protected List<PendingActions.Entry> entries() {
		return StaffCore.pending().debtsOf(target.id());
	}

	@Override
	protected ItemStack header() {
		int items = entries().stream().mapToInt(PendingActions.Entry::count).sum();
		return Icon.head(target)
				.name(target.name() + " owes", Theme.ACCENT)
				.field("Items", String.valueOf(items), items > 0 ? Theme.WARN : Theme.MUTED)
				.gap()
				.lore("Items a rollback put back that were not on them.", Theme.MUTED)
				.lore("Taken off them the next time they log in, and", Theme.MUTED)
				.lore("given to nobody — the originals are already back.", Theme.MUTED)
				.build();
	}

	@Override
	protected ItemStack emptyIcon() {
		return Icon.of(Mc.pane(DyeColor.LIME))
				.name(target.name() + " owes nothing", Theme.GOOD)
				.build();
	}

	@Override
	protected ItemStack icon(PendingActions.Entry debt) {
		Item item = Mc.itemFromId(debt.item(), null);
		Icon icon = (item == null ? Icon.of(Items.PAPER) : Icon.of(item))
				.name(OwedReport.describe(debt.item(), debt.count()), Theme.WARN)
				.field("Why", debt.reason() == null ? "a rollback" : debt.reason())
				.field("Booked", TimeFormat.ago(debt.createdAt()))
				.gap()
				.lore("Taken off them on their next login.", Theme.MUTED);
		if (Permissions.check(viewer, Nodes.ROLLBACK)) icon.action("Right-click", "forgive this one");
		return icon.count(Math.max(1, Math.min(64, debt.count()))).build();
	}

	@Override
	protected void onPick(PendingActions.Entry debt, Click click) {
		if (!click.isRight() || !Permissions.check(viewer, Nodes.ROLLBACK)) {
			Sfx.deny(viewer);
			return;
		}
		String what = OwedReport.describe(debt.item(), debt.count());
		ConfirmMenu.open(viewer, "Forgive",
				Icon.of(Items.MILK_BUCKET)
						.name("Forgive " + what, Theme.WARN)
						.field("Owed by", target.name())
						.gap()
						.lore("Nothing is taken off them for it.")
						.build(),
				() -> {
					if (StaffCore.pending().cancelDebit(debt.id())) {
						viewer.sendSystemMessage(Theme.good("Forgave " + what + " owed by "
								+ target.name() + "."));
						OwedItemsMenu.record(viewer, "forgave " + what + " owed by " + target.name());
						Sfx.bigSuccess(viewer);
					} else {
						viewer.sendSystemMessage(Theme.warn("That debt has already been paid or forgiven."));
						Sfx.error(viewer);
					}
					reopen(viewer, target);
				},
				() -> reopen(viewer, target));
	}

	@Override
	protected void decorateFooter() {
		if (entries().isEmpty() || !Permissions.check(viewer, Nodes.ROLLBACK)) return;
		button(SLOT_FORGIVE_ALL, Icon.of(Items.MILK_BUCKET)
				.name("Forgive everything " + target.name() + " owes", Theme.WARN)
				.action("Click", "choose, then confirm")
				.build(), click -> confirmForgiveAll(viewer, target, () -> reopen(viewer, target)));
	}

	/** Writes off one player's debts after a confirmation, then goes wherever {@code after} says. */
	static void confirmForgiveAll(ServerPlayer viewer, NameAndId target, Runnable after) {
		if (!Permissions.check(viewer, Nodes.ROLLBACK)) {
			viewer.sendSystemMessage(Theme.bad("You do not have " + Nodes.ROLLBACK + "."));
			Sfx.deny(viewer);
			return;
		}
		int items = StaffCore.pending().debtsOf(target.id()).stream()
				.mapToInt(PendingActions.Entry::count).sum();
		ConfirmMenu.open(viewer, "Forgive",
				Icon.head(target)
						.name("Forgive " + items + " item(s) owed by " + target.name(), Theme.WARN)
						.gap()
						.lore("Nothing is taken off them on their next login.")
						.warn("Cannot be undone.")
						.build(),
				() -> {
					int written = StaffCore.pending().forgive(target.id());
					viewer.sendSystemMessage(Theme.good("Forgave " + written + " item(s) owed by "
							+ target.name() + "."));
					OwedItemsMenu.record(viewer, "forgave " + written + " item(s) owed by " + target.name());
					Sfx.bigSuccess(viewer);
					after.run();
				},
				after);
	}

	@Override
	protected Runnable backTarget() {
		return () -> PlayerActionsMenu.reopen(viewer, target);
	}

	@Override
	protected String backLabel() {
		return target.name();
	}
}
