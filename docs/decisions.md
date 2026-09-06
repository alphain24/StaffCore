# Decisions

Why things are the way they are, with the measurements that decided them. Kept separate from
the README so the reasoning is findable without making the front page longer.

**Decisions with a measurement behind them:** [X-ray thresholds](#x-ray-thresholds) ·
[Pinning the build](#pinning-the-build) · [Namespace](#namespace)

**Background moved out of the README:** [Porting to 26.2](#porting-notes-for-262) ·
[The panel](#the-panel) · [Screen map](#screen-map) · [Icons](#icons) · [Sounds](#sounds) ·
[When x-ray says nothing](#if-the-x-ray-detector-never-says-anything) ·
[Storage safety](#storage-safety) · [Design notes](#design-notes) ·
[Version-sensitive code](#version-sensitive-code) · [Recently closed](#recently-closed) ·
[Adding a module](#adding-a-module)

---

## X-ray thresholds

**Date:** 2026-09-06
**Decides:** `xraySampleFloor`, `xrayAlertConfidence`, `xrayNoticeConfidence`,
`xrayRatioThreshold`, `xrayDirectnessFloor`
**Lives in:** `XrayTuning`, which the config defaults read from
**Reproduce with:** `./gradlew test --tests '*XrayThresholdTest'`

### The problem with the previous values

The thresholds were last moved from `(500, 70)` to `(200, 55)`. The recorded reason was that
the detector was too quiet — nobody cleared 500 blocks in six hours, so the sweep never scored
anybody.

That reason cannot be wrong, which is what is wrong with it. Any bar can be lowered until the
feature speaks, and "it speaks more often now" is not evidence that the things it says are
true. There was no false-positive measurement anywhere in the repository, so the cost of the
change was never established — and the whole output of this detector is an accusation aimed at
a named player.

### What was measured

Six mining patterns generated block by block with plausible geometry (`MiningPatterns`), since
roughly half the detector's signal comes from *where* blocks were broken rather than what they
were. Five are honest, one is the cheat:

| Pattern | Intent | Blocks |
|---|---|---|
| Strip mine | straight tunnels at y=12, veins taken from the walls | 787 |
| Branch mine | a spine with eighteen ribs | 525 |
| Cave clearing | wandering an open system, taking what is exposed | 616 |
| Quarry | a pit taken down five layers, everything removed | 840 |
| Deepslate diamond hunt | y=-54, going for diamond specifically | 1020 |
| **Guided tunnelling** *(control)* | short right-angle approaches straight on to veins | 328–450 |

Each was generated at 40 seeds — 200 honest sessions and 40 guided — and scored across a grid
of `(sampleFloor, alertConfidence)` pairs.

### Result 1: the old settings alerted on honest mining

At the shipped `(200, 55)` with `xrayRatioThreshold` 0.04:

| Pattern | Score | Against an alert line of 55 |
|---|---|---|
| Strip mine | 60 | **alerts** |
| Deepslate diamond hunt | 60 | **alerts** |
| Branch mine | 54 | near miss |
| Cave clearing | 54 | near miss |
| Quarry | 0 | quiet |
| Guided tunnelling | 96 | alerts (correctly) |

Across all 40 seeds the worst honest session scored **66**. The detector was not quiet — it was
reporting ordinary strip miners and anyone hunting diamond at y=-54 to staff, and lowering the
bar had made that worse.

The dominant cause was `xrayRatioThreshold` at 0.04. Deepslate at y=-54 genuinely returns
around 13% ore, which scored the full 40 points on that signal alone. The ground being rich is
not evidence about the player.

### Result 2: the sample floor runs backwards

The knob that reads as "be more careful" is the one that does the most damage.

Guided mining is *efficient*: it breaks far less cover to reach far more ore. That makes the
cheating sessions the **small** ones — 328 to 450 blocks — while every honest pattern ran 525
to 1020. Raising the floor filters out precisely the population the detector exists for.

| Sample floor | Honest players scored | Cheats caught | Cheats missed |
|---|---|---|---|
| 100 | 200 | 40 | 0 |
| 200 | 200 | 40 | 0 |
| 300 | 200 | 40 | 0 |
| **400** | **200** | **0** | **40** |
| 500 | 200 | 0 | 40 |

At 400 the detector misses every cheat and keeps every honest player in scope. The original 500
was worse than switching the feature off, and "raise the floor to be safe" is the most natural
wrong move available.

### Result 3: the two populations separate cleanly

With `xrayRatioThreshold` at 0.12 and `xrayDirectnessFloor` at 12.0, the grid shows **zero
false positives across all 200 honest sessions at every alert level from 55 to 75**, while all
40 guided sessions are caught at any floor ≤ 300.

- Worst honest session: **50**
- Weakest guided session: **81**

### Chosen

| Knob | Value | Why |
|---|---|---|
| `xraySampleFloor` | 200 | 128 blocks of headroom below the smallest cheat; raising it blinds the detector |
| `xrayAlertConfidence` | 65 | midpoint of the measured gap — 15 above worst honest, 16 below weakest cheat |
| `xrayNoticeConfidence` | 55 | just above the worst honest score, so an honest player draws not even a notice |
| `xrayRatioThreshold` | 0.12 | 0.04 scored rich ground as if it were evidence |
| `xrayDirectnessFloor` | 12.0 | unchanged; it was not a contributor to the false positives |

`XrayThresholdTest` asserts the alert line stays inside that gap, so moving any of these
without re-measuring fails the build.

### What this does not establish

**These are generated patterns, not real players.** The honest set is an informed guess at how
people mine, and a real population is wider and stranger than six shapes at forty seeds. What
the measurement establishes is that the detector no longer flags the specific honest behaviours
it demonstrably used to flag — not that it flags nobody.

`XrayReplay` exists to close that gap on real data. Point it at a copy of a live database:

```bash
./gradlew test --tests '*XrayThresholdTest' -Dstaffcore.replay.db=/path/to/staffcore.db
```

It prints how many distinct players would be alerted and noticed at each candidate pair,
reading the database read-only and asserting nothing about somebody else's player base. Until
that has been run against a real server, this entry describes a detector tuned against
synthetic data, and the README's known limits say so.

A cheat who deliberately mines cover to keep their numbers respectable still scores below the
line. That is inherent to scoring a block log and is not something a threshold can fix.

---

## Pinning the build

**Date:** 2026-09-06
**Decides:** `loom_version`, and wrapper validation in CI

`loom_version` was `1.17-SNAPSHOT`. A snapshot is republished under the same coordinate, so
the same commit could build one day and fail the next with nothing in the history to blame —
and the failure would land on whoever pulled next, not on whoever caused it. Pinned to
**1.17.20**, the newest released 1.17.x, which is the line the snapshot was tracking.

`fabric_api_version` was already exact at `0.157.0+26.2` (the `+26.2` is part of Fabric's
version string, not a dynamic-version range), as are the loader, SQLite, JUnit and the Gradle
distribution. Loom was the only moving part.

Wrapper validation was added to all three workflows. `gradle-wrapper.jar` is a checked-in
binary that every build executes before any code in this repository runs, which makes it the
quietest place in the project to hide something; `gradle/actions/wrapper-validation` matches it
against Gradle's published checksums.

---

## Namespace

**Date:** 2026-09-06
**Decides:** the Java package and `maven_group`

Moved from `dev.lebron.staffcore` to `io.github.alphain24.staffcore`.

`dev.lebron` asserts ownership of a domain nobody here controls. That is a problem twice over:
it is not a namespace this project can defend if anything is ever published to a Maven
repository, and it is a name collision waiting to happen with whoever does own it. `io.github.`
plus a GitHub username is the conventional answer for a project without its own domain, and it
is tied to an account that actually exists and holds this repository.

Done before the first release rather than after, because the package name is part of the
published artifact: changing it later breaks every mixin config, every entrypoint and anything
anybody has built against it.

Touched 162 files — every source file, both mixin configs (which name the package as a
string, so a rename that misses one fails at runtime rather than at compile time),
`fabric.mod.json`'s two entrypoints, `maven_group`, and the README's source links. Verified by
the boot check rather than the compiler: 17/17 hooks present, 13 verified applied.

---

# Moved from the README

The README had grown to eight hundred lines, and the reasoning was the best material in it and
the part nobody reached — it sat below four reference sections a reader has to scroll past.
Everything below was moved here unchanged so the front page could stay a reference.

## Porting notes for 26.2

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

---

## Screen map

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

---

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

---

## If the x-ray detector never says anything

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

---

## Storage safety

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

---

<a id="testing-layers"></a>

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

---

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

---

## Recently closed

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

---

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
