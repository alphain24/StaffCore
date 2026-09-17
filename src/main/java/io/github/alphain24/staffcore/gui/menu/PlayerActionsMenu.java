package io.github.alphain24.staffcore.gui.menu;

import io.github.alphain24.staffcore.StaffCore;
import io.github.alphain24.staffcore.compat.Mc;
import io.github.alphain24.staffcore.gui.Gui;
import io.github.alphain24.staffcore.gui.Guis;
import io.github.alphain24.staffcore.gui.Icon;
import io.github.alphain24.staffcore.gui.Sfx;
import io.github.alphain24.staffcore.gui.Theme;
import io.github.alphain24.staffcore.module.Mods;
import io.github.alphain24.staffcore.modules.punish.AddressBans;
import io.github.alphain24.staffcore.modules.punish.Punishment;
import io.github.alphain24.staffcore.permission.Nodes;
import io.github.alphain24.staffcore.permission.Permissions;
import io.github.alphain24.staffcore.storage.PendingActions;
import io.github.alphain24.staffcore.util.TimeFormat;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.function.Consumer;

/**
 * One player's file: everything StaffCore knows about them and everything you can do.
 * <p>
 * Keyed on a {@link NameAndId} rather than a live player, because the person you most
 * need this screen for has usually just logged off. Actions that genuinely need them
 * present stay in their place with the reason written on them, not silently missing.
 * <p>
 * The layout is a fixed grid, the same for every player whatever state they are in:
 * <pre>
 *   .  .  BAN  .  HEAD  .  MUTE .  .
 *   A  .  ■    ■  ■     ■  ■    .  A     Actions
 *   R  .  ■    ■  ■     ■  ■    .  R     Record
 *   I  .  ■    ■  ■     ■  ■    .  I     Items
 *   L  .  ■    ■  ■     ■  ■    .  L     Location
 *   ←  .  .    .  .     .  .    .  ✕
 * </pre>
 * Five buttons to a row in the middle five columns, a coloured label at both ends of each
 * row, and the status cards above the first, middle and last column. Nothing moves when a
 * player goes offline or a ban is lifted — a button that cannot be used says why in place —
 * so the same thing is always under the same spot.
 */
public class PlayerActionsMenu extends Gui {

	// Top row: what is in force either side of who they are, over columns 2, 4 and 6.
	private static final int BAN_CARD = 2;
	private static final int HEADER = 4;
	private static final int MUTE_CARD = 6;

	/** Where each row starts; buttons go in columns 2 to 6, labels in columns 0 and 8. */
	private static final int ACTIONS = 9;
	private static final int RECORD = 18;
	private static final int ITEMS = 27;
	private static final int LOCATION = 36;

	private static final int BACK = 45;
	private static final int CLOSE = 53;

	private final NameAndId target;

	public static void open(ServerPlayer viewer, NameAndId target) {
		Guis.navigate(viewer, Theme.title(target.name()),
				(id, inv, v) -> new PlayerActionsMenu(id, inv, v, target));
	}

	static void reopen(ServerPlayer viewer, NameAndId target) {
		Guis.goBack(viewer, Theme.title(target.name()),
				(id, inv, v) -> new PlayerActionsMenu(id, inv, v, target));
	}

	private PlayerActionsMenu(int containerId, Inventory playerInventory, ServerPlayer viewer,
			NameAndId target) {
		super(containerId, playerInventory, viewer, 6);
		this.target = target;
		render();
	}

	private ServerPlayer online() {
		MinecraftServer server = Mc.server(viewer);
		return server == null ? null : server.getPlayerList().getPlayer(target.id());
	}

	// ---------------------------------------------------------------------- layout

	@Override
	protected void build() {
		ServerPlayer live = online();
		Punishment ban = Mods.punish().activeBan(target.id());
		Punishment mute = Mods.punish().activeMute(target.id());
		String offline = live == null ? target.name() + " is offline." : null;

		set(BAN_CARD, card("Ban", ban, Items.BARRIER));
		set(HEADER, headerIcon(live, ban, mute));
		set(MUTE_CARD, card("Mute", mute, Items.NOTE_BLOCK));

		actionsRow(live, ban, mute, offline);
		recordRow();
		itemsRow(live, offline);
		locationRow(offline);

		backButton(BACK, "the player list", () -> PlayerListMenu.openForInspection(viewer));
		button(CLOSE, Theme.closeButton(), click -> viewer.closeContainer());
		fillEmpty(Theme.filler());
	}

	private void actionsRow(ServerPlayer live, Punishment ban, Punishment mute, String offline) {
		label(ACTIONS, DyeColor.RED, "Actions", "Punish · IP ban · Freeze · Lift ban · Lift mute");

		tile(ACTIONS, 0, Nodes.PUNISH, Items.NETHERITE_AXE, "Punish", Theme.BAD, null,
				icon -> icon.lore("Warn, kick, mute or ban."),
				"open the ladder", click -> PunishMenu.open(viewer, target));

		long ipBans = Mods.punish().addressBans().forSource(target.id()).stream()
				.filter(AddressBans.AddressBan::inForce).count();
		tile(ACTIONS, 1, Nodes.IP_BAN, Items.IRON_BARS, "IP Ban", ipBans > 0 ? Theme.WARN : Theme.BAD,
				null,
				icon -> icon.lore(ipBans > 0 ? "Their connection is banned now."
						: "Ban the account and its connection."),
				ipBans > 0 ? "see or lift it" : "choose a reason",
				click -> AddressBanMenu.open(viewer, target));

		boolean frozen = live != null && Mods.freeze().isFrozen(live);
		tile(ACTIONS, 2, Nodes.FREEZE, Items.PACKED_ICE, "Freeze", frozen ? 0x8FD3FF : Theme.TEXT,
				offline,
				icon -> icon.lore("Lock them where they stand.")
						.state(frozen, "Frozen", "Free to move"),
				frozen ? "release them" : "freeze them", click -> {
					ServerPlayer p = online();
					if (p == null) return;
					boolean now = Mods.freeze().toggle(p, Mc.name(viewer));
					viewer.sendSystemMessage(now
							? Theme.good(target.name() + " is frozen.")
							: Theme.info(target.name() + " is free to move."));
					Sfx.success(viewer);
					render();
				});

		tile(ACTIONS, 3, Nodes.UNPUNISH, Items.TOTEM_OF_UNDYING, "Lift Ban", Theme.GOOD,
				ban == null ? "They are not banned." : null,
				icon -> icon.lore("Also lifts any IP ban taken from them."),
				"lift it", click -> confirmRevoke(true));

		tile(ACTIONS, 4, Nodes.UNPUNISH, Items.JUKEBOX, "Lift Mute", Theme.GOOD,
				mute == null ? "They are not muted." : null,
				icon -> icon.lore("They can talk again straight away."),
				"lift it", click -> confirmRevoke(false));
	}

	private void recordRow() {
		label(RECORD, DyeColor.YELLOW, "Record",
				"History · Notes · Appeals · Linked accounts · Risk profile");

		int punishments = Mods.punish().historyCount(target.id());
		tile(RECORD, 0, Nodes.HISTORY, Items.BOOK, "History", Theme.TEXT, null,
				icon -> icon.lore("Every punishment they have had.")
						.field("Records", String.valueOf(punishments),
								punishments > 0 ? Theme.WARN : Theme.MUTED),
				"read it", click -> HistoryMenu.open(viewer, target));

		int notes = Mods.notes().count(target.id());
		tile(RECORD, 1, Nodes.NOTES, Items.WRITABLE_BOOK, "Notes", Theme.TEXT, null,
				icon -> icon.lore("What staff have written about them.")
						.field("Notes", String.valueOf(notes), notes > 0 ? Theme.WARN : Theme.MUTED),
				"read and write notes", click -> NotesMenu.open(viewer, target));

		int appeals = Mods.appeals().openCountFor(target.id());
		tile(RECORD, 2, Nodes.APPEALS, Items.PAPER, "Appeals", Theme.TEXT, null,
				icon -> icon.lore("What they have said in their defence.")
						.field("Open", String.valueOf(appeals), appeals > 0 ? Theme.WARN : Theme.MUTED),
				"read them", click -> AppealsMenu.openFor(viewer, target));

		int alts = Mods.identity().altCount(target.id());
		tile(RECORD, 3, Nodes.ALTS, Items.IRON_CHAIN, "Linked Accounts", Theme.TEXT, null,
				icon -> icon.lore("Accounts sharing an address. A lead, not proof.")
						.field("Linked", String.valueOf(alts), alts > 0 ? Theme.WARN : Theme.MUTED),
				"review them", click -> AltsMenu.open(viewer, target));

		tile(RECORD, 4, Nodes.HISTORY, Items.COMPARATOR, "Risk Profile", Theme.TEXT, null,
				icon -> icon.lore("Everything on record, weighed. Not a verdict."),
				"see it", click -> RiskProfileMenu.open(viewer, target));
	}

	private void itemsRow(ServerPlayer live, String offline) {
		label(ITEMS, DyeColor.CYAN, "Items",
				"Inventory · Ender chest · Snapshots · Owed items · Security check");

		tile(ITEMS, 0, Nodes.INVSEE, Items.CHEST, "Inventory", Theme.TEXT, null,
				icon -> icon.lore(live == null ? "Read from their save file."
						: "Live, " + Mods.inventory().usedSlots(live) + " slot(s) in use."),
				live == null ? "open a read-only view" : "open a live view",
				click -> InvseeMenu.open(viewer, target));

		tile(ITEMS, 1, Nodes.ENDERCHEST, Items.ENDER_CHEST, "Ender Chest", Theme.TEXT, offline,
				icon -> icon.lore("Where hidden things usually end up."),
				"open it", click -> EnderChestMenu.open(viewer, target));

		int snaps = Mods.inventory().snapshotCount(target.id());
		tile(ITEMS, 2, Nodes.INVSEE, Items.ITEM_FRAME, "Snapshots", Theme.TEXT, null,
				icon -> icon.lore("Saved copies of their inventory.")
						.field("Stored", String.valueOf(snaps)),
				"browse them", click -> SnapshotsMenu.open(viewer, target));

		int owed = StaffCore.pending().debtsOf(target.id()).stream()
				.mapToInt(PendingActions.Entry::count).sum();
		tile(ITEMS, 3, Nodes.ROLLBACK, Items.GOLD_NUGGET, "Owed Items", owed > 0 ? Theme.WARN : Theme.TEXT,
				null,
				icon -> icon.lore("Rolled-back items they no longer had.")
						.field("Owes", owed > 0 ? owed + " item(s)" : "nothing",
								owed > 0 ? Theme.WARN : Theme.MUTED),
				"see or forgive", click -> PlayerDebtsMenu.open(viewer, target));

		tile(ITEMS, 4, Nodes.SECURITY_CHECK, Items.SPYGLASS, "Security Check", Theme.TEXT, offline,
				icon -> icon.lore("Impossible items and illegal enchants."),
				"run the checks", click -> SecurityMenu.open(viewer, online()));
	}

	private void locationRow(String offline) {
		label(LOCATION, DyeColor.PURPLE, "Location",
				"Teleport to · Bring here · Teleports · Logs · Block history");

		tile(LOCATION, 0, Nodes.TP, Items.ENDER_PEARL, "Teleport To", Theme.TEXT, offline,
				icon -> icon.lore("Go to where they are standing."),
				"go there", click -> {
					ServerPlayer p = online();
					if (p == null) return;
					Mods.teleport().toPlayer(viewer, p);
					viewer.sendSystemMessage(Theme.info("Teleported to " + target.name() + "."));
					viewer.closeContainer();
				});

		tile(LOCATION, 1, Nodes.TP_HERE, Items.LEAD, "Bring Here", Theme.TEXT, offline,
				icon -> icon.lore("Pull them to you.").warn("They will notice."),
				"bring them", click -> {
					ServerPlayer p = online();
					if (p == null) return;
					Mods.teleport().bringHere(viewer, p);
					p.sendSystemMessage(Theme.info("You were brought to a staff member."));
					viewer.sendSystemMessage(Theme.good("Brought " + target.name() + " to you."));
					viewer.closeContainer();
				});

		int teleports = Mods.teleport().log().forPlayer(target.id(), 200).size();
		tile(LOCATION, 2, Nodes.LOGS, Items.ENDER_EYE, "Teleport History", Theme.TEXT, null,
				icon -> icon.lore("Commands, pearls, portals and staff.")
						.field("On record", teleports >= 200 ? "200+" : String.valueOf(teleports)),
				"see where they went", click -> TeleportHistoryMenu.open(viewer, target));

		tile(LOCATION, 3, Nodes.LOGS, Items.RECOVERY_COMPASS, "Logs", Theme.TEXT, null,
				icon -> icon.lore("Joins, leaves and deaths, with coordinates."),
				"read them", click -> LogsMenu.open(viewer, target));

		tile(LOCATION, 4, Nodes.ROLLBACK, Items.IRON_PICKAXE, "Block History", Theme.TEXT, null,
				icon -> icon.lore("The grief log for them, around you."),
				"open the log", click -> GriefMenu.openFor(viewer, target.name()));
	}

	// --------------------------------------------------------------------- revokes

	private void confirmRevoke(boolean ban) {
		String what = ban ? "ban" : "mute";
		ConfirmMenu.open(viewer,
				"Lift " + what,
				Icon.head(target)
						.name("Lift " + what + " on " + target.name(), Theme.GOOD)
						.lore("They will be able to " + (ban ? "join again" : "talk again") + " immediately.")
						.build(),
				() -> {
					MinecraftServer server = Mc.server(viewer);
					if (server == null) return;
					int n = Mods.punish().revoke(server, target.id(), Mc.name(viewer), ban);
					if (n > 0) {
						viewer.sendSystemMessage(Theme.good("Lifted the " + what + " on " + target.name() + "."));
						Mods.alerts().onStaffAction(server,
								Mc.name(viewer) + " lifted a " + what + " on " + target.name());
						Sfx.bigSuccess(viewer);
					} else {
						viewer.sendSystemMessage(Theme.warn("There was no active " + what + " to lift."));
						Sfx.deny(viewer);
					}
					reopen(viewer, target);
				},
				() -> reopen(viewer, target));
	}

	// ---------------------------------------------------------------- status cards

	private ItemStack card(String what, Punishment p, Item item) {
		boolean isBan = what.equals("Ban");
		if (p == null) {
			return Icon.of(Mc.pane(DyeColor.LIME))
					.name(isBan ? "Not banned" : "Not muted", Theme.GOOD)
					.lore("No " + (isBan ? "ban" : "mute") + " in force.", Theme.MUTED)
					.build();
		}
		return Icon.of(item)
				.name(isBan ? "Banned" : "Muted", Theme.BAD)
				.field("Type", p.type().label())
				.field("Reason", p.reason())
				.field("By", p.staffName())
				.field("Issued", TimeFormat.ago(p.createdAt()))
				.field("Expires", p.remaining(), p.isPermanent() ? Theme.BAD : Theme.WARN)
				.glow()
				.build();
	}

	// -------------------------------------------------------------------- header

	private ItemStack headerIcon(ServerPlayer live, Punishment ban, Punishment mute) {
		Icon icon = Icon.head(target)
				.name(target.name(), Theme.ACCENT)
				.state(live != null, "Online", "Offline");

		if (live != null) {
			icon.field("Health", "%.0f / %.0f".formatted(live.getHealth(), live.getMaxHealth()))
					.field("World", live.level().dimension().identifier().getPath())
					.field("At", "%d, %d, %d".formatted(live.getBlockX(), live.getBlockY(), live.getBlockZ()));
		}

		icon.gap()
				.field("UUID", target.id().toString())
				.field("Punishments", String.valueOf(Mods.punish().historyCount(target.id())))
				.field("Notes", String.valueOf(Mods.notes().count(target.id())));

		String lastIp = Mods.identity().lastAddress(target.id());
		if (lastIp != null && Permissions.check(viewer, Nodes.ALTS)) {
			icon.field("Last address", lastIp);
		}

		int alts = Mods.identity().altCount(target.id());
		if (alts > 0 && Permissions.check(viewer, Nodes.ALTS)) {
			icon.field("Linked accounts", String.valueOf(alts), Theme.WARN);
		}

		if (ban != null) icon.warn("Banned — " + ban.reason());
		if (mute != null) icon.warn("Muted — " + mute.reason());

		return icon.build();
	}

	// -------------------------------------------------------------------- helpers

	/** The same coloured label at both ends of a row, naming what is in it. */
	private void label(int row, DyeColor colour, String name, String contents) {
		ItemStack label = Icon.of(Mc.pane(colour))
				.name(name, Theme.ACCENT)
				.lore(contents, Theme.MUTED)
				.build();
		set(row, label);
		set(row + 8, label.copy());
	}

	/**
	 * One button, in column {@code 2 + index} of its row, every one built the same way: a name,
	 * a line saying what it is, any counts, then what a click does.
	 * <p>
	 * A button stays in its place whatever happens. Without the node it says which one is
	 * missing; when it cannot be used right now — they are offline, there is no ban to lift —
	 * it says that instead of the click line. Two different failures, two different messages,
	 * and the grid never shifts under the cursor.
	 *
	 * @param unavailable why it cannot be used right now, or null when it can
	 */
	private void tile(int row, int index, String node, Item item, String name, int colour,
			String unavailable, Consumer<Icon> body, String does, Action run) {

		int slot = row + 2 + index;
		if (!Permissions.check(viewer, node)) {
			set(slot, Icon.of(item)
					.name(name, Theme.MUTED)
					.lore("Locked — needs " + node + ".", Theme.MUTED)
					.build());
			return;
		}

		Icon icon = Icon.of(item).name(name, unavailable == null ? colour : Theme.MUTED);
		body.accept(icon);
		icon.gap();

		if (unavailable != null) {
			set(slot, icon.warn(unavailable).build());
			return;
		}
		button(slot, icon.action("Click", does).build(), click -> {
			Sfx.click(viewer);
			run.run(click);
		});
	}
}
