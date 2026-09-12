package io.github.alphain24.staffcore.gui.menu;

import io.github.alphain24.staffcore.gui.Icon;
import io.github.alphain24.staffcore.gui.Sfx;
import io.github.alphain24.staffcore.gui.Theme;
import io.github.alphain24.staffcore.module.Mods;
import io.github.alphain24.staffcore.permission.Nodes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * What lives under which section of the staff panel.
 *
 * <h2>Why the panel needed sections at all</h2>
 * It was twenty-five buttons on one screen in four unlabelled bands. Everything was one click
 * away, which sounds like a feature and is the problem: there was no answer to "where would I
 * find the container log", because the grouping existed only in the head of whoever placed the
 * slots. A flat list stops being navigable somewhere around a dozen entries and this was
 * double that.
 *
 * <h2>How things were grouped</h2>
 * By <b>what you are trying to do</b>, not by which module implements it. A staff member
 * chasing a griefed build does not care that the container log is part of the grief module and
 * the inventory snapshot is part of the inventory module; they care that both answer "what
 * happened to this chest".
 * <ul>
 *   <li><b>Players</b> — everything about one person: their file, inventory, notes, history,
 *       linked accounts, snapshots.</li>
 *   <li><b>Punishments</b> — acting on somebody, and everything that follows from it: the
 *       ladder, reports that ask for it, appeals that contest it.</li>
 *   <li><b>Security</b> — is this player's stuff possible, and the case model that collects the
 *       answers. Cases live here rather than under Players because a case is about a
 *       <em>suspicion</em>, and every subsystem that raises one reports into this section.</li>
 *   <li><b>X-ray &amp; cheats</b> — its own section rather than part of Security, because it is
 *       the one area where the server's answer depends on what <em>other</em> mods are
 *       installed. The anti-xray companion status belongs next to the detection it changes the
 *       meaning of.</li>
 *   <li><b>World</b> — what happened to the blocks: the grief log, rollbacks, and the way back
 *       from a rollback.</li>
 *   <li><b>Server</b> — the machine rather than the players: chat control, maintenance,
 *       analytics, diagnostics.</li>
 *   <li><b>Discord</b> — its own section because it is a separate surface with its own
 *       permissions and its own failure modes, not a feature of anything above.</li>
 * </ul>
 *
 * <h2>What is deliberately not here</h2>
 * The personal toggles — staff mode, vanish, staff chat, alerts, command spy, back, noclip.
 * They stay on the panel itself. They are the most-used buttons in the mod and they are
 * one-click actions rather than places to go, so filing them under a section would be tidier
 * and worse.
 */
public final class StaffSections {
	private StaffSections() {}

	// ------------------------------------------------------------------- players

	public static void players(ServerPlayer viewer) {
		SectionMenu.open(viewer, "Players", "Everything about one person.", List.of(
				SectionMenu.Entry.of(Nodes.STAFF_GUI, Icon.of(Items.PLAYER_HEAD)
						.name("Player list", Theme.ACCENT)
						.lore("Everyone online. Click one to open their file.")
						.build(),
						v -> PlayerListMenu.open(v, PlayerListMenu.Purpose.INSPECT)),

				SectionMenu.Entry.of(Nodes.INVSEE, Icon.of(Items.CHEST)
						.name("Inventories", Theme.ACCENT)
						.lore("Look in somebody's inventory or ender chest.")
						.lore("Works on players who are offline.", Theme.MUTED)
						.build(),
						v -> PlayerListMenu.open(v, PlayerListMenu.Purpose.INVSEE)),

				SectionMenu.Entry.of(Nodes.NOTES, Icon.of(Items.WRITABLE_BOOK)
						.name("Notes", Theme.ACCENT)
						.lore("What the last person on shift wanted you to know.")
						.build(),
						v -> PlayerListMenu.open(v, PlayerListMenu.Purpose.NOTES)),

				SectionMenu.Entry.of(Nodes.HISTORY, Icon.of(Items.BOOK)
						.name("Punishment history", Theme.ACCENT)
						.lore("Every warn, mute, kick and ban a player has had.")
						.build(),
						v -> PlayerListMenu.open(v, PlayerListMenu.Purpose.HISTORY)),

				SectionMenu.Entry.of(Nodes.TP, Icon.of(Items.ENDER_PEARL)
						.name("Teleport", Theme.ACCENT)
						.lore("Go to a player.")
						.build(),
						v -> PlayerListMenu.open(v, PlayerListMenu.Purpose.TELEPORT)),

				SectionMenu.Entry.of(Nodes.FREEZE, Icon.of(Items.PACKED_ICE)
						.name("Freeze", Theme.ACCENT)
						.lore("Stop somebody moving while you talk to them.")
						.build(),
						v -> PlayerListMenu.open(v, PlayerListMenu.Purpose.FREEZE)),

				// Under Players rather than Security, because it answers "what was this
				// person doing" rather than "is this person cheating". The x-ray replay is
				// the other one, and it lives under X-ray because it is about a dig.
				SectionMenu.Entry.of(Nodes.REPLAY, Icon.of(Items.RECOVERY_COMPASS)
						.name("Session replay", Theme.ACCENT)
						.lore("Watch where somebody went, from inside their path.")
						.lore("Needs positionTracking on; it does not fill in", Theme.MUTED)
						.lore("the past.", Theme.MUTED)
						.build(),
						v -> PlayerListMenu.open(v, PlayerListMenu.Purpose.REPLAY))));
	}

	// --------------------------------------------------------------- punishments

	public static void punishments(ServerPlayer viewer) {
		int open = Mods.reports().openCount();

		SectionMenu.open(viewer, "Punishments", "Acting on somebody, and what follows.", List.of(
				SectionMenu.Entry.of(Nodes.PUNISH, Icon.of(Items.IRON_SWORD)
						.name("Punish a player", Theme.ACCENT)
						.lore("The offence ladder, or set it by hand.")
						.build(),
						v -> PlayerListMenu.open(v, PlayerListMenu.Purpose.PUNISH)),

				SectionMenu.Entry.of(Nodes.REPORT_VIEW, Icon.of(open > 0
								? Items.WRITTEN_BOOK : Items.PAPER)
						.name("Reports", open > 0 ? Theme.WARN : Theme.ACCENT)
						.lore("What players have asked staff to look at.")
						.gap()
						.field("Waiting", String.valueOf(open))
						.build(),
						ReportsMenu::open),

				SectionMenu.Entry.of(Nodes.APPEALS, Icon.of(Items.NAME_TAG)
						.name("Appeals", Theme.ACCENT)
						.lore("Punished players contesting it.")
						.build(),
						AppealsMenu::open),

				SectionMenu.Entry.of(Nodes.HISTORY, Icon.of(Items.BOOK)
						.name("History", Theme.ACCENT)
						.lore("Look up what a player has already had.")
						.build(),
						v -> PlayerListMenu.open(v, PlayerListMenu.Purpose.HISTORY))));
	}

	// -------------------------------------------------------------------- security

	public static void security(ServerPlayer viewer) {
		SectionMenu.open(viewer, "Security", "Is this player's stuff possible?", List.of(
				SectionMenu.Entry.of(Nodes.SECURITY_CHECK, Icon.of(Items.SPYGLASS)
						.name("Check a player", Theme.ACCENT)
						.lore("Run every passive check against one person.")
						.build(),
						v -> PlayerListMenu.open(v, PlayerListMenu.Purpose.SECURITY)),

				SectionMenu.Entry.of(Nodes.ITEMSCAN, Icon.of(Items.REDSTONE_TORCH)
						.name("Sweep everyone", Theme.ACCENT)
						.lore("Scan every online player for impossible items.")
						.lore("Reports in chat; changes nothing.", Theme.MUTED)
						.build(),
						StaffSections::sweep),

				SectionMenu.Entry.of(Nodes.VAULT, Icon.of(Items.BUNDLE)
						.name("Contraband vault", Theme.ACCENT)
						.lore("What has been confiscated, and who from.")
						.lore("Nothing is destroyed; it is held here.", Theme.MUTED)
						.build(),
						VaultMenu::open),

				SectionMenu.Entry.of(Nodes.SECURITY_CHECK, Icon.of(Items.PAPER)
						.name("Cases", Theme.ACCENT)
						.lore("Open investigations, and the signals behind them.")
						.lore("Every detector reports into this.", Theme.MUTED)
						.build(),
						CasesMenu::open),

				SectionMenu.Entry.of(Nodes.CONTRABAND_EDIT, Icon.of(Items.BARRIER)
						.name("Contraband rules", Theme.ACCENT)
						.lore("Which items count as impossible.")
						.lore("Click an item to ban it; no typing ids.", Theme.MUTED)
						.build(),
						ContrabandMenu::open),

				SectionMenu.Entry.of(Nodes.ALTS, Icon.of(Items.NETHERITE_SCRAP)
						.name("Linked accounts", Theme.ACCENT)
						.lore("Accounts sharing an address with somebody.")
						.lore("Pick a player, then open their file.", Theme.MUTED)
						.lore("A lead, never a verdict.", Theme.MUTED)
						.build(),
						v -> PlayerListMenu.open(v, PlayerListMenu.Purpose.INSPECT))));
	}

	// ------------------------------------------------------------- xray & cheats

	public static void antiCheat(ServerPlayer viewer) {
		boolean preventing = io.github.alphain24.staffcore.modules.security.AntiXrayCompanion
				.present();
		boolean decoys = io.github.alphain24.staffcore.modules.security.Canaries.enabled();

		SectionMenu.open(viewer, "X-ray & cheats",
				"Detection after the fact, and what prevents it.", List.of(
				SectionMenu.Entry.of(Nodes.SECURITY_CHECK, Icon.of(preventing
								? Items.EMERALD_BLOCK : Items.REDSTONE_BLOCK)
						.name("Prevention", preventing ? Theme.GOOD : Theme.WARN)
						.lore(preventing
								? "An anti-xray mod is installed and hiding real ore."
								: "No anti-xray installed.")
						.lore(preventing ? "" : "StaffCore detects x-ray after the fact and",
								Theme.MUTED)
						.lore(preventing ? "" : "does not prevent it.", Theme.MUTED)
						.gap()
						.lore("Decoys and bulk anti-xray do not run together;", Theme.MUTED)
						.lore("see the decoy screen for why.", Theme.MUTED)
						.build(),
						v -> v.sendSystemMessage(Theme.info(
								io.github.alphain24.staffcore.modules.security
										.AntiXrayCompanion.startupLine()))),

				SectionMenu.Entry.of(Nodes.SECURITY_CHECK, Icon.of(Items.DIAMOND_ORE)
						.name("X-ray report", Theme.ACCENT)
						.lore("Score one player's mining against chance.")
						.lore("A p-value, not a hunch \u2014 and offline players", Theme.MUTED)
						.lore("can be scored too.", Theme.MUTED)
						.build(),
						v -> PlayerListMenu.open(v, PlayerListMenu.Purpose.XRAY)),

				SectionMenu.Entry.of(Nodes.SECURITY_CHECK, Icon.of(decoys
								? Items.SCULK_SENSOR : Items.SCULK)
						.name("Decoy blocks", decoys ? Theme.ACCENT : Theme.MUTED)
						.lore("Fake ore only an x-ray client can see.")
						.gap()
						.state(decoys, "Running", "Off")
						.build(),
						CanaryMenu::open),

				SectionMenu.Entry.of(Nodes.ANALYTICS, Icon.of(Items.WRITTEN_BOOK)
						.name("Threshold evidence", Theme.ACCENT)
						.lore("What the detection thresholds are justified against.")
						.lore("Cleared cases, and why each was cleared.", Theme.MUTED)
						.build(),
						v -> {
							v.sendSystemMessage(Theme.info(
									io.github.alphain24.staffcore.modules.cases.TrainingCorpus
											.composition().describe()));
							v.closeContainer();
						})));
	}

	// ----------------------------------------------------------------------- world

	public static void world(ServerPlayer viewer) {
		SectionMenu.open(viewer, "World", "What happened to the blocks.", List.of(
				SectionMenu.Entry.of(Nodes.LOGS, Icon.of(Items.IRON_PICKAXE)
						.name("Grief log", Theme.ACCENT)
						.lore("Who broke it, who placed it, who opened it.")
						.build(),
						GriefMenu::open),

				SectionMenu.Entry.of(Nodes.ROLLBACK, Icon.of(Items.CLOCK)
						.name("Restore points", Theme.ACCENT)
						.lore("Undo a rollback you ran with the wrong radius.")
						.lore("Kept seven days.", Theme.MUTED)
						.build(),
						RestorePointsMenu::open),

				SectionMenu.Entry.of(Nodes.INSPECT_MODE, Icon.of(Items.STICK)
						.name("Inspect mode", Theme.ACCENT)
						.lore("Click any block to read its history.")
						.lore("/staff inspect toggles it.", Theme.MUTED)
						.build(),
						v -> v.sendSystemMessage(Theme.info(
								"Use /staff inspect, then click a block.")))));
	}

	// ---------------------------------------------------------------------- server

	public static void server(ServerPlayer viewer) {
		SectionMenu.open(viewer, "Server", "The machine rather than the players.", List.of(
				SectionMenu.Entry.of(Nodes.CHAT_CONTROL, Icon.of(Items.LEVER)
						.name("Control", Theme.ACCENT)
						.lore("Chat lock, maintenance mode, broadcasts.")
						.build(),
						ControlMenu::open),

				SectionMenu.Entry.of(Nodes.ANALYTICS, Icon.of(Items.MAP)
						.name("Analytics", Theme.ACCENT)
						.lore("Who is on, how often, and for how long.")
						.build(),
						AnalyticsMenu::open),

				SectionMenu.Entry.of(null, Icon.of(Items.COMPARATOR)
						.name("Server status", Theme.ACCENT)
						.lore("Hooks, storage, and what is not working.")
						.build(),
						DiagnosticsMenu::open)));
	}

	// --------------------------------------------------------------------- discord

	public static void discord(ServerPlayer viewer) {
		boolean configured = Mods.discord().isConfigured();

		SectionMenu.open(viewer, "Discord", "The other surface staff act from.", List.of(
				SectionMenu.Entry.of(Nodes.RELOAD, Icon.of(configured
								? Items.ENDER_EYE : Items.ENDER_PEARL)
						.name("Webhook", configured ? Theme.GOOD : Theme.MUTED)
						.lore(configured
								? "Configured. Punishments and reports are posted."
								: "Not configured. Nothing is being posted.")
						.gap()
						.lore("Set discordWebhookUrl in config/staffcore.json.", Theme.MUTED)
						.build(),
						v -> v.sendSystemMessage(configured
								? Theme.good("Discord webhook is configured.")
								: Theme.warn("No discordWebhookUrl set, so nothing is posted."))),

				// Named rather than left blank. An empty section reads as a missing feature,
				// and what is actually true is that the bridge is one-way so far.
				SectionMenu.Entry.of(null, Icon.of(Items.PAPER)
						.name("What is posted", Theme.ACCENT)
						.lore("Punishments, reports and security flags go out.")
						.lore("Nothing comes back in yet — the bridge is one-way.",
								Theme.MUTED)
						.gap()
						.lore("Staff addresses and session data never leave the", Theme.MUTED)
						.lore("server, in any embed, export or log line.", Theme.MUTED)
						.build(),
						v -> v.sendSystemMessage(Theme.info(
								"Discord receives punishments, reports and security flags. "
										+ "Addresses and session data are never sent.")))));
	}

	// --------------------------------------------------------------------- helpers

	/** The item sweep, reported in chat because its result is a list rather than a screen. */
	private static void sweep(ServerPlayer viewer) {
		MinecraftServer server = io.github.alphain24.staffcore.compat.Mc.server(viewer);
		if (server == null) return;

		var hits = Mods.security().sweep(server);
		if (hits.isEmpty()) {
			viewer.sendSystemMessage(Theme.good("Item sweep clean — nothing impossible online."));
			Sfx.success(viewer);
			return;
		}
		viewer.sendSystemMessage(Theme.warn(
				"Item sweep found " + hits.size() + " player(s) worth a look:"));
		hits.forEach(h -> viewer.sendSystemMessage(Theme.info("  " + h)));
		Sfx.alertPing(viewer);
	}
}
