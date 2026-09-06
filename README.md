# StaffCore

A staff-management suite for **Minecraft 26.2** on Fabric. Server-side only — your players
install nothing, and neither do your staff. Every tool is reachable from a chest menu, and
every menu has a command behind it for the people who would rather type.

**[Read the handbook](docs/handbook.html)** for the full guide, or carry on here for the
short version.

---

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
3. Drop `staffcore-1.0.0.jar` into `mods/`.
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

### Porting notes for 26.2

This is not a cosmetic version bump. If you are moving code from 1.21.x, these are the
changes that actually break things — all of them verified against the 26.2 jar, not
guessed:

| Was | Is now |
|---|---|
| `ResourceLocation` | `net.minecraft.resources.Identifier` |
| `GameProfile` (in server APIs) | `net.minecraft.server.players.NameAndId` — a record with `id()` / `name()` |
| `ClickType` | `net.minecraft.world.inventory.ContainerInput` |
| `Entity#getServer()` | gone — use `player.level().getServer()` |
| `ServerPlayer#serverLevel()` | gone — `level()` is covariant and returns `ServerLevel` |
| `ResourceKey#location()` | `identifier()` |
| `Player#hasPermissions(int)` | `player.permissions().hasPermission(Permissions.COMMANDS_MODERATOR)` |
| `Player#playNotifySound(…)` | gone — send a `ClientboundSoundPacket` yourself |
| `Items.BLACK_STAINED_GLASS_PANE` | `Items.STAINED_GLASS_PANE.pick(DyeColor.BLACK)` (`ColorCollection`) |
| `ItemStack#getMaxStackSize()` | `stack.getOrDefault(DataComponents.MAX_STACK_SIZE, 64)` |
| `new ResolvableProfile(profile)` | `ResolvableProfile.createResolved(profile)` — the class is abstract now |
| `MinecraftServer#getProfileCache()` | `server.services().nameToIdCache()` |
| `PlayerList#canPlayerLogin(SocketAddress, GameProfile)` | `(SocketAddress, NameAndId)` |
| authlib `GameProfile.getName()` / `getId()` | `name()` / `id()` — it is a record |

Almost all of it is absorbed by [`compat/Mc.java`](src/main/java/io/github/alphain24/staffcore/compat/Mc.java).

## Build

The Gradle wrapper is committed, so:

```bash
./gradlew build         # compile + tests -> build/libs/staffcore-1.0.0.jar
./gradlew test          # the JUnit suite on its own
./gradlew runServer     # dev server with the mod loaded
```

### Tests and CI

Two layers, because they catch different things.

**JUnit** (`src/test/java`) covers everything that runs without Minecraft — the storage layer,
address ranges, duration parsing, permission group resolution. These are the paths that only
execute when something has already gone wrong: a half-written transaction, a corrupt database,
a schema arriving from an older version. Impossible to exercise by playing the game, trivial
here. They found two real bugs on first run: backups taken in the same second collided on
their filename and silently failed, and the corruption fallback left an unusable backup at the
database path so "start empty" became "do not start".

**A boot check** (`.github/scripts/boot-check.sh`) starts a real server and fails if any hook
is broken. This is the layer that matters most, because it catches the failure a compiler
cannot see: a mixin that builds fine and then does not attach. That happened twice during
development and both times a human found it by reading the log. Verified in both directions —
it passes on a healthy build and fails on a deliberately broken one.

| Workflow | When | Does |
|---|---|---|
| `build.yml` | every push and PR | Compile, JUnit, upload the jar |
| `boot.yml` | push, PR, weekly | Boot a server, assert every hook applied |
| `snapshot.yml` | daily | The same boot check against the newest MC snapshot; opens an issue if it breaks, never blocks a merge |

The snapshot job is *expected* to fail sometimes. That is the point: a snapshot that moves an
injection point should surface on a schedule rather than on somebody's server the week the
release lands.

---

## The panel

`/staff` opens the root screen. Three bands, in the order a shift actually runs.

```
                        ┌─ StaffCore ─┐
  you      ▸  Staff Mode · Vanish · Staff Chat · Alerts · Command Spy · Return
  people   ▸  Players · Reports · Punish · Inventories · Notes · History · Security
  server   ▸  Server Control · Grief Log · Analytics · Item Scanner · Appeals · Discord
  info     ▸  Your Record · Server Status · Help
```

Anything you lack permission for is drawn as an **iron-bars "Locked"** bar naming the node,
rather than hidden. Staff can see what exists and ask for it instead of assuming the mod
is broken.

### Screen map

```
/staff  Staff Panel
 ├─ Players ─────────── Player List ─┬─ (left) whatever you came in for
 │                                   └─ (right) Player File
 ├─ Player File ──┬─ Punish ─── (offence) ─── Confirm     [or Manual ─ Duration ─ Reason]
 │                ├─ History          (paged, wipe behind a confirm)
 │                ├─ Notes            (paged, delete behind a confirm)
 │                ├─ Inventory        (live, read-only or editable)
 │                ├─ Snapshots ────── Snapshot View (delete, restore)
 │                ├─ Ender Chest      (live, 27 slots)
 │                ├─ Logs             (sessions / deaths)
 │                ├─ Linked Accounts  (same-IP alts)
 │                ├─ Appeals          (their side of it)
 │                └─ Security Check   (paged findings)
 ├─ Reports ─────────── claim / resolve / teleport
 ├─ Appeals ─────────── accept (lifts the punishment) / reject
 ├─ Server Control ──── chat lock · clear · broadcast · maintenance · TPS
 ├─ Grief Log ───────── paged block history ─┬─ Rollback (ghost preview, then confirm)
 │                                            └─ Rollback History (undo a rollback)
 └─ Analytics ───────── staff leaderboard
```

Nothing irreversible happens without a **Confirm** screen, and confirm and cancel sit five
slots apart so a misclick on a screen that just changed lands on filler.

---

## Icons

Chosen so a screen is readable at a glance without reading a single word.

| Thing | Icon | Thing | Icon |
|---|---|---|---|
| Staff Mode | Diamond Chestplate | Punish | Netherite Axe |
| Vanish | Eye of Ender | Warn | Paper |
| Freeze | Packed Ice | Kick | Iron Boots |
| Staff Chat | Oak Sign | Mute | Note Block |
| Alerts | Bell | Ban | Netherite Axe |
| Command Spy | Sculk Sensor | Temp-ban | Iron Axe |
| Teleport / Return | Ender Pearl | Permanent | Bedrock |
| Bring Here | Lead | Timed | Clock |
| Players | Player Head (real skin) | Lift punishment | Totem of Undying |
| Reports | Paper (stack size = count) | Lift mute | Jukebox |
| Notes | Writable Book | Wipe history | Lava Bucket |
| History | Book | Broadcast | Goat Horn |
| Inventory | Chest | Clear chat | Sponge |
| Snapshots | Writable Book | Chat lock | Barrier |
| Security | Spyglass | Maintenance | Iron Door |
| Item Scanner | Hopper | Server status | Beacon |
| Contraband vault | Bundle | Ender chest | Ender Chest |
| Grief Log | TNT | Analytics | Map |
| Broke a block | Iron Pickaxe | Placed a block | Bricks |
| Discord | Amethyst Shard | Help | Knowledge Book |
| Confirm / Cancel | Lime / Red Concrete | Locked | Iron Bars |

Player heads carry the real skin. Anything currently *switched on* gets an enchantment
shimmer, so state is visible in peripheral vision.

Frames are black stained glass; the invsee frame turns **red** in edit mode.

## Sounds

All of it in [`gui/Sfx.java`](src/main/java/io/github/alphain24/staffcore/gui/Sfx.java) — one file,
one voice. Navigation is quiet and high, state changes are pitched (up for on, down for
off), anything that lands on another player is loud and low. Every sound is sent to a
single client, so a vanished admin clicking through menus is silent to everyone else.

| Event | Sound | Pitch |
|---|---|---|
| Menu opens / closes | `block.barrel.open` / `close` | 1.5 |
| Button press | `ui.button.click` | 1.1 |
| Into a sub-menu / back out | `block.amethyst_block.chime` | 1.4 / 0.9 |
| Page turn | `item.book.page_turn` | 1.1 |
| Toggle on / off | `block.note_block.pling` / `bass` | 1.8 / 0.7 |
| Small win (note saved, report claimed) | `entity.experience_orb.pickup` | 1.3 |
| Big win (punishment landed, rollback done) | `entity.player.levelup` | 1.4 |
| Refused | `entity.villager.no` | 1.0 |
| Something broke | `block.anvil.land` | 1.9 |
| Staff mode on / off | `block.beacon.activate` / `deactivate` | 1.6 |
| Vanish on / off | `entity.enderman.teleport` | 1.6 / 0.8 |
| Invsee opens | `block.ender_chest.open` | 1.3 |
| Alert / new report | `block.note_block.bell` | 1.5 |
| Staff chat message | `entity.experience_orb.pickup` | 1.9 (quiet) |
| **A ban lands** (heard by all staff) | `entity.lightning_bolt.thunder` | 1.4 |
| Target: frozen / unfrozen | `entity.player.hurt_freeze` / chime | — |
| Target: warned / muted | `note_block.pling` / `bass`, low | 0.6 / 0.5 |

---

## Commands

**Everything lives under `/staff`.** Tab-complete the one word and the whole tree is there,
and nothing StaffCore adds can collide with another mod's `/ban` or `/kick`. Two commands
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

### If the x-ray detector never says anything

By design it is quiet, and quiet is indistinguishable from broken. `/staff xray <player>`
always answers — score, sample size, and the reasoning behind it — so you can tell the two
apart. The usual causes, in order of how often they are the real one:

`/staff xray <player>` now ends with **"Why you have not been alerted"** and names the one
reason that actually applies, rather than leaving you to work it out. The candidates:

- **You placed the ore you were breaking.** Ore a player placed themselves is excluded from
  their own score, so seeding a wall with iron ore to test the detector proves nothing. This
  is the one that catches people out, because it is the natural way to test.
- **Not enough mining yet.** `xraySampleFloor` is ore-plus-filler blocks in a six-hour
  window, and it is **200** rather than anything higher on purpose. Raising it does not make
  the detector more careful, it makes it blind to the person it is for: guided mining breaks
  *less* cover to reach more ore, so those sessions are the small ones. At a floor of 400 the
  measured set missed every cheat and kept every honest player in scope.
- **Score under the bar.** Alerts start at `xrayAlertConfidence`, **65**, with a quiet notice
  from `xrayNoticeConfidence`, **55**. Both sit in the gap measured between honest and guided
  mining — the worst honest pattern scores 50, the best-hidden cheat 81. An alert still means
  at least two of ore fraction, directness, detours and ancient debris agreed.
- **The timer has not come round.** The sweep runs every `xraySweepMinutes`, now 5.
- **You were testing as an operator.** The sweep once skipped everyone holding `staff.gui`,
  which without a permissions plugin resolves to op level — so whoever was testing was the
  one player guaranteed never to be scored. It now skips only staff who are *clocked on*, and
  `xraySkipStaffOnDuty: false` turns even that off.

**`xrayNoticeConfidence` (35)** is the other half of the fix: a score above it but below the
alert line produces a quiet, explicitly-not-an-accusation line to staff. Without it there is
no way to tell "nobody is cheating" from "this has never once run" — and for a long time it
was the second.

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

### Safety

- **Versioned migrations.** Schema changes are a numbered list driven by SQLite's
  `user_version`, so each runs once, in declared order, each in its own transaction. A fresh
  database is stamped at the current version rather than replaying history. Migrations are
  append-only: editing an existing one rewrites the past for servers that already ran it.
- **Corruption recovery.** The file is opened, `PRAGMA integrity_check`ed, and if it fails
  the damaged file is moved aside (kept, never deleted — it is the only copy of whatever was
  in it), the newest usable backup is restored, and failing that StaffCore starts empty.
  It never refuses to boot: a server is somebody's whole community and this is one mod on it.
- **Backups.** One per server start via `VACUUM INTO`, so the copy is coherent even under
  load — copying the file by hand while WAL is live gives you something that may be missing
  the log. Rotated to `databaseBackups`; `/staff backup` takes one on demand.
- **Export.** `/staff export` writes one CSV per table into a timestamped folder. A backup
  restores; an export is *readable*, which is what a data request or an audit actually needs.
  The table list comes from SQLite itself, so a table added later exports without anybody
  remembering to update the code.
- **Transactions.** `Storage.inTransaction` wraps the multi-statement work — deleting a
  snapshot and its items, writing a rollback restore point — so a crash in the gap cannot
  leave a half-applied change nothing will ever notice. It is also what stops a
  four-thousand-block rollback costing four thousand separate commits.

Verified by deliberately corrupting a live database: detected, quarantined, restored from
backup, server booted normally.

**In memory only** (cleared on restart): `/back` return points, mass-grief burst counters,
and inspect mode. Everything else survives.

---

## Design notes

A few decisions worth knowing before you change things.

**Item movement is refused server-side.** `Gui.clicked` blocks every click that is not a
registered button, rather than trusting the client. A modified client cannot walk items
out of a menu.

**Menus open one tick late.** `Guis` defers every open through `server.execute`, because
most opens happen from inside a button handler — swapping `containerMenu` underneath the
code still using it is how server-side GUIs desync.

**Invsee is genuinely live, and works offline.** `InventoryViewContainer` proxies slots
through an `InventorySource` instead of copying: a live source is the target's real
`Inventory`, a stored source is their save file decoded through `PlayerList#loadPlayerData`.
Offline views are always read-only — writing NBT back under a player who might reconnect
mid-edit is a corruption risk no amount of care makes safe. A read-only viewer's writes are
dropped at the container, not at the UI. Opening in edit mode is announced to staff chat.

**Vanish is 27 mixins, not a visibility flag.** Invisibility is the easy part; the leaks are
sound, sleep counting, pressure plates, mob targeting, trial spawners, the server-list
sample, command selectors, projectile trails. Two hooks earned their keep by covering whole
families at once: `PlayerList#broadcastSystemMessage` catches death, advancement and
leave lines together, and `getNearestPlayer` covers mob spawning, phantoms and proximity AI.

One deliberate half-measure: `DistanceManager#addPlayer` raises the spawn counter *and* the
chunk-loading ticket in one call, so cancelling it — the obvious reading of "vanished
players don't load chunks" — would leave the staff member in unloaded void. Only the spawn
half is redirected.

**Staff tools cannot leave staff mode.** Four layers, because one is not enough: manual
drops are refused (`PlayerDropMixin`), death drops are stripped before the drop loop runs
(`InventoryDropMixin`), any tool that reaches the ground is destroyed rather than collected
(`ItemEntityMixin`), and a two-second sweep removes one from anybody not on duty and alerts
staff. A loose tool is a permanently circulating item the scanner has to keep flagging.

**Noclip is spectator, deliberately.** The client runs its own collision, so a server-side
`noPhysics` flag is ignored — the player walks into the wall on their own screen and gets
corrected back. Vanilla's own "may I pass through blocks" check is `isSpectator`, so that is
what the toggle switches to, restoring the duty gamemode on the way out.

That has one consequence worth knowing: vanilla also drops **container clicks** for
spectators, before they reach the menu at all, which silently kills every button in this
mod. `SpectatorMenuMixin` redirects that single check so it answers "no" only when the open
menu is a StaffCore `Gui`. Real chests stay untouchable to spectators.

**The cursor is never cleared.** `Gui.resync()` re-sends the window but leaves the carried
stack alone. An earlier version cleared it to "reset" the cursor, which silently destroyed
items: in edit mode, picking a stack up and clicking anywhere inert deleted it. Vanilla's
`removed()` already returns a carried stack when the menu closes, so nothing else has to.

**One chat handler.** Mutes, the staff channel and the global chat lock all want a say;
three separate listeners would run in module-registration order. `ChatRouter` runs them in
one explicit order: a mute beats everything, staff chat beats the global lock.

**Inventories are copied slot-by-slot, never serialised.** The NBT/component storage API
has been rewritten twice recently; `getItem`/`setItem` has not moved.

**Rollback does not duplicate items.** Restoring a broken block while the griefer keeps the
drop is a duplication exploit, so rollback reclaims what the restored blocks originally
dropped — ground items first, then the offender's own inventory. Rolled-back rows are
*marked*, not deleted, so running the same rollback twice is a no-op and the audit trail
survives. It always previews first, and works by player **or** by area.

**Punishments come from offences, not from staff judgement.** The ladder for each offence
lives in config; the rung is picked from how many times that player has already been done
for *that specific offence*. Three warnings for spam do not make their first griefing
report a permanent ban.

**Anything that used to die on restart now persists.** Vanish, freeze anchors, staff mode
and the on-duty inventory stash all live in the database. Clocking on writes the stash
before clearing the inventory, and it is cleared only after a successful hand-back — so the
failure mode is a duplicate restore attempt, never a loss.

**The grief log never runs on the main thread.** Reads are paged (28 rows plus a count) on
a dedicated single thread and handed back via `server.execute`. Writes go the same way.

**Punishments have one door.** `PunishmentModule.apply` is the only path that writes,
enforces, broadcasts and mirrors to Discord. Nothing else disconnects a punished player.

## Version-sensitive code

Everything that touches a volatile Minecraft signature is isolated so a patch release is a
one-file fix, not a hunt:

| File | Holds |
|---|---|
| [`compat/Mc.java`](src/main/java/io/github/alphain24/staffcore/compat/Mc.java) | identity, server/level access, permissions, sounds, teleport, player heads, colour collections, stack limits, block registry, tick time |
| [`gui/Sfx.java`](src/main/java/io/github/alphain24/staffcore/gui/Sfx.java) | every `SoundEvents` constant |

This paid off immediately. Porting to 26.2 turned up thirteen breaking API changes, and
twelve of them were fixed inside `Mc.java` alone — the modules and menus only needed a
mechanical rename.

`Mc.sound` has both a `SoundEvent` and a `Holder<SoundEvent>` overload on purpose:
`SoundEvents` declares some entries as one and some as the other — in 26.2
`UI_BUTTON_CLICK` and the note blocks are `Holder.Reference`, the rest are bare
`SoundEvent` — and having both means `Sfx` compiles whichever way a constant is declared.

### Mixins

Two configs, deliberately:

- **`staffcore.mixins.json`** (`required: true`) — `PlayerListMixin` only. It injects into
  `PlayerList#canPlayerLogin`, which is the one place vanilla asks "may this profile in?".
  Bans and maintenance mode do not work without it, so it fails loudly.
- **`staffcore.optional.mixins.json`** (`required: false`, `defaultRequire: 0`) — everything
  else: command spy, the PLACE half of the grief log, container close, and the two dozen
  vanish hooks. These target methods whose signatures move more often. If one stops applying
  after an update the server still boots; you lose that one feature until the injection
  point is fixed.

`StartupCheck` runs once the world is up and asks two things of each hooked method: whether
the target still exists, and whether our handler was actually injected into it.

The second question is answered by **asking Mixin**, not by inspecting the target class.
`MixinFailureRecorder` implements `IMixinErrorHandler` and is registered during `preLaunch`
(`MixinDiagnostics`), so it hears about every failure from Mixin's own `onApplyError`
callback, with the reason attached.

Two earlier attempts inferred it reflectively and were wrong in opposite directions, which is
worth recording so nobody tries them again. Searching for the handler by its declared name
reported *every working mixin as broken*, because Mixin renames merged handlers. Loosening
that match then reported *genuinely broken mixins as fine*, because Mixin merges the handler
method into the target **before** it wires the injection up — so a failed wiring leaves an
orphaned method behind for a reflective search to find. A present handler proves the mixin
was processed, not that it took effect, and nothing reflective can tell those apart: the
difference is in the target method's bytecode.

Failures with no entry in the hook table are still reported, by mixin class name, so nothing
fails silently just because nobody wrote it a friendly label.

---

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

### Recently closed

Things that used to be on this list, and what replaced them:

| Was | Now |
|---|---|
| Rollback ignored offline offenders and banked loot | Chests they filled are emptied back; the rest is collected on next login |
| Container rollback failed silently on a full chest | The deferred count is reported; rows still stay un-retired for a retry |
| The vault only returned items to online players | Offline returns are queued and delivered on next login, cancellable meanwhile |
| The vault listed the newest 500 items | Paged through the database; every state is reachable, including returned and destroyed |
| Snapshots were fixed at 10 per player | `maxSnapshotsPerPlayer`, 0 for unlimited |
| The contraband watch never saw world containers | Chests are checked as they are opened and closed; staff tools found in one go to the vault |
| Ender chests were documented as swept but were not | They are now, behind `scanEnderChests` |
| The contraband rules screen refused to edit past 45 entries | It pages |
| Vanished players always loaded chunks | `vanishLoadsChunks`, defaulting to the old behaviour |
| Every node fell back to op level without a permissions mod | `config/staffcore-permissions.json` — real groups, `/staff perms` |
| Ledger parity: no search, no purge, no inspect toggle | `/staff search`, `/staff purge`, `/staff inspect` |
| Rollback of a double chest restored only half of it | Both halves resolve as one container, from either block |
| Shift-clicking in the inventory view did nothing useful | It moves stacks between the target and your own inventory |
| Inspect mode broke the block and reported nothing | It reports, and the break is cancelled twice over |
| A death snapshot restored while the drops were still on the ground doubled everything | The drops are swept first; only what is genuinely gone is recreated |
| Stolen items could be seen in the container log but not undone | Shift-click a take to undo one thief, or put everything back at once |
| Snapshots showed nothing in armour/offhand, and restoring gave none back | The reader sized its buffer to 36 and dropped slots 36-42; it now uses the real container size |
| Rolled-back chests returned single and misoriented | `block_log.state` stores the full block state, not just the id |
| A thief who dropped, banked or ender-chested the loot kept it | `LootRecovery` chases ground, inventory, ender chest, banked chests, then queues the rest |
| Fresh installs silently lacked columns that migrations added | `CREATE TABLE` and the migration list are asserted to agree |
| `minecraft:spawn_egg` was not an item, so no spawn egg was ever contraband | `#spawn_eggs` rule covers all 88; configs migrated; self test fails on dead entries |
| Inventory views never updated once open | They redraw twice a second |
| No way to read an item's real component data | Middle-click a slot, or `/staff nbt` |
| X-ray flagged strip mining and ignored ore type | Detour detection, per-ore breakdown, rarity weighting, four time windows |
| Renamed players vanished from search | Stored names refresh on join |
| Block-log writes queued at shutdown were lost | The writer drains before the database closes |
| Container logs on a double chest only ever showed the half you clicked | Both halves are queried — inspector, container log and theft undo |
| Rollback charged you for blocks broken in creative, which never dropped anything | Gamemode is recorded at break time; creative breaks cost nothing |
| Owed items and anti-cheat findings could be created but never viewed or cleared | `/staff owed`, `/staff owed forgive`, `/staff anticheat` |
| The grief log was empty on every default install | The noise filter's clause was built but never added to the query |
| A migration inserted mid-list never ran on upgraded servers | Migrations are append-only, and drift is reconciled on every boot |
| Copper double chests read as two unrelated singles | Pairing asks `chestCanConnectTo` instead of comparing blocks |
| Chest boats and container minecarts were never watched | Logged like any other container |
| Creeper and TNT damage was invisible to the log | Explosions are recorded, contents included, and roll back like any other break |
| Fire burned builds down with nothing in the log but the flint-and-steel | Every block fire eats is recorded, contents included |
| Rollback debts never expired and survived an undo | `debtExpiryDays`, and undo cancels its own debts |
| Log output was mojibake on non-UTF-8 consoles | Console strings are ASCII, checked in the source and in the boot log |
| Item recovery missed anything in an unloaded chunk or already pocketed | Pickups are logged, so recovery is a query rather than a search |

## Adding a module

```java
public class ExampleModule implements Module {
    @Override public String id() { return "example"; }
    @Override public void onEnable() { /* register events here */ }
}
```

Register it in `StaffCore.registerModules()`, add a typed accessor to `Mods`, add any nodes
to `Nodes`, and hang a button off `StaffPanelMenu`. Menus extend `Gui` (fixed layout) or
`PagedGui<T>` (28-entry list with page controls) — describe the screen in `build()` and
call `render()` at the end of your constructor.

## Licence

MIT. See [LICENSE](LICENSE).
