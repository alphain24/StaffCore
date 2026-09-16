# StaffCore

A staff-management suite for **Minecraft 26.2** on Fabric. Server-side only — your players
install nothing, and neither do your staff. Every tool is reachable from a chest menu, and
every menu has a command behind it for the people who would rather type.

> ### Before you download
> | | |
> |---|---|
> | **Minecraft** | 26.2 |
> | **Fabric Loader** | 0.19.3 or newer |
> | **Java** | **25** |
>
> Java 25 is the one that catches people out. Most servers are on 17 or 21, and this will not
> start on either — 26.2 itself requires 25, so the mod cannot ask for less. Check with
> `java -version` before downloading anything.

**[Read the handbook](docs/handbook.html)** for the full guide, or carry on here for the
short version.

---

> **Why things are the way they are** lives in
> **[docs/decisions.md](docs/decisions.md)** — the design notes, the porting notes, the mixin
> post-mortems, and the measurements behind the thresholds. This file is the reference; that
> one is the reasoning. **What changed** is in [CHANGELOG.md](CHANGELOG.md).

## What it does

Twenty-one modules behind one `/staff` command:

- **Moderation** — warn, mute, kick, temp-ban and ban, from a preset offence ladder or by
  hand. Reports from players, appeals from the punished, notes for the next person on shift.
- **Investigation** — a block and container log that answers who broke it, who placed it,
  who opened it and who emptied it. Inspect any block for its own history, roll an area back
  or shift-click one row of the log to roll back just that, and undo the rollback if you got
  it wrong.
- **Inventories** — live views of what somebody is carrying, editable behind their own
  permission, with snapshots taken automatically before a death, a logout, a staff edit or a
  rollback. Restoring a death snapshot does not duplicate the drops.
- **Anti-cheat support** — contraband detection covering every item that cannot be obtained
  in survival, an x-ray heuristic that reads ore type and mining pattern, a live score of how
  many sealed diamond veins and decoy veins each player uncovers — ore showing in a cave does
  not count — weighed against the directions their tunnels could have gone, alt detection, and
  a bridge for findings from an anti-cheat you already run.
- **Staff tools** — vanish that actually hides you, staff mode with an inventory stash,
  freeze, teleport, staff chat, command spy, and per-person activity records.

Everything it records goes in a SQLite file next to your world, backed up on every start.

## Installing

1. Install **Fabric Loader 0.19.3 or newer** for Minecraft 26.2.
2. Drop **[Fabric API](https://modrinth.com/mod/fabric-api)** into `mods/`.
3. Drop `staffcore-<version>.jar` into `mods/`. For the Discord bot, add
   `staffcore-discord-<version>.jar` from the same release too — see
   [the Discord companion](#discord-companion--staffcore-discord).
4. Start the server.

Both jars are on the [releases page](https://github.com/alphain24/StaffCore/releases).

SQLite is bundled inside the jar (~12 MB, mostly native libraries), so there is nothing else
to install and no database to set up. The mod is server-side: players connect with a vanilla
client.

Requires **Java 25**.

### Where the files are

Everything you edit is in one folder, `config/staffcore/`, written on first start:

```
your-server/
├── mods/
│   ├── staffcore-1.1.0.jar
│   └── staffcore-discord-1.0.0.jar      (optional: the Discord bot)
├── config/
│   └── staffcore/
│       ├── staffcore.json               settings
│       ├── permissions.json             groups, when there is no permissions mod
│       ├── discord.json                 the bot's settings (with the Discord jar)
│       └── discord.token                the bot's token, alone (with the Discord jar)
└── world/
    ├── staffcore.db                     everything StaffCore records
    └── staffcore-backups/
```

A server that ran an earlier build had these loose in `config/` as `staffcore.json`,
`staffcore-permissions.json`, `staffcore-discord.json` and `staffcore-discord.token`. They are
moved into the folder on the first start, settings and all, and the log says so. If a file is in
both places, the folder's copy is used and the old one is left for you to delete.

### First five minutes

```
/staff
```

That opens the panel, and everything is reachable from there. A few worth knowing:

| Command | Does |
|---|---|
| `/staff` | The panel |
| `/staff mode` | Clock on — stashes your inventory, hands you the staff tools |
| `/staff vanish` | Disappear properly: tab list, player count, sounds, the lot |
| `/staff inspect` | Click any block to see its history |
| `/staff selftest` | Check the mod is working after an update |

By default **operators have every permission**. Once you have staff to assign, set
`operatorsBypass` to false in `config/staffcore/permissions.json` and put people in groups —
see [Permissions](#permissions).

### Checking it works

The boot log ends with a line like:

```
[StaffCore] Health check: 22/22 hooks present, 19 verified applied.
```

If those numbers do not match, the log names which feature is affected. `/staff selftest`
goes further and exercises the database, the command tree and the detectors against your
live server. Both are covered in [the handbook](docs/handbook.html#health).

---

## Toolchain

| | |
|---|---|
| Minecraft | `26.2` |
| Java | `25` |
| Fabric Loader | `0.19.3` |
| Fabric API | `0.157.0+26.2` |
| Loom | `1.17.20` |
| Gradle | `9.7.0` |
| Mappings | none — 26.x ships unobfuscated |

26.x is the first unobfuscated Minecraft, so `build.gradle` has **no `mappings` line** and
the class names in this source are the real ones.

## Build

The Gradle wrapper is committed, so:

```bash
./gradlew build         # compile + tests -> build/libs/staffcore-<version>.jar
./gradlew test          # the JUnit suite on its own
./gradlew runServer     # dev server with the mod loaded
```

### Tests and CI

| Workflow | When | Does |
|---|---|---|
| `build.yml` | every push and PR | Compile, JUnit, upload the jar |
| `boot.yml` | push, PR, weekly | Boot a server, assert every hook applied |
| `gametest.yml` | push, PR, weekly | Boot a server and run every `@GameTest` against real players, mobs and blocks |
| `snapshot.yml` | daily | The same boot check against the newest MC snapshot; opens an issue if it breaks, never blocks a merge |

Four layers, because they answer four different questions: does it compile, did every hook
attach, does the feature actually behave, and will the next Minecraft version break it. The
third is the one that matters most — a mixin can apply perfectly to a method whose logic no
longer does what the feature needs.

```bash
./gradlew runGametest   # a real server, real players, real mobs
```

[What each layer has caught, and why there are four](docs/decisions.md#testing-layers).

---

## Commands

**Everything lives under `/staff`.** Tab-complete the one word and the whole tree is there,
and nothing StaffCore adds can collide with another mod's `/ban` or `/kick`. Set
`rootAliases: true` to also get `/vanish`, `/freeze`, `/mute` and the rest at the root — each
registered only where the name is free, and the skipped ones logged by name. Two commands
deliberately stay at the root: `/report`, because it belongs to players rather than staff,
and `/sc`, because it is typed dozens of times a shift.

`/staff <name>` with no subcommand opens that player's file. Brigadier tries every literal
first, so `/staff ban Notch` still hits the ban subcommand — only a bare name falls through.

Punishment commands take a **profile**, not an online player, so they work on someone who
has already logged off.

| Command | Node | Does |
|---|---|---|
| `/staff` | `staff.gui` | Open the panel |
| `/staff <player>` | `staff.gui` | Open that player's file |
| `/staff mode` · `/staff vanish` | `staff.mode`, `staff.vanish` | Clock on, disappear |
| `/staff freeze <player>` | `staff.freeze` | Lock a player in place |
| `/staff warn <player> <reason…>` | `staff.punish.warn` | Logged warning |
| `/staff kick <player> <reason…>` | `staff.punish.kick` | Disconnect once |
| `/staff mute` · `/staff tempmute <player> <dur> <reason…>` | `staff.punish.mute` | Silence |
| `/staff ban` · `/staff tempban <player> <dur> <reason…>` | `staff.punish.ban` | Refuse at login |
| `/staff unban <player>` · `/staff unmute <player>` | `staff.punish.revoke` | Lift it; unbanning also lifts any IP ban taken from them |
| `/staff ipban` · `/staff tempipban <player> [dur] <reason…>` | `security.ipban` | Ban the account and the connection it last joined from. Needs a second person to `/staff approve` while `requireTwoPersonApproval` is on. Refused for loopback or private addresses and for a connection 10 or more other accounts have used |
| `/staff unipban <player>` | `staff.punish.revoke` | Lift only the connection ban, for somebody who shares it |
| `/staff ipbans` | `staff.history` | IP bans in force, with who issued, who approved and refused logins |
| `/staff history <player> [clear]` | `staff.history`, `staff.history.clear` | Their record |
| `/staff risk <player>` | `staff.history` | Everything on record about them — bans, open and actioned cases, detector signals, reports, banned linked accounts, account age — weighed, with every point shown. Lifted punishments and cleared cases count for nothing. Nothing acts on it |
| `/staff notes <player> [remove <n>]` | `staff.notes[.remove]` | Read their notes; write one with `/staff note` |
| `/staff chat` | `staff.chat` | Toggle the staff channel; `/sc <msg>` for one line |
| `/staff alerts` | `staff.alerts` | Toggle your alerts |
| `/staff reports` | `report.view` | Open the queue |
| `/staff goto <player>` · `/staff back` | `staff.tp` | Teleport, return |
| `/staff bring <player>` | `staff.tphere` | Pull them to you |
| `/staff tppos <x> <y> <z>` | `staff.tppos` | Teleport to coordinates |
| `/staff invsee <player>` | `security.invsee` | Inventory — **works offline**; shift-click a slot to vault it |
| `/staff seccheck <player>` · `/staff scan` | `security.check`, `security.itemscanner` | Checks, sweep |
| `/staff xray <player> [hours]` | `security.check` | Score one player on demand, offline included |
| `/staff replay <player> [timespan]` | `staff.replay` | Watch their session back — needs `positionTracking` on |
| `/staff replay pause · resume · speed · restart · exit` | `staff.replay` | Playback controls; `exit` leaves an x-ray dig replay too |
| `/staff preview [area] …` | `grief.rollback` | What a rollback would change, writing nothing |
| `/staff rollback <player> <radius> [minutes]` | `grief.rollback` | Undo block changes back to how the area was before them. A chest broken with items in it always comes back with them |
| `/staff rollback list` | `grief.rollback` | Recent rollbacks and their references — restore points kept 7 days |
| `/staff undo [ref]` | `staff.gui` | Undo your last undoable action, or the one named: `R-<n>` a rollback (`grief.rollback`), `I-<n>` a debit (`grief.rollback`), `P-<n>` a punishment (`staff.punish.revoke`) |
| `/staff search <query…>` | `grief.search` | `key:value` query over both logs |
| `/staff purge <age> [player] [confirm]` | `grief.purge` | Delete history early — previews first |
| `/staff inspect` | `grief.inspect.mode` | Hold inspect mode; any block click shows its history |
| `/staff spy` | `control.spy` | Command spy |
| `/staff clearchat` · `lockchat` · `unlockchat` | `control.chat` | Chat controls |
| `/staff broadcast <msg>` | `control.broadcast` | Server-wide message |
| `/staff maintenance` | `control.maintenance` | MOTD swap, blocks new logins **and kicks everyone already on** |
| `/staff enderchest <player>` | `security.enderchest` | Live ender chest view |
| `/staff logs <player>` | `staff.logs` | Joins, leaves and deaths |
| `/staff tphistory <player>` | `staff.logs` | Every teleport of 16+ blocks or change of world, whatever caused it, with staff teleports named; kept as long as the grief log and left out of `/staff export` unless personal data is included |
| `/staff alts <player>` | `staff.alts` | Accounts sharing an address |
| `/staff appeals` | `staff.appeals` | Appeal queue |
| `/staff rollback area <radius> [minutes]` | `grief.rollback` | Undo **everyone's** changes here |
| `/staff stats <player>` | `analytics.stats` | One staff member's totals |
| `/staff note <player> <text>` | `staff.notes` | Attributed, timestamped, never deleted |
| `/staff audit <staff> [days]` | `staff.audit` | Everything a staff member did, with case links |
| `/staff audit <staff> origins` | `staff.audit.addresses` | Where they acted from — admin-only, hashed |
| `/staff approve [id]` | `staff.approve` | Confirm, and carry out, somebody else's staged IP ban |
| `/staff perms [list \| set \| unset \| explain]` | `staff.perms` | Built-in groups, when no permissions mod is installed; `explain <player>` prints their resolved nodes |
| `/staff backup` | `staff.reload` | Write a database backup now |
| `/staff export [addresses [confirm]]` | `staff.reload` | Dump every table to CSV; addresses are redacted unless asked for |
| `/staff selftest` | `staff.reload` | Prove the mod works, not just that it started |
| `/staff grief test [blocks]` | `staff.reload` | Destroy a few blocks yourself (10 by default, by hand or with TNT, crystals, beds or anchors) and watch the mass-grief alert arrive, marked TEST; no case, nothing to Discord |
| `/staff nbt` | `security.invsee` | Read the held item's component data |
| `/staff cases [status \| mine]` | `staff.gui` | Open cases, strongest first |
| `/staff case <id>` | `staff.gui` | Read one case — the id completes as you type; add `note`, `assign`, `investigating`, `cleared`, or `actioned` (on its own, closes with the punishment they got since the case opened, its reason going into the case history) |
| `/staff cases type <kind>` | `staff.gui` | Cases of one kind: `griefing`, `cheating`, `illegal_items`, `ban_evasion`, `chat`, `other` |
| `/staff case open <player> <kind> [summary]` | `staff.gui` | Open a case by hand; a player has at most one open case of each kind |
| `/staff case <id> category <kind>` | `staff.gui` | Move a case to another kind, for a report sorted wrongly |
| `/staff case <id> evidence` | `staff.gui` | List the evidence to open; add `replay <ago> [length]`, `blocks [radius] [ago]`, `location`, `snapshot`, `view <n>`, `retract <n>` |
| `/staff case <id> tp` | `staff.tp` | Go to where the case happened: the newest filed location, else the block damage, else the replay's start |
| `/staff vault` | `security.vault` | The contraband vault; `security.vault.destroy` to destroy an item for good; `security.vault.clear` to destroy everything held at once or delete the returned-and-destroyed history |
| `/staff contraband` | `security.vault` | The contraband rules; `security.contraband.edit` to change them |
| `/staff anticheat [player]` | `security.check` | Bridge state, or one player's findings |
| `/staff anticheat test <player> [n]` | `staff.reload` | Push a synthetic finding through the pipeline |
| `/staff owed [player]` | `grief.rollback` | Who still owes items from a rollback — only what they picked up and no longer have; also under World → Owed items |
| `/staff owed forgive <player> [confirm]` | `grief.rollback` | Write a debt off |
| `/staff discord` · `/staff discord link` | any staff node | Show your Discord link, or get a one-time code to link — only while the Discord companion is running |
| `/staff discord unlink [player]` | any staff node; `staff.perms` for somebody else's | End a Discord link, so nothing can be done as that account from Discord |
| `/staff reload` | `staff.reload` | Re-read config and permission groups |
| `/staff status` | `staff.reload` | Modules, TPS, storage, and which hooks are broken |
| **`/report <player> <reason…>`** | `report.use` | **Open to everyone** — stays at root |
| **`/appeal <text…>`** | `appeal.use` | **Open to everyone** — works while muted |
| **`/sc <message>`** | `staff.chat` | One line in staff chat |

Durations are `30m`, `6h`, `7d`, `1h30m`, or `perm`. Anything unparseable is **refused**,
not silently treated as zero.

### Permissions

Install [fabric-permissions-api](https://github.com/lucko/fabric-permissions-api) (and
LuckPerms) and StaffCore picks it up automatically — the lookup is reflective and done
once at class-load. That remains the best answer and wins outright wherever it is present.

**Without one, `config/staffcore/permissions.json` answers instead.** It is written with
starter groups on first run and holds groups (node lists, with `@other` to inherit and
`staff.*` wildcards) plus a player-to-group map. Assign people with
`/staff perms set <player> <group>`; the file is re-read by `/staff reload`.

```jsonc
{
  "groups": {
    "helper":    [ "staff.gui", "staff.mode", "staff.vanish", … ],
    "moderator": [ "@helper", "staff.punish.*", "control.spy", … ],
    "admin":     [ "@moderator", "control.*", "security.*", … ]
  },
  "players":  { "<uuid or name>": "moderator" },
  "defaultGroup": "",        // what an unlisted player gets; empty = nothing
  "operatorsBypass": true    // set false once your staff are assigned
}
```

Like `staffcore.json`, the file carries a `configVersion`. When a starter group changes, a
group on an existing server is updated **only if it still holds the old default exactly** — a
group you have edited is never touched, and every change is logged. Version 1 added
`staff.notes` to `helper`: its `staff.notes.*` covers the nodes beneath `staff.notes` but not
`staff.notes` itself, so before it only operators could write a note.

`operatorsBypass` starts **on** so writing this file cannot lock you out before you have
added yourself to it. Turn it off and op stops being a way around the groups. Only then do
staff ranks actually mean anything on a server without LuckPerms — before this, every node
fell back to op level, which made a trainee helper exactly as powerful as an admin.

Op level is still the last resort, for a node no group mentions.

`report.use` is the one exception to all of it: it resolves through the provider's
*default-true* overload, so `/report` stays open to everyone rather than quietly becoming
admin-only.

---

## Config — `config/staffcore/staffcore.json`

Written with defaults on first run; `/staff reload` re-reads it.

The file carries a `configVersion`. When a default changes in a way existing servers should
inherit, the version is bumped and the value is moved **only if it still holds the old
default** — anything you have deliberately set is left alone, and every change is logged.
Changing a default in the code alone would never reach a server that has already run once.

```jsonc
{
  "configVersion": 4,                 // managed by StaffCore; don't edit
  "discordWebhookUrl": "",            // empty = bridge off
  "requireReason": true,
  "publicPunishmentBroadcast": true,  // false = staff-only announcements
  "presetReasons":   [ … ],           // these become the buttons in the Reason menu
  "presetDurations": [ { "label": "7 days", "spec": "7d" }, … ],
  "reportCooldownSeconds": 60,
  "displayTimezone": "UTC",           // staff-facing times only; logs stay UTC
  "confirmExpirySeconds": 60,         // how long a preview stays good for; 0 = forever
  "rollbackWarnBlocks": 500,          // preview says so loudly above this; 0 = never
  "xrayMinimumVolume": 512,           // smallest dig worth scoring; below this is noise
  "caseAutoAssign": true,             // unclaimed cases go to the staff member online with the fewest
  "canaryBlocks": true,               // decoy ores; forced off by a bulk anti-xray mod
  "canaryDensity": 12,                // decoy veins per player: 1-9 blocks in deepslate, rarer 1-2 in stone; 0 disables
  "canaryNotifyEachFind": true,       // tell staff each time a decoy vein is uncovered, with the score
  "canaryMaxY": 16,                   // where diamonds stop generating; lower keeps decoys deeper, higher changes nothing
  "canaryRadius": 48,                 // must be inside their render distance
  "canaryForceWithBulkAntiXray": false, // NOT a supported mode - see the handbook before using
  "rootAliases": false,               // /ban, /vanish etc. at the root, if free
  "tpsAlertFloor": 17.0,
  "defaultRollbackMinutes": 60,

  // Shown in the server list while maintenance mode is on. Legacy codes work:
  // §6 gold, §l bold, §r reset, §7 grey. \n splits the two server-list lines.
  "maintenanceMotd": "§6§lSERVER IN MAINTENANCE\n§7Please wait while we update things — back shortly.",

  "staffModeGameMode": "survival",     // creative | survival | spectator while on duty
  "offences": [ ... ],                 // the punish menu is built from this
  "discordInvite": "",                 // e.g. https://discord.gg/abc123 - shown on the ban screen so people can appeal
  "allowInGameAppeals": true,
  "appealCooldownDays": 7,             // after a rejection, before that punishment can be appealed again; 0 = no wait
  "appealStaleDays": 7,                // an appeal staff asked a question on closes as stale if unanswered this long; 0 = never
  "appealAttemptsPerHour": 5,          // per Discord account, wrong codes included; 0 = no limit
  "detectBanEvasion": true,
  "altSubnetMatching": true,           // also link accounts sharing an address *range*
  "connectionRetentionDays": 90,       // personal data; 0 keeps forever
  "hashConnectionAddresses": true,     // matching still works; the plaintext goes
  "altMinConfidence": 40,              // below this, a link is not worth an alert
  "logContainerAccess": true,          // most "griefing" is theft
  "logExplosions": true,               // creeper and TNT damage, so it can be rolled back
  "explosionLogCap": 512,              // blocks recorded per blast; 0 for no limit
  "logFireDamage": true,               // blocks fire burns away, so a burned build comes back
  "deathDropSweepRadius": 16,          // how far around a death to look for its items
  "rollbackReclaimsDrops": true,       // without this, rollback duplicates items
  "rollbackChasesBankedLoot": false,   // follow loot stashed in chests outside the radius
  "rollbackReclaimsFromStaff": false,  // take back what you picked up at the scene yourself
  "logItemPickups": true,              // who picked what up; makes recovery work at any distance
  "pickupLogRetentionMinutes": 180,    // pickups are kept for hours, not days
  "rollbackPointRetentionDays": 7,     // how long a rollback stays undoable; 0 disables undo
  "positionTracking": false,           // record where players go, so sessions can be replayed
  "positionSampleHz": 2,               // samples per second, per moving player; 1-10
  "positionRetentionDays": 7,          // personal data, and the biggest table; 0 keeps forever
  "debtExpiryDays": 7,                 // unpaid rollback debts are written off; 0 keeps them
  "massGriefBlocks": 120,              // alert threshold, 0 disables
  "massGriefWindowSeconds": 20,
  "massGriefCountsExplosions": true,   // TNT, crystals, beds and anchors a player set off count too
  "vanishBlocksPickup": true,
  "vanishSilentJoin": true,
  "vanishLoadsChunks": true,           // false = vanish leaves no trace, and you fly into void

  "staffModeInvulnerable": true,       // on duty means untouchable, vanished or not
  "illegalItems": [ ... ],             // bedrock, spawners, barriers, portal frames...
  "operatorItems": [ ... ],            // command/structure/jigsaw blocks, debug stick
  "scanEnderChests": true,
  "watchContraband": true,             // alert the moment a player is seen holding one
  "watchContainers": true,             // check chests as they are opened and closed
  "griefLogRetentionDays": 14,
  "snapshotRetentionDays": 30,
  "maxSnapshotsPerPlayer": 10,         // 0 keeps every one
  "autoSnapshotOnLogout": true,        // "I logged off with it" is the commonest dispute
  "autoSnapshotOnStaffEdit": true,     // before a staff member opens an inventory to edit
  "autoSnapshotBeforeDebit": true,     // before a rollback takes items back off somebody
  "databaseBackups": 5,                // written on every start, rotated; 0 disables
  "hideMiningNoise": true,             // keep the log readable on a busy server
  "xrayAlertConfidence": 65,          // no single signal can reach this on its own
  "xrayNoticeConfidence": 55,         // quiet heads-up below the alert line; 0 disables
  "xraySweepMinutes": 5,
  "xraySkipStaffOnDuty": true,        // off-duty staff are still scored
  "xrayMinimumFinds": 3,              // veins a session must uncover before its score is said
  "xrayNaturalVeinsPer1000Faces": 2.0 // what honest mining finds; refined by the server's own
}
```

`vanishLoadsChunks` is the one to think about before changing. Leaving it **on** — the
default — means a vanished admin still holds chunks open around them, so they can see and
fly through the world; spawning is already suppressed either way, so farms behave as though
nobody is there. Turning it **off** makes vanish complete, at the cost that chunks nobody
else is holding open will not load for you. Reasonable on a busy server, a bad idea on a
quiet one. Either way the change lands on the next chunk boundary you cross rather than the
instant you toggle vanish, since that is when chunk tickets are registered.

The punish GUI is entirely preset-driven: add a reason or a duration here and a new button
appears. Presets are the only option in the GUI on purpose — a fixed vocabulary makes
punishment history comparable across a team in a way free text never is. Anyone who needs
a bespoke reason uses the command, which the menu tells them.

## Storage

SQLite at `<world>/staffcore.db`, opened on server start with WAL enabled. If it fails to
open, every module that needs it degrades quietly and the rest of the mod still works.

| Table | Holds |
|---|---|
| `punishments`, `notes`, `reports`, `appeals` | The enforcement record |
| `block_log`, `container_log` | Who broke it, and who emptied it |
| `command_log` | Staff commands, for the spy and the analytics |
| `connections`, `session_log`, `death_log` | Identity — addresses, sessions, deaths |
| `snapshots`, `snapshot_items` | Inventory snapshots |
| `container_snapshot` | What was inside a container at one instant — on break, and before a rollback |
| `rollback_point`, `rollback_change` | Enough of the world to undo a rollback |
| `staff_state`, `stash` | Vanish, freeze, duty state and on-duty inventories |
| `contraband_vault` | Items taken off players and kept |
| `pending_actions` | Items owed to a player who was offline when staff decided |

## Recommended companion: an anti-xray mod

StaffCore **detects** x-ray after the fact. It does not prevent it, and the two are different
halves of the same problem — one catalogues cheating and the other stops it.

Prevention means rewriting the block palette of every chunk on its way out, per player, without
costing the server its tick budget. That is heavy, performance-sensitive work that already
exists and already targets this Minecraft version, so StaffCore does not ship a second copy of
it. Install one of these alongside:

| Mod | Licence | Notes |
|---|---|---|
| [AntiXray](https://github.com/DrexHD/AntiXray) by DrexHD | MIT | Paper's `0367-Anti-Xray.patch`, ported. Fabric and NeoForge. |
| [Meow Anti-Xray](https://github.com/xiaoyiluck666/MeowAnti-Xray) by xiaoyiluck | MIT | Paper-like obfuscation, engine mode 2 by default. Fabric and NeoForge. |

Whichever is installed is named at startup and in `/staff status`. With neither, that line says
so — silence there would read as "prevention is handled", which is the one thing it must not.

**Installing one turns StaffCore's canary blocks off**, and the startup report says why. The
reason is about the player, not the packets: a bulk anti-xray already fills the world with ore
that is not there, so somebody using an x-ray pack learns within an hour that nothing it shows
them is real and stops digging to any of it. That does not make a decoy hit harder to read — it
removes the true positives, because nobody walks to a decoy when nobody walks to anything. The
rest of the x-ray detection — which works from the block log, after the fact — is unaffected
and is the more useful half anyway.

`canaryForceWithBulkAntiXray` places them anyway. It is **not a supported mode**: it exists to
test how the two interact, it warns at startup, and signals produced with it on are not
defensible in an appeal.

## Discord companion — `staffcore-discord`

A second jar, `staffcore-discord-1.0.0.jar`, installed beside StaffCore. It runs a Discord bot
inside the server process, so there is nothing else to host. It is its own jar because the
Discord library and what it needs come to about 11 MB, and a server that does not want Discord
should not carry them.

**What it does today:** lets staff link their Discord account to their Minecraft account; posts
punishments, reports, alerts, appeals and the staff log to channels you choose, each report, appeal
and case with a thread; puts buttons on reports to claim, resolve, escalate, look at the player, add
a note and freeze them; takes ban and mute appeals with `/appeal` and the code from the ban screen,
with buttons to accept, reject, ask the player something and close; runs the in-game `/staff`
commands for looking players up and punishing them; and bridges staff chat both ways.

### Setting it up

**[The step-by-step installation guide](docs/discord-setup.md)** covers where every file goes, making
and inviting the bot, filling in the settings, linking accounts, appeals and troubleshooting. In short:

1. Create an application and a bot in the Discord developer portal, and invite the bot to your
   server with the link in the guide. It needs no privileged intents unless you bridge staff chat,
   which needs **Message Content Intent** switched on for the bot. It needs to view, send messages,
   embed links, read message history, create public threads and send messages in threads — and
   Manage Channels and Manage Roles to make its own private channels, which can be taken away again
   once it has.
2. Put both jars in `mods/` and start the server once. It writes `config/staffcore/discord.json`
   and an empty `config/staffcore/discord.token` (readable only by the server's account, on Linux).
3. Paste the bot token, and nothing else, into the token file; fill in the settings; restart.

`/staff status` shows whether the bot is off, connecting, connected, or what stopped it, and so
does the staff panel.

### In the staff panel

`/staff` → **Discord** is open to every staff member (`staff.gui`), because everybody links their
own account there. The panel button itself says whether the bot is running.

| Entry | Who | Shows |
|---|---|---|
| **Bot** | everyone | Running, connecting, off, not set up, not working, stopped or not installed; who it is connected as and the ping. Owners see what is wrong; others see that something is. Click to refresh, with the full report in chat. |
| **Your link** | everyone | The Discord account yours is linked to, or a click that gives you a link code |
| **Channels** | `staff.reload` | Each channel and whether posts reach it |
| **Linked staff** | `staff.reload` | How many accounts are linked; click to list them, each with an unlink suggestion |
| **Webhook** | `staff.reload` | Whether `discordWebhookUrl` is set, and whether the bot has quieted it |
| **What goes to Discord** | everyone | What is posted and what can be done from there |
| **Setup guide** | everyone | A link to [the installation guide](docs/discord-setup.md) |

The panel shows what the bot reports; its settings are changed in the file and read at the next
start.

### `config/staffcore/discord.json`

Every key is checked at startup, and each problem is logged naming the key and what the
companion is doing instead.

| Key | Default | What changing it does |
|---|---|---|
| `enabled` | `false` | Starts the bot at the next server start. Off, the token file is not even read; existing links are kept and cannot be used. |
| `guildId` | empty | The Discord server the bot works in. It answers only there and reads roles only from there. The bot stays off until this is a real server id. |
| `roleNodes` | empty | Role id → the StaffCore permissions that role may use from Discord, each named. Wildcards and unknown permissions are refused and logged. An unmapped role allows nothing. |
| `requestTimeoutSeconds` | `10` | How long a Discord user waits for the server before being told it timed out (2–60). The request still completes if the server gets to it later. |
| `punishmentsChannelId` | `create` | Where punishments are posted. Empty posts none. |
| `reportsChannelId` | `create` | Where reports are posted, with a thread and buttons. Empty posts none, and reports reach Discord only as alerts. |
| `alertsChannelId` | `create` | Where detector signals are posted and where cases get their threads. Empty posts none, and only cases a report opened get a thread. |
| `appealsChannelId` | `create` | Where appeals are posted for staff, each with a thread and buttons. Setting it offers `/appeal` to players and has the bot read direct messages sent to it, which is where players answer questions and hear verdicts. Empty takes no appeals from Discord. |
| `appealIntakeChannelId` | empty | The one channel `/appeal` is answered in, so appeals can be made somewhere players see while `appealsChannelId` stays staff-only. Empty answers `/appeal` anywhere in the server. Never `create`: it is for players, so you make it. |
| `staffLogChannelId` | `create` | Where every audited staff action is posted — one post per command on a busy server. Empty posts none. |
| `staffChatChannelId` | empty | A channel bridged with staff chat both ways. Setting it makes the bot ask for Message Content Intent; without that switched on in the portal it cannot log in, which is why it is not `create` to begin with. Empty bridges nothing. |
| `discordAlertSeverity` | `70` | The lowest signal confidence (0–100) posted to the alerts channel on its own. A signal that opens a case is always posted, because the case needs its thread. |
| `serverName` | empty | Shown on report posts, for a network sharing one Discord. Empty shows nothing. |
| `playerHeadUrl` | `https://mc-heads.net/avatar/{uuid}/64` | The head picture on posts about a player. Discord fetches it, so that service sees player ids and nothing else. Empty shows no heads. |

Each channel setting is a channel id, `"create"`, or empty. **`"create"` has the bot make the
channel when it connects**: under a **StaffCore** category, private — hidden from `@everyone`, open
to the bot and to the staff roles in `roleNodes`, and to administrators as Discord always is — and
its id is written back into the file in place of `"create"`, so it is made once. Threads in those
channels are as private as the channels. The bot never changes a channel's permissions after making
it, and making channels needs Manage Channels and Manage Roles, which can be removed afterwards.

Setting any of the first five channels makes StaffCore's own `discordWebhookUrl` stop posting once
the bot has connected, so nothing arrives twice. A bot that never connects leaves the webhook
posting.

### Channels

| Channel | Each post | Afterwards |
|---|---|---|
| Punishments | Reason, staff, the player and their prior count, duration, expiry, case, punishment id | A reversal edits the post |
| Reports | Reason, player and prior count, reporter, assignee, status, server, when, case; a thread; Claim, Resolve, Escalate, Profile, History, Add Note and Freeze buttons | Claiming and resolving edit the post and are said in the thread; resolving turns the report's own buttons off and closes the thread |
| Alerts | The signal, its confidence and its case; a thread when it opened the case | Notes, assignments, punishments and closing are said in the case's thread; weaker signals about the case go there too |
| Appeals | The player, the Discord account that filed and whose Minecraft account it is linked to, the punishment with its reason, who issued it and when, the appeal, evidence count, when, case; a thread; Accept, Reject, Request More Info, Close, Punishment, Profile, Evidence and Staff Note buttons | Questions to the player and their answers are said in the thread; a verdict edits the post, turns the verdict buttons off and closes the thread |
| Staff log | Who, the command as recorded, the player it names, when, case | — |
| Staff chat | Every staff chat line from the game | Lines typed there go into staff chat in game, marked `[Discord]` |

A case opened by a report is discussed in the report's thread rather than a second one. Cases
opened by hand get a post in the alerts channel.

**The buttons are the in-game actions, through the same checks.** Claim takes a report over from
whoever holds it, as clicking it in the queue does, and Resolve closes it; both need
`report.view`. Escalate hands the report to the player's open case, or opens one, links the report
and marks the case as being investigated; it needs `staff.gui`, the node `/staff case open` needs.
Profile (`staff.gui`) and History (`staff.history`) answer privately. Add Note (`staff.notes`) goes
onto the player's record and their open case, as `/staff note` does. Freeze (`staff.freeze`) holds a
player who is online. Every one is subject to the permission rules below and recorded against the
linked account, lookups included. Profile shows conduct only: nothing drawn from where a player
connects from, and not the accounts linked to them that way.

Only linked staff holding `staff.chat` are bridged into staff chat; anybody else gets a short reply
that disappears. Lines from the game are sent with mentions switched off.

### Commands

`/staff` in Discord runs the staff commands, with player names completing as you type and every
answer private to whoever asked:

| Command | Needs | Does |
|---|---|---|
| `/staff history <player>` | `staff.history` | Their punishments, newest first |
| `/staff notes <player>` | `staff.notes.view` | Their notes, retracted ones marked |
| `/staff profile <player>` | `staff.gui` | Their standing: punishments, points, notes, what is in force, open cases |
| `/staff case <id>` | `staff.gui` | A case and the latest of its history |
| `/staff evidence <case>` | `staff.gui` | The evidence filed on a case |
| `/staff staff-history <staff> [days]` | `staff.audit` | What a staff member did — never where from |
| `/staff analytics [staff]` | `analytics.stats` | Server totals, and one staff member's or the busiest staff's numbers |
| `/staff warn <player> <reason>` | `staff.punish.warn` | Warn |
| `/staff mute <player> <reason> [duration]` | `staff.punish.mute` | Mute; permanent without a duration |
| `/staff ban <player> <reason> [duration]` | `staff.punish.ban` | Ban; permanent without a duration |
| `/staff unmute <player> [reason]` · `/staff unban <player> [reason]` | `staff.punish.revoke` | Lift it |
| `/staff freeze <player>` · `/staff unfreeze <player>` | `staff.freeze` | Hold or release a player who is online |
| `/staff note <player> <text>` | `staff.notes` | Add a note |

Each runs through the same services as its in-game command: the permission (in game and in
`roleNodes`), the punishment rate limit, and the guard against punishing somebody who outranks you,
whose refusal is shown rather than lost. Everything that changes something from Discord — commands and
buttons alike, staff chat excepted — also counts against **`discordActionsPerMinute`** in
`config/staffcore/staffcore.json` (default 20; 0 disables): several of these have no limit in game, where doing
forty of them means standing in the server doing them. Lookups are recorded in the audit log, as they
are in game. IP bans, rollbacks and inventory edits have no command and are refused to Discord by the
services that run them.

### Appeals

A banned player types `/appeal` with the code from their ban screen, and a form asks why the
punishment should be lifted. The appeal is posted to `appealsChannelId` with a thread. On the ban
screen, "type /appeal with the appeal code below" appears once the bot is connected and taking
appeals.

**Filing is the one thing an unlinked Discord account can do.** Somebody banned cannot join to link,
so the code stands in for a permission: it names one punishment, cannot be guessed, and every
attempt counts against the account — `appealAttemptsPerHour`, wrong codes included. Anybody
holding a photograph of the ban screen can file; the post shows which Discord account filed and
which Minecraft account it is linked to, and says so when that is not the punished player.

- One open appeal per punishment.
- After a rejection, that punishment cannot be appealed again for `appealCooldownDays`. A close
  without a decision leaves no wait.
- **Accept** lifts the punishment the appeal was against, and only that one, marked reversed with
  the appeal named; **Reject** leaves it standing; **Close** ends the appeal without a verdict. Each
  is written into the punishment's case. All three need `staff.appeals`, as the appeals screen does.
- **Request More Info** sends the player a question without the asking staff member's name. They
  answer by replying to the bot's direct message, and the answer is added to the thread. Replies are
  accepted only from the account that filed, on an appeal staff have asked about.
- An appeal staff asked about and the player did not answer for `appealStaleDays` is closed as
  stale; the player can appeal again. An appeal staff have not got to is never marked stale.
- The player is sent the verdict by direct message, without the name of whoever decided. A player
  with direct messages from the server switched off never hears, and the thread says so.
- **Punishment** (`staff.history`), **Profile** (`staff.gui`) and **Evidence** (`staff.gui`) answer
  privately; **Staff Note** (`staff.notes`) goes on the player's record, never into the thread.

Accepting an appeal in game, from `/staff appeals`, now goes through the same path, so it too lifts
only the appealed punishment rather than every ban and mute the player has.

**Nothing a player typed can ping or link.** Report reasons, appeals and names have markdown, links
and mentions escaped, and every message is sent with mentions disabled as well.

Where each post went is remembered in `world/staffcore-discord/threads.json`, so edits and thread
lines still find their post after a restart. It holds Discord ids and the posts' text, is kept for
90 days, and losing it costs a new post where an edit would have gone.

Posts made while the bot is not connected are counted in `/staff status` and not made.

### The token

The token lives in its own file, never in `staffcore.json` or the settings file, because those
get pasted into support channels. It is never logged, never shown in `/staff status`, never in
an error, and not in `/staff export` — nothing writes it to the database. If the file can be
read by every user on the machine, the companion warns at startup and in `/staff status`.

The companion's own code never logs the token. As a backstop for everything else on the server
— the Discord library, the HTTP client, other mods — a filter drops any log line containing
it, and `/staff status` counts how many were dropped.

### Linking and permissions

In game, `/staff discord link` gives a one-time code; in your Discord server, `/link <code>`
completes it. Codes last 10 minutes, work once, and wrong guesses are capped per Discord
account. Being signed in to the Minecraft account is what proves the link, so it cannot be
claimed from Discord alone. The linked player is told in game if they are online, and `/staff discord unlink`
ends it; `/staff discord unlink <player>` (needs `staff.perms`) cuts off somebody else's.
Unlinking ends a link rather than deleting it, so what an account did from Discord stays
attributable.

What somebody may do from Discord is **the smaller of** what their Discord roles map to and
what their linked Minecraft account holds in game, read again on every request:

- A role can only narrow. Giving somebody a Discord role that maps to bans grants nothing unless
  their Minecraft account can already ban. A compromised Discord role is not a Minecraft admin.
- An unlinked Discord account holds nothing, whatever its roles.
- A banned Minecraft account can do nothing from Discord.
- Being opped in game is not a wildcard here; only permissions the account actually resolves count.
- With a permissions plugin such as LuckPerms, permissions can only be read while the player is
  online, so an offline account can do nothing from Discord until they join. Without one, the
  groups file and the op list answer for offline accounts exactly as the command tree would.
- **IP bans, rollbacks and inventory edits never run from Discord**, for anybody, and approving a
  staged action counts as running it. This is enforced in the services that do those things, not
  only by leaving them out of the bot.

Actions from Discord are recorded against the linked Minecraft account and name the Discord
user. No address is recorded for them.

### Bundled libraries

Nested unmodified in the companion jar:

| Library | Licence |
|---|---|
| JDA 6.6.0 | Apache 2.0 |
| OkHttp 5.5.0, Okio 3.18.1 | Apache 2.0 |
| nv-websocket-client 2.14 | Apache 2.0 |
| Jackson 2.22 (annotations, core, databind) | Apache 2.0 |
| Kotlin standard library 2.2.21 | Apache 2.0 |
| JetBrains annotations 13.0 | Apache 2.0 |
| Apache Commons Collections 4.6.0 | Apache 2.0 |
| Trove4j 3.1.0 (`net.sf.trove4j:core`) | LGPL 2.1 — shipped as its own unmodified jar inside the companion, so it can be replaced |

## Known limits

Stated plainly rather than papered over. Everything here is a deliberate boundary or a
known gap — not a bug list.

**Accountability**

- **The console can stage a two-person action but can never approve one.** Intended, not an
  oversight. Approval exists to put a second *person* behind something irreversible, and the
  console, RCON, a command block, a scheduled function and an unlinked Discord user are all
  the same case: each holds every permission or none, and none of them belongs to an account
  anybody could ask about it afterwards. An approval granted by nobody satisfies the letter of
  the check and none of its purpose.
- Proposing is a different matter, and still allowed — the console can stage an IP ban for a
  person to confirm.
- **The practical cost:** on a server with one admin online, an IP ban waits until somebody
  else signs in. Turn it off with `requireTwoPersonApproval` if that trade is wrong for you.
- **Only IP bans go through it so far.** Mass rollbacks and inventory edits were meant to as
  well, and are named in the approval code, but nothing stages them yet: a staff member with
  the node runs them alone, behind the usual preview, confirm, rate limit and region lock.
- **IP bans let operators through.** An owner whose own connection is caught by a ban cannot
  otherwise come in to lift it. Every other account on a banned connection is refused, staff
  are told who tried, and nothing more is done to that account.
- **Staff cannot punish sideways or upwards, and the console can.** Rank is not a field
  anywhere — it is the set of StaffCore nodes somebody holds, so it cannot drift from the
  permissions actually granted. You may punish someone whose nodes are a strict subset of
  yours; equal sets and two sets that merely differ are both refused, because inventing an
  order between them would be inventing the authority the rule exists to check. The case it
  is for is a staff member removing the person who was about to remove them, which is
  unrecoverable by the time anyone notices. **The escape hatch is the console**, which is the
  server owner's own hand and is audited like everything else — without it, two admins who
  had fallen out would leave a server nobody could fix.
- **An offline staff member cannot be punished when permissions live in a plugin.** A
  permissions API answers about a connected player, so their rank is unknowable while they
  are offline and the action is refused rather than guessed at. Waiting is recoverable;
  banning the admin is not. With StaffCore's own group file there is no such gap.

**Recovery and evidence**

- **Rollback warns about spawn, not about claims.** A rollback overlapping world spawn or its
  vanilla `spawn-protection` radius is called out and needs a preview first, because spawn is
  built by staff over months and is logged exactly the way griefing is. Claims belonging to
  GriefPrevention, FTB Chunks or anything similar are invisible from here — StaffCore does not
  depend on any claims mod, and a check that appeared to cover claims while covering none of
  them would be worse than not offering one.
- **Rollback only reaches the area and window you give it.** That is the scope you asked
  for, and widening it silently would be worse than not widening it. What *is* chased
  beyond the radius is the offender's own loot: ground drops are reclaimed, a live
  inventory is debited, chests they filled during the same window are emptied back
  (`rollbackChasesBankedLoot`), and anything an offline offender still owes is collected
  the next time they log in. Loot handed to somebody else is gone.
- **Container rollback still needs somewhere to put things.** A full chest cannot take the
  stack back. The log row is left un-retired so retrying after clearing space works, and
  the count of what would not fit is now reported rather than left for you to notice.

**Detection**

- **Decoy blocks are packet-verified, not client-verified.** The server is checked to grow
  decoy veins in sealed rock, choose the right fake ore for the surrounding rock, send it at the
  right position, count the break that uncovers one, and send the real block back. What has
  **not** been confirmed by anybody watching a screen is that an x-ray client actually draws
  it. That matters more than it sounds: a decoy that never reaches a client is never uncovered
  by a cheater, and silence from it looks exactly like a server with nobody cheating. The check that closes this is written out step by step in
  [docs/manual-checks/](docs/manual-checks/) — one sitting covers this and the vanish claims
  together, and each script says what to change in this file when it passes. **If only one
  check is ever run, it should be this one:** a decoy that never reaches a client makes the
  whole canary layer detect nothing rather than detect less. It has not been run.
- **Session replay is packet-verified, not client-verified, and it is the same primitive.**
  The replay is tested to reconstruct a window coordinate by coordinate, to skip gaps rather
  than fly across them, and to release each overlaid block at the right instant. What has
  **not** been confirmed by anybody watching a screen is that the overlay is drawn at all, or
  that it survives a chunk reload — and it is drawn with the same
  `ClientboundBlockUpdatePacket` the decoys use, which was found to be "hit or miss" months
  after every server-side test passed. The replay re-asserts its overlay on a five-second timer
  for exactly that reason, and that fix has never been watched working. Ten-minute script in
  [docs/manual-checks/replay.md](docs/manual-checks/replay.md). It has not been run.
- **Alt detection is still a lead, never a verdict.** It links accounts by exact address and
  by address range, scores each link 0-100 from how often the address was shared and whether
  the two accounts have ever been online together, and shows its reasoning. A shared house
  still trips it and a determined evader with a clean VPN still beats it. Only an exact
  match can ever auto-ban.
- **The x-ray check is a heuristic.** It flags, it never acts, and by default it will not
  commit to a verdict below a 512-block volume of rock. It no longer counts ore the player placed
  themselves, or ore and air a cave left open, but it is still inference from a block log. An
  alert from ore needs the hidden-vein rate and the way the player's tunnels turned to agree;
  a cheater who digs one straight tunnel and only peeks sideways leaves little of the second and
  is held to a stricter bar on the first. The thresholds are measured
  against generated mining patterns rather than real player data — see
  [decisions.md](docs/decisions.md), which records what that does and does not establish.
- **The live vein score starts from worked-out numbers, not measured ones.** How many sealed
  veins an honest miner uncovers per thousand opened faces (`xrayNaturalVeinsPer1000Faces`) is
  derived from vanilla's diamond placement settings and set on the high side; it is checked
  against simulated branch mining and simulated x-ray mining, not against real players. Each
  server refines it from its own honest mining while it runs, within a factor of three, and
  forgets that on restart. A datapack that changes diamond generation needs the value
  changed by hand.
- **Analytics cannot read judgement.** It now shows follow-through, response time and
  overturn rate beside the raw counts, so it is no longer only a volume ranking — but a
  number still cannot tell you whether a ban was the right call. Read the leaderboard as a
  prompt to go and look at someone's work.

**Not built**

- **A Discord post made while the bot is disconnected is dropped.** It is counted in
  `/staff status` rather than queued and sent when the bot reconnects
  ([the companion](#discord-companion--staffcore-discord)).
- **No map integrations.** BlueMap, Dynmap and Squaremap would each need that mod present as
  a compile dependency.

**Structural**

- **One part of x-ray detection runs on the server thread, deliberately.** Working out how
  much ore a player *walked past* means reading block states, and a `ServerLevel` may only be
  touched from the server thread — doing it anywhere else is a data race that surfaces as a
  crash weeks later in somebody else's log. So the census stays on the tick. It is bounded to
  the rock immediately around the dig, it skips unloaded chunks rather than loading them, and
  it is timed: roughly 3 ms of a 50 ms tick per scored session, once every `xraySweepMinutes`.
  The database read — the largest part, and the part that grows with history — runs on a
  worker. Measurements are in [decisions.md](docs/decisions.md). The live vein score has the
  same split for the same reason: what a break uncovered is read inside the break event, about
  thirty-six block reads below y 16 (more only when a diamond is actually touched), and the
  scoring runs on the worker. When a straight leg of tunnel ends, the directions the player did
  not take are read too: at most four corridors of sixteen blocks, about 500 reads, once per leg.
- **A permission node written by hand, rather than taken from `Nodes`, fails in a shape that
  looks like success.** `Actor` resolves permissions by walking the nodes declared in `Nodes`,
  so a string that is not one of them is in nobody's resolved set — denied to every player, and
  granted to operators through the operator fallback. It therefore works perfectly for whoever
  is testing it, because whoever is testing it is opped, and is silently missing for everyone
  else. A typo has the same shape: `staff.punsh` compiles, resolves to nothing, and quietly
  removes a permission from every non-operator. `NodeLiteralTest` fails the build on a literal
  at a permission check, so the source tree is covered. The shape is still worth recognising
  on sight, because it applies to every node written by hand outside it — a permissions
  plugin's group definition, an LuckPerms command, a wiki page somebody copies from — and
  no test here can reach any of those.
- **Almost every mixin is non-fatal.** Exactly one (the login gate behind bans and
  maintenance) is required; the rest use `defaultRequire: 0`, so an update that moves an
  injection point costs you that feature rather than your server. The startup check now asks
  two questions — is the target method still there, and did our handler actually get
  injected into it — so a mixin that silently fails to apply is named in the log rather than
  passing as healthy.
- **The port is compile-verified, not runtime-verified.** Every 26.2 API change was read out
  of the real jar with `javap`, and the startup check's detection logic was tested against
  it. But behaviour needing a live client and a second account — vanish, the vault, the
  maintenance kick — has been reasoned about rather than watched working. This one is true
  by construction and stays until somebody runs it.
- **The tests cover logic, not visibility.** `./gradlew test` runs the headless half —
  storage, migrations, corruption recovery, rollback maths, the vanish state machine, log
  parsing — and CI boots a real server on top of that and fails if a hook is missing. What
  is *not* automated is the part that needs two clients in a world: the per-leak Fabric
  gametests (tab list, selectors, sound, waypoints) and killing the server mid-write to
  prove each state transition survives it. Both were planned; neither is worth claiming
  before it exists. The manual checks in the handbook cover the same ground by hand.
- **There are more mixins now, not fewer.** Thirty-one classes, thirty of them non-fatal.
  The vanish rework replaced the fragile one — a mixin targeting a package-private inner
  class by string — with hooks on public methods, and then added more of those. Each is
  individually sturdier and each is one more thing an update can move. "Reduce the number of
  fragile mixins" was on the list; what actually shipped was reducing how badly any one of
  them can fail, which is not the same thing.

## Licence

MIT. See [LICENSE](LICENSE).
