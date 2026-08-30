package dev.lebron.staffcore.gui.menu;

import net.minecraft.server.players.NameAndId;
import dev.lebron.staffcore.compat.Mc;
import dev.lebron.staffcore.gui.Guis;
import dev.lebron.staffcore.gui.Icon;
import dev.lebron.staffcore.gui.PagedGui;
import dev.lebron.staffcore.gui.Sfx;
import dev.lebron.staffcore.gui.Theme;
import dev.lebron.staffcore.module.Mods;
import dev.lebron.staffcore.modules.punish.Punishment;
import dev.lebron.staffcore.permission.Nodes;
import dev.lebron.staffcore.permission.Permissions;
import dev.lebron.staffcore.util.TimeFormat;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * Every punishment a player has taken, newest first.
 * <p>
 * Spent and lifted entries stay in the list, dimmed. Deleting history is a separate,
 * confirmed action behind {@link Nodes#HISTORY_CLEAR} — a record that quietly tidies
 * itself is not a record.
 */
public class HistoryMenu extends PagedGui<Punishment> {

	private static final int SLOT_CLEAR = 47;

	private final NameAndId target;

	public static void open(ServerPlayer viewer, NameAndId target) {
		Guis.navigate(viewer, Theme.title("History", target.name()),
				(id, inv, v) -> new HistoryMenu(id, inv, v, target));
	}

	private HistoryMenu(int containerId, Inventory playerInventory, ServerPlayer viewer, NameAndId target) {
		super(containerId, playerInventory, viewer);
		this.target = target;
		render();
	}

	@Override
	protected List<Punishment> entries() {
		return Mods.punish().history(target.id());
	}

	@Override
	protected ItemStack icon(Punishment p) {
		boolean live = p.inForce();

		Icon icon = Icon.of(p.type().icon())
				.name(p.type().label(), live ? p.type().color() : Theme.MUTED)
				.field("Reason", p.reasonOr("No reason given"))
				.field("By", p.staffName() == null ? "console" : p.staffName())
				.field("When", TimeFormat.ago(p.createdAt()))
				.field("Exact", TimeFormat.stamp(p.createdAt()))
				.gap();

		if (p.type().persistent()) {
			icon.field("Expires", p.remaining(), p.isPermanent() ? Theme.BAD : Theme.WARN);
		}

		if (live) {
			icon.lore("● Still in force", Theme.BAD).glow();
		} else if (p.revokedBy() != null) {
			icon.lore("○ Lifted by " + p.revokedBy(), Theme.GOOD);
		} else if (p.type().persistent()) {
			icon.lore("○ Expired", Theme.MUTED);
		} else {
			icon.lore("○ One-off", Theme.MUTED);
		}

		return icon.build();
	}

	@Override
	protected void onPick(Punishment entry, Click click) {
		// The list is a record, not a control panel — reading is all it does. Lifting a
		// live punishment lives on the player's file where the confirmation belongs.
		Sfx.page(viewer);
	}

	@Override
	protected ItemStack header() {
		List<Punishment> all = entries();
		long bans = all.stream().filter(p -> p.type().isBan()).count();
		long mutes = all.stream().filter(p -> p.type().isMute()).count();

		return Icon.head(target)
				.name(target.name() + "'s history", Theme.ACCENT)
				.field("Records", String.valueOf(all.size()))
				.field("Bans", String.valueOf(bans))
				.field("Mutes", String.valueOf(mutes))
				.gap()
				.lore("Newest first.")
				.build();
	}

	@Override
	protected ItemStack emptyIcon() {
		return Icon.of(Mc.pane(DyeColor.LIME))
				.name("Clean record", Theme.GOOD)
				.lore(target.name() + " has never been punished.")
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

	@Override
	protected void decorateFooter() {
		if (!Permissions.check(viewer, Nodes.HISTORY_CLEAR)) return;

		button(SLOT_CLEAR, Icon.of(Items.LAVA_BUCKET)
				.name("Wipe history", Theme.BAD)
				.lore("Delete every record for this player.")
				.gap()
				.warn("This cannot be undone.")
				.build(), click -> ConfirmMenu.open(viewer, "Wipe history",
				Icon.head(target)
						.name("Wipe " + target.name() + "'s history", Theme.BAD)
						.field("Records to delete", String.valueOf(entries().size()))
						.gap()
						.warn("Active bans and mutes are deleted too, which lifts them.")
						.build(),
				() -> {
					Mods.punish().clearHistory(target.id());
					MinecraftServer server = Mc.server(viewer);
					if (server != null) {
						Mods.alerts().onStaffAction(server,
								Mc.name(viewer) + " wiped " + target.name() + "'s punishment history");
					}
					viewer.sendSystemMessage(Theme.warn("Wiped " + target.name() + "'s history."));
					Sfx.bigSuccess(viewer);
					open(viewer, target);
				},
				() -> open(viewer, target)));
	}
}
