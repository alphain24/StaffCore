package io.github.alphain24.staffcore.gui.menu;

import io.github.alphain24.staffcore.StaffCore;
import io.github.alphain24.staffcore.compat.Mc;
import io.github.alphain24.staffcore.gui.Gui;
import io.github.alphain24.staffcore.gui.Guis;
import io.github.alphain24.staffcore.gui.Icon;
import io.github.alphain24.staffcore.gui.Sfx;
import io.github.alphain24.staffcore.gui.Theme;
import io.github.alphain24.staffcore.module.Mods;
import io.github.alphain24.staffcore.modules.analytics.AnalyticsModule;
import io.github.alphain24.staffcore.permission.Nodes;
import io.github.alphain24.staffcore.permission.Permissions;
import io.github.alphain24.staffcore.util.TimeFormat;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The root screen: {@code /staff}.
 * <p>
 * Three bands, in the order a shift actually runs — what you switch on about yourself,
 * what you do to other people, what you do to the server. Anything the viewer lacks
 * permission for is drawn as a locked bar rather than hidden, so staff can see what
 * exists and ask for it instead of guessing the mod is broken.
 */
public class StaffPanelMenu extends Gui {

	private static final int SLOT_TITLE = 4;

	// Band 1 — you
	private static final int STAFF_MODE = 10;
	private static final int VANISH = 11;
	private static final int STAFF_CHAT = 12;
	private static final int ALERTS = 13;
	private static final int SPY = 14;
	private static final int RETURN = 15;
	private static final int MOVEMENT = 16;

	// Band 2 — people
	private static final int PLAYERS = 19;
	private static final int REPORTS = 20;
	private static final int PUNISH = 21;
	private static final int INVSEE = 22;
	private static final int NOTES = 23;
	private static final int HISTORY = 24;
	private static final int SECURITY = 25;
	private static final int CASES = 26;

	// Band 3 — server
	private static final int CONTROL = 28;
	private static final int GRIEF = 29;
	private static final int ANALYTICS = 30;
	private static final int SCANNER = 31;
	private static final int APPEALS = 32;
	private static final int DISCORD = 33;
	private static final int CONTRABAND = 34;

	// Band 4 — info
	private static final int SHIFT = 38;
	private static final int STATUS = 40;
	private static final int HELP = 42;

	private static final int CLOSE = 49;

	public static void open(ServerPlayer viewer) {
		Guis.open(viewer, Theme.title(), StaffPanelMenu::new);
	}

	/** Used when returning from a sub-menu — quieter, and keeps the audio direction honest. */
	public static void reopen(ServerPlayer viewer) {
		Guis.goBack(viewer, Theme.title(), StaffPanelMenu::new);
	}

	private StaffPanelMenu(int containerId, Inventory playerInventory, ServerPlayer viewer) {
		super(containerId, playerInventory, viewer, 6);
		render();
	}

	@Override
	protected void build() {
		MinecraftServer server = Mc.server(viewer);
		if (server == null) return;

		set(SLOT_TITLE, branding(server));

		buildPersonalBand();
		buildPeopleBand();
		buildServerBand();
		buildInfoBand(server);

		button(CLOSE, Theme.closeButton(), click -> viewer.closeContainer());
		fillEmpty(Theme.filler());
	}

	/** The title item: what this panel is, and the one number worth seeing first. */
	private ItemStack branding(MinecraftServer server) {
		int openReports = Mods.reports().openCount();

		Icon icon = Icon.of(Items.NETHER_STAR)
				.name("StaffCore", Theme.ACCENT)
				.lore("Everything this server's staff tools can do.")
				.gap()
				.field("Online", String.valueOf(server.getPlayerList().getPlayerCount()))
				.field("Staff on duty", String.valueOf(Mods.staffMode().activeCount()));

		if (openReports > 0) {
			icon.warn(openReports + " report(s) waiting");
		} else {
			icon.lore("● Nothing waiting", Theme.GOOD);
		}

		return icon.glow().build();
	}

	// ------------------------------------------------------------------ band one

	private void buildPersonalBand() {
		boolean onDuty = Mods.staffMode().isActive(viewer);
		gated(STAFF_MODE, Nodes.STAFF_MODE, Icon.of(Items.DIAMOND_CHESTPLATE)
				.name("Staff Mode", onDuty ? Theme.GOOD : Theme.TEXT)
				.lore("Stash your inventory and pick up the staff toolset.")
				.gap()
				.state(onDuty, "On duty", "Off duty")
				.action("Click", onDuty ? "clock off" : "clock on")
				.build(), onDuty, click -> {
			Mods.staffMode().toggle(viewer);
			render();
		});

		boolean hidden = Mods.vanish().isVanished(viewer);
		gated(VANISH, Nodes.VANISH, Icon.of(Items.ENDER_EYE)
				.name("Vanish", hidden ? Theme.GOOD : Theme.TEXT)
				.lore("Disappear from everyone without staff.vanish.")
				.gap()
				.state(hidden, "Invisible", "Visible")
				.action("Click", hidden ? "reappear" : "disappear")
				.build(), hidden, click -> {
			Mods.vanish().toggle(viewer);
			render();
		});

		boolean scOn = Mods.staffChat().isToggled(viewer);
		gated(STAFF_CHAT, Nodes.CHAT, Icon.of(Items.OAK_SIGN)
				.name("Staff Chat", scOn ? Theme.GOOD : Theme.TEXT)
				.lore("Route everything you type to staff only.")
				.gap()
				.state(scOn, "Channel on", "Channel off")
				.action("Click", "toggle")
				.lore("Or use /sc <message> for one line.")
				.build(), scOn, click -> {
			Mods.staffChat().toggle(viewer);
			render();
		});

		boolean alerts = Mods.alerts().isSubscribed(viewer);
		gated(ALERTS, Nodes.ALERTS, Icon.of(Items.BELL)
				.name("Alerts", alerts ? Theme.GOOD : Theme.TEXT)
				.lore("Reports, security flags and TPS warnings.")
				.gap()
				.state(alerts, "Subscribed", "Silenced")
				.action("Click", "toggle")
				.build(), alerts, click -> {
			Mods.alerts().toggle(viewer);
			render();
		});

		boolean spying = Mods.control().isSpy(viewer.getUUID());
		gated(SPY, Nodes.SPY, Icon.of(Items.SCULK_SENSOR)
				.name("Command Spy", spying ? Theme.GOOD : Theme.TEXT)
				.lore("See commands other players run.")
				.gap()
				.state(spying, "Listening", "Not listening")
				.action("Click", "toggle")
				.build(), spying, click -> {
			Mods.control().toggleSpy(viewer);
			render();
		});

		boolean onDutyNow = Mods.staffMode().isActive(viewer);
		boolean noclip = Mods.staffMode().isNoclip(viewer);
		int speed = Mods.staffMode().speedOf(viewer);
		gated(MOVEMENT, Nodes.STAFF_MODE, Icon.of(Items.FEATHER)
				.name("Movement", noclip ? Theme.GOOD : Theme.TEXT)
				.lore("Noclip and fly speed, while you are on duty.")
				.gap()
				.state(noclip, "Noclip on — walls are not solid", "Noclip off")
				.field("Fly speed", speed + "×")
				.gap()
				.action("Left-click", "toggle noclip")
				.action("Right-click", "cycle fly speed")
				.build(), noclip, click -> {
			if (!Mods.staffMode().isActive(viewer)) {
				viewer.sendSystemMessage(Theme.warn("Clock on first — these only apply on duty."));
				Sfx.deny(viewer);
				return;
			}
			if (click.isRight()) Mods.staffMode().cycleSpeed(viewer);
			else Mods.staffMode().toggleNoclip(viewer);
			render();
		});

		boolean canReturn = Mods.teleport().hasReturnPoint(viewer);
		gated(RETURN, Nodes.TP, Icon.of(Items.ENDER_PEARL)
				.name("Return", canReturn ? Theme.TEXT : Theme.MUTED)
				.lore("Go back to where your last teleport started.")
				.gap()
				.state(canReturn, "Return point set", "Nowhere to return to")
				.build(), false, click -> {
			if (!Mods.teleport().back(viewer)) {
				viewer.sendSystemMessage(Theme.warn("You have not teleported anywhere yet."));
				Sfx.deny(viewer);
				return;
			}
			viewer.closeContainer();
		});
	}

	// ------------------------------------------------------------------ band two

	private void buildPeopleBand() {
		MinecraftServer server = Mc.server(viewer);
		int online = server == null ? 0 : server.getPlayerList().getPlayerCount();

		gated(PLAYERS, Nodes.STAFF_GUI, Icon.of(Items.PLAYER_HEAD)
				.name("Online Players", Theme.TEXT)
				.field("Online", String.valueOf(online))
				.gap()
				.action("Click", "browse everyone and act on them")
				.build(), false, click -> PlayerListMenu.openForInspection(viewer));

		int open = Mods.reports().openCount();
		gated(REPORTS, Nodes.REPORT_VIEW, Icon.of(Items.PAPER)
				.name("Reports", open > 0 ? Theme.WARN : Theme.TEXT)
				.field("Waiting", String.valueOf(open), open > 0 ? Theme.WARN : Theme.MUTED)
				.gap()
				.action("Click", "open the queue")
				.count(Math.max(1, open))
				.build(), open > 0, click -> ReportsMenu.open(viewer));

		gated(PUNISH, Nodes.PUNISH, Icon.of(Items.NETHERITE_AXE)
				.name("Punish", Theme.BAD)
				.lore("Warn, kick, mute or ban a player.")
				.gap()
				.action("Click", "pick who")
				.build(), false, click ->
				PlayerListMenu.open(viewer, PlayerListMenu.Purpose.PUNISH));

		gated(INVSEE, Nodes.INVSEE, Icon.of(Items.CHEST)
				.name("Inventories", Theme.TEXT)
				.lore("Look inside what someone is carrying.")
				.gap()
				.action("Click", "pick who")
				.build(), false, click ->
				PlayerListMenu.open(viewer, PlayerListMenu.Purpose.INVSEE));

		gated(NOTES, Nodes.NOTES, Icon.of(Items.WRITABLE_BOOK)
				.name("Notes", Theme.TEXT)
				.lore("Sticky records that outlive your shift.")
				.gap()
				.action("Click", "pick who")
				.build(), false, click ->
				PlayerListMenu.open(viewer, PlayerListMenu.Purpose.NOTES));

		gated(HISTORY, Nodes.HISTORY, Icon.of(Items.BOOK)
				.name("History", Theme.TEXT)
				.lore("Every punishment a player has ever taken.")
				.gap()
				.action("Click", "pick who")
				.build(), false, click ->
				PlayerListMenu.open(viewer, PlayerListMenu.Purpose.HISTORY));

		gated(SECURITY, Nodes.SECURITY_CHECK, Icon.of(Items.SPYGLASS)
				.name("Security Check", Theme.TEXT)
				.lore("Impossible items, illegal enchants, x-ray patterns.")
				.gap()
				.action("Click", "pick who")
				.build(), false, click ->
				PlayerListMenu.open(viewer, PlayerListMenu.Purpose.SECURITY));

		// Beside the other people-shaped screens, because a case is about a person. Browsing
		// only: clicking one closes the panel and prints it into chat, where the actions live.
		int openCases = Mods.cases().openCount();
		gated(CASES, Nodes.STAFF_GUI, Icon.of(Items.WRITABLE_BOOK)
				.name("Cases", openCases > 0 ? Theme.WARN : Theme.TEXT)
				.field("Open", String.valueOf(openCases), openCases > 0 ? Theme.WARN : Theme.MUTED)
				.lore("Everything the detectors noticed, grouped by who.")
				.gap()
				.action("Click", "browse them")
				.count(Math.max(1, openCases))
				.build(), openCases > 0, click -> CasesMenu.open(viewer));
	}

	// ---------------------------------------------------------------- band three

	private void buildServerBand() {
		boolean locked = Mods.control().isChatMuted();
		boolean maintenance = Mods.control().isMaintenance();

		gated(CONTROL, Nodes.CHAT_CONTROL, Icon.of(Items.REDSTONE_TORCH)
				.name("Server Control", (locked || maintenance) ? Theme.WARN : Theme.TEXT)
				.lore("Chat lock, maintenance mode, broadcasts, TPS.")
				.gap()
				.state(!locked, "Chat open", "Chat locked")
				.state(!maintenance, "Open to all", "Maintenance mode")
				.build(), locked || maintenance, click -> ControlMenu.open(viewer));

		gated(GRIEF, Nodes.ROLLBACK, Icon.of(Items.TNT)
				.name("Grief Log", Theme.TEXT)
				.lore("What was broken and built around you, and by whom.")
				.gap()
				.action("Click", "inspect the area you are standing in")
				.build(), false, click -> GriefMenu.open(viewer));

		gated(ANALYTICS, Nodes.ANALYTICS, Icon.of(Items.MAP)
				.name("Analytics", Theme.TEXT)
				.lore("Who is working, and how much.")
				.gap()
				.action("Click", "open the leaderboard")
				.build(), false, click -> AnalyticsMenu.open(viewer));

		gated(SCANNER, Nodes.ITEMSCAN, Icon.of(Items.HOPPER)
				.name("Item Scanner", Theme.TEXT)
				.lore("Sweep every online player for impossible items.")
				.gap()
				.action("Click", "run a sweep now")
				.build(), false, click -> runSweep());

		int openAppeals = Mods.appeals().openCount();
		gated(APPEALS, Nodes.APPEALS, Icon.of(Items.PAPER)
				.name("Appeals", openAppeals > 0 ? Theme.WARN : Theme.TEXT)
				.field("Waiting", String.valueOf(openAppeals), openAppeals > 0 ? Theme.WARN : Theme.MUTED)
				.lore("What punished players have said in their defence.")
				.gap()
				.action("Click", "open the queue")
				.build(), openAppeals > 0, click -> AppealsMenu.open(viewer));

		boolean discord = Mods.discord().isConfigured();
		Icon discordIcon = Icon.of(Items.AMETHYST_SHARD)
				.name("Discord Bridge", discord ? Theme.GOOD : Theme.MUTED)
				.lore("Punishments, reports and alerts mirrored to a channel.")
				.gap()
				.state(discord, "Webhook configured", "No webhook set");
		if (!discord) {
			discordIcon.lore("Set discordWebhookUrl in config/staffcore.json.");
		}
		set(DISCORD, discordIcon.build());

		int held = Mods.security().vault().heldCount();
		gated(CONTRABAND, Nodes.VAULT, Icon.of(Items.BUNDLE)
				.name("Contraband", held > 0 ? Theme.WARN : Theme.TEXT)
				.field("Held", String.valueOf(held), held > 0 ? Theme.WARN : Theme.MUTED)
				.lore("What counts as contraband, and what has been taken.")
				.gap()
				.action("Left-click", "open the vault")
				.action("Right-click", "edit the rules")
				.build(), held > 0, click -> {
					if (click.isRight()) {
						ContrabandMenu.open(viewer);
					} else {
						VaultMenu.open(viewer);
					}
				});
	}

	// ----------------------------------------------------------------- band four

	private void buildInfoBand(MinecraftServer server) {
		AnalyticsModule.StaffStat me = Mods.analytics().forStaff(Mc.name(viewer));
		set(SHIFT, Icon.head(Mc.profile(viewer))
				.name("Your Record", Theme.ACCENT)
				.field("Punishments issued", String.valueOf(me.punishments()))
				.field("Reports handled", String.valueOf(me.reportsHandled()))
				.field("Commands logged", String.valueOf(me.commands()))
				.gap()
				.state(Mods.staffMode().isActive(viewer), "On duty right now", "Off duty")
				.build());

		double tps = Mods.control().currentTps(server);
		int brokenHooks = StaffCore.brokenFeatures().size();

		Icon status = Icon.of(Items.BEACON)
				.name("Server Status", Theme.ACCENT)
				.field("TPS", "%.2f".formatted(tps), Mods.control().tpsColor(tps))
				.field("Players", server.getPlayerList().getPlayerCount() + " / "
						+ server.getPlayerList().getMaxPlayers())
				.field("Staff on duty", String.valueOf(Mods.staffMode().activeCount()))
				.field("Vanished", String.valueOf(Mods.vanish().vanishedCount()))
				.field("Frozen", String.valueOf(Mods.freeze().frozenCount()))
				.field("Uptime", TimeFormat.duration(System.currentTimeMillis() - StaffCore.startedAt()));

		// A broken hook is invisible from inside the game — that is the whole problem with a
		// non-fatal mixin. Surfacing the count on the screen staff already look at means
		// nobody has to know the diagnostics screen exists in order to find out.
		if (brokenHooks > 0) {
			status.field("Broken features", String.valueOf(brokenHooks), Theme.BAD);
		}
		status.gap().action("Click", "open diagnostics");

		button(STATUS, status.build(), click -> DiagnosticsMenu.open(viewer));

		set(HELP, Icon.of(Items.ENCHANTED_BOOK)
				.name("How this works", Theme.MUTED)
				.paragraph("Everything here also has a command, and every command starts with "
						+ "/staff — tab-complete it and the whole list is there.", Theme.MUTED)
				.gap()
				.field("Panel", "/staff")
				.field("One player", "/staff <name>")
				.field("Duty", "/staff mode, /staff vanish")
				.field("People", "/staff ban, notes, history, invsee")
				.field("Server", "/staff reports, broadcast, rollback")
				.gap()
				.lore("Only /report and /sc sit outside the tree.")
				.build());
	}

	// --------------------------------------------------------------------- helpers

	/**
	 * Places a button if the viewer holds {@code node}, or a locked bar if not.
	 * {@code lit} adds the enchantment shimmer for anything currently switched on.
	 */
	private void gated(int slot, String node, ItemStack icon, boolean lit, Action action) {
		if (!Permissions.check(viewer, node)) {
			set(slot, Theme.lockedButton("Needs " + node));
			return;
		}
		ItemStack finished = lit
				? Icon.of(icon).glow().build()
				: icon;
		button(slot, finished, click -> {
			Sfx.click(viewer);
			action.run(click);
		});
	}

	private void runSweep() {
		MinecraftServer server = Mc.server(viewer);
		if (server == null) return;

		var hits = Mods.security().sweep(server);
		if (hits.isEmpty()) {
			viewer.sendSystemMessage(Theme.good("Item sweep clean — nothing impossible online."));
			Sfx.success(viewer);
			return;
		}
		viewer.sendSystemMessage(Theme.warn("Item sweep found " + hits.size() + " player(s) worth a look:"));
		hits.forEach(h -> viewer.sendSystemMessage(Theme.info("  " + h)));
		Sfx.alertPing(viewer);
	}

}
