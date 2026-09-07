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

Nineteen modules behind one `/staff` command:

- **Moderation** — warn, mute, kick, temp-ban and ban, from a preset offence ladder or by
  hand. Reports from players, appeals from the punished, notes for the next person on shift.
- **Investigation** — a block and container log that answers who broke it, who placed it,
  who opened it and who emptied it. Inspect any block for its own history, roll an area back,
  and undo the rollback if you got the radius wrong.
- **Inventories** — live views of what somebody is carrying, editable behind their own
  permission, with snapshots taken automatically before a death, a logout, a staff edit or a
  rollback. Restoring a death snapshot does not duplicate the drops.
- **Anti-cheat support** — contraband detection covering every item that cannot be obtained
  in survival, an x-ray heuristic that reads ore type and mining pattern, alt detection, and
  a bridge for findings from an anti-cheat you already run.
- **Staff tools** — vanish that actually hides you, staff mode with an inventory stash,
  freeze, teleport, staff chat, command spy, and per-person activity records.

Everything it records goes in a SQLite file next to your world, backed up on every start.

## Installing

1. Install **Fabric Loader 0.19.3 or newer** for Minecraft 26.2.
2. Drop **[Fabric API](https://modrinth.com/mod/fabric-api)** into `mods/`.
3. Drop `staffcore-<version>.jar` into `mods/`.
4. Start the server.

SQLite is bundled inside the jar (~12 MB, mostly native libraries), so there is nothing else
to install and no database to set up. The mod is server-side: players connect with a vanilla
client.

Requires **Java 25**.

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
`operatorsBypass` to false in `config/staffcore-permissions.json` and put people in groups —
see [Permissions](#permissions).

### Checking it works

The boot log ends with a line like:

```
[StaffCore] Health check: 19/19 hooks present, 15 verified applied.
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
| `/staff unban <player>` · `/staff unmute <player>` | `staff.punish.revoke` | Lift it |
| `/staff history <player> [clear]` | `staff.history`, `staff.history.clear` | Their record |
| `/staff notes <player> [add \| list \| remove <n>]` | `staff.notes[.view\|.remove]` | Sticky records |
| `/staff chat` · `/staff say <msg>` | `staff.chat` | Toggle channel, one-off line |
| `/staff alerts` | `staff.alerts` | Toggle your alerts |
| `/staff reports` | `report.view` | Open the queue |
| `/staff goto <player>` · `/staff back` | `staff.tp` | Teleport, return |
| `/staff bring <player>` | `staff.tphere` | Pull them to you |
| `/staff tppos <x> <y> <z>` | `staff.tppos` | Teleport to coordinates |
| `/staff invsee <player>` | `security.invsee` | Inventory — **works offline**; shift-click a slot to vault it |
| `/staff lookup <player>` | `staff.gui` | Same as `/staff <player>` |
| `/staff seccheck <player>` · `/staff scan` | `security.check`, `security.itemscanner` | Checks, sweep |
| `/staff xray <player> [hours]` | `security.check` | Score one player on demand, offline included |
| `/staff preview [area] …` | `grief.rollback` | What a rollback would change, writing nothing |
| `/staff rollback <player> <radius> [minutes]` | `grief.rollback` | Undo block changes |
| `/staff rollback undo [id \| list]` | `grief.rollback` | Undo a rollback — restore points kept 7 days |
| `/staff search <query…>` | `grief.search` | `key:value` query over both logs |
| `/staff purge <age> [player] [confirm]` | `grief.purge` | Delete history early — previews first |
| `/staff inspect` | `grief.inspect.mode` | Hold inspect mode; any block click shows its history |
| `/staff spy` | `control.spy` | Command spy |
| `/staff clearchat` · `lockchat` · `unlockchat` | `control.chat` | Chat controls |
| `/staff broadcast <msg>` | `control.broadcast` | Server-wide message |
| `/staff maintenance` | `control.maintenance` | MOTD swap, blocks new logins **and kicks everyone already on** |
| `/staff enderchest <player>` | `security.enderchest` | Live ender chest view |
| `/staff logs <player>` | `staff.logs` | Joins, leaves and deaths |
| `/staff alts <player>` | `staff.alts` | Accounts sharing an address |
| `/staff appeals` | `staff.appeals` | Appeal queue |
| `/staff rollback area <radius> [minutes]` | `grief.rollback` | Undo **everyone's** changes here |
| `/staff stats <player>` | `analytics.stats` | One staff member's totals |
| `/staff perms [list \| set \| unset \| explain]` | `staff.perms` | Built-in groups, when no permissions mod is installed; `explain <player>` prints their resolved nodes |
| `/staff backup` | `staff.reload` | Write a database backup now |
| `/staff export [addresses [confirm]]` | `staff.reload` | Dump every table to CSV; addresses are redacted unless asked for |
| `/staff selftest` | `staff.reload` | Prove the mod works, not just that it started |
| `/staff nbt` | `security.invsee` | Read the held item's component data |
| `/staff panel` | `staff.gui` | Open the panel |
| `/staff cases [status \| mine]` | `staff.gui` | Open cases, strongest first |
| `/staff case <id>` | `staff.gui` | Read one case; add `note`, `assign`, `claim`, `investigating`, `cleared`, `actioned` |
| `/staff vault` | `security.vault` | The contraband vault; `security.vault.destroy` to destroy an item for good |
| `/staff contraband` | `security.vault` | The contraband rules; `security.contraband.edit` to change them |
| `/staff anticheat [player]` | `security.check` | Bridge state, or one player's findings |
| `/staff anticheat test <player> [n]` | `staff.reload` | Push a synthetic finding through the pipeline |
| `/staff owed [player]` | `grief.rollback` | Who still owes items from a rollback |
| `/staff owed forgive <player> [confirm]` | `grief.rollback` | Write a debt off |
| `/staff owed undo <id> [confirm]` | `grief.rollback` | Give back what a debit took |
| `/staff reload` | `staff.reload` | Re-read config and permission groups |
| `/staff status` | `staff.reload` | Modules, TPS, storage, and which hooks are broken |
| **`/report <player> <reason…>`** | `report.use` | **Open to everyone** — stays at root |
| **`/appeal <text…>`** | `appeal.use` | **Open to everyone** — works while muted |
| **`/sc <message>`** | `staff.chat` | Shortcut for `/staff say` |

Durations are `30m`, `6h`, `7d`, `1h30m`, or `perm`. Anything unparseable is **refused**,
not silently treated as zero.

### Permissions

Install [fabric-permissions-api](https://github.com/lucko/fabric-permissions-api) (and
LuckPerms) and StaffCore picks it up automatically — the lookup is reflective and done
once at class-load. That remains the best answer and wins outright wherever it is present.

**Without one, `config/staffcore-permissions.json` answers instead.** It is written with
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

`operatorsBypass` starts **on** so writing this file cannot lock you out before you have
added yourself to it. Turn it off and op stops being a way around the groups. Only then do
staff ranks actually mean anything on a server without LuckPerms — before this, every node
fell back to op level, which made a trainee helper exactly as powerful as an admin.

Op level is still the last resort, for a node no group mentions.

`report.use` is the one exception to all of it: it resolves through the provider's
*default-true* overload, so `/report` stays open to everyone rather than quietly becoming
admin-only.

---

## Config — `config/staffcore.json`

Written with defaults on first run; `/staff reload` re-reads it.

The file carries a `configVersion`. When a default changes in a way existing servers should
inherit, the version is bumped and the value is moved **only if it still holds the old
default** — anything you have deliberately set is left alone, and every change is logged.
Changing a default in the code alone would never reach a server that has already run once.

```jsonc
{
  "configVersion": 3,                 // managed by StaffCore; don't edit
  "discordWebhookUrl": "",            // empty = bridge off
  "requireReason": true,
  "publicPunishmentBroadcast": true,  // false = staff-only announcements
  "presetReasons":   [ … ],           // these become the buttons in the Reason menu
  "presetDurations": [ { "label": "7 days", "spec": "7d" }, … ],
  "reportCooldownSeconds": 60,
  "rootAliases": false,               // /ban, /vanish etc. at the root, if free
  "xrayRatioThreshold": 0.12,         // ore fraction that trips the heuristic
  "xraySampleFloor": 200,             // blocks needed before it will fire at all
  "tpsAlertFloor": 17.0,
  "defaultRollbackMinutes": 60,

  // Shown in the server list while maintenance mode is on. Legacy codes work:
  // §6 gold, §l bold, §r reset, §7 grey. \n splits the two server-list lines.
  "maintenanceMotd": "§6§lSERVER IN MAINTENANCE\n§7Please wait while we update things — back shortly.",

  "staffModeGameMode": "survival",     // creative | survival | spectator while on duty
  "offences": [ ... ],                 // the punish menu is built from this
  "discordInvite": "",                 // shown on the ban screen so people can appeal
  "allowInGameAppeals": true,
  "detectBanEvasion": true,
  "altSubnetMatching": true,           // also link accounts sharing an address *range*
  "connectionRetentionDays": 90,       // the only personal data here; 0 keeps forever
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
  "debtExpiryDays": 7,                 // unpaid rollback debts are written off; 0 keeps them
  "massGriefBlocks": 120,              // alert threshold, 0 disables
  "massGriefWindowSeconds": 20,
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
  "xrayDirectnessFloor": 12.0,         // filler blocks between veins
  "xrayAlertConfidence": 65,          // no single signal can reach this on its own
  "xrayNoticeConfidence": 55,         // quiet heads-up below the alert line; 0 disables
  "xraySweepMinutes": 5,
  "xraySkipStaffOnDuty": true         // off-duty staff are still scored
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

## Known limits

Stated plainly rather than papered over. Everything here is a deliberate boundary or a
known gap — not a bug list.

**Recovery and evidence**

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

- **Alt detection is still a lead, never a verdict.** It links accounts by exact address and
  by address range, scores each link 0-100 from how often the address was shared and whether
  the two accounts have ever been online together, and shows its reasoning. A shared house
  still trips it and a determined evader with a clean VPN still beats it. Only an exact
  match can ever auto-ban.
- **The x-ray check is a heuristic.** It flags, it never acts, and by default it will not
  commit to a verdict below 200 mined blocks. It no longer counts ore the player placed
  themselves, but it is still inference from a block log. The thresholds are measured
  against generated mining patterns rather than real player data — see
  [decisions.md](docs/decisions.md), which records what that does and does not establish.
- **Analytics cannot read judgement.** It now shows follow-through, response time and
  overturn rate beside the raw counts, so it is no longer only a volume ranking — but a
  number still cannot tell you whether a ban was the right call. Read the leaderboard as a
  prompt to go and look at someone's work.

**Not built**

- **Discord is outbound only.** Punishments, reports and alerts go out through a webhook;
  nothing comes back. Two-way needs a gateway connection, a bot token and roughly 10 MB of
  JDA, which belongs in a separate jar.
- **No map integrations.** BlueMap, Dynmap and Squaremap would each need that mod present as
  a compile dependency.

**Structural**

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
