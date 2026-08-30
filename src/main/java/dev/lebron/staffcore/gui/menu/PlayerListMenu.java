package dev.lebron.staffcore.gui.menu;

import dev.lebron.staffcore.compat.Mc;
import dev.lebron.staffcore.gui.Guis;
import dev.lebron.staffcore.gui.Icon;
import dev.lebron.staffcore.gui.PagedGui;
import dev.lebron.staffcore.gui.Sfx;
import dev.lebron.staffcore.gui.Theme;
import dev.lebron.staffcore.module.Mods;
import dev.lebron.staffcore.permission.Nodes;
import dev.lebron.staffcore.permission.Permissions;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Everyone online, as heads you can act on.
 * <p>
 * The same screen serves every "pick a player" flow — {@link Purpose} decides what a
 * left-click does. Right-click always opens the player's full file, so no matter which
 * door you came in through you are one click from everything else.
 */
public class PlayerListMenu extends PagedGui<ServerPlayer> {

	public enum Purpose {
		INSPECT("Players", "open their file", Nodes.STAFF_GUI),
		PUNISH("Punish", "open the punish menu", Nodes.PUNISH),
		INVSEE("Inventories", "look in their inventory", Nodes.INVSEE),
		NOTES("Notes", "read and write notes", Nodes.NOTES),
		HISTORY("History", "read their punishment history", Nodes.HISTORY),
		SECURITY("Security", "run a security check", Nodes.SECURITY_CHECK),
		TELEPORT("Teleport", "teleport to them", Nodes.TP),
		FREEZE("Freeze", "freeze or unfreeze them", Nodes.FREEZE);

		final String crumb;
		final String verb;
		final String node;

		Purpose(String crumb, String verb, String node) {
			this.crumb = crumb;
			this.verb = verb;
			this.node = node;
		}
	}

	private final Purpose purpose;

	public static void open(ServerPlayer viewer, Purpose purpose) {
		Guis.navigate(viewer, Theme.title(purpose.crumb),
				(id, inv, v) -> new PlayerListMenu(id, inv, v, purpose));
	}

	public static void openForInspection(ServerPlayer viewer) {
		open(viewer, Purpose.INSPECT);
	}

	private PlayerListMenu(int containerId, Inventory playerInventory, ServerPlayer viewer, Purpose purpose) {
		super(containerId, playerInventory, viewer);
		this.purpose = purpose;
		render();
	}

	// -------------------------------------------------------------------- content

	@Override
	protected List<ServerPlayer> entries() {
		MinecraftServer server = Mc.server(viewer);
		if (server == null) return List.of();

		List<ServerPlayer> players = new ArrayList<>(server.getPlayerList().getPlayers());
		// Staff first is tempting but wrong: the people you need are the ones being
		// reported, so sort plainly by name and let the badges do the work.
		players.sort(Comparator.comparing(p -> Mc.name(p).toLowerCase(java.util.Locale.ROOT)));
		return players;
	}

	@Override
	protected ItemStack icon(ServerPlayer target) {
		boolean frozen = Mods.freeze().isFrozen(target);
		boolean vanished = Mods.vanish().isVanished(target);
		boolean onDuty = Mods.staffMode().isActive(target);
		int punishments = Mods.punish().historyCount(target.getUUID());
		int notes = Mods.notes().count(target.getUUID());

		int nameColor = frozen ? 0x8FD3FF : onDuty ? Theme.ACCENT : Theme.TEXT;

		Icon icon = Icon.head(Mc.profile(target))
				.name(Mc.name(target), nameColor)
				.field("Health", "%.0f / %.0f".formatted(target.getHealth(), target.getMaxHealth()))
				.field("World", target.level().dimension().identifier().getPath())
				.field("At", "%d, %d, %d".formatted(
						target.getBlockX(), target.getBlockY(), target.getBlockZ()))
				.field("UUID", target.getUUID().toString())
				.gap()
				.field("Punishments", String.valueOf(punishments), punishments > 0 ? Theme.WARN : Theme.MUTED)
				.field("Notes", String.valueOf(notes), notes > 0 ? Theme.WARN : Theme.MUTED);

		if (frozen) icon.warn("Frozen");
		if (vanished) icon.lore("○ Vanished", Theme.MUTED);
		if (onDuty) icon.lore("● On duty", Theme.ACCENT);
		if (target == viewer) icon.lore("This is you.", Theme.MUTED);

		icon.gap()
				.action("Left-click", purpose.verb)
				.action("Right-click", "open their full file");

		if (frozen || punishments > 0) icon.glow();
		return icon.build();
	}

	@Override
	protected void onPick(ServerPlayer target, Click click) {
		if (click.isRight()) {
			PlayerActionsMenu.open(viewer, Mc.profile(target));
			return;
		}

		if (!Permissions.check(viewer, purpose.node)) {
			viewer.sendSystemMessage(Theme.bad("You do not have " + purpose.node + "."));
			Sfx.deny(viewer);
			return;
		}

		switch (purpose) {
			case INSPECT -> PlayerActionsMenu.open(viewer, Mc.profile(target));
			case PUNISH -> PunishMenu.open(viewer, Mc.profile(target));
			case INVSEE -> InvseeMenu.open(viewer, target);
			case NOTES -> NotesMenu.open(viewer, Mc.profile(target));
			case HISTORY -> HistoryMenu.open(viewer, Mc.profile(target));
			case SECURITY -> SecurityMenu.open(viewer, target);
			case TELEPORT -> {
				Mods.teleport().toPlayer(viewer, target);
				viewer.sendSystemMessage(Theme.info("Teleported to " + Mc.name(target) + "."));
				viewer.closeContainer();
			}
			case FREEZE -> {
				boolean frozen = Mods.freeze().toggle(target);
				viewer.sendSystemMessage(frozen
						? Theme.good(Mc.name(target) + " is frozen.")
						: Theme.info(Mc.name(target) + " is free to move."));
				Sfx.success(viewer);
				render();
			}
		}
	}

	// --------------------------------------------------------------------- chrome

	@Override
	protected ItemStack header() {
		MinecraftServer server = Mc.server(viewer);
		int count = server == null ? 0 : server.getPlayerList().getPlayerCount();

		return Icon.of(Items.PLAYER_HEAD)
				.name(purpose.crumb, Theme.ACCENT)
				.field("Online", String.valueOf(count))
				.gap()
				.lore("Left-click to " + purpose.verb + ".")
				.lore("Right-click anyone for everything else.")
				.build();
	}

	@Override
	protected ItemStack emptyIcon() {
		return Icon.of(Items.STRUCTURE_VOID)
				.name("Nobody online", Theme.MUTED)
				.lore("An empty server is a quiet shift.")
				.build();
	}

	@Override
	protected Runnable backTarget() {
		return () -> StaffPanelMenu.reopen(viewer);
	}
}
