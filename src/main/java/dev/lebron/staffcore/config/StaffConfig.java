package dev.lebron.staffcore.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.lebron.staffcore.StaffCore;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * config/staffcore.json — written with defaults on first run.
 * <p>
 * The preset lists drive the punishment GUI: staff never have to type a reason or a
 * duration, they click one. Add entries here and they show up as new buttons.
 */
public final class StaffConfig {

	/**
	 * What this file was last written by, so a changed default can reach an existing install.
	 * <p>
	 * Changing a field's initialiser only affects servers that have never run the mod.
	 * Everybody else has a file on disk holding the <em>old</em> default, and no amount of
	 * editing the Java changes it — which is exactly how "staff mode is creative now" shipped
	 * and then didn't happen for the one person who had already started the server once.
	 * <p>
	 * Bumped whenever a default changes in a way existing servers should inherit. Values a
	 * server owner has deliberately set are never touched; see {@link #migrate}.
	 */
	public int configVersion = 0;

	/** The version this build writes. Raise it when a default changes and should propagate. */
	private static final int CURRENT_VERSION = 2;

	// ---- discord -------------------------------------------------------------
	public String discordWebhookUrl = "";

	// ---- punishments ---------------------------------------------------------
	public boolean requireReason = true;
	/** Broadcast bans/mutes to the whole server rather than just to staff. */
	public boolean publicPunishmentBroadcast = true;

	public List<String> presetReasons = new ArrayList<>(List.of(
			"Cheating / unfair advantage",
			"Griefing",
			"Chat abuse / harassment",
			"Advertising",
			"Scamming",
			"Exploiting a bug",
			"Inappropriate skin or name",
			"Ban evasion"
	));

	public List<Duration> presetDurations = new ArrayList<>(List.of(
			new Duration("30 minutes", "30m"),
			new Duration("1 hour", "1h"),
			new Duration("6 hours", "6h"),
			new Duration("1 day", "1d"),
			new Duration("3 days", "3d"),
			new Duration("7 days", "7d"),
			new Duration("30 days", "30d"),
			new Duration("Permanent", "perm")
	));

	/** Offence ladders — the punish menu is built from this list. */
	public java.util.List<dev.lebron.staffcore.modules.punish.Offence> offences =
			dev.lebron.staffcore.modules.punish.Offence.defaults();

	// ---- staff mode ----------------------------------------------------------
	/**
	 * Gamemode while on duty: {@code survival}, {@code creative} or {@code spectator}.
	 * <p>
	 * Creative by default, because that is what being on duty actually needs: fly to the
	 * report, phase through the base you are inspecting, and put back the block you just
	 * proved was griefed. Survival keeps staff visible to anti-cheat and stops them being an
	 * item source, which is the safer choice on a server where staff are not fully trusted;
	 * spectator is the least intrusive but blocks interaction entirely, so the staff toolset
	 * stops working.
	 * <p>
	 * Whatever this is set to, the gamemode a staff member was in before clocking on is
	 * stored and given back when they clock off — so switching this does not strand anybody
	 * in the wrong mode.
	 */
	public String staffModeGameMode = "creative";
	/** Staff on duty cannot be hurt, vanished or not. */
	public boolean staffModeInvulnerable = true;

	// ---- grief ---------------------------------------------------------------
	/** Log container opens as well as block changes — most "griefing" is theft. */
	public boolean logContainerAccess = true;
	/**
	 * Remove the items a rollback's restored blocks originally dropped. Without this,
	 * every rollback is a duplication exploit.
	 */
	public boolean rollbackReclaimsDrops = true;
	/**
	 * Follow loot the offender banked in a chest outside the rollback radius.
	 * <p>
	 * Ground drops and a live inventory only cover somebody caught in the act. The obvious
	 * defeat is to carry the haul somewhere else and put it in a chest, which used to be out
	 * of reach — but the container log already records every stack put into every container,
	 * by whom and when, so it is not. Only that player's own deposits inside the same time
	 * window are touched, and only up to what is actually owed.
	 */
	public boolean rollbackChasesBankedLoot = true;
	/** Blocks broken inside the window before staff are alerted. 0 disables. */
	public int massGriefBlocks = 120;
	public int massGriefWindowSeconds = 20;

	// ---- vanish --------------------------------------------------------------
	/** Stop vanished staff picking up items they walk over. */
	public boolean vanishBlocksPickup = true;
	/** Suppress the join and leave messages of a player who was vanished. */
	public boolean vanishSilentJoin = true;
	/** Broadcast a real-looking "left the game" on vanish and "joined" on un-vanish. */
	public boolean vanishFakeMessages = true;
	/** Hide death, advancement and other server announcements about a vanished player. */
	public boolean vanishHidesAnnouncements = true;
	/** Leave vanished players out of /list, the player count and command selectors. */
	public boolean vanishHidesFromLists = true;
	/** Vanished players make no sound placing, breaking, walking or using items. */
	public boolean vanishSilent = true;
	/** Vanished players do not trigger plates, vibrations, collisions or pickups. */
	public boolean vanishIntangible = true;
	/** Mobs, spawners and raids behave as though vanished players are not there. */
	public boolean vanishIgnoredByMobs = true;
	/**
	 * Whether a vanished player still keeps chunks loaded around them.
	 * <p>
	 * Leaving this on is the safe default and what StaffCore has always done: vanished staff
	 * already stop chunks counting for mob spawning, so farms behave as though nobody is
	 * there, while the admin keeps a world they can actually see and fly through.
	 * <p>
	 * Turning it off makes vanish complete — a vanished player leaves no trace on the server
	 * at all — at a real cost: chunks nobody else is holding open will not load for you,
	 * so you will fly into unloaded void unless a real player is nearby. Worth it on a busy
	 * server where somebody is always in the area; a bad idea on a quiet one.
	 * <p>
	 * The change takes effect on the next chunk boundary you cross, not the instant you
	 * toggle vanish: tickets are registered when a player moves between chunk sections, and
	 * forcing a re-registration would mean reaching into the chunk manager for a setting
	 * that is off by default. In practice this is invisible, because anyone who cares about
	 * chunk loading is moving.
	 */
	public boolean vanishLoadsChunks = true;

	/** Items impossible to obtain in survival. Flagged as IMPOSSIBLE. */
	public java.util.List<String> illegalItems =
			dev.lebron.staffcore.modules.security.IllegalItems.creativeOnlyDefaults();
	/** Operator tooling. Flagged as IMPOSSIBLE and called out separately. */
	public java.util.List<String> operatorItems =
			dev.lebron.staffcore.modules.security.IllegalItems.operatorOnlyDefaults();
	/** Also scan ender chests during a security check. */
	public boolean scanEnderChests = true;
	/** Alert staff the moment a normal player is seen holding an illegal item. */
	public boolean watchContraband = true;
	/**
	 * Check the contents of chests, barrels and shulkers as players open and close them.
	 * <p>
	 * The inventory watch only ever sees what somebody is carrying, so the way to keep a
	 * banned item was to leave it in a chest. This closes that without polling the world:
	 * the grief log already snapshots every container anyone opens, and the check rides
	 * along on a copy it has taken anyway.
	 */
	public boolean watchContainers = true;

	/**
	 * Days of block history to keep. The log grows by roughly one row per block broken by
	 * anyone, so on a busy server this is the difference between a database you can query
	 * and one you cannot. 0 keeps everything.
	 */
	public int griefLogRetentionDays = 14;
	/**
	 * Hide ordinary mining from the grief log view by default.
	 * <p>
	 * Fifty players mining fills the log with stone within minutes and buries the one
	 * broken chest you are looking for. The rows are still written — the x-ray heuristic
	 * needs them — they are just filtered out of the view unless you ask for them.
	 */
	public boolean hideMiningNoise = true;
	/** Blocks treated as mining noise by the filter above. */
	public java.util.List<String> miningNoise = new java.util.ArrayList<>(java.util.List.of(
			"minecraft:stone", "minecraft:cobblestone", "minecraft:deepslate",
			"minecraft:cobbled_deepslate", "minecraft:dirt", "minecraft:grass_block",
			"minecraft:gravel", "minecraft:sand", "minecraft:sandstone", "minecraft:andesite",
			"minecraft:diorite", "minecraft:granite", "minecraft:tuff", "minecraft:netherrack",
			"minecraft:basalt", "minecraft:blackstone", "minecraft:end_stone",
			"minecraft:soul_sand", "minecraft:soul_soil", "minecraft:snow", "minecraft:ice",
			"minecraft:short_grass", "minecraft:tall_grass", "minecraft:fern", "minecraft:seagrass",
			"minecraft:kelp", "minecraft:kelp_plant", "minecraft:vine", "minecraft:water",
			"minecraft:lava", "minecraft:torch", "minecraft:wall_torch"
	));

	/**
	 * Days of inventory snapshots to keep. 0 keeps them for ever.
	 * <p>
	 * Snapshots now survive restarts, which means they also accumulate: one per death, per
	 * player, indefinitely. A month is long enough to settle any dispute anybody is still
	 * arguing about.
	 */
	public int snapshotRetentionDays = 30;

	/**
	 * How many snapshots to keep per player, oldest dropped first. 0 keeps every one.
	 * <p>
	 * Snapshots are evidence for a dispute, not an archive — but how much evidence a server
	 * wants to hold is its own call, so this is a number rather than a constant.
	 */
	public int maxSnapshotsPerPlayer = 10;

	/**
	 * Capture an inventory automatically when a player logs out.
	 * <p>
	 * "I logged off with it and came back without it" is the single most common inventory
	 * dispute, and the only moment that can answer it is one nobody is ever present for.
	 * <p>
	 * Deliberately event-driven rather than periodic. A snapshot every few minutes for every
	 * player would cost tens of thousands of rows a day and, worse, would keep evicting the
	 * snapshots that matter — automatic captures are ranked below deliberate ones for exactly
	 * that reason, so turning this on can never cost you a snapshot you would otherwise have
	 * had.
	 */
	public boolean autoSnapshotOnLogout = true;

	/**
	 * Capture before a staff member opens an inventory in edit mode.
	 * <p>
	 * Reaching into somebody else's inventory is the most abusable thing in the mod. The
	 * opening is already announced in staff chat; this makes it answerable as well, and
	 * protects the staff member as much as the player — "it was already missing" is a claim
	 * that needs evidence pointing either way.
	 */
	public boolean autoSnapshotOnStaffEdit = true;

	/**
	 * Capture before a rollback takes items back off somebody.
	 * <p>
	 * Rollback now debits an offender's inventory to stop repairs duplicating items. That is
	 * correct, and it is also the mod reaching into a player's inventory on the strength of a
	 * log query — which is exactly the kind of action that should leave a before-picture.
	 */
	public boolean autoSnapshotBeforeDebit = true;

	// ---- storage -------------------------------------------------------------
	/**
	 * How many database backups to keep. 0 disables backups entirely.
	 * <p>
	 * One is written on every server start, and rotated oldest-out. Everything else the mod
	 * does about corruption is damage control — setting a broken file aside, starting empty
	 * rather than refusing to boot — and none of it gets the data back. A copy does.
	 * <p>
	 * They are written with {@code VACUUM INTO}, so a backup is a coherent snapshot of
	 * committed state even if the server is busy. Copying the file by hand while the server
	 * runs gives you something that may be missing whatever was still in the write-ahead
	 * log, which is the sort of backup you find out is useless at the worst moment.
	 */
	public int databaseBackups = 5;

	// ---- ban evasion ---------------------------------------------------------
	/** Alert staff when a joining account shares an address with a banned one. */
	public boolean detectBanEvasion = true;
	/** Automatically ban the new account too. Off by default — shared houses are real. */
	public boolean autoBanEvaders = false;
	/**
	 * Also link accounts sharing an address <em>range</em> rather than an exact address.
	 * <p>
	 * Home addresses rotate. An account that was 203.0.113.40 last week and 203.0.113.91
	 * today is invisible to an exact match, which is most of what "a VPN defeats it" really
	 * meant. A /24 (IPv4) or /64 (IPv6) match is a much weaker signal than an exact one and
	 * is scored as such — it never reaches the confidence that an exact match does, and it
	 * can never trigger {@link #autoBanEvaders}.
	 */
	public boolean altSubnetMatching = true;
	/** Confidence below which a linked account is not worth telling staff about. */
	public int altMinConfidence = 40;

	// ---- appeals -------------------------------------------------------------
	/** Shown on the ban screen. Empty hides the appeal line entirely. */
	public String discordInvite = "";
	public boolean allowInGameAppeals = true;

	// ---- reports -------------------------------------------------------------
	public int reportCooldownSeconds = 60;

	// ---- security ------------------------------------------------------------
	/** Diamonds-per-stone ratio above which the x-ray heuristic fires. */
	public double xrayRatioThreshold = 0.04D;
	/**
	 * Ore-plus-filler blocks a player must have broken before the detector will say anything.
	 * <p>
	 * Lowered from 500. The old figure was defensible in isolation — thin data really does
	 * produce nonsense — but on any server that is not enormous, almost nobody clears 500
	 * blocks inside the six-hour window, so the sweep scored nobody and the whole feature
	 * looked broken. A detector that is never wrong because it never speaks is not a
	 * detector.
	 * <p>
	 * 200 is still enough that a handful of lucky finds cannot carry a score on their own.
	 */
	public int xraySampleFloor = 200;
	/** Mean filler blocks between veins below which mining looks guided. */
	public double xrayDirectnessFloor = 12.0D;
	/**
	 * Confidence at which staff are alerted automatically.
	 * <p>
	 * Lowered from 70. No single signal can reach this, so an alert still means at least two
	 * of ore fraction, directness, beelines and ancient debris agreed — which is the property
	 * worth keeping. 70 required nearly all of them at once and effectively never fired.
	 */
	public int xrayAlertConfidence = 55;
	/**
	 * Confidence at which staff get a quieter heads-up rather than an alert. 0 disables.
	 * <p>
	 * Exists because silence is indistinguishable from absence. Without this, a server owner
	 * has no way to tell "nobody is cheating" from "the detector has never once run" — and
	 * the second is what it looked like for a long time. A near miss is reported once, quietly,
	 * and says plainly that it is not an accusation.
	 */
	public int xrayNoticeConfidence = 35;
	/** How often the background sweep scores active miners, in minutes. 0 disables. */
	public int xraySweepMinutes = 5;
	/** Skip staff who are clocked on. Staff mining off-duty are still scored. */
	public boolean xraySkipStaffOnDuty = true;

	/**
	 * How long an unpaid rollback debt stands before it is written off, in days. 0 keeps it
	 * forever.
	 * <p>
	 * A debit stops a rollback duplicating items: the wall goes back up, so whoever knocked it
	 * down must not keep the blocks too. That reasoning holds while they still have them. Once
	 * a sweep of the ground, their inventory, their ender chest and the chests they filled has
	 * found nothing for a week, the likeliest explanation is that the items no longer exist —
	 * and charging them at that point takes items earned honestly since, to repay ones that
	 * were never duplicated.
	 */
	public int debtExpiryDays = 7;

	/**
	 * Take back items the staff member running a rollback had picked up at the scene.
	 * <p>
	 * Those drops came out of what is being restored, so leaving them in a pocket means the
	 * restore prints items. Bounded by the shortfall and by the item types involved, applied
	 * only to whoever ran the command, and a snapshot is taken first.
	 */
	public boolean rollbackReclaimsFromStaff = true;

	/**
	 * Record who picks items up off the ground.
	 * <p>
	 * This is what lets a rollback reach items somebody has already pocketed, and what makes
	 * recovery work at any distance: a query does not care whether the chunk is loaded or
	 * whether the drop still exists. Without it, recovery can only scan the ground nearby,
	 * which misses everything already taken and everything in an unloaded chunk.
	 * <p>
	 * The highest-volume table in the mod, which is why it is kept for hours rather than days.
	 */
	public boolean logItemPickups = true;
	/** How long pickups are kept, in minutes. Only has to outlive a scene, not a season. */
	public int pickupLogRetentionMinutes = 180;

	// ---- anti-cheat bridge ---------------------------------------------------
	/**
	 * Accept findings from an installed anti-cheat and surface them as staff alerts.
	 * <p>
	 * Off means findings are neither recorded nor alerted on — the provider carries on doing
	 * whatever it does, StaffCore simply stops listening.
	 */
	public boolean antiCheatBridge = true;
	/**
	 * Confidence a detection needs before it reaches the alert channel, 0-100.
	 * <p>
	 * Anti-cheats flag on thresholds that expect to be wrong sometimes, and every one of
	 * those going to staff chat trains people to scroll past the channel. Everything is
	 * recorded regardless and readable on the player's file; this only governs the shouting.
	 * Mitigations and punishments always alert — those already happened to somebody.
	 */
	public int antiCheatAlertConfidence = 60;
	/** How long to keep anti-cheat findings, in days. 0 keeps them forever. */
	public int antiCheatRetentionDays = 30;

	// ---- control -------------------------------------------------------------
	/** Fire a staff alert when TPS drops below this. */
	public double tpsAlertFloor = 17.0D;

	/**
	 * Shown in the server list while maintenance mode is on. Legacy formatting codes work:
	 * {@code §6} gold, {@code §l} bold, {@code §r} reset, {@code §7} grey. {@code \n} splits
	 * the two lines the server list gives you.
	 */
	public String maintenanceMotd =
			"§6§lSERVER IN MAINTENANCE\n§7Please wait while we update things — back shortly.";

	// ---- grief ---------------------------------------------------------------
	/** Default rollback window for the GUI button, in minutes. */
	public int defaultRollbackMinutes = 60;
	/**
	 * Days to keep rollback restore points, so a rollback can be undone. 0 disables undo.
	 * <p>
	 * Rollback is the most destructive thing here and was the only destructive thing with no
	 * way back — which mostly showed up as staff being reluctant to repair grief at all. A
	 * week is long enough for somebody to log in and notice their build is missing.
	 */
	public int rollbackPointRetentionDays = 7;

	public record Duration(String label, String spec) {}

	/** The configured duty gamemode, or null when the value is unrecognised. */
	public net.minecraft.world.level.GameType dutyGameMode() {
		if (staffModeGameMode == null) return net.minecraft.world.level.GameType.CREATIVE;
		for (net.minecraft.world.level.GameType type : net.minecraft.world.level.GameType.values()) {
			if (type.getName().equalsIgnoreCase(staffModeGameMode.trim())) return type;
		}
		return net.minecraft.world.level.GameType.CREATIVE;
	}

	// -------------------------------------------------------------- load / save

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static StaffConfig instance = new StaffConfig();

	public static StaffConfig get() {
		return instance;
	}

	private static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve("staffcore.json");
	}

	public static void load() {
		Path file = path();
		if (!Files.exists(file)) {
			instance = new StaffConfig();
			instance.configVersion = CURRENT_VERSION;
			save();
			StaffCore.LOGGER.info("[StaffCore] Wrote default config to {}", file);
			return;
		}
		try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			StaffConfig loaded = GSON.fromJson(r, StaffConfig.class);
			instance = loaded != null ? loaded : new StaffConfig();
			if (migrate(instance)) save();
		} catch (IOException | RuntimeException e) {
			StaffCore.LOGGER.error("[StaffCore] Config is unreadable - falling back to defaults", e);
			instance = new StaffConfig();
		}
	}

	/**
	 * Brings an older config forward when a default has changed.
	 * <p>
	 * The rule is that a migration only moves a value that still holds the <em>old default</em>.
	 * Anybody who deliberately chose that value keeps it — which does mean somebody who
	 * explicitly wanted the old default gets moved too, and that is the honest trade: there
	 * is no way to distinguish "never touched it" from "chose exactly this" in a plain JSON
	 * file, and silently ignoring the new default for everyone who ever started the server is
	 * the worse of the two failures. Every change is logged, so it is visible and reversible.
	 *
	 * @return true when anything changed and the file should be rewritten
	 */
	private static boolean migrate(StaffConfig cfg) {
		if (cfg.configVersion >= CURRENT_VERSION) return false;
		int from = cfg.configVersion;

		// v1: staff mode became creative by default. Being on duty means flying to the
		// report and putting back the block you just proved was griefed, and survival made
		// both of those awkward — with only the staff toolset in hand you cannot place
		// anything at all.
		if (from < 1 && "survival".equalsIgnoreCase(String.valueOf(cfg.staffModeGameMode).trim())) {
			cfg.staffModeGameMode = "creative";
			StaffCore.LOGGER.info(
					"[StaffCore] Config upgrade: staffModeGameMode survival -> creative. "
							+ "Set it back in config/staffcore.json if that was deliberate.");
		}

		// v2: the contraband list carried entries that match no item, so they protected
		// nothing while looking like protection. The worst of them was minecraft:spawn_egg,
		// which is not an item id — the eighty-eight real spawn eggs were never flagged by
		// the one entry meant to cover them all. Replaced with the #spawn_eggs rule, which
		// matches every one and stays right when the next mob is added.
		if (from < 2) {
			migrateIllegalItems(cfg);
		}

		cfg.configVersion = CURRENT_VERSION;
		StaffCore.LOGGER.info("[StaffCore] Config upgraded from v{} to v{}.", from, CURRENT_VERSION);
		return true;
	}

	/**
	 * Drops contraband entries that match nothing, and adds the spawn-egg rule.
	 * <p>
	 * Only removes names that are known to be dead — a name this build does not recognise
	 * could easily belong to a mod that happens to be uninstalled right now, and deleting
	 * somebody's rule because the mod behind it is temporarily absent would be its own bug.
	 */
	private static void migrateIllegalItems(StaffConfig cfg) {
		if (cfg.illegalItems == null) return;

		// Every one of these is a block with no item form, or an id that never existed.
		List<String> dead = List.of(
				"minecraft:spawn_egg", "minecraft:frosted_ice", "minecraft:bubble_column",
				"minecraft:nether_portal", "minecraft:end_portal", "minecraft:end_gateway");

		boolean hadSpawnEgg = cfg.illegalItems.contains("minecraft:spawn_egg");
		int before = cfg.illegalItems.size();
		cfg.illegalItems.removeIf(dead::contains);

		String rule = dev.lebron.staffcore.modules.security.IllegalItems.RULE_SPAWN_EGGS;
		if (hadSpawnEgg && !cfg.illegalItems.contains(rule)) {
			cfg.illegalItems.add(0, rule);
			StaffCore.LOGGER.info("[StaffCore] Config upgrade: minecraft:spawn_egg matched no "
					+ "item and never flagged anything - replaced with {}, which covers all "
					+ "of them.", rule);
		}

		int removed = before - cfg.illegalItems.size();
		if (removed > 0) {
			StaffCore.LOGGER.info("[StaffCore] Config upgrade: removed {} contraband entr(y/ies) "
					+ "that match no item.", removed);
		}
	}

	public static void save() {
		Path file = path();
		try {
			Files.createDirectories(file.getParent());
			try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
				GSON.toJson(instance, w);
			}
		} catch (IOException e) {
			StaffCore.LOGGER.error("[StaffCore] Could not write config", e);
		}
	}
}
