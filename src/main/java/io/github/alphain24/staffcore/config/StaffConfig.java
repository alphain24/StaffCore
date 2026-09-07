package io.github.alphain24.staffcore.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.github.alphain24.staffcore.StaffCore;
import io.github.alphain24.staffcore.modules.security.XrayTuning;
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
	private static final int CURRENT_VERSION = 3;

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
	public java.util.List<io.github.alphain24.staffcore.modules.punish.Offence> offences =
			io.github.alphain24.staffcore.modules.punish.Offence.defaults();

	// ---- staff mode ----------------------------------------------------------
	/**
	 * Gamemode while on duty: {@code survival}, {@code creative} or {@code spectator}.
	 * <p>
	 * <b>Survival by default.</b> Creative was the old default and it is the reason four
	 * separate containment layers exist: staff tools are refused on drop, stripped on death,
	 * destroyed if they reach the ground, and swept every two seconds — all of it work to stop
	 * an unlimited item source leaking into the economy. Survival removes the source rather
	 * than containing it, and the layers stay as defence in depth.
	 * <p>
	 * Very little is actually lost. Flight comes from being on duty, not from creative, so
	 * staff still fly to a report; noclip is its own toggle and still works; and putting back
	 * a griefed block is what rollback is for. What goes is the ability to conjure items,
	 * which is exactly the part that needed containing.
	 * <p>
	 * <b>Creative remains available</b> and costs three things worth knowing about: staff
	 * become an unlimited item source, so every item they hand out is outside the economy and
	 * indistinguishable from a duplication bug; they stop looking like ordinary players to an
	 * anti-cheat, so their own behaviour is no longer checked; and the containment layers
	 * become load-bearing rather than belt-and-braces.
	 * <p>
	 * <b>Spectator</b> is the least intrusive of the three, and the staff toolset stops
	 * working under it: the tools are items used by right-clicking, and vanilla drops
	 * interactions for spectators before they reach any of this mod's code. The chest menus
	 * still work, so {@code /staff} remains usable.
	 * <p>
	 * Whatever this is set to, the gamemode a staff member was in before clocking on is
	 * stored and given back when they clock off — so switching this does not strand anybody
	 * in the wrong mode.
	 */
	public String staffModeGameMode = "survival";
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
	public boolean rollbackChasesBankedLoot = false;
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
			io.github.alphain24.staffcore.modules.security.IllegalItems.creativeOnlyDefaults();
	/** Operator tooling. Flagged as IMPOSSIBLE and called out separately. */
	public java.util.List<String> operatorItems =
			io.github.alphain24.staffcore.modules.security.IllegalItems.operatorOnlyDefaults();
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
	 * <p>
	 * <b>No longer switches anything off.</b> Every inventory mutation now goes through
	 * {@code InventoryGateway}, which snapshots unconditionally: a door with an exemption is
	 * not a door. Kept so existing config files still load, and honest about doing nothing.
	 */
	@Deprecated
	public boolean autoSnapshotOnStaffEdit = true;

	/**
	 * Capture before a rollback takes items back off somebody.
	 * <p>
	 * Rollback now debits an offender's inventory to stop repairs duplicating items. That is
	 * correct, and it is also the mod reaching into a player's inventory on the strength of a
	 * log query — which is exactly the kind of action that should leave a before-picture.
	 * <p>
	 * <b>No longer switches anything off</b>, for the same reason as
	 * {@link #autoSnapshotOnStaffEdit}: the gateway snapshots every mutation whatever this
	 * says. Kept so existing config files still load.
	 */
	@Deprecated
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
	/**
	 * Also link accounts sharing an address <em>range</em> rather than an exact address.
	 * <p>
	 * Home addresses rotate. An account that was 203.0.113.40 last week and 203.0.113.91
	 * today is invisible to an exact match, which is most of what "a VPN defeats it" really
	 * meant. A /24 (IPv4) or /64 (IPv6) match is a much weaker signal than an exact one and
	 * is scored as such — it never reaches the confidence that an exact match does, and it
	 * is never enough on its own to act on.
	 */
	public boolean altSubnetMatching = true;
	/**
	 * How long connection records are kept, in days. 0 keeps them forever.
	 * <p>
	 * An address is the only personal data this mod stores. Everything else it records is
	 * conduct on the server, which belongs to the server in a way somebody's home address
	 * does not — and it was the one category with no retention limit while grief logs,
	 * snapshots, anti-cheat findings and debts all had one.
	 * <p>
	 * Ninety days is long enough for alt detection to be useful, since evasion happens within
	 * days of a ban rather than months.
	 */
	public int connectionRetentionDays = 90;

	/**
	 * Store addresses as one-way hashes rather than in the clear.
	 * <p>
	 * Costs nothing, because nothing here needs to read an address — only to know whether two
	 * accounts used the same one, which is an equality test and survives hashing exactly.
	 * Both matching passes still work: exact matching compares the address hash, range
	 * matching compares a separately hashed prefix.
	 * <p>
	 * The hash is salted with a value generated once per server and kept in the database.
	 * IPv4 is 32 bits, so an unsalted digest of every possible address can be built in
	 * seconds and would be an encoding rather than a hash.
	 * <p>
	 * Turning this on converts anything already stored, so the table cannot end up half in
	 * each form — which would quietly stop two accounts matching because one row predates
	 * the change.
	 */
	public boolean hashConnectionAddresses = true;

	/** Confidence below which a linked account is not worth telling staff about. */
	public int altMinConfidence = 40;

	// ---- appeals -------------------------------------------------------------
	/** Shown on the ban screen. Empty hides the appeal line entirely. */
	public String discordInvite = "";
	public boolean allowInGameAppeals = true;

	// ---- reports -------------------------------------------------------------
	public int reportCooldownSeconds = 60;

	/**
	 * Register {@code /ban}, {@code /kick}, {@code /vanish} and friends at the root.
	 * <p>
	 * Off by default, and the default is the interesting half. One entry point avoids
	 * collisions outright, which is why it is the way round it is — but staff muscle memory
	 * is {@code /ban}, not {@code /staff ban}, and telling somebody their reflexes are wrong
	 * is not a design.
	 * <p>
	 * When this is on, each alias is registered <em>only</em> if nothing else has claimed it,
	 * and the ones that were skipped are logged by name. Quietly overwriting another mod's
	 * {@code /ban} would be worse than not offering the alias at all: the command would still
	 * work and would do something other than what the person typing it expected.
	 */
	public boolean rootAliases = false;

	/**
	 * How long a confirmation prompt stays good for, in seconds. 0 disables the expiry.
	 * <p>
	 * A prompt shows you what an action would do. Between seeing it and confirming it the
	 * world moves — the player logs off, another staff member handles it, the chest is
	 * emptied — and confirming a ten-minute-old preview is confirming a description of a
	 * server that no longer exists. That gap is where an irreversible action goes wrong.
	 * <p>
	 * Raising it makes a slow shift less annoying. Lowering it makes the preview a stronger
	 * statement about the present. Setting it to 0 means a confirm screen left open over lunch
	 * still works, which is the behaviour this replaced.
	 */
	public int confirmExpirySeconds = 60;

	/**
	 * Block changes above which a rollback preview says so loudly. 0 disables the warning.
	 * <p>
	 * Not a limit — nothing is refused for being big, because the rollback that undoes a real
	 * raid is enormous and refusing it would be refusing the tool's main purpose. This is only
	 * the line above which the size is worth reading twice.
	 * <p>
	 * Raising it makes big rollbacks routine. Lowering it makes the warning appear on ordinary
	 * ones, which is the failure to avoid: a warning that fires every time teaches everybody to
	 * press through it, and then the one that mattered goes past unread too.
	 */
	public int rollbackWarnBlocks = 500;

	// ---- canaries ------------------------------------------------------------

	/**
	 * Show each player a few fake ores in solid rock, and notice who goes straight to one.
	 * <p>
	 * Turned off automatically when a bulk anti-xray mod is installed, whatever this says.
	 * Both rewrite what the client is told is there, so a canary is not guaranteed to arrive
	 * and a player breaking a fake block cannot be told apart from one breaking the other
	 * mod's — see the startup report, which says so when it happens.
	 * <p>
	 * Off means the detector still works. It reads the break log after the fact, which is the
	 * more useful half and needs nothing sent to anybody.
	 */
	public boolean canaryBlocks = true;

	/**
	 * How many decoys one player has out at a time. 0 disables.
	 * <p>
	 * Deliberately tiny, and far below the density a bulk anti-xray uses. That mod is hiding
	 * ore, so it wants fakes everywhere; this is asking a question, so each one has to stay
	 * rare enough that walking into it means something. Raise it and the odds of a legitimate
	 * miner meeting one by chance stop being negligible.
	 */
	public int canaryDensity = 6;

	/**
	 * The highest Y a canary is placed at.
	 * <p>
	 * Below the depth where people build and above nothing in particular. Placing them near
	 * the surface would put decoys inside the ground under somebody's house, which is both
	 * useless — nobody x-rays for stone at Y 60 — and the likeliest way to annoy a builder.
	 */
	public int canaryMaxY = 16;

	/**
	 * Blocks from a player a canary may be placed within.
	 * <p>
	 * Close enough to be inside their render distance, so an x-ray client actually shows it;
	 * far enough that it is not underfoot. Outside this, the client has not been sent the
	 * chunk and the decoy is invisible to everybody including a cheater.
	 */
	public int canaryRadius = 48;

	/**
	 * Canary hits in one session before a case is opened automatically.
	 * <p>
	 * One is noise: a decoy can end up in the path of a tunnel somebody was digging anyway,
	 * and a single unlucky hit should never be the thing that opens an investigation. Three
	 * separate ones is not luck.
	 * <p>
	 * Below the threshold the hits are still recorded and still visible in the player's
	 * context — quiet, not discarded.
	 */
	public int canaryCaseThreshold = 3;

	// ---- display -------------------------------------------------------------

	/**
	 * The timezone staff-facing times are printed in. A zone id, such as
	 * {@code Europe/London} or {@code America/New_York}.
	 * <p>
	 * Changing this changes display only. Stored times stay epoch milliseconds and log lines
	 * stay UTC, because a log is read later by somebody in another place and one rendered in
	 * the writer's local time carries a silent offset. Every printed time names its zone, so
	 * nothing here can make a timestamp mean something other than what it says.
	 * <p>
	 * An unrecognised zone falls back to UTC and is reported at startup rather than failing
	 * the boot: a typo here should cost a preference, not the ability to read a ban record.
	 */
	public String displayTimezone = "UTC";

	// ---- accountability ------------------------------------------------------

	/**
	 * Punishments one staff member may issue in a minute. 0 disables.
	 * <p>
	 * Not about ordinary busy shifts — four a minute is a great deal of moderating. This is
	 * what stands between a compromised staff account and the whole player base: forty bans in
	 * a minute is a dead server, four is a bad evening, and only one of those is recoverable.
	 * <p>
	 * Enforced inside {@code PunishmentModule.apply} rather than in the command, so the GUI,
	 * the API and any future path get it without being told about it.
	 */
	public int maxPunishmentsPerMinute = 6;

	/**
	 * Rollbacks one staff member may run in a minute. 0 disables.
	 * <p>
	 * Lower than punishments because each one is bigger: a rollback rewrites an area and
	 * charges people for what it puts back, and running several in quick succession is far
	 * more often a mistake being repeated than a job being done.
	 */
	public int maxRollbacksPerMinute = 3;

	/**
	 * Require a second staff member to confirm a mass rollback, IP ban or inventory edit.
	 * <p>
	 * <b>Read the cost before turning this off, and before leaving it on.</b> With it on, a
	 * server with one admin online cannot do any of those three until somebody else logs in —
	 * and a staff member holding every permission still cannot approve their own, because an
	 * approval you can grant yourself is a confirmation prompt with extra steps.
	 * <p>
	 * The value is not the permission check. It is a second person reading what is about to
	 * happen while the first says it out loud.
	 */
	public boolean requireTwoPersonApproval = true;

	/** How long a staged action waits for its second signature, in minutes. */
	public int approvalExpiryMinutes = 10;

	// ---- warnings ------------------------------------------------------------

	/**
	 * Points a warning is worth when nobody says otherwise.
	 * <p>
	 * One, so the total reads as a count of warnings until somebody wants finer grain. A
	 * scale that starts complicated is one nobody tunes.
	 */
	public int warnPointsDefault = 1;

	/**
	 * Days before a warning stops counting towards escalation. 0 keeps them forever.
	 * <p>
	 * Decay is not softness. A player warned three times in a week is a different person from
	 * one warned three times across two years, and a ladder that cannot tell those apart
	 * eventually bans somebody for having been around a long time. The warning itself is
	 * never deleted — only its weight in the total goes.
	 */
	public int warnDecayDays = 90;

	/**
	 * Points at which the ladder starts suggesting something heavier. 0 disables it.
	 * <p>
	 * A <b>suggestion</b>, always. Crossing this never punishes anybody — it puts a
	 * recommendation in front of a staff member who confirms or ignores it.
	 * <p>
	 * That is deliberate and worth not "fixing" later. An automatic ladder fires on a count
	 * rather than a judgement, it is a rule players learn to sit just underneath, and the case
	 * where it is most likely to be wrong — somebody warned repeatedly by one staff member
	 * with a grudge — is exactly the case where a second human is the only safeguard there is.
	 */
	public int warnEscalationPoints = 3;

	// ---- cases ---------------------------------------------------------------

	/**
	 * Confidence at or above which a signal opens a case on its own.
	 * <p>
	 * The knob that decides how noisy the case list is, and the one worth getting right. Set
	 * it low and every observation becomes a case, which trains staff to close cases without
	 * reading them — at which point the case model is worse than the alert channel it
	 * replaced. Set it high and a player accumulating weak signals is never looked at.
	 * <p>
	 * Below this, a signal is still <em>kept</em> and shown in that player's context panel; it
	 * just does not interrupt anybody. And a signal of any strength joins a case that is
	 * already open, because three weak things about one player is the shape of a real problem
	 * and is exactly what a scrolling alert channel could never show.
	 */
	public int caseAutoOpenSeverity = 70;

	/**
	 * Days without a signal or any staff activity before a case is marked stale. 0 disables.
	 * <p>
	 * Stale is not a deletion and not a verdict. It means nothing has happened here for a
	 * while, which is worth knowing and is not the same as deciding the player was innocent —
	 * a case that went quiet because everybody was busy reads exactly like one that went quiet
	 * because there was nothing in it, and only a human can tell those apart.
	 */
	public int caseStaleDays = 14;

	/**
	 * How much weight a contraband find carries as a signal, 0-100.
	 * <p>
	 * Below {@link #caseAutoOpenSeverity} on purpose. One banned item is worth recording and
	 * is not worth interrupting anybody about — it is far more often a leftover from a
	 * gamemode change or an old world than evidence of anything. What makes it useful is that
	 * it joins a case somebody is already working, where "and they are carrying operator
	 * tooling" is exactly the corroboration an investigator wants.
	 */
	public int contrabandSignalConfidence = 45;

	/**
	 * How much weight a mass-grief burst carries as a signal, 0-100.
	 * <p>
	 * High, because unlike the other detectors this one is watching something that already
	 * happened rather than inferring intent: somebody really did break that many blocks that
	 * fast. What it cannot know is whether they were allowed to, which is why it opens a case
	 * for a human rather than acting.
	 */
	public int massGriefSignalConfidence = 75;

	/**
	 * How much weight an x-ray verdict carries when it is reported as a signal.
	 * <p>
	 * The detector's own confidence is used where it has one; this is the floor applied to a
	 * verdict that cleared the alert threshold. Set below {@link #caseAutoOpenSeverity} to
	 * keep x-ray findings out of the case list entirely, which is a reasonable thing to want
	 * while tuning on a new server.
	 */
	public int xraySignalConfidence = 80;

	/**
	 * How much weight a player report carries as a signal, 0-100.
	 * <p>
	 * The one signal produced by a human who watched something happen and chose to tell
	 * somebody, which is a better claim on attention than any heuristic here. Set at the
	 * auto-open threshold so a report opens a case on its own — the report queue already
	 * exists, and this makes the report joinable to whatever else is known about that player
	 * rather than living in a separate list.
	 */
	public int reportSignalConfidence = 70;

	// ---- security ------------------------------------------------------------
	//
	// Every x-ray default comes from XrayTuning rather than being written here. The numbers
	// carry a measurement behind them (docs/decisions.md), and having one home for them is
	// what lets a test prove the documentation still agrees with the code — the last round of
	// tuning left three different sample floors in circulation across the docs and the source.

	/** Ore-to-total fraction above which the blunt ratio signal starts scoring. */
	public double xrayRatioThreshold = XrayTuning.RATIO_THRESHOLD;
	/** Ore-plus-filler blocks a player must have broken before the detector will say anything. */
	public int xraySampleFloor = XrayTuning.SAMPLE_FLOOR;
	/** Mean filler blocks between veins below which mining looks guided. */
	public double xrayDirectnessFloor = XrayTuning.DIRECTNESS_FLOOR;
	/** Confidence at which staff are alerted automatically. */
	public int xrayAlertConfidence = XrayTuning.ALERT_CONFIDENCE;
	/** Confidence at which staff get a quieter heads-up rather than an alert. 0 disables. */
	public int xrayNoticeConfidence = XrayTuning.NOTICE_CONFIDENCE;
	/** How often the background sweep scores active miners, in minutes. 0 disables. */
	public int xraySweepMinutes = XrayTuning.SWEEP_MINUTES;
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
	public boolean rollbackReclaimsFromStaff = false;

	/**
	 * Record blocks destroyed by explosions, so they can be rolled back.
	 * <p>
	 * Creeper damage is the commonest destruction on most servers and was invisible to the
	 * log entirely, because only player block-breaking was ever recorded. Contents of exploded
	 * containers are captured too, so a chest comes back with what was in it.
	 */
	public boolean logExplosions = true;
	/**
	 * Most blocks to record from a single explosion. 0 for no limit.
	 * <p>
	 * A creeper takes out a few dozen. A TNT cannon or a chain reaction can level thousands,
	 * and writing every one buries the incident the log is meant to make readable.
	 */
	public int explosionLogCap = 512;
	/**
	 * Record blocks that fire burns away, so a burned build can be put back.
	 * <p>
	 * Lighting the first block is a placement and was always logged; everything the fire then
	 * ate was not, so a wooden build could burn to nothing and leave a single flint-and-steel
	 * in the record and no damage at all.
	 */
	public boolean logFireDamage = true;

	/**
	 * How far around a death to look for its items when restoring a snapshot, in blocks.
	 * <p>
	 * Restoring hands the player everything the snapshot held, so anything of theirs still
	 * lying about has to be taken off the ground or the two copies both exist. Drops land
	 * close by, but water pushes them and people wander while collecting, so the search is
	 * deliberately wider than the pile.
	 * <p>
	 * It can only remove items the snapshot actually contains, and only as many as are
	 * missing, so widening it finds more of what belongs to the death without letting it take
	 * anything that does not.
	 */
	public int deathDropSweepRadius = 16;
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
			validate();
		} catch (IOException | RuntimeException e) {
			StaffCore.LOGGER.error("[StaffCore] Config is unreadable - falling back to defaults", e);
			instance = new StaffConfig();
		}
	}

	/**
	 * Checks the values that can be wrong in a way the code cannot recover from silently.
	 * <p>
	 * Every complaint here names the key, what is in it, and what happens now — a warning
	 * that says "invalid config" costs the reader a grep and tells them nothing about whether
	 * their server is behaving. Nothing throws: a config file is edited by hand under time
	 * pressure, and refusing to boot over a mistyped timezone is a worse failure than the
	 * mistyped timezone.
	 *
	 * @return the problems found, already logged, for the self test to report
	 */
	public static List<String> validate() {
		List<String> problems = new ArrayList<>();
		StaffConfig cfg = get();

		if (io.github.alphain24.staffcore.util.TimeFormat.zoneOrNull(cfg.displayTimezone) == null) {
			problems.add("displayTimezone is \"" + cfg.displayTimezone + "\", which is not a "
					+ "timezone id. Times are being printed in UTC. Use a region id such as "
					+ "Europe/London or America/New_York.");
		}

		for (String problem : problems) {
			StaffCore.LOGGER.error("[StaffCore] Config: {}", problem);
		}
		return problems;
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

		// v3: two rollback behaviours became opt-in. Both are correct and both are
		// surprising — one follows stolen items into chests outside the rollback radius, the
		// other takes items back off the staff member who ran it. Correct and surprising is
		// the wrong combination for something that removes items from a player's inventory,
		// so new servers now start with them off.
		//
		// Existing servers keep what they had. Changing a default is a decision for a new
		// install; changing behaviour under a running server is somebody logging in to find
		// their rollbacks quietly stopped doing half of what they did yesterday.
		if (from < 3) {
			cfg.rollbackChasesBankedLoot = true;
			cfg.rollbackReclaimsFromStaff = true;
			StaffCore.LOGGER.info(
					"[StaffCore] Config upgrade: rollbackChasesBankedLoot and "
							+ "rollbackReclaimsFromStaff are now off by default for new servers. "
							+ "Yours keep their current behaviour. Set them to false in "
							+ "config/staffcore.json if you would rather take the new default.");
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

		String rule = io.github.alphain24.staffcore.modules.security.IllegalItems.RULE_SPAWN_EGGS;
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
