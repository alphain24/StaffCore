package io.github.alphain24.staffcore.gui.menu;

import io.github.alphain24.staffcore.compat.Mc;
import io.github.alphain24.staffcore.gui.Guis;
import io.github.alphain24.staffcore.gui.Icon;
import io.github.alphain24.staffcore.gui.PagedGui;
import io.github.alphain24.staffcore.gui.Sfx;
import io.github.alphain24.staffcore.gui.Theme;
import io.github.alphain24.staffcore.module.Mods;
import io.github.alphain24.staffcore.modules.teleport.TeleportLog;
import io.github.alphain24.staffcore.permission.Nodes;
import io.github.alphain24.staffcore.permission.Permissions;
import io.github.alphain24.staffcore.util.TimeFormat;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * Where one player has teleported from and to, newest first — including staff teleporting to
 * them and bringing them somewhere.
 * <p>
 * Every row can be followed: click goes to where they arrived, right-click to where they left
 * from. Both use the ordinary staff teleport, so {@code /staff back} returns you and the trip is
 * itself on your own history.
 */
public final class TeleportHistoryMenu extends PagedGui<TeleportLog.Entry> {

	private final NameAndId target;

	public static void open(ServerPlayer viewer, NameAndId target) {
		Guis.navigate(viewer, Theme.title("Teleports", target.name()),
				(id, inv, v) -> new TeleportHistoryMenu(id, inv, v, target));
	}

	private TeleportHistoryMenu(int containerId, Inventory playerInventory, ServerPlayer viewer,
			NameAndId target) {
		super(containerId, playerInventory, viewer);
		this.target = target;
		render();
	}

	@Override
	protected List<TeleportLog.Entry> entries() {
		return Mods.teleport().log().forPlayer(target.id(), 500);
	}

	@Override
	protected ItemStack header() {
		return Icon.head(target)
				.name(target.name() + "'s teleports", Theme.ACCENT)
				.lore("Every jump of " + TeleportLog.JUMP_BLOCKS + "+ blocks or change of world,")
				.lore("whatever caused it, and staff teleports to them.")
				.gap()
				.lore("Newest first. Kept as long as the grief log.", Theme.MUTED)
				.build();
	}

	@Override
	protected ItemStack icon(TeleportLog.Entry entry) {
		boolean aboutThem = entry.player().equals(target.id());
		Icon icon = Icon.of(iconFor(entry.cause()))
				.name(aboutThem ? entry.cause().label()
						: entry.name() + ": " + entry.cause().label(), Theme.ACCENT)
				.field("When", TimeFormat.ago(entry.at()) + " (" + TimeFormat.stamp(entry.at()) + ")");
		if (entry.actor() != null) icon.field("By", entry.actor());
		if (entry.otherName() != null) {
			icon.field(entry.cause() == TeleportLog.Cause.STAFF_BRING ? "Brought to" : "To", entry.otherName());
		}
		icon.field("From", where(entry.fromWorld(), entry.fromX(), entry.fromY(), entry.fromZ()))
				.field("To", where(entry.toWorld(), entry.toX(), entry.toY(), entry.toZ()))
				.field("Distance", entry.distance() < 0 ? "another world"
						: String.format(java.util.Locale.ROOT, "%.0f blocks", entry.distance()));
		if (Permissions.check(viewer, Nodes.TP)) {
			icon.gap()
					.action("Click", "go to where they arrived")
					.action("Right-click", "go to where they left from");
		}
		return icon.build();
	}

	private static net.minecraft.world.item.Item iconFor(TeleportLog.Cause cause) {
		return switch (cause) {
			case WORLD_CHANGE -> Items.OBSIDIAN;
			case RESPAWN -> Items.TOTEM_OF_UNDYING;
			case STAFF_TO_PLAYER, STAFF_BRING, STAFF_TO_PLACE, STAFF_BACK -> Items.COMMAND_BLOCK;
			case TELEPORT -> Items.ENDER_PEARL;
		};
	}

	private static String where(String world, double x, double y, double z) {
		String short_ = world == null ? "?" : world.startsWith("minecraft:") ? world.substring(10) : world;
		return String.format(java.util.Locale.ROOT, "%.0f, %.0f, %.0f (%s)", x, y, z, short_);
	}

	@Override
	protected void onPick(TeleportLog.Entry entry, Click click) {
		if (!Permissions.check(viewer, Nodes.TP)) {
			viewer.sendSystemMessage(Theme.bad("Going there needs " + Nodes.TP + "."));
			Sfx.deny(viewer);
			return;
		}
		boolean origin = click.isRight();
		String world = origin ? entry.fromWorld() : entry.toWorld();
		MinecraftServer server = Mc.server(viewer);
		ServerLevel level = server == null ? null
				: io.github.alphain24.staffcore.modules.replay.ReplayStage.levelOf(server, world);
		if (level == null) {
			viewer.sendSystemMessage(Theme.bad("This server no longer has " + world + "."));
			Sfx.deny(viewer);
			return;
		}
		viewer.closeContainer();
		Mods.teleport().toPosition(viewer, level,
				origin ? entry.fromX() : entry.toX(),
				origin ? entry.fromY() : entry.toY(),
				origin ? entry.fromZ() : entry.toZ());
		viewer.sendSystemMessage(Theme.good("Taken to where " + entry.name()
				+ (origin ? " left from." : " arrived.") + " /staff back returns you."));
	}

	@Override
	protected ItemStack emptyIcon() {
		return Icon.of(Items.STRUCTURE_VOID)
				.name("No teleports on record", Theme.MUTED)
				.lore(target.name() + " has not teleported since this was recorded,")
				.lore("or it was longer ago than the grief log keeps.")
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
}
