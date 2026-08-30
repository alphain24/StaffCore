package dev.lebron.staffcore.gui.menu;

import net.minecraft.server.players.NameAndId;
import dev.lebron.staffcore.compat.Mc;
import dev.lebron.staffcore.gui.Gui;
import dev.lebron.staffcore.gui.Guis;
import dev.lebron.staffcore.gui.Icon;
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

/**
 * One player's file: everything StaffCore knows about them and everything you can do.
 * <p>
 * Keyed on a {@link NameAndId} rather than a live player, because the person you most
 * need this screen for has usually just logged off. Actions that genuinely need them
 * present are drawn greyed with the reason, not silently missing.
 */
public class PlayerActionsMenu extends Gui {

	private static final int HEADER = 4;

	private static final int PUNISH = 10;
	private static final int HISTORY = 11;
	private static final int NOTES = 12;
	private static final int INVENTORY = 13;
	private static final int SNAPSHOTS = 14;
	private static final int SECURITY = 15;
	private static final int FREEZE = 16;

	private static final int TP_TO = 20;
	private static final int BRING = 21;
	private static final int REVOKE_BAN = 23;
	private static final int REVOKE_MUTE = 24;

	private static final int ENDERCHEST = 17;
	private static final int LOGS = 28;
	private static final int ALTS = 29;
	private static final int BAN_CARD = 30;
	private static final int MUTE_CARD = 32;
	private static final int GRIEF = 33;
	private static final int APPEALS = 34;

	private static final int BACK = 36;
	private static final int CLOSE = 44;

	private final NameAndId target;

	public static void open(ServerPlayer viewer, NameAndId target) {
		Guis.navigate(viewer, Theme.title(target.name()),
				(id, inv, v) -> new PlayerActionsMenu(id, inv, v, target));
	}

	static void reopen(ServerPlayer viewer, NameAndId target) {
		Guis.goBack(viewer, Theme.title(target.name()),
				(id, inv, v) -> new PlayerActionsMenu(id, inv, v, target));
	}

	private PlayerActionsMenu(int containerId, Inventory playerInventory, ServerPlayer viewer, NameAndId target) {
		super(containerId, playerInventory, viewer, 5);
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

		set(HEADER, headerIcon(live, ban, mute));

		action(PUNISH, Nodes.PUNISH, Icon.of(Items.NETHERITE_AXE)
				.name("Punish", Theme.BAD)
				.lore("Warn, kick, mute or ban.")
				.gap()
				.action("Click", "open the ladder")
				.build(), true, click -> PunishMenu.open(viewer, target));

		int punishments = Mods.punish().historyCount(target.id());
		action(HISTORY, Nodes.HISTORY, Icon.of(Items.BOOK)
				.name("History", Theme.TEXT)
				.field("Records", String.valueOf(punishments), punishments > 0 ? Theme.WARN : Theme.MUTED)
				.gap()
				.action("Click", "read every punishment")
				.build(), true, click -> HistoryMenu.open(viewer, target));

		int notes = Mods.notes().count(target.id());
		action(NOTES, Nodes.NOTES, Icon.of(Items.WRITABLE_BOOK)
				.name("Notes", Theme.TEXT)
				.field("Notes", String.valueOf(notes), notes > 0 ? Theme.WARN : Theme.MUTED)
				.gap()
				.action("Click", "read and write notes")
				.build(), true, click -> NotesMenu.open(viewer, target));

		action(INVENTORY, Nodes.INVSEE, Icon.of(Items.CHEST)
				.name("Inventory", Theme.TEXT)
				.lore(live == null
						? "Read from their save file."
						: Mods.inventory().usedSlots(live) + " slot(s) in use.")
				.gap()
				.action("Click", live == null ? "open a read-only view" : "open a live view")
				.build(), true, click -> InvseeMenu.open(viewer, target));

		int snaps = Mods.inventory().snapshotCount(target.id());
		action(SNAPSHOTS, Nodes.INVSEE, Icon.of(Items.ENDER_CHEST)
				.name("Snapshots", Theme.TEXT)
				.field("Stored", String.valueOf(snaps))
				.lore("Frozen copies of their inventory.")
				.gap()
				.action("Click", "browse snapshots")
				.build(), true, click -> SnapshotsMenu.open(viewer, target));

		action(SECURITY, Nodes.SECURITY_CHECK, Icon.of(Items.SPYGLASS)
				.name("Security Check", Theme.TEXT)
				.lore("Impossible items, illegal enchants, mining patterns.")
				.gap()
				.action("Click", "run the checks")
				.build(), live != null, click -> SecurityMenu.open(viewer, online()));

		boolean frozen = live != null && Mods.freeze().isFrozen(live);
		action(FREEZE, Nodes.FREEZE, Icon.of(Items.PACKED_ICE)
				.name("Freeze", frozen ? 0x8FD3FF : Theme.TEXT)
				.lore("Lock them where they stand so they cannot run.")
				.gap()
				.state(frozen, "Frozen", "Free to move")
				.action("Click", frozen ? "release them" : "freeze them")
				.build(), live != null, click -> {
			ServerPlayer p = online();
			if (p == null) return;
			boolean now = Mods.freeze().toggle(p);
			viewer.sendSystemMessage(now
					? Theme.good(target.name() + " is frozen.")
					: Theme.info(target.name() + " is free to move."));
			Sfx.success(viewer);
			render();
		});

		action(TP_TO, Nodes.TP, Icon.of(Items.ENDER_PEARL)
				.name("Teleport To", Theme.TEXT)
				.lore("Go to where they are standing.")
				.build(), live != null, click -> {
			ServerPlayer p = online();
			if (p == null) return;
			Mods.teleport().toPlayer(viewer, p);
			viewer.sendSystemMessage(Theme.info("Teleported to " + target.name() + "."));
			viewer.closeContainer();
		});

		action(BRING, Nodes.TP_HERE, Icon.of(Items.LEAD)
				.name("Bring Here", Theme.TEXT)
				.lore("Pull them to you.")
				.warn("They will notice.")
				.build(), live != null, click -> {
			ServerPlayer p = online();
			if (p == null) return;
			Mods.teleport().bringHere(viewer, p);
			p.sendSystemMessage(Theme.info("You were brought to a staff member."));
			viewer.sendSystemMessage(Theme.good("Brought " + target.name() + " to you."));
			viewer.closeContainer();
		});

		buildRevokes(ban, mute);
		buildIdentityBand(live);
		buildStatusCards(ban, mute);

		button(BACK, Theme.backButton("the player list"), click ->
				PlayerListMenu.openForInspection(viewer));
		button(CLOSE, Theme.closeButton(), click -> viewer.closeContainer());
		fillEmpty(Theme.filler());
	}

	// --------------------------------------------------------------------- revokes

	private void buildRevokes(Punishment ban, Punishment mute) {
		action(REVOKE_BAN, Nodes.UNPUNISH, Icon.of(Items.TOTEM_OF_UNDYING)
				.name("Lift Ban", ban != null ? Theme.GOOD : Theme.MUTED)
				.lore(ban != null ? "They are banned right now." : "They are not banned.")
				.gap()
				.action("Click", "lift it")
				.build(), ban != null, click -> confirmRevoke(true));

		action(REVOKE_MUTE, Nodes.UNPUNISH, Icon.of(Items.JUKEBOX)
				.name("Lift Mute", mute != null ? Theme.GOOD : Theme.MUTED)
				.lore(mute != null ? "They are muted right now." : "They are not muted.")
				.gap()
				.action("Click", "lift it")
				.build(), mute != null, click -> confirmRevoke(false));
	}

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

	/** Ender chest, logs, linked accounts, grief history and appeals. */
	private void buildIdentityBand(ServerPlayer live) {
		action(ENDERCHEST, Nodes.ENDERCHEST, Icon.of(Items.ENDER_CHEST)
				.name("Ender Chest", Theme.TEXT)
				.lore("Where anything worth hiding usually ends up.")
				.gap()
				.action("Click", "open it")
				.build(), live != null, click -> EnderChestMenu.open(viewer, target));

		action(LOGS, Nodes.LOGS, Icon.of(Items.WRITTEN_BOOK)
				.name("Logs", Theme.TEXT)
				.lore("Joins, leaves and deaths, with coordinates.")
				.gap()
				.action("Click", "read their history")
				.build(), true, click -> LogsMenu.open(viewer, target));

		int alts = Mods.identity().altCount(target.id());
		action(ALTS, Nodes.ALTS, Icon.of(Items.PLAYER_HEAD)
				.name("Linked Accounts", alts > 0 ? Theme.WARN : Theme.TEXT)
				.field("Sharing an address", String.valueOf(alts))
				.lore("A lead, not proof.")
				.gap()
				.action("Click", "review them")
				.build(), true, click -> AltsMenu.open(viewer, target));

		action(GRIEF, Nodes.ROLLBACK, Icon.of(Items.TNT)
				.name("Their block history", Theme.TEXT)
				.lore("Grief log filtered to this player, around you.")
				.gap()
				.action("Click", "open the log here")
				.build(), true, click -> GriefMenu.openFor(viewer, target.name()));

		int appeals = Mods.appeals().openCountFor(target.id());
		action(APPEALS, Nodes.APPEALS, Icon.of(Items.PAPER)
				.name("Appeals", appeals > 0 ? Theme.WARN : Theme.TEXT)
				.field("Open", String.valueOf(appeals))
				.lore("What they have said in their defence.")
				.gap()
				.action("Click", "read them")
				.build(), true, click -> AppealsMenu.openFor(viewer, target));
	}

	// ---------------------------------------------------------------- status cards

	private void buildStatusCards(Punishment ban, Punishment mute) {
		set(BAN_CARD, card("Ban status", ban, Items.NETHERITE_AXE));
		set(MUTE_CARD, card("Mute status", mute, Items.NOTE_BLOCK));
	}

	private ItemStack card(String label, Punishment p, net.minecraft.world.item.Item item) {
		if (p == null) {
			return Icon.of(Mc.pane(DyeColor.LIME))
					.name(label, Theme.GOOD)
					.lore("Nothing active.")
					.build();
		}
		return Icon.of(item)
				.name(label, Theme.BAD)
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

	/**
	 * Places an action, or an explanatory stand-in when the viewer lacks the node or the
	 * target is offline. Two different failures, two different messages — a staff member
	 * should never have to guess which one they hit.
	 */
	private void action(int slot, String node, ItemStack icon, boolean available, Action run) {
		if (!Permissions.check(viewer, node)) {
			set(slot, Theme.lockedButton("Needs " + node));
			return;
		}
		if (!available) {
			set(slot, Icon.of(icon)
					.gap()
					.warn(target.name() + " must be online for this.")
					.build());
			return;
		}
		button(slot, icon, click -> {
			Sfx.click(viewer);
			run.run(click);
		});
	}
}
