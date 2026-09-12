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

	// Band 2 — sections. Everything that is a place to go rather than a switch to flip.
	private static final int SEC_PLAYERS = 19;
	private static final int SEC_PUNISH = 20;
	private static final int SEC_SECURITY = 21;
	private static final int SEC_XRAY = 22;
	private static final int SEC_WORLD = 23;
	private static final int SEC_SERVER = 24;
	private static final int SEC_DISCORD = 25;

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

	/** Shift-click on any back arrow: the panel, with the path behind it forgotten. */
	public static void home(ServerPlayer viewer) {
		Guis.home(viewer, Theme.title(), StaffPanelMenu::new);
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
		buildSectionBand();
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

	// ---------------------------------------------------------------- band two

	/**
	 * The sections.
	 *
	 * <h2>Why this replaced two rows of buttons</h2>
	 * The panel used to put fifteen destinations on one screen in two unlabelled bands. That is
	 * a list, not a structure: everything was one click away and nothing was findable, because
	 * the grouping only existed in the head of whoever chose the slot numbers.
	 * <p>
	 * Seven sections replace them, grouped by what somebody is trying to do rather than by
	 * which module implements it. See {@link StaffSections} for what went where and why.
	 * <p>
	 * The personal toggles above are deliberately <em>not</em> sectioned. They are the
	 * most-used buttons in the mod and they are actions rather than places, so putting them one
	 * click further away would be tidier and worse.
	 */
	private void buildSectionBand() {
		MinecraftServer server = Mc.server(viewer);
		int online = server == null ? 0 : server.getPlayerList().getPlayerCount();

		gated(SEC_PLAYERS, Nodes.STAFF_GUI, Icon.of(Items.PLAYER_HEAD)
				.name("Players", Theme.ACCENT)
				.lore("Files, inventories, notes, history, teleport, freeze.")
				.gap()
				.field("Online", String.valueOf(online))
				.build(), false, click -> StaffSections.players(viewer));

		int open = Mods.reports().openCount();
		gated(SEC_PUNISH, Nodes.PUNISH, Icon.of(Items.NETHERITE_AXE)
				.name("Punishments", open > 0 ? Theme.WARN : Theme.ACCENT)
				.lore("Punish, reports, appeals, history.")
				.gap()
				.field("Reports waiting", String.valueOf(open),
						open > 0 ? Theme.WARN : Theme.MUTED)
				.count(Math.max(1, open))
				.build(), open > 0, click -> StaffSections.punishments(viewer));

		int openCases = Mods.cases().openCount();
		gated(SEC_SECURITY, Nodes.SECURITY_CHECK, Icon.of(Items.SPYGLASS)
				.name("Security", openCases > 0 ? Theme.WARN : Theme.ACCENT)
				.lore("Checks, sweeps, the vault, and cases.")
				.gap()
				.field("Open cases", String.valueOf(openCases),
						openCases > 0 ? Theme.WARN : Theme.MUTED)
				.count(Math.max(1, openCases))
				.build(), openCases > 0, click -> StaffSections.security(viewer));

		// Its own section rather than part of Security, because this is the one area whose
		// answer depends on what other mods are installed \u2014 and the prevention status
		// belongs beside the detection whose meaning it changes.
		boolean preventing = io.github.alphain24.staffcore.modules.security.AntiXrayCompanion
				.present();
		gated(SEC_XRAY, Nodes.SECURITY_CHECK, Icon.of(Items.DIAMOND_ORE)
				.name("X-ray & cheats", Theme.ACCENT)
				.lore("Mining analysis, decoys, and what prevents x-ray.")
				.gap()
				.state(preventing, "Prevention installed", "Detection only")
				.build(), false, click -> StaffSections.antiCheat(viewer));

		gated(SEC_WORLD, Nodes.LOGS, Icon.of(Items.IRON_PICKAXE)
				.name("World", Theme.ACCENT)
				.lore("Grief log, rollbacks, restore points, inspect.")
				.build(), false, click -> StaffSections.world(viewer));

		boolean maintenance = Mods.control().isMaintenance();
		gated(SEC_SERVER, Nodes.CHAT_CONTROL, Icon.of(Items.LEVER)
				.name("Server", maintenance ? Theme.WARN : Theme.ACCENT)
				.lore("Chat, maintenance, broadcasts, analytics, status.")
				.gap()
				.state(!maintenance, "Open to all", "Maintenance mode")
				.build(), maintenance, click -> StaffSections.server(viewer));

		gated(SEC_DISCORD, Nodes.RELOAD, Icon.of(Items.ENDER_EYE)
				.name("Discord", Theme.ACCENT)
				.lore("What is posted, and whether the bridge is configured.")
				.build(), false, click -> StaffSections.discord(viewer));
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
}
