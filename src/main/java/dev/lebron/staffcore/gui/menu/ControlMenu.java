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
import dev.lebron.staffcore.util.TimeFormat;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Switches that affect everyone at once.
 * <p>
 * The two that change what other players can do — chat lock and maintenance mode — go
 * through a confirmation. Everything on this screen is visible to the whole server the
 * moment it is pressed, so a stray click should not be able to lock two hundred people
 * out of chat.
 */
public class ControlMenu extends Gui {

	private static final int HEADER = 4;

	private static final int CHAT_LOCK = 10;
	private static final int CHAT_CLEAR = 11;
	private static final int BROADCAST = 12;
	private static final int MAINTENANCE = 13;
	private static final int SPY = 14;
	private static final int TPS = 15;
	private static final int PLAYERS = 16;

	private static final int REPORTS = 20;
	private static final int GRIEF = 21;
	private static final int ANALYTICS = 23;
	private static final int SCANNER = 24;

	private static final int BACK = 36;
	private static final int CLOSE = 44;

	public static void open(ServerPlayer viewer) {
		Guis.navigate(viewer, Theme.title("Server Control"), ControlMenu::new);
	}

	static void reopen(ServerPlayer viewer) {
		Guis.silent(viewer, Theme.title("Server Control"), ControlMenu::new);
	}

	private ControlMenu(int containerId, Inventory playerInventory, ServerPlayer viewer) {
		super(containerId, playerInventory, viewer, 5);
		render();
	}

	@Override
	protected void build() {
		MinecraftServer server = Mc.server(viewer);
		if (server == null) return;

		set(HEADER, header());

		buildChatControls(server);
		buildServerControls(server);
		buildShortcuts();

		button(BACK, Theme.backButton("the staff panel"), click -> StaffPanelMenu.reopen(viewer));
		button(CLOSE, Theme.closeButton(), click -> viewer.closeContainer());
		fillEmpty(Theme.filler());
	}

	// ----------------------------------------------------------------------- chat

	private void buildChatControls(MinecraftServer server) {
		boolean locked = Mods.control().isChatMuted();

		gated(CHAT_LOCK, Nodes.CHAT_CONTROL, Icon.of(locked ? Items.BARRIER : Items.PAPER)
				.name("Chat Lock", locked ? Theme.BAD : Theme.TEXT)
				.lore("Stop everyone without control.chat from talking.")
				.gap()
				.state(!locked, "Chat is open", "Chat is locked")
				.action("Click", locked ? "unlock chat" : "lock chat")
				.build(), locked, click -> {
			if (locked) {
				Mods.control().setChatMuted(server, false);
				Sfx.toggleOff(viewer);
				render();
				return;
			}
			ConfirmMenu.open(viewer, "Lock chat",
					Icon.of(Items.BARRIER)
							.name("Lock chat for everyone", Theme.BAD)
							.field("Players affected", String.valueOf(server.getPlayerList().getPlayerCount()))
							.gap()
							.lore("Staff with control.chat can still talk.")
							.build(),
					() -> {
						Mods.control().setChatMuted(server, true);
						Mods.alerts().onStaffAction(server, Mc.name(viewer) + " locked chat");
						Sfx.bigSuccess(viewer);
						reopen(viewer);
					},
					() -> reopen(viewer));
		});

		gated(CHAT_CLEAR, Nodes.CHAT_CONTROL, Icon.of(Items.SPONGE)
				.name("Clear Chat", Theme.TEXT)
				.lore("Push the visible history off everyone's screen.")
				.gap()
				.lore("Staff with command spy on keep their view.")
				.action("Click", "clear it")
				.build(), false, click -> {
			Mods.control().clearChat(server, Mc.name(viewer));
			Mods.alerts().onStaffAction(server, Mc.name(viewer) + " cleared chat");
			Sfx.success(viewer);
		});

		gated(BROADCAST, Nodes.BROADCAST, Icon.of(Items.GOAT_HORN)
				.name("Broadcast", Theme.TEXT)
				.paragraph("Send a message to the whole server with a chime.", Theme.MUTED)
				.gap()
				.field("Command", "/staff broadcast <message>")
				.lore("Free text needs a command — there is nowhere to type here.")
				.build(), false, click -> {
			viewer.sendSystemMessage(Theme.info("Use /staff broadcast <message>."));
			Sfx.click(viewer);
		});
	}

	// --------------------------------------------------------------------- server

	private void buildServerControls(MinecraftServer server) {
		boolean maintenance = Mods.control().isMaintenance();

		gated(MAINTENANCE, Nodes.MAINTENANCE, Icon.of(Items.IRON_DOOR)
				.name("Maintenance Mode", maintenance ? Theme.WARN : Theme.TEXT)
				.lore("Only staff with control.maintenance can join.")
				.gap()
				.state(!maintenance, "Open to everyone", "Staff only")
				.action("Click", maintenance ? "reopen the server" : "close the server")
				.build(), maintenance, click -> {
			if (maintenance) {
				Mods.control().toggleMaintenance(server);
				Sfx.toggleOff(viewer);
				render();
				return;
			}
			ConfirmMenu.open(viewer, "Maintenance",
					Icon.of(Items.IRON_DOOR)
							.name("Close the server to non-staff", Theme.WARN)
							.field("Currently online", String.valueOf(server.getPlayerList().getPlayerCount()))
							.gap()
							.lore("Players already on stay on. New joins are refused.")
							.build(),
					() -> {
						Mods.control().toggleMaintenance(server);
						Mods.alerts().onStaffAction(server, Mc.name(viewer) + " enabled maintenance mode");
						Sfx.bigSuccess(viewer);
						reopen(viewer);
					},
					() -> reopen(viewer));
		});

		boolean spying = Mods.control().isSpy(viewer.getUUID());
		gated(SPY, Nodes.SPY, Icon.of(Items.SCULK_SENSOR)
				.name("Command Spy", spying ? Theme.GOOD : Theme.TEXT)
				.lore("Watch the commands other players run.")
				.gap()
				.state(spying, "Listening", "Not listening")
				.build(), spying, click -> {
			Mods.control().toggleSpy(viewer);
			render();
		});

		double tps = Mods.control().currentTps(server);
		set(TPS, Icon.of(Items.CLOCK)
				.name("Performance", Theme.ACCENT)
				.field("TPS", "%.2f".formatted(tps), Mods.control().tpsColor(tps))
				.field("Mean tick", "%.2f ms".formatted(Mc.meanTickMs(server)))
				.field("Uptime", TimeFormat.duration(
						System.currentTimeMillis() - dev.lebron.staffcore.StaffCore.startedAt()))
				.gap()
				.lore("Staff are alerted automatically when this drops.")
				.build());

		set(PLAYERS, Icon.of(Items.PLAYER_HEAD)
				.name("Population", Theme.ACCENT)
				.field("Online", server.getPlayerList().getPlayerCount() + " / "
						+ server.getPlayerList().getMaxPlayers())
				.field("Staff on duty", String.valueOf(Mods.staffMode().activeCount()))
				.field("Vanished", String.valueOf(Mods.vanish().vanishedCount()))
				.field("Frozen", String.valueOf(Mods.freeze().frozenCount()))
				.build());
	}

	// ------------------------------------------------------------------ shortcuts

	private void buildShortcuts() {
		int open = Mods.reports().openCount();
		gated(REPORTS, Nodes.REPORT_VIEW, Icon.of(Items.PAPER)
				.name("Reports", open > 0 ? Theme.WARN : Theme.TEXT)
				.field("Waiting", String.valueOf(open))
				.build(), open > 0, click -> ReportsMenu.open(viewer));

		gated(GRIEF, Nodes.ROLLBACK, Icon.of(Items.TNT)
				.name("Grief Log", Theme.TEXT)
				.lore("What changed around where you are standing.")
				.build(), false, click -> GriefMenu.open(viewer));

		gated(ANALYTICS, Nodes.ANALYTICS, Icon.of(Items.MAP)
				.name("Analytics", Theme.TEXT)
				.lore("Staff activity and totals.")
				.build(), false, click -> AnalyticsMenu.open(viewer));

		gated(SCANNER, Nodes.ITEMSCAN, Icon.of(Items.HOPPER)
				.name("Item Scanner", Theme.TEXT)
				.lore("Sweep everyone online for impossible items.")
				.build(), false, click -> {
			MinecraftServer server = Mc.server(viewer);
			if (server == null) return;
			var hits = Mods.security().sweep(server);
			if (hits.isEmpty()) {
				viewer.sendSystemMessage(Theme.good("Sweep clean."));
				Sfx.success(viewer);
			} else {
				viewer.sendSystemMessage(Theme.warn("Sweep flagged " + hits.size() + " player(s):"));
				hits.forEach(h -> viewer.sendSystemMessage(Theme.info("  " + h)));
				Sfx.alertPing(viewer);
			}
		});
	}

	// -------------------------------------------------------------------- helpers

	private ItemStack header() {
		return Icon.of(Items.REDSTONE_TORCH)
				.name("Server Control", Theme.ACCENT)
				.lore("Switches here are felt by everyone online.")
				.gap()
				.state(!Mods.control().isChatMuted(), "Chat open", "Chat locked")
				.state(!Mods.control().isMaintenance(), "Open to all", "Maintenance mode")
				.build();
	}

	private void gated(int slot, String node, ItemStack icon, boolean lit, Action action) {
		if (!Permissions.check(viewer, node)) {
			set(slot, Theme.lockedButton("Needs " + node));
			return;
		}
		button(slot, lit ? Icon.of(icon).glow().build() : icon, click -> {
			Sfx.click(viewer);
			action.run(click);
		});
	}
}
