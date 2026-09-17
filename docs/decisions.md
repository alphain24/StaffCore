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

## Commit history

**Date:** 2026-09-06

Before this round the repository had two commits, while the README cited development history
as evidence throughout: bugs found on the first JUnit run, two mixin failures caught by reading
logs, two reflective detection approaches that failed in opposite directions. All of that is
probably true and none of it was verifiable, because none of it was in the history. It also
could not be bisected, which is the practical cost — a regression traceable to "one of these
two commits" is traceable to nothing.

From here the history is at real granularity: one commit per change, with a message that says
what was wrong and why the fix is shaped the way it is. That is worth stating rather than
assuming, because it is the sort of discipline that quietly stops.

**What the history still does not cover.** Everything before this round remains two commits.
The claims the README made about that period are not made verifiable by anything here; where
they described a bug that is now covered by a test, the test is the evidence and the anecdote
is decoration. Several of those anecdotes were moved into this file with the design notes, and
they should be read as recollection rather than record.

---

## Inventory mutation paths

**Date:** 2026-09-07

Remediation item 7 described "four independent paths" that write to player inventories:
rollback debit, invsee edit, snapshot restore, vault return. That enumeration was **wrong**
twice over. It routed a fifth it had not named — pending settlement — and the Gate 0 audit
found two more origins writing directly, outside the gateway entirely.

The guarantee worth stating is not "there are four paths". It is **every mutation goes through
one door**, which is now enforced mechanically by `GatewayIsTheOnlyDoorTest` rather than
asserted in prose.

There are seven mutation origins:

| Origin | What it is | Named in item 7? |
|---|---|---|
| `ROLLBACK_DEBIT` | taking items back after a rollback | yes |
| `INVSEE_EDIT` | a staff member editing an inventory | yes |
| `SNAPSHOT_RESTORE` | writing a recorded inventory back | yes |
| `VAULT_RETURN` | handing a confiscated item back | yes |
| `CONFISCATION` | taking contraband, or a leaked staff tool | **no — found by Gate 0** |
| `STAFF_MODE_STASH` | clearing on clock-on, restoring on clock-off | **no — found by Gate 0** |
| `PENDING_SETTLEMENT` | delivering or collecting while offline | yes |

The vault showed the asymmetry best: handing an item *back* was audited through the gateway
while *taking* it was not.

### The audit floor

The date this matters on is not the date the gateway was written. It is the date each origin
started going through it — before that, those items moved with **no audit row at all**, and an
investigation reading `inventory_audit` for an old incident will find nothing and must not read
that absence as evidence that nothing happened.

There are two floors, because the door closed in two stages.

| From | Commit | Origins that began writing an audit row |
|---|---|---|
| **2026-09-06** | `362fb7d` — item 7, one door for every write | `ROLLBACK_DEBIT`, `INVSEE_EDIT`, `SNAPSHOT_RESTORE`, `VAULT_RETURN`, `PENDING_SETTLEMENT` |
| **2026-09-07** | `68a79dd` — Gate 0, close the two gaps the audit found | `CONFISCATION` (contraband, the per-slot button, the leaked-staff-tool sweep), `STAFF_MODE_STASH` (clearing on clock-on and restoring on clock-off), and the vault *take* side |

So: an inventory question about anything before 2026-09-06 has no audit trail in this mod at
all. A question about confiscation or the staff-mode stash between 2026-09-06 and 2026-09-07
has one only for the five origins in the first row — a staff member who confiscated an item on
the 6th left no record of it here, and that is a gap in the data rather than a clean sheet.

`GatewayIsTheOnlyDoorTest` is what stops a third floor being added later without anybody
noticing, and it dates from `68a79dd` too.

---

## Phase 2.3 coverage

**Date:** 2026-09-07

Gate 2 asks that every item in 2.3 has a test or a documented manual check. This is that
record, written because the honest answer is not "all of them are tested" — some of these are
a line of chat appearing on a screen, and asserting that a component was constructed is not the
same as knowing a person read it.

Three categories, and the distinction is the point:

- **Automated** — a test fails if the behaviour goes. Twenty of twenty-four.
- **Automated in part** — the logic is tested; the rendering is not. The test would still pass
  if the line never reached a screen.
- **Manual** — needs eyes. Listed in the handbook under the checks worth running by hand.

| # | Item | Covered by |
|---|---|---|
| 1 | Operation id on every destructive command | `OperationIdTest`; the echo is in one helper, `okWithOp` |
| 2 | Zero results reported explicitly | **manual** — a scan found one unguarded list; the rest were already explicit |
| 3 | Relative and absolute timestamps together | `TimeFormatTest` |
| 4 | Prior-punishment count beside a name | **in part** — `Link.subject` resolves it; that staff see two different broadcast lines is manual |
| 5 | Punishment id and appeal code on the disconnect screen | `DisconnectScreenTest`, `AppealCodeTest` |
| 6 | Ban message states reason, length, expiry, appeal | `DisconnectScreenTest` |
| 7 | Tab-complete offline players from the database | `KnownPlayersTest` |
| 8 | Duration parsing, and refusing what it cannot read | `DurationParserTest` |
| 9 | Ambiguous names refused with candidates | **in part** — `KnownPlayersTest` covers resolution; the clickable candidate list is manual |
| 10 | Confirmations expire | `StaffSessionTest` |
| 11 | `/staff undo` with no argument | `StaffSessionTest`, `BypassAttemptTest` |
| 12 | The last looked-up player | `StaffSessionTest` |
| 13 | Refuse to punish at or above your own rank | `RankTest`, `BypassAttemptTest` |
| 14 | Refuse to punish yourself | `RankTest` |
| 15 | Warn on a rollback overlapping spawn | `RollbackWarningTests` (gametest) |
| 16 | Warn above `rollbackWarnBlocks` | `RollbackWarningTests` (gametest) |
| 17 | Region lock for the duration of a rollback | `RegionLockTest`, `RollbackWarningTests` |
| 18 | Staff mode survives a restart | `StaffModePersistenceTest` |
| 19 | Three briefing lines on staff join | **in part** — `StaffBriefingTest` covers the counts and the silence; the lines appearing is manual |
| 20 | Online player list recorded against an incident | `WitnessesTest`, `AccountabilityTests` (gametest) |
| 21 | A ban expiring while offline noted in the case | `ExpirySweepTest`, `AccountabilityTests` (gametest) |
| 22 | `server_version` and `mod_version` on every audit row | `AuditVersionsTest` |
| 23 | Reason is always free text | **manual** — the command path is exercised throughout; the one-click fill from the panel is a screen |
| 24 | Name history with timestamps | `NameHistoryTest`, `AccountabilityTests` (gametest) |

### What the "in part" rows actually mean

Each of them has a real test of the thing that can be wrong in a way nobody notices — the
count, the resolution, the silence when there is nothing to say — and no test of the last inch,
where a component is handed to a player. That inch fails visibly: a missing line is missing on
every join, to every staff member, immediately. It is the arithmetic behind it that fails
quietly, and that is the half with tests under it.

### Two guards added while doing this

Both were written after being bitten, not before.

**`FreshInstallTest`.** A new database skips every migration by design and is stamped up to
date, so `incident_witness` — written only as a migration — did not exist on a fresh server
while the version counter read as current. That is the second time this shape has appeared;
the first was `block_log.gamemode`, in the mirror direction. The test now opens a new database,
scans the source for every table the code reads or writes, and checks they are all there.

**`GametestRegistrationTest`.** The gametest entrypoint list is written by hand, so adding a
test file is two steps and only one of them fails loudly if it is missed. `AccountabilityTests`
was written, compiled, and ran zero times, and the suite reported success — which is the one
result a test must never be able to give.
---

## X-ray detection, rebuilt

**Date:** 2026-09-08

The weighted 0-100 score is gone. It combined four signals with weights somebody chose, and
its problem was not accuracy — it separated the corpus — but that it had no units. Moving the
threshold from 70 to 65 changed the number of alerts and nothing in the repository could say
what it changed about the claim being made.

A hypergeometric p-value has units: if this player had been digging without knowing where the
ore was, the chance of doing at least this well is one in whatever. That is a sentence a staff
member can repeat to the person they are confronting, and one an appeal can argue with.

### The population is the modelling decision

Not the blocks removed — every one of those was drawn, so the sample is the population and the
question is vacuous. Not the bounding box — somebody mining at two ends of a corridor gets half
a million blocks they were never near.

It is **the excavation plus one step of rock**: what they were choosing between at each swing.
That shape decides the answer. A straight tunnel draws nearly its whole shell, so a tunneller
scores as unremarkable whatever they find — correctly, because they were not choosing. A dig
that wanders has a shell far larger than the path through it.

Six-connected, not twenty-six. A diagonal is not reachable in one swing, and counting it would
inflate the population roughly fourfold — which makes every result look more surprising than it
is, in the direction that accuses people. **Where the tie could go either way, it goes towards
saying nothing.** Degenerate inputs are the same principle applied to arithmetic: an empty
volume, more ore than blocks, more found than drawn all return p = 1, because a one-in-a-million
p-value out of a bookkeeping mistake is how somebody gets banned for a division by zero.

### Tool and fortune are not axes of this model

The brief asked for segmentation by y-band, dimension, cave-versus-solid-rock, **and tool and
fortune level**. The first three are in. The last two are not, and will not be.

`block_log` records neither, so they could not be applied to a single existing row — but that is
the smaller reason. The three that are in all change **how much ore was in the rock**, which is
the population term the calculation needs. Fortune changes what drops from an ore once it has
been found; it is not a term in a draw-without-replacement model at all. Tool tier is a proxy
for intent, and intent does not belong in the statistic — it belongs in the human reading it.

Recorded so it is not re-proposed by analogy with the axes that do belong.

### A correctness bug the rewrite fixed, not a performance win

The old sweep iterated `server.getPlayerList().getPlayers()`. **It only ever scored players who
were online at the moment it ran** — and somebody who mines for an hour and then logs off is
exactly the profile worth scoring. The block log outlives the session; the sweep did not use it
that way. The window now decides who is examined, not who happens to be connected.

Moving the work off the tick was the reason for touching it. This was the more important thing
found while doing so.

### Timings

Measured by `XrayTimingTests` on a real server: 8 players x 1200 breaks = 9,600 rows of
`block_log`, one segment scored, 2,740 block states censused. Three runs, cold JVM each time:

| Stage | Runs | Where it runs |
|---|---|---|
| Read `block_log` | 11.6 / 14.7 / 30.7 ms | **worker thread** in the sweep; caller's thread for `/staff xray` |
| Ore census | 2.7 / 2.7 / 4.5 ms | **server thread** — unavoidable, see Known limits |
| Arithmetic | 2.0 / 2.0 / 3.0 ms | server thread |

So roughly **5 ms of a 50 ms tick, once every `xraySweepMinutes`**. The read — the largest part
by far, and the part that grows with history — is the half that moved off.

Two optimisations came directly out of taking the measurement rather than assuming:

- The census turned every block state into a string to compare it, allocating an `Identifier`
  and a `String` per block. Comparing `Block` instances instead took it from 8.6 ms to 2.7 ms.
- The tail sum called six log-gammas per term. A log-space recurrence made it three
  multiplications and a logarithm. **Three earlier versions of that loop were wrong**, each
  quietly: summing with per-term log-gammas was merely slow; walking down from the top risked
  the first term underflowing to zero, which reads downstream as certainty of guilt; and
  walking down with an early exit stopped on the first iteration, because terms rise towards
  the mode before they fall — that one turned an ordinary result into one in 10^242. All three
  are now tests.

### Thresholds

`xrayAlertConfidence` stays at 65, and now has a justification rather than a history. Across
twenty seeds of each of six honest techniques and the guided one, the corpus separates: the
worst honest session and the weakest guided one do not overlap, and 65 sits in the gap.
`XrayThresholdTest` asserts the gap exists rather than asserting the number.

`xraySampleFloor` (200 blocks broken) is replaced by `xrayMinimumVolume` (512 blocks of rock
within reach). A count of blocks broken says nothing about whether the arithmetic can work; a
player who removed thirty blocks from a pocket of forty has drawn almost all of it, and the
statistics on a population that small are confident and meaningless. That is where the old
detector's false positives lived.

`xrayRatioThreshold` and `xrayDirectnessFloor` are gone with the signals they weighted.

### The corpus states its own ore density

The synthetic patterns have no world to census, so they have to supply the ground truth. The
first attempt guessed a density from real Minecraft generation rates and applied it to the whole
population, taking the larger of that and what the player found — which says "there were exactly
as many ores as they got", and scored **every honest pattern at 99**.

The density is now measured from the corpus itself: an unguided miner's find rate *is* the
ambient density, so the clean patterns define it and the guided one is scored against it. Not
circular — the question asked of the guided pattern is precisely whether it beat an unguided
miner in the same rock, and the clean patterns are the only available statement of that rate.
---

## Two decisions in the replay viewer

**Date:** 2026-09-08

Both are places where the brief asked for one thing and the code does another. Recorded with
the reasoning rather than the outcome, because the outcome on its own reads as an oversight.

### The inventory is not stashed

The brief says to restore state "through the existing staff-mode stash machinery". The
gamemode, the position and the vanish flag go through the same *rule* as the stash — written
before anything changes, cleared only after the player is demonstrably back. The inventory does
not go through it at all.

A spectator cannot pick anything up, drop anything, or be hit. There is nothing for a stash to
protect the inventory from, and `InventoryGateway.replaceAll` is the one piece of code in this
mod that can lose somebody's entire inventory. Adding a second caller to it to solve a problem
that does not exist trades a real risk for an imaginary one.

The rule that was worth taking from the stash is the ordering, not the storage.

### The replay sidebar is sent, not registered

A vanilla sidebar lives on `ServerScoreboard`, which is shared by everybody on the server. A
staff member investigating a player would have put a panel reading "Ore taken: 30 of 40" on
that player's screen, along with everyone else's.

So the objective and its rows are sent as packets to one connection. The objective exists only
in the client that receives it: nothing is stored server-side, nothing is broadcast, and a
disconnect takes it away with no cleanup because there was never anything on the server to
clean up. Same technique as the decoys and the rollback preview — tell one client something the
server does not believe.

### And one thing deliberately absent from that sidebar

There is no "Decoys broken: 0" line. Canaries are switched off entirely when a bulk anti-xray
mod is installed, so a zero means "no separate signal was available" and not "this player
passed a test" — and on a panel somebody reads immediately before deciding whether to ban
someone, it would be the most reassuring line there. Absence of signal reading as a passed test
is how a panel misleads the person who trusts it.

---

## Canary false-positive rate

**Date:** 2026-09-08

> **Superseded on 2026-09-13** by [Decoys count when uncovered](#decoys-count-when-uncovered).
> The zero below was real, and so was the reason for it: the rule it measured made a hit
> impossible for everybody, cheaters included.

Gate 3 asks for this measured against a synthetic legit-mining corpus. Run by
`CanaryFalsePositiveTests` on a real server, against real decoy placement and the real
retirement path.

**Sixty-two decoys across five honest mining shapes. Zero hits.**

| Scenario | Decoys | Hits |
|---|---|---|
| Tunnel passing beside decoys | 3 | 0 |
| Neighbour then decoy, back to back | 4 | 0 |
| Two miners converging on one decoy field | 3 | 0 |
| Explosion, then mining the rubble | 16 | 0 |
| Branch mine across a decoy field | 36 | 0 |
| **Control: decoy broken cold** | **3** | **3** |

### Why the control is the important row

A decoy layer that never places, never matches the rock, or never records a hit scores a
false-positive rate of zero — the same number a perfect one scores. Every clean result above is
worthless without a sequence that must fire and does.

### The conditions, since a percentage is not actionable

The rate is zero because of a structural property rather than a threshold: a decoy is placed
only in fully encased rock, so reaching it requires breaking one of its six neighbours, and
**that break retires it inside its own event**. The orderings tried:

- **Immediate succession.** Neighbour break and decoy break with nothing between them, which is
  the fastest a player with efficiency and haste can produce and the ordering that would break
  a queue. Retirement is synchronous, so the second break finds nothing.
- **Across breakers.** One player's tunnel exposing another player's decoy. Retirement is
  global; scoping it per owner would leak false positives between people who never met.
- **Without a break event at all.** An explosion uncovering sixteen decoys at once, then a
  player mining the rubble. Handled through `logExplosion`, and retired *before* the
  `logExplosions` config gate — whether explosions are logged is a preference, whether a decoy
  is left in a crater is correctness.

`CanaryRetirementIsSynchronousTest` guards the property the corpus rests on: nothing in
`Canaries` may use an executor, a future or a scheduler, and the break hook must reach it
inline. A behavioural corpus keeps passing after somebody moves the work off-thread for a good
reason; this does not.

### What this measurement cannot tell you

**A clean rate is also what a completely invisible decoy would produce.** If the block update
never reaches a client, or reaches it and is not drawn, then the false-positive rate is zero
and so is the true-positive rate — and only the first of those appears in this corpus.

The packet is verified: `CanaryPacketTests` checks the decoy matches its surrounding rock, that
the packet carries that state at that position, that it differs from what is really there, and
that the resync carries the real block back. What is **not** verified is a client rendering it.
That needs a real client with an x-ray pack and a person watching a screen. Until somebody runs
`docs/manual-checks/decoy-visibility.md` — a numbered script with a blank result line, so it is
one person-hour away rather than a research task — the decoy layer is packet-verified rather
than client-verified, and this measurement should be read as "the retirement rule holds" rather than
"decoys work".
---

## The failure class: verified by something that was not running

**Date:** 2026-09-08

This project has now found the same bug ten times in ten unrelated places. Each looked like
a different mistake. Most were found by accident; the last four were found on purpose, by the
probe described below. Naming the class is worth more than the ten fixes, because the eleventh
will not resemble any of them.

**The shape: a green result that is not evidence.** Something reports success, and the reason it
reports success is that the thing meant to be checking never examined what it claimed to.

A passing test and a vacuously passing test are identical in every observable way. There is no
warning, no slower run, no different output — the only difference is that one of them would go
red if the code broke and the other would not. That is why these survive: every signal a person
uses to decide whether a check is working says the same thing in both cases.

### The instances

| What looked verified | Why it was not |
|---|---|
| Permission checks using a string literal | A node absent from `Nodes` is in nobody's resolved set, so it is denied to every player and granted to operators through the fallback. It works perfectly for whoever is testing, because whoever is testing is opped. |
| `./gradlew build` covering the gametests | The gametest source set was never compiled by `check`. A signature change broke it while the build stayed green. |
| `AccountabilityTests` running | Gametest classes are listed by hand in `fabric.mod.json`. The file was written, compiled, and ran zero times; the suite said "all tests passed". |
| `VanishCollisionMixin` stopping pushes | It hooked `Entity.isPushable`, which `LivingEntity` overrides. The mixin applied cleanly, was reported healthy, and never ran. |
| The maintenance kick being tested | The tests called `toggleMaintenance` against an empty player list. The kick ran, kicked nobody, asserted nothing, and passed by having nothing to do. |
| Mobs do not acquire a vanished player | Mock players override `gameMode()` to return CREATIVE outright, and `Mob.asValidTarget` returns null for creative players before looking at anything else. It had a control, and the control tested the wrong path. |
| `getNearestPlayer` cannot find a vanished player | Passed with vanish switched off. **The explanation recorded here was wrong** — see the correction below. The finding held anyway, because it came from the probe rather than from the explanation. |
| A vanished player does not press plates | The plate never detected the mock player at all, so "it did not fire" was true either way. |
| Un-vanishing recomputes abilities | With nothing concealed, nothing was restored, so the abilities trivially matched what the resolver said they should be. |
| `CanaryRetirementIsSynchronousTest` | The deferral pattern was written `"\b(?:...)"`. In a Java string that is a backspace character, not a word boundary. It compiled, matched nothing, and both real assertions passed on an empty result. |

### Four ways in, all producing the same green

- **The verifier never ran.** Not compiled, not registered, not reached.
- **The subject never ran.** The check executed; the code under it did not — a mixin on an
  overridden method, a sweep over an empty list.
- **The verifier ran and found nothing.** A scan whose pattern stopped matching, a query whose
  filter excludes everything. An empty result set satisfies "no offenders found".
- **It passed for the wrong reason.** Creative players are never targeted by mobs regardless of
  vanish; operators hold every node regardless of the permission file.

### The countermeasure, stated once

**Any check that can pass by finding nothing must assert that it found something.**

That is the whole rule, and it is why several tests in this repository look like they are
testing themselves. They are:

- `NodeLiteralTest` asserts its scan matched more than fifty call sites before trusting that it
  found no literals.
- `ActorBoundaryTest`, `GatewayIsTheOnlyDoorTest`, `FreshInstallTest` and `AuditVersionsTest`
  each carry a companion test that hands the scan something it must catch.
- `CanaryFalsePositiveTests` pairs every honest scenario with a control that must register a
  hit, because a decoy layer that never places scores the same zero as a perfect one.
- `GametestRegistrationTest` compares the files on disk against the entrypoint list, in both
  directions.
- `XrayTimingTests` asserts a non-zero block-state count alongside the timing, so a census that
  read nothing cannot report itself as fast.

Where a planted failure is cheap, plant one and watch it fail before trusting the pass. That is
how the gateway bypass check, the node literal check, the fresh-install check and the audit
version check were each confirmed. It takes two minutes and it is the only way to tell the two
kinds of green apart.

### Probing for it: switch the feature off and see what still passes

The taxonomy above named two vanish gametests as vacuous. Switching vanish off entirely and
re-running found **four**, which is the point — reasoning about which tests are hollow finds
some of them, and the probe finds all of them.

The technique is one line: make the setup step a no-op, run the suite, and read the list of
tests that did *not* fail. Every one of those is testing something other than what its name
claims.

| Vanish gametest | Failed with vanish off? |
|---|---|
| The tracker refuses to show a vanished player | yes |
| A vanished player is not pushable | yes |
| A vanished player does not block building | yes |
| The hooks agree on who is hidden | yes |
| Mobs do not acquire a vanished player | **no — deleted** |
| `getNearestPlayer` cannot find a vanished player | **no — deleted** |
| A vanished player does not press plates | **no — deleted** |
| Un-vanishing recomputes abilities | **no — deleted** |

All four failed for a related reason: a gametest mock player is permanently in creative, which
short-circuits exactly the paths the tests aimed at. `Mob.asValidTarget` refuses a creative
player before checking anything else; the plate never detected the mock in the first place; and
with nothing concealed, nothing is restored, so the abilities trivially matched.

> **Correction, 2026-09-10.** The original version of this paragraph gave a second reason: that
> a mock player is not in the server's player list. **That is false.**
> `GameTestHelper.makeMockServerPlayerInLevel` calls `PlayerList.placeNewPlayer` — confirmed
> from the 26.2 bytecode. Mock players are ordinary entries in the player list.
>
> It is worth leaving the correction visible rather than editing the claim away, because of
> what the wrong explanation went on to cost. It was repeated in `Harness`, and from there it
> was used to justify `MaintenanceTests` toggling a server-wide flag that **disconnects every
> non-staff player in the list**. Gametests inside a batch run at the same time, so that test
> was kicking other tests' players every run — five and seven of them, once somebody counted.
> The victims failed with "Failed to invoke test method", which reads as an unrelated internal
> error, and it was written off as flakiness.
>
> The `getNearestPlayer` finding itself still stands, because it was established by switching
> vanish off and watching the test pass anyway. What was never established is *why*, and the
> confident wrong answer is what made anybody stop looking. That is the taxonomy rule paying
> out exactly as written: **the probe is the method, the explanation is a story about the
> probe** — and a story is capable of being load-bearing somewhere else.

**One of the four had a control and was still hollow.** The mob test established that
`setTarget` worked on that zombie with another mob as the target — a real control, testing the
wrong thing, because the failure was specific to a *player* argument. A control has to exercise
the same path the assertion does.

Deleted rather than left in place. A test known to pass with the feature off is worse than no
test, because it occupies the slot a real one would go in and it reports success while doing it.

The abilities claim is now pinned properly and headlessly: `RevealResolvesAbilitiesTest` checks
that vanish never assigns an ability directly and does call the resolver, and `AbilityStateTest`
covers the resolver behaviourally including the survival case a mock player cannot reach. The
other three need a real player in a real player list in survival, and are written up as a
ten-minute script in `docs/manual-checks/vanish.md`.

### A taxonomy tells you where to look, never what you will find

The list above named two hollow vanish tests. The probe found four. The two extra ones failed
for a mock-player property that was not in the taxonomy at all — not being in the server's
player list, as opposed to being permanently creative — and it stayed invisible for exactly as
long as anybody reasoned about which tests were hollow instead of switching the feature off and
reading what still passed.

So the ordering is: **the taxonomy is the prompt, the probe is the method.** A list of past
failures is worth having because it makes somebody suspicious in the right area. It is not worth
trusting, because the next instance is by definition the one the list does not describe — and a
list read as a checklist produces a search that stops at the last familiar shape.

This is the same mistake in a different costume as reasoning about branch topology from a
mental picture of three parallel branches rather than running `rev-list`, or listing four
inventory paths from the code that named them rather than from the code that writes to
inventories. Each time, a model that was right about most of the territory was treated as the
territory.

### A control has to exercise the same path the assertion does

The anti-vacuity rule — a check that can pass by finding nothing must assert that it found
something — would have called the mob test sound. It had a control. The control established
that `setTarget` worked on that zombie by targeting *another mob*, which is a real mechanism
check and passed for real reasons.

The assertion then called `setTarget` with a **player** argument, and that path failed for a
reason nothing to do with vanish: `Mob.asValidTarget` refuses a creative player before checking
anything else, and the mock player is permanently creative. The control proved the machinery
worked on the path it took. The assertion took a different one.

So the rule needs its second half: **the control must differ from the assertion only in the
thing being tested.** Same call, same argument types, same code path — with the feature off
rather than on. A control that reaches the assertion by another route is measuring a mechanism
nobody is worried about.

The cheapest form of a correct control is the probe: run the assertion with the feature
disabled and require that it fails.

### A sibling class: a comparison whose arms share a limit measures the limit

**Date:** 2026-09-10

Every instance above is the same shape — *verified by something that was not running*. This one
ran, produced a number, and the number described the apparatus instead of the subject. It is
worth separating because the countermeasure for the first class does not catch it.

The Gate 4 measurement compared one player-hour of continuous movement against one player-hour
of a player moving half the time, to show that the "no rows while standing still" rule pays for
itself. It reported that stillness saved **nine percent**.

The writer takes a bounded batch — 4,096 rows — and hands whatever is left back to the worker
thread. In a test there is no worker thread. Both arms offered 7,200 samples and both wrote
4,096, so the comparison was between two numbers that had been clipped to the same ceiling. The
real answer, once it drained properly, was **half**.

Nothing was un-run. The test executed, the database grew, the assertion evaluated, the control
would have passed. What failed is that **the quantity being measured was capped above the range
the comparison needed**, so the difference the test existed to detect had nowhere to appear.

**The general form:** when two arms of a comparison share a limit, and both reach it, the
comparison measures the limit. It is nastier than the other seven for one specific reason:

> **Nine percent is a plausible answer.** Nobody interrogates a plausible number.

Zero would have been investigated. A hundred percent would have been investigated. A modest,
directionally-correct, unremarkable figure is the one that gets written into the documentation
and quoted for a year. Every other failure in this taxonomy announced itself as an absence — no
findings, no rows, an empty result. This one announced itself as a result.

**The countermeasure is not "be suspicious of small numbers".** It is to make the apparatus
visible in the output. The measurement now prints the row count of each arm alongside its size,
and the assertion checks that a full player-hour actually reached the disk:

```
assertTrue(samples > 7000, "only " + samples + " samples were written for an hour of
        continuous movement at 2 Hz, so this measured something other than a player-hour");
```

That assertion is what caught it. Not the comparison, which was happy — the sanity check on the
input to the comparison. So the rule to carry forward is: **a measurement should assert the size
of its own sample, not only the shape of its result.** The anti-vacuity rule says a check that
can pass by finding nothing must assert it found something. This is the quantitative twin: a
check that produces a number must assert the number came from as much data as it claims.

### Two more of the same family, found the same week

Both turned up while running the suite repeatedly rather than by reading it, which is itself the
point: neither is visible in the source.

**The measurement that measured the machine.** `XrayTimingTests` asserts that the on-thread ore
census costs less than half a tick. It failed about twice in twelve runs, at 27ms and 41ms
against a 25ms budget, while the honest figure is well under a millisecond. Nothing had got
slower — the machine was compiling, running a second server, and occasionally collecting
garbage. A single wall-clock sample measures the apparatus and the code together and cannot say
which moved.

Fixed by asserting against **the fastest of five runs**. The minimum is the right statistic for
a budget question: it is the least contaminated by scheduling noise, and it still moves when the
code gets slower, because nothing makes the fastest of five faster except the work being
smaller. The tempting alternative — widen the budget until it stops flaking — is how a guard
stops guarding.

**The tidy-up that wiped somebody else's test.** Gametests inside a batch run at the same time,
in different parts of the world, and seven test classes each began and ended with a global
`forgetAll()` that cleared canary and illusion state for *every* player at once. So one test's
cleanup could erase another test's decoys part way through its assertions. That produced a
failure roughly one run in eight, in whichever test happened to be unlucky — which reads as
flakiness rather than as a shared-state bug, and flakiness is the thing that teaches people to
re-run a suite instead of reading it.

The resets are gone rather than reordered. Isolation was already available and free: every mock
player has its own UUID, all this state is keyed by it, and every assertion that reads a shared
collection filters by a position inside its own test area. A test that genuinely needs a clean
global slate needs its own batch, not a reset.

**What the three have in common** is that the code under test was fine and the *instrument* was
not — a cap, a busy machine, a neighbour. None of them would have been found by reading the
test, and all three announced themselves as ordinary failures or ordinary numbers rather than as
anything alarming. That is the standing hazard with a suite this size: **the apparatus is code
too, and nothing tests it.**

### Why this belongs in a decision record

Because the instinct it fights is a good one. Every one of these was written by somebody trying
to be careful, and the check was the careful part. The lesson is not "be more careful" — it is
that carefulness produces checks, and a check is a piece of code that can itself be wrong in a
way that makes it silent. The verifier needs a verifier, and the cheapest one is a deliberate
failure.
---

## The feedback loop, and why a clear needs a reason

**Date:** 2026-09-08

The tuning mistake this fixes is written into this repository's history. The last time the
x-ray thresholds moved, the reason recorded was that the detector was too quiet. That reason
cannot be wrong — any bar can be lowered until a feature speaks — and nothing anywhere could say
who it would start speaking about.

A resolved case answers that. Every cleared case is a player the detector flagged and a human
then decided was fine; every actioned one is a player the detector flagged and a human agreed
about. Those are labels, and a threshold can be measured against them instead of against its own
volume.

### Cleared is not one thing

"I looked at the tunnel and they were following a vein they could see" and "nobody got round to
this and it aged out" both leave a case marked cleared. They are completely different labels
wearing the same status, and only the first is evidence about the detector.

With one value for both, the corpus fills with the second kind — because on a busy server the
second kind is far more common — and a threshold validated against it drifts toward whatever
staff had capacity for rather than toward what was true. Nothing about that failure announces
itself. The number simply gets larger and means less.

So a clear carries a reason, and **only one reason counts as a negative**:

| Reason | Counts as a negative? |
|---|---|
| `investigated` — looked into it, they were not cheating | **yes** |
| `unclear` — looked into it, could not tell | no |
| `not-investigated` — closed without looking | no |
| `left` — subject left the server | no |
| `duplicate` — same incident as another case | no |
| `stale` — aged out with nobody acting | no |

An actioned case is a positive whatever note is attached: somebody punished a player over it,
which is as clear a statement that the detector was right as this system can produce.

The `unclear` exclusion is the one most worth having. An inconclusive case is not evidence the
detector was wrong, and counting it as one would train the threshold to fire less often on
exactly the cases that are hardest to judge — which is the population where it earns its keep.

### Size is never quoted without composition

"Validated against 209 resolved cases" and "validated against 9 real clears and 200 timeouts"
describe the same query and completely different amounts of evidence. The first is what a bare
count looks like.

`TrainingCorpus.Composition` carries both, and nothing returns a size on its own. It appears
under every `/staff xray` report and in `/staff corpus`, and below thirty usable cases both say
so plainly rather than letting a number stand in for a mandate.

### Two exclusions that are about honesty rather than statistics

- **Cases closed before the reason column existed are unlabelled, not innocent.** Backfilling
  them as investigated would invent a judgement nobody made, and every invented one would be a
  vote that the detector was wrong.
- **A reason the enum cannot read is excluded, not guessed at.** Silently mapping an unknown
  value to the one that counts as a negative is how a schema change becomes a shifted threshold.

### The staleness sweep records its own reason

It sets `stale` as a value, not only as a status. A row with no reason would be merely
unlabelled — the same bucket as a case closed before reasons existed — rather than the bucket
that names why it does not count. The distinction matters when reading the composition: one is
"we did not ask", the other is "nobody answered".
---

## When the truth lives on the other side of the wire

**Date:** 2026-09-10

Found by a person running `docs/manual-checks/decoy-visibility.md` with an x-ray resource pack,
and reported as: decoys "work but it's hit or miss — works for some diamonds but not others".

### The bug

A decoy was sent to the client exactly once, at placement, and `maintain` returned early once a
player had their full complement — so no decoy packet ever went out a second time.

But `ClientboundBlockUpdatePacket` is **a delta against the chunk the client is holding at that
moment**. It is not state. The next time that chunk reaches the client — walking out of view
distance and back, relogging, a dimension change, any resend the server does for its own
reasons — the honest chunk arrives and the decoy is gone from the screen. The server carries on
listing it in `/staff canary`.

So the feature degraded with player movement: decoys placed recently in chunks held
continuously worked, and the rest silently did not. Exactly "hit or miss", and the proportion
that still worked fell the longer somebody played.

### Why no test caught it, and why that is not the vacuous-pass class

Every automated test asked the server what it believed, and the server's belief was correct
throughout. The map had the right positions, the packet carried the right block at the right
position, the retirement rule fired at the right times. All of that was true and the feature was
broken anyway.

This is a different failure from a check that was not running. It is **a check that ran, was
right about everything it examined, and examined the wrong side of the boundary.** The
correctness of a decoy does not live in the server's map. It lives in what a client is currently
drawing, and no server-side assertion can reach that.

The generalisation worth keeping: **when a feature's correctness lives in another process,
server-side tests establish that you sent the right thing, never that the right thing is still
true.** Anything delivered as a delta — a packet against a chunk, a patch against a document, an
event against a subscriber's state — needs either a re-send that does not depend on knowing when
the far side forgot, or a way to ask.

### The fix

Re-send on a timer, and validate while doing it. `maintain` now refreshes every live decoy each
pass rather than returning early, and retires any whose underlying block is no longer plain
stone — because blocks change without break events too, and a decoy over a position that is now
air is a diamond floating in a tunnel.

Re-sending on a timer rather than hooking chunk delivery is deliberate. There is no per-player
chunk event in this Fabric API — `ServerChunkEvents.CHUNK_LOAD` is server-side lifecycle, not
view distance — so catching every path would mean a mixin on `ChunkMap`, which is precisely the
fragility the canary design avoided in the first place. A handful of ten-byte packets every five
seconds is robust against paths nobody has thought of, including ones a future version invents.

`CanaryRefreshTests` pins it, and was confirmed by restoring the early return and watching the
regression test fail with the right message. Three companions guard the ways the fix could
itself go wrong: refreshing must not count as finding a decoy, a retired decoy must never come
back, and a decoy over changed rock must be dropped.

### What this says about the manual check

It found a real bug on its first run, in a feature that had sixty automated tests and a measured
false-positive rate. That is the argument for writing checks a person can follow rather than
filing the claim as a known limit — and it is why the script's result line was left blank rather
than optimistically ticked.

The visibility claim itself came back **positive**: decoys are drawn by an x-ray client. The
intermittency was delivery, not rendering. The load-bearing question for item 3.2 — does a
cheating client see the decoy at all — is answered yes.
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

`/staff` opens the root screen: your own switches, seven sections, and the information row.
It was twenty-five buttons in three bands until the sections replaced them; why is in the
`StaffSections` javadoc.

```
                        ┌─ StaffCore ─┐
  you       ▸  Staff Mode · Vanish · Staff Chat · Alerts · Command Spy · Movement · Return
  sections  ▸  Players · Punishments · Security · X-ray & cheats · World · Server · Discord
  info      ▸  Your Record · Server Status · How this works
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
 ├─ Punishments ─────── Punish · Reports (claim / resolve) · Appeals (accept lifts the
 │                      appealed punishment) · History · Banned players · By staff
 ├─ Security ────────── Check · Sweep · Vault · Cases · Contraband rules · Linked accounts
 ├─ X-ray & cheats ──── Prevention · X-ray report · Decoy blocks · Threshold evidence
 ├─ World ───────────── Grief Log ─┬─ Rollback (ghost preview, then confirm)
 │                                 └─ Restore Points (undo a rollback) · Owed · Inspect
 ├─ Server ──────────── Control (chat lock · clear · broadcast · maintenance) · Analytics · Status
 └─ Discord ─────────── Bot · Your link · Channels · Linked staff · Webhook · Guide
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

<a id="idempotent-packets"></a>

## A packet that is correct once and fatal twice

**Date:** 2026-09-12

Reported: a session replay opens, works for about a second, then disconnects the viewer with a
network protocol error.

### What it was

The replay sidebar is a scoreboard objective sent to one connection. `ReplaySidebar.show` sent
`ClientboundSetObjectivePacket` with `METHOD_ADD` **every time it was called**.
`Scoreboard.addObjective` throws `IllegalArgumentException` on a name the client already holds —
it checks the map and throws before doing anything else, confirmed in the 26.2 bytecode — and an
exception raised inside the netty pipeline drops the connection.

The x-ray replay drew the sidebar once, at entry, and never again. It was correct for a year.
The session replay redraws every twenty ticks to advance its clock, so the **second** draw killed
the client. "About a second" was not vague: it was `SIDEBAR_EVERY = 20`.

### The part worth keeping

This was introduced by generalising `show` from "display a finding" to "display these rows", so
a second caller could reuse it. The generalisation was right and the reuse was right. What was
missed is that **the new caller used it differently in a way the old one could not**: once
versus repeatedly.

So the rule is not "be careful when generalising". It is narrower and checkable:

> When a second caller starts using a packet-sending helper **repeatedly** where the first used
> it **once**, every packet it sends must be idempotent — and scoreboard, boss bar, team and
> objective packets are the ones that are not.

Minecraft's protocol has an add/change/remove shape in several places, and `add` is usually the
one that throws. A helper that only ever ran once has never been asked whether it is safe to run
twice, and nothing about reading it says which.

### Why no test caught it

Everything here is packets to a client, so the server-side tests said the right bytes were sent
— which was true, and the bytes were fatal. This is the "truth lives on the other side of the
wire" class again, with a new wrinkle: it was not that the server sent *nothing* useful, it was
that sending the same correct thing twice was the defect.

What is testable on this side is the property that decides it, so `show` now returns whether it
created the objective, and `SidebarLifecycleTests` asserts the first draw creates and the next
forty do not. Probed by making it always re-create: the test failed with "redraw 1 tried to
create the objective again".

The record of which clients hold the objective is dropped on disconnect as well as on hide — the
opposite error would be a viewer whose second replay skips creating an objective their client no
longer has, leaving them watching an empty panel.

---

<a id="config-reconciliation"></a>

## The config file had the same bug as the database schema

**Date:** 2026-09-12

Reported by somebody upgrading the mod: new settings never appeared in `staffcore.json`. The
only way to get them was to delete the file and let it regenerate.

### Why it happened

Gson fills the fields a file mentions and leaves the rest at their Java defaults. So a new
setting always *worked* — the server ran with the right value — it simply never appeared on
disk. The file was rewritten only when `migrate()` returned true, which happens only when
`configVersion` moves, and most releases add a setting without needing a migration.

This is the **schema bug in a second place**, and it is worth stating in those terms because the
database already had the fix: `REQUIRED_COLUMNS` and `REQUIRED_TABLES` exist because a version
counter is *a claim about history, not a description of what is actually there*. Nobody thought
to apply the same reasoning to the config, because the config fails in the gentler direction.
The database version lost data loudly; this one only lost visibility.

### Why "only visibility" was still serious

A server owner cannot configure what they cannot see, and the workaround they find on their own
is deleting the file — **which resets every choice they have ever made**. The bug quietly
pushed people towards the most destructive possible response to it.

Measured against a real config from this repo's own test server, at `configVersion` 3:

- **26 settings missing**, not the four I first guessed — the whole canary group, the approval
  and rate-limit group, the case thresholds, the signal confidences, and position history.
- **3 settings present that no longer exist**: `xrayRatioThreshold`, `xraySampleFloor` and
  `xrayDirectnessFloor`, left over from the scorer Phase 3 deleted. That is its own trap —
  somebody could have spent an evening tuning `xraySampleFloor` and wondering why the detector
  never changed its mind.

### The fix

`loadFrom` now reconciles the file against the declared fields on every boot, and writes back
when the shape differs, when a migration ran, or when `validate()` had to clamp something. That
last one mattered more than it looks: clamping happened *after* the decision to save, so a file
could go on saying `positionSampleHz: 999` while the server used 10 — a config that disagrees
with the running server is worse than one that is merely out of date.

Added and removed keys are **named in the log**, not counted. "Your config changed" is not
something anybody can act on; "positionTracking was added" is, and "xraySampleFloor is not
recognised and is being removed" is the only warning somebody gets before a setting they were
relying on disappears.

A file that already has every setting is left byte-for-byte alone. Rewriting on every boot
would also have fixed the bug, and would have churned the file forever and hidden real changes
among the no-ops.

### What the test is actually protecting

Not "the key appears" — that is the easy half, and a fix that regenerated the file would pass
it. The half that matters is that **reconciling touches nothing the owner set**: a
deliberately-disabled `requireTwoPersonApproval` must stay disabled, or the cure does exactly
what the disease was pushing people to do. Verified end to end against the real stale config:
68 keys became 91, 65 shared, **zero changed values**.

---

<a id="shared-state"></a>

## The suite is a concurrent program, and nobody was treating it as one

**Date:** 2026-09-10

Gametests inside a batch run **at the same time**, in different parts of the same world, on the
same server, against the same singletons. That fact was known and had never been designed for.

### What the audit found

Every gametest class, checked for global mutable state touched in setup, teardown or at class
level. Four things, and the ranking is not what it looked like going in.

| Found | Actually shared? | Scoped by |
|---|---|---|
| `forgetAll()` in seven classes | **Yes, actively** | Deleted. Per-player UUIDs already isolate; leftover state costs nothing in a server about to exit |
| `MaintenanceTests` toggling a server-wide flag | **Yes, and it was destroying other tests** | Splitting "set the flag" from "clear the room" |
| `StaffConfig.get().canaryDensity = 1` | Yes | Deleted. The test asserts on its own decoy's position instead of on a count |
| `XraySweep.lastTiming` static | Yes, read-after-write | Returned with the findings instead of stashed |

Everything else that looked risky was not. Rate limits are keyed by staff UUID; vanish, vault,
debts, replay sessions and illusions are all keyed by player UUID; the remaining mutable statics
are written once at boot or are idempotent caches. **Fourteen of the mod's fifteen mutable
statics were fine.** The dangerous ones were the two that hold a value somebody reads back
expecting their own write.

### The one that was doing real damage

`toggleMaintenance` disconnects every non-staff player in the player list. `MaintenanceTests`
called it on every run. Other tests' mock players were in that list — five and seven of them,
once something counted — so it was kicking them mid-assertion, every run, and had been for
months. The victims failed with `Failed to invoke test method: null`, which reads as an
unrelated internal error rather than as somebody pulling the floor out.

It survived because of a wrong sentence in `Harness`: that mock players are not in the player
list. They are; `makeMockServerPlayerInLevel` calls `PlayerList.placeNewPlayer`. That sentence
was written as an aside about what the harness can assert, and it ended up being the reason a
destructive test was believed safe. **A comment can be load-bearing without anybody deciding it
should be.**

The fix is a decomposition rather than a workaround: closing the door and emptying the room are
different decisions, `setMaintenance(server, on, disconnectPlayers)` says which is which, and
the test takes the half it is actually about. Nothing was serialised — the coupling is gone, not
deferred.

### The structural test

`SharedStateTest` runs in both directions, because either alone misses half of it.

**Down** — no gametest may write to the config singleton or call a global reset. Both are
source scans with their own anti-vacuity checks, and both were probed by planting a real
offender.

**Up** — every mutable `static` in the mod must be enumerated with what kind it is. This is the
`ActorBoundaryTest` pattern: the list is not interesting, the *addition* is. A mutable static
that is not a keyed collection is global by construction — there is no key, so there is nothing
to scope it by, and any two callers in flight share it. `XraySweep.lastTiming` had exactly that
shape, and adding it today would now fail the build until somebody classified it.

---

<a id="timing-assertions"></a>

## Timing assertions: assert the work, record the clock

**Date:** 2026-09-10

`XrayTimingTests` required the on-thread ore census to finish inside half a tick. It failed
about twice in twelve runs at 27ms and 41ms, against a 25ms budget, while the honest figure is
under a millisecond. Nothing had got slower.

The obvious fix is a wider budget and it is the wrong one, because **a timing test that is
loosened every time it fails converges on asserting nothing**, and each loosening looks locally
reasonable. Best-of-five was the second-best answer and still leaves a wall-clock number in a
pass/fail gate, where a slow enough machine eventually reaches it anyway.

**The rule: assert the work, record the clock.**

The gate is now `blocksRead` — how many block states the census actually touched. It is a
function of the input rather than of the hardware, it is what the census costs, and it is what
Gate 3's claim was always about: the one part that cannot leave the server thread is *bounded*.
Two consecutive runs make the case better than the argument does:

| Run | census (µs) | blocks read |
|---|---|---|
| 1 | 31,764 | **2,740** |
| 2 | 8,117 | **2,740** |

A factor of four in the timing; not one block of difference in the work.

Two assertions carry it. The census must read fewer than 10,000 block states for one player's
session — reading the population instead of the shell, or censusing everybody rather than the
one asked for, would each blow through that by an order of magnitude on any machine. And
`timing.players()` must be 1, because the log holds eight players and only one was asked about:
work proportional to the log rather than to the question would show there and nowhere else.

The empty case is now asserted as **zero** block reads rather than "under 5ms". Zero is what
"costs nothing" means, it cannot be reached by a fast machine, and it cannot be satisfied by a
sweep that does a little work quickly.

The microsecond figures are still measured and still logged. They belong here, as recorded
numbers somebody can compare against next year, rather than as a threshold that fails on a busy
afternoon. **No test in either suite now asserts on elapsed time.**

---

<a id="packet-persistence"></a>

## A block update is a delta, not a state

**Date:** 2026-09-10

The single most consequential thing anybody has got wrong on this project, and it was wrong in
three features at once for months.

### What the bug is

StaffCore shows one player a block the world does not have in three places: canary decoys, the
replay overlay, and the rollback preview. All three did it with
`ClientboundBlockUpdatePacket`, and all three treated sending it as *establishing* something.

It does not establish anything. It is **a delta against the chunk that client is currently
holding**. It is not stored server-side, it is not re-applied, and nothing records that the
client's copy now differs from the world. `PlayerChunkSender.sendChunk` builds
`ClientboundLevelChunkWithLightPacket` straight from the honest `LevelChunk` — verified from the
26.2 bytecode, not from memory — so the moment that chunk is sent again the client takes the
real data and every delta for it is gone. The server has no idea.

That happens constantly, for reasons nobody chose: leaving view distance and returning, a
relog, a dimension change, any resend the server makes for its own purposes.

### Why it was missed, twice

**The first time** it was reasoned about correctly and the conclusion was still wrong. When the
chunk-serialisation mixin was replaced with per-player block updates, the requirement that bulk
anti-xray meets by rewriting the chunk palette was judged not to carry over. The reasoning was
about **volume** — a per-block packet for every hidden ore across a world would be absurd, and
StaffCore is showing a handful rather than hiding thousands. That part is right.

But volume is only one of two reasons palette rewriting exists. The other is **persistence**:
when the lie is in the chunk, the chunk carries it, and a resend carries it too. Accounting for
one reason and not the other produced a correct-sounding argument for an approach that could not
hold.

**The second time** the symptom was found — a person testing with an x-ray pack reported decoys
were "hit or miss" — and the fix was a five-second re-send of everything. That worked, and it
was still the wrong shape. Which brings us to the part worth recording.

### Why polling was the wrong answer even though it worked

Four reasons, and only the first is about tidiness:

1. It polls a problem that has an exact event.
2. It costs packets proportional to blocks × players, forever, for a picture that has usually
   not changed at all.
3. It leaves a window — up to five seconds — in which the client is looking at the truth.
4. **A flickering ore is a tell.** An x-ray user who notices that some ores blink and others do
   not learns to distrust the ones that blink. That is not a cosmetic problem: it selectively
   trains the observant users, who are exactly the ones worth catching. A decoy that is
   occasionally wrong is worse than no decoy at all, because it teaches the lesson.

Point 4 is the one that generalises. **A countermeasure with an observable signature trains the
population it is aimed at.** Anything that fires on a timer against an adversary who can watch
it has a period, and a period is a fingerprint.

### The fix: one primitive, one event

`BlockIllusions` now owns every block this server is lying to a client about — per viewer, per
owning feature, indexed by chunk. `ChunkSendMixin` injects at the return of
`PlayerChunkSender.sendChunk` and re-asserts whatever belonged in that chunk, for that player,
in the same call.

Three properties matter:

- **At `RETURN`, not `HEAD`.** The re-asserted updates have to arrive *after* the chunk that
  erased them. A connection delivers in order, so injecting after the chunk packet has been
  handed to `send` is what guarantees it.
- **Indexed by chunk.** This runs for every chunk sent to every player for the life of the
  server. The common case is one map lookup returning null. Anything proportional to "how many
  illusions this player has" rather than "how many are in this chunk" would be a per-chunk cost
  on the busiest path there is.
- **Sourced.** A staff member can hold live decoys while watching a replay. Clearing the replay
  must not hand their client the truth about a decoy.

Fixed once, at the primitive, rather than three times. The bug was never about decoys; it was
about a protocol fact that three features had each reasoned about separately and got wrong the
same way.

### On the mixin, and why this one is not the one that was deleted

The Fabric API has no per-player chunk-send event in this version. Checked against every
`*Events` class in every `fabric-api` jar on the classpath rather than from memory:
`ServerChunkEvents` is server-side chunk lifecycle (load, generate, unload, status change) and
`EntityTrackingEvents` is about entities. Neither fires when a chunk goes to a player.

A previous mixin on `ChunkMap$TrackedEntity` was removed for good reasons: it targeted a
package-private inner class by string and cancelled a vanilla method at `HEAD`, taking over
behaviour it then had to reimplement. `ChunkSendMixin` shares none of that.
`PlayerChunkSender` is a public class, the injection is at `RETURN`, it cancels nothing, changes
no argument, and touches no serialisation. **It fires a callback; it does not participate.**
That is the distinction worth keeping — "avoid mixins" was never the rule, and reading it as one
is how the persistence requirement got dropped in the first place.

Tiered `IMPORTANT` rather than `OPTIONAL`. Losing it switches no feature off: decoys go on being
placed, counted and reported as live while evaporating from every client that reloads a chunk.
The true-positive rate goes to zero and the false-positive rate stays at zero, which reads as
the feature working perfectly.

`/staff status` reports how many illusions have been re-asserted since boot, because **zero is
meaningful** — on a server with decoys out and players moving, zero means the hook is not
firing, and nothing else would say so.

### What is still unverified

That a client behaves as the protocol says. The server demonstrably sends the honest chunk and
now demonstrably re-asserts after it; whether the re-assertion lands visibly is the one part
that needs a person watching a screen. See `docs/manual-checks/`.

---

<a id="position-history"></a>

## Position history: what an hour costs, and why the table is shaped like that

Phase 4 added the only table in this mod that records something other than an action somebody
took. A `block_log` row exists because a player broke a block. A `position_log` row exists
because a player existed. That difference decides almost everything below.

### Measured, Gate 4

`PositionGrowthTest` writes a known number of player-hours into a real SQLite file and asks
SQLite how much bigger it got. Run on 2026-09-10, at the default `positionSampleHz` of 2:

| Scenario | Rows | On disk |
|---|---|---|
| One player-hour, moving continuously | 7,197 | **160 KB** (22.8 bytes/row) |
| One player-hour, moving about half the time | 3,163 | **80 KB** |

Twenty players online four hours a day, moving half of it, is about **45 MB a week** — which
the seven-day default retention then holds flat rather than growing.

The second row is the one that says the storage rules work. Nothing is written while a player
stands still, so half an hour of standing costs nothing at all; the gap between two timestamps
already records it. The first version of this measurement said stillness saved nine percent,
which was the measurement being wrong rather than the feature: `write()` takes a bounded batch
and hands the rest back to the worker thread, and in a test there is no worker, so both
scenarios were clipped to the same 4,096 rows and looked identical. A measurement that clips
both arms equally cannot see the thing it is comparing.

### Four decisions, and what each is worth

- **The identity lives once per run, not once per sample.** A UUID is 36 bytes of text — on its
  own more than the entire rest of a row. `position_run` carries it, the world, and the absolute
  origin; `position_log` carries only measurements. This is most of the saving.
- **Deltas in 1/32-block fixed point.** SQLite gives every `REAL` eight bytes whatever it holds
  and stores a small `INTEGER` in one. A player walking covers about two blocks between samples,
  which is a one-byte number. `PositionSchemaTest` asserts the columns are integers.
- **`WITHOUT ROWID`.** The read is always "every sample of this run in time order", so the
  primary key is the access path. An ordinary table would need a separate index over the same
  two columns — on the largest table in the database, that is storing the key twice.
- **No rows while standing still.** Half the cost of a realistic session, for a comparison
  against the previous sample.

Every one of those is invisible if it breaks. The feature keeps working perfectly and quietly
costs twice as much, and nothing in the mod would notice — which is why `PositionSchemaTest`
asks SQLite whether the table has a `rowid` rather than grepping the DDL it was handed, and why
the ceiling in `PositionGrowthTest` is asserted rather than only printed.

### `ended_at` is an upper bound until the run closes

A run's row is written when the run starts, with `ended_at` set to the latest it could possibly
finish, and narrowed to the truth when it closes.

That way round because the other way fails silently. If the process is killed nothing calls
`closeRun`, so whatever was written at the start is what stays. An `ended_at` equal to
`started_at` would exclude that run from every window query that should have found it, and its
samples would sit on disk with nothing able to read them and nothing able to delete them. Being
too generous costs one extra empty run in a query result. `PositionGrowthTest` covers both: a
clean restart, and a run nothing ever closed.

### Retention deletes whole runs, and the guardrail says it should not delete at all

The phase guardrail is that migrations are append-only and nothing deletes a historical row —
reversal, expiry and staleness are states, never deletions. Position history is the exception,
deliberately, and the same exception connection records already are: **the rule is about
evidence, and this is not evidence.** A record of where somebody was, kept after it is useful,
is not an audit trail with better retention. It is surveillance with worse hygiene.

Deletion is by whole run, by start time, which takes up to a minute of data slightly *newer*
than the window asked for. That is the right direction to be wrong in here, and it falls out of
the design rather than being chosen: a delta chain can only be read from its beginning, so a
half-deleted run reconstructs into a smooth plausible path through the wrong part of the world.

### Exports name what they release

`/staff export` withheld position rows from the moment they existed, but the flag that releases
them was called `addresses`. It is now `personal`, with `addresses` kept working. A flag that
releases more than its name says is the same mismatch this project keeps finding after the
fact — and unlike the others, this one would have been found by somebody handing over a
spreadsheet.

Withheld by the row rather than the column, because there is no column of a position log that
is not the point. The header line is still written so a withheld table can be told from a
missing one.

### One thing that cannot be off-thread, said rather than skipped

The guardrail asks for replay sampling to stay off the server thread. Reading a player's
position cannot: an entity's coordinates are mutated by the tick loop with no synchronisation,
so reading them from a worker is a data race that would mostly work and occasionally record a
position no player was ever at — which is the worst possible failure for this feature, because
it is invisible and it is evidence.

What stays on the tick thread is therefore: three doubles and two floats per online player, once
every ten ticks, a comparison against the last sample, and a queue push. Everything else — the
delta encoding, the decision to restart a chain, the transaction — runs on the grief log's
writer thread, shared rather than new because this database's whole concurrency story is one
connection and one writer.

This is the same call as the on-thread census in item 3.3, recorded for the same reason: a
guardrail worth having is worth saying out loud when it cannot be met.

---

<a id="replay-honesty"></a>

## A replay can be wrong without looking wrong

Everything in Phase 4 that could break loudly does. The parts worth writing down are the three
that would have produced a smooth, plausible, completely false picture — shown to somebody
deciding whether to punish a player.

**Interpolating across a gap.** Position history records movement, so an hour of AFK is an hour
between two samples. Interpolated, that is a staff member watching somebody glide four hundred
blocks in a flight that never happened. Gaps are held for a moment, jumped, and announced in
chat with their real length. Hiding time is necessary; hiding it silently is how a replay
becomes misleading evidence.

**Encoding a teleport as movement.** A 500-block jump between two samples is a `/tp`, a portal
or a missed stretch. As a delta it draws somebody crossing the map in a straight line at
impossible speed, which reads as evidence of cheating and is not. The chain restarts instead.

**Replaying in the world as it is now.** Without the block overlay, every hole the player made
is already open when they arrive at it, and a rollback since has erased the lot. Watching
somebody tunnel through a tunnel that is already open looks exactly like watching them tunnel.
The blocks they changed are painted back and released one at a time as the clock reaches each.

The rule the overlay runs on is that a position shows the `before` of the **earliest change
still to come**. The test written for the broken-replaced-broken case asserted the opposite and
failed: half way between the first break and the placement the position must be painted *as
air*, which is not the same as painting nothing. If somebody rolled that area back afterwards,
the world has a block there now, and leaving it unpainted shows it standing at a moment when it
was rubble. The code was right and the expectation was not — worth recording, because the
project's usual finding is the other way round.

**And what the replay is not.** Two samples a second says where somebody went. It says nothing
reliable about *how* they moved between them: the smooth motion is this mod interpolating
between two readings half a second apart. Anything read off it about movement mechanics is a
property of the interpolation. `positionSampleHz` does not fix that — it multiplies the storage
cost and buys detail the playback discards.

---

<a id="one-way-out"></a>

## Two replays, one way out

The x-ray viewer and the session replay show completely different things. Everything about
*leaving* is identical: the gamemode to put back, the position to return to, the vanish flag not
to disturb, the painted blocks to take down, the sidebar to remove.

That runs from five places — the exit command, a disconnect, a death, a dimension change, and a
restart — and four of them fire for every player on the server. A second copy would have five
chances to drift out of step with the first, and the symptom of that drift is a staff member
stuck in spectator at the bottom of a stranger's mine with no way back.

So `ReplayStage` owns all of it and `XrayReplayView` kept its entry points and lost ninety
lines. The way home stays on disk in `ReplaySession`, written before the player is touched; what
is new is a per-viewer teardown hook, which is deliberately in memory only. It exists to stop a
playback driver that is running right now, and a driver cannot survive a restart — so after one
there is nothing to run and nothing that needed running.

---

## Why the ban screen has no buttons

Banned players asked for a Discord link they could click and an appeal code they could copy.
Neither is possible on the ban screen, for reasons that are in the client:

- The 26.2 client never gives the disconnect screen a click handler, so text on it cannot be
  clicked. Hovering can show a tooltip, and nothing can be copied or selected.
- That screen's one extra button, labelled "Report To Server", is filled in only when the
  player's own game hits an error. A server-sent disconnect, which is what a ban is, never
  carries it.

A window during connection setup was built and removed. A dialog there can open links and copy
text, and the ban stayed safe: the end-of-setup check still refused. But it could not replace the
ban screen, only come before it. Every way a connection ends shows the client's own disconnected
screen, and every server-made window gets Mojang's "custom screen" warning icon. Players saw two
screens and a warning, and the old screen on its own looked better. The code is in the history
at 53beb81 and 5ef730a if the client ever changes.

So the ban screen is text. The link and code are copyable where the client allows it, which is
chat: a muted player gets a clickable invite and a click-to-copy appeal code.

---

## Back follows the path you took

Every back arrow used to name its own destination, and most named the staff panel. That was
right when the panel was one flat page. Once the panel was split into sections, it was wrong
almost everywhere: the player list is reached from Players, X-ray and Session replay, so a fixed
destination can be right for one of them at most, and "back" from two screens deep meant
starting over.

So `Guis` keeps a short history per viewer (`NavigationHistory`), and every arrow is drawn by
`Gui.backButton`. Click goes back one screen along the path actually taken; shift-click goes to
the panel. The arrow names the screen it goes back to, read from that screen's title, so it
does not repeat its author's guess.

- **Screens are compared by class.** Steve's file and Alex's file count as the same screen.
  Opening the screen already on top replaces it (a filter change is not a new level). An
  explicit return (`goBack`) drops everything above the screen it returns to, so a finished
  punishment is not one click behind the file.
- **A confirmation is never stepped back into.** Rebuilding one would put "Do it." back in front
  of somebody who already did it, with a fresh open time that passes the staleness check.
  `ConfirmMenu` is replaced by whatever it opens next instead of staying underneath it.
- **A screen whose opener does real work re-enters through that opener.** Invsee ends its edit
  session on close, so rebuilding it from the old factory would bring back a session that no
  longer exists. It and the ender chest go back through `open`, which re-runs their checks.
- **The history is dropped when the menus close for real**, and on disconnect. A path through
  screens that are no longer open would send the next command-opened screen back to wherever
  staff were an hour ago. Without a history, each arrow falls back to its old fixed destination,
  now the screen's section rather than the panel.
- **Shift-click checks `staff.gui`.** Some sub-screens can be opened without it, so reaching one
  does not prove the viewer is allowed the panel.

---

<a id="decoys-count-when-uncovered"></a>

## Decoys count when uncovered

**Date:** 2026-09-13

Breaking a decoy in-game did nothing. That was the design working. A decoy sits in sealed rock,
a hit needed the decoy broken while all six neighbours stood, and breaking any neighbour
retired the decoy silently. Every client, honest or not, reaches a sealed block through a
neighbour. The canary false-positive corpus measured zero because nothing could ever hit.

So the event changed from "broke the decoy" to "uncovered it": the break that opens a face onto
any block of a decoy vein. That counts once for the whole vein, and the vein goes back to rock
on the owner's screen. An honest tunnel now uncovers decoys too, so an uncovering is no longer a
verdict. It is a count, and it goes into a score.

**The score (`OreSense`).** Every break is checked for what it uncovered: rock faces that were
sealed until now, and sealed diamond veins among them. Honest mining finds hidden veins in
proportion to the faces it opens. X-ray mining finds far more per face, because it digs towards
ore it can see. The expected count is a Poisson rate (faces times a per-face chance, scaled by
depth band). Hidden veins and decoy veins are tested separately and the smaller tail kept, then
doubled for the two tests. Pooled, three decoys would drown in a dozen ordinary veins. The
result goes on the same 0-99 scale as the sweep, through the same notice and alert lines. A
floor of `xrayMinimumFinds` (3) stops one lucky strike being reported as one in a thousand.

**Decoys became veins.** Grown like ore blobs, one to ten blocks, mostly small, matched to the
rock. Twelve veins per player by default instead of six single blocks; configs still on six are
migrated (v4). They are placed from 24 below the player to 8 above, not down to eight above
bedrock, which put every decoy above the diamond layer where people strip-mine.

**The rates.** `xrayNaturalVeinsPer1000Faces` comes from vanilla's placed features: the small,
buried and large diamond features on a triangle peaking at y -64, the medium one uniform below
y -4. That gives about 2.8 ore blocks per 1000 at the bottom of the world. Each band's rate is
refined from that server's mining below the notice line, within a factor of three of the
configured value.

**Measured by `OreSenseSimulationTest`**, which runs the real `uncover` and `score` against a
simulated world at that density, no caves (every vein sealed, the luckiest case for honest
miners), decoys topped up the way `Canaries` does it:

| Miner | Sessions | Result |
|---|---|---|
| Honest branch mining, 3000 breaks | 150 | 0 alerts, 0 notices, worst score 3; 1.19 sealed veins per 1000 faces; 3.5 decoys against 8.7 expected |
| Honest, world with 1.5× the diamonds | 60 | 0 alerts, worst score 9 |
| X-ray, straight to the nearest visible vein or decoy | 60 | 60 alerted, median 9 finds at the alert; 10.1 sealed veins per 1000 faces |

The first cut used 3.0 per 1000 and doubled the decoy expectation. It caught every cheat, but at
a median of 13 finds. The table is at 2.0 per 1000 and decoy blocks over rock volume. The honest
rate it would take to reach 2.0 is 1.7 times what the simulation finds at the densest depth.

**Revised 2026-09-14, after testing on a real server showed no alerts.** The pipeline worked in
tests end to end — real block breaking, the break event, the worker, the case — so the silence
was the rules, not a bug. Creative players were not scored, and operators test from creative;
staff mode was skipped by `xraySkipStaffOnDuty` without telling anybody; and nothing was said at
all below three finds. Creative is now scored (a builder in creative uncovers ore and decoys at
the rate anybody digging does), staff on duty are told in chat that their mining is not scored,
and every decoy vein uncovered is a quiet staff line with the current score
(`canaryNotifyEachFind`). A case still needs the score. Decoys also now look like what the rock
really holds: one to nine blocks in deepslate, one or two in stone, where they are four times
rarer.

What this does not establish: how real players mine. Honest players explore caves, and ore seen
from a cave was meant never to be counted — it was, which is the next section. Careful cheaters
mix in branch mining. Nobody has watched an x-ray
client draw a decoy vein, which is still the manual check in `docs/manual-checks/`.

**Threads.** Reading what a break uncovered stays in the break event, because the world can
only be read on the server thread and the next break changes it: about thirty-six block reads
below y 16, plus a vein walk when a diamond is touched. Scoring, sessions and calibration run on
the grief worker. Raising the signal comes back to the server thread, because announcing reads
the player list. Decoy retirement stays synchronous (`CanaryRetirementIsSynchronousTest`): a
deferred retirement would let one vein be counted twice.

---

## Caves, and the way a tunnel turns

**Date:** 2026-09-15

Reported from a real server: a player mined the ore they could see in a cave, with no x-ray, and
was flagged. Both detectors were at fault, each for its own reason.

**The live score.** `uncover` skipped a diamond facing the cave, but judged the block of the same
vein behind it on its own — sealed on five sides, so a hidden find. Mining a visible vein scored
its back half. A vein is now hidden only if no block of it touches open space other than the
face just opened, and breaking a diamond (which only a visible one can be) marks its vein as
seen. Measured by putting the old rule back: in `OreSenseSimulationTest`'s new cave world, **30
of 100 honest cave-mining sessions alerted, worst score 99, 1,789 "hidden" veins**. With the
rule: 0 alerts, 0 notices, worst score 7.

**The sweep.** The hypergeometric draw assumes every block was chosen blind, and counted the
cave's air as rock that could have been dug. Mining the gold, redstone and diamonds along a cave
wall is almost every ore in very few blocks — the shape of cheating. The census now takes open
space the player did not dig out of the population, and any ore touching it out of the ore count
and the finds. `XrayScoreTests` builds the same eight gold ores twice: sealed in rock they are a finding
under one in twenty; along a cave, no finding at all (with the old arithmetic, eight blind finds
in twelve blocks).

**The tunnel (`DigPath`).** Staff asked for movement to count as well. The rate test has an
honest explanation it cannot rule out — the configured density is an estimate, and a patch
richer than it makes an honest miner look lucky — so a second, local measurement was added. The
tunnel is split into straight legs; when a leg ends, the directions not taken are read as
straight corridors from the corner, and hidden veins per block of rock they would have looked
into is set against the finds per face the leg itself opened.

The first version counted per step and was **biased against honest miners**: over 150 honest
branch-mining sessions it expected 1,801 finds on legs that made 2,549. Corridors ran through the
player's own side tunnels (no rock, no ore) and vertical corridors look into fewer blocks per
step. Counting rock blocks looked into, until the first hit, removes both: 2,663 expected against
2,549. Per block rather than per leg because a leg ends when the player turns, and an honest
player who turns the moment they find something stops a leg exactly at a find — per leg, that
reads as aiming; per block, it does not (optional stopping leaves a Poisson rate unbiased).

**Agreement.** An alert from ore now needs the hidden-vein rate and the tunnel to agree (the
larger of the two chances), or a vein rate a hundred times less likely on its own for a cheater
who never turns. Decoys stand alone, as before. The sweep's alerts need the live session's
tunnel to look aimed, or a decoy; otherwise they are a quiet line saying the dig does not back
it up.

| Miner (`OreSenseSimulationTest`) | Sessions | Result |
|---|---|---|
| Honest branch mining, no caves | 150 | 0 alerts, 0 notices, worst 5; tunnel 2,549 finds against 2,663 |
| Honest, 1.5× diamonds | 60 | 0 alerts, worst 26 |
| Honest cave mining, caves carved first, half of exposed ore discarded as vanilla does | 100 | 0 alerts, 0 notices, worst 7 |
| X-ray, no caves | 60 | 60 alerted, median 9 finds; tunnel 6,265 finds against 936 |
| X-ray among caves | 40 | 40 alerted |

**Threads.** Reading the directions not taken is on the server thread, in the break event that
ends a leg: at most four corridors of sixteen blocks, eight blocks a step — about 500 reads, once
per leg rather than per break — and counted (`OreSense.pathBlocksRead`). The judgement is on the
worker with the rest of the score.

What this does not establish: real players, again. Caves are worms three blocks across; ravines,
aquifers and lush caves are not simulated. A cheater who digs one long straight tunnel and only
peeks sideways leaves little tunnel evidence and has to be caught by the vein rate alone at the
stricter bar, or by decoys.

---

## Who a Discord user is allowed to be

**Date:** 2026-09-15

Phase 5.2 of the build brief: the security model for the Discord companion, built before the
companion does anything, so that everything added afterwards arrives behind it.

**Permissions are the intersection, read per request.** What a Discord user may do is the set of
nodes their roles map to (the companion's config) intersected with what their linked Minecraft
account resolves in game right now. Neither side alone was acceptable: roles alone make whoever
can hand out a Discord role a Minecraft admin, and the game alone gives the owner no way to say
"bans stay in game". The intersection means each side can only take away. Nothing is cached — a
demotion, a ban or an unlink takes effect on the next click, and `DiscordAccessTests` checks
promotion and demotion both reach Discord without a restart.

**"In game" means what the command tree would answer, not what `Rank.of` answers.** `Rank.of`
decides whether a *target* is staff and deliberately counts anybody on the op list as an
operator. Used to decide what somebody may *do*, that is the permissive direction. So there is a
separate `Rank.inGame`, which asks `Permissions`' own resolution (`withoutProvider`, now shared
with the in-game check rather than copied) including vanilla's moderator level from the op
entry. The operator flag is never a grant on the Discord path.

**Offline with a permissions plugin is refused.** fabric-permissions-api answers only about
connected players. Rather than guess, an offline account on a LuckPerms server holds nothing on
Discord until they join. This will be the commonest complaint about the companion, and it is the
correct side to be wrong on.

**"An unlinked Discord user can read, never write" — how it was read.** The brief also says reads
are "for linked staff with the node" (5.5). Both hold if "read" for an unlinked user means seeing
what the companion posts to channels, which Discord's own channel permissions govern, and not
running lookups. So an unlinked account holds no nodes at all, reads included. If the intent was
that unlinked role holders may run lookups, this is the place to change, and it is one line in
`DiscordGate.decide`.

**In-game-only actions are refused in the services, by channel.** `DiscordReach.refusal` is
called by `AddressBans.request`, `Approvals.approve`, `GriefModule.rollback` and every
`InventoryGateway` entry point. Leaving those actions out of the bot's command list would have
been enough for today and not for the next way in. Approving is refused because it is what runs
a staged action; staging from Discord stays possible. Rollback previews are refused too: there is
nobody on Discord to read one, and a preview takes the region lock.

**Links are proved from the Minecraft side.** The code comes from `/staff discord link`, issued
only to a signed-in player, so possession of the Minecraft account is the proof and a link cannot
be claimed from Discord. Codes are 40 bits, ten minutes, single use, in memory only, and wrong
guesses are capped at five per Discord account per ten minutes. Links are ended rather than
deleted (`discord_links`, migration 28), one per account in each direction.

**The token.** Its own file. Never logged, never in status, never in an error — every message
about the file describes it without quoting it, because a token with one stray character fails
the shape check while still being almost all secret. The companion never logs it, and a Log4j
filter on every logger configuration drops any event whose message or exception chain contains
it. The filter sits on the loggers rather than the context because a context-wide filter is
consulted before a message is formatted, with the pattern and its arguments apart, and a logger's
own filter sees the finished event.
`DiscordBotTest` makes a fake gateway log the token as an argument, in an exception and in a
cause, then reads back everything that was logged; with the filter disabled it fails.
World-readable is the POSIX "others" bit, or on Windows an allow entry for Everyone, Users or
Authenticated Users — Java reports Everyone as `\Everyone`, which the first version missed.

**Two flags, not one.** 5.1 silenced StaffCore's webhook as soon as a companion declared itself.
A companion that only links accounts would then have left the server posting nothing to Discord
at all. `declareDiscordCompanion` now only enables linking; `declareDiscordPosting`, which 5.3
calls, is what silences the webhook.

---

## Channels, threads and the buttons on a report

**Date:** 2026-09-15

Phase 5.3: the companion posts punishments, reports, alerts, appeals and the staff log, gives each
report, appeal and case a thread, puts seven buttons on a report, and bridges staff chat.

**Deciding is apart from doing.** `Router` turns StaffCore's events into plain `Outbound`
operations — send, edit, a line in a thread — with no Discord in sight, which is how `RouterTest`
covers every event without a bot. `JdaGateway` carries them out one at a time, in order, on the
companion's own thread, waiting for each answer because the next operation needs the ids Discord
hands back.

**The router remembers what it sent, not only what Discord confirmed.** The first version decided
from the thread book, which is only written once Discord answers. A claim arriving a moment after
its report found nothing to edit and was posted as a stray line; an auto-assignment straight after
a case opened was dropped. The router now keeps the last message it asked for under each key
(bounded at 2,000, the book covers anything older). Putting the book back as the only source fails
seven of `RouterTest`'s cases.

**A report and its case share a thread.** A player report is also a signal, and at the default
confidence it opens a case. Posting it as a report and again as an alert put one report in front of
staff twice, with the investigation in a second thread. So with a reports channel set, a report
signal is not posted as an alert, and the case it opened is given the report's thread.
`ReportFiled` is now published after the signal lands, carrying its case id, so the companion knows
which case it is. Cases opened by hand are posted in the alerts channel.

**What Escalate means.** StaffCore has no escalation for reports, so the button maps onto what a
staff member escalating would do in game: hand the report to the player's open case, or open one of
the kind its words suggest, link the report, and mark the case investigating. It needs `staff.gui`,
which is what `/staff case open` needs. The report itself is left open: somebody still has to answer
it.

**One path, two doors.** Where a Discord button needed a service the command did not have, the
command's logic moved into a service both use: `ReportModule.claimOrTakeOver` (the queue screen's
takeover), `NotesModule.write` (`/staff note`'s case attachment). Neither door has its own copy to
drift.

**The alert threshold does not decide threads.** `discordAlertSeverity` decides which signals are
posted on their own; a signal that opens a case is always posted, because otherwise a server that
raised the threshold would have cases with nowhere to discuss them. Weaker signals about a case
that has a thread go into the thread.

**The staff log's target is read, not passed.** Audit rows record the command as typed, and dozens of
call sites write them. Rather than change every one, `StaffAudit.targetIn` takes the word after the
subcommand and accepts it only when it is a name the server has seen — online, or in
`name_history`. Local lookups only; `PlayerLookup.profile` was avoided because it can go to Mojang.

**What players typed is inert.** Every piece of player text is escaped for markdown, links and
mentions, and every message is sent with mentions disabled, so a report reason of `@everyone
[free](https://…)` is shown and not obeyed. Embeds are cut to Discord's limits before sending —
`EmbedAndTextTest` found that a title, description and footer at their separate maximums already
exceed the 6,000-character total, which Discord answers by refusing the whole post.

**No privileged intent unless asked for.** Reading a channel's messages is the one thing that needs
Message Content Intent, and only the staff chat bridge reads messages. The bot asks for it only
when `staffChatChannelId` is set, and says in `/staff status` which setting needs it if Discord
refuses.

**The webhook stands aside only once the bot is connected.** `declareDiscordPosting` is called on
ready, and only when a posting channel is set, so a bot that never connects leaves the webhook
posting.

**Posts while disconnected are counted and dropped.** Bounded queueing is item 5.6. Until then a
post the bot cannot make is counted in `/staff status` rather than held.

**API version 2.** Record shapes changed (`ReportFiled` gained a case, `StaffAction` a target) and
events were added, so a companion built for version 1 now refuses to start instead of failing on
the first event.

---

## Appeals from Discord

**Date:** 2026-09-15

Phase 5.4: `/appeal` with the code off a ban screen, a thread and buttons per appeal, accepting
through the punishment service, and the guards the brief asks for.

**Filing is the one write an unlinked account can make.** The security rules say an unlinked Discord
user can read and never write, and the appeals item says a player appeals from Discord. Both hold
only if an appeal is not a staff action: it is the punished player's own request, and somebody
banned cannot join the game to link. So filing checks no permission, and the appeal code does the
job one would — it names one punishment, is sixty random bits, and every attempt is counted per
Discord account whether the code was right or not. Answering a question about one's own appeal is
the only other thing, and it lands only on an open appeal that account filed and staff asked about.
Every staff action on an appeal goes through the 5.2 gate behind `staff.appeals`.

**Accepting lifts one punishment, and the appeals screen was wrong.** The in-game screen lifted every
active ban and every active mute when an appeal was accepted. An appeal names one punishment, and an
upheld mute appeal is not grounds to lift a ban. `PunishmentModule.reverse` lifts one, sharing
everything that follows a lift — events, case note, the address ban a player's ban brought with it —
with `revoke`, and `AppealModule.decide` is now the single path the screen and Discord both use.
Putting the old rule back fails `acceptingLiftsTheAppealedPunishmentAndNothingElse`. Appeals older
than the `punishment_id` column still get the old rule; it is the only one that can apply to them.

**Stale means the player walked away, not that staff did.** "Abandoned appeals expire to stale" could
be read as any appeal nobody touched. That would close appeals staff are sitting on and blame the
player for the wait. An appeal goes stale only when staff asked it a question more than
`appealStaleDays` ago and the player has not answered since. Reading it the other way fails
`anAppealThePlayerStoppedAnsweringGoesStale...`. Stale is a state, the row stays, and there is no
cooldown after it.

**The appellant is reached by direct message.** An appeals channel with staff discussion in its
threads is somewhere a server will want private, and a player who cannot see it cannot see a
question asked there. So questions and verdicts go to the filer by direct message, and they answer
by replying. The bot asks for the direct-message intent, which is not privileged, and Discord gives
bots the content of messages sent to them directly. Nothing sent to the player names a staff member.
A message that cannot be delivered is said in the appeal's thread, so staff are not left believing a
question was asked. `appealIntakeChannelId` lets `/appeal` be used somewhere players can see while
the posts stay in a staff-only channel.

**Who filed is shown, including when it is not the player.** Anybody with a photograph of the ban
screen can file; that is accepted rather than defended against, since anything stronger would cost a
banned player the one route back they can reach. The post shows the filing account and the Minecraft
account it is linked to, and says so when that is somebody else.

**The form opens before the code is checked.** Discord allows three seconds to open a form, and asking
the server first could take longer on a busy tick. The code is checked, and the attempt counted, when
the form is sent.

**API version 3.** `AppealFiled` and `AppealDecided` changed shape and `AppealConversation` was added.

---

## Commands from Discord

**Date:** 2026-09-15

Phase 5.5: `/staff` in Discord, with the reads and writes the brief lists.

**The punishment door reports why it refused.** `PunishmentModule.apply` refused a rate-limited or
rank-guarded punishment by messaging the acting staff member in game, or the log. From Discord there
is nobody in game to message, and a ban that silently did not happen is worse than one that said no. An
overload takes a callback for the refusal; the in-game callers are unchanged. `BypassAttemptTest`'s
check that every call site passes an identity looked at the last argument, which is now the callback, so
it finds the actor by position instead — and still fails when that argument is `null`, which was tried.

**A Discord action limit, on top of the in-game ones.** The brief gates Discord writes by rate limit.
Bans, mutes and warnings already meet the punishment limit inside `apply`. Unbanning, unmuting,
freezing and noting have no limit in game, where doing forty means standing in the server doing them;
from Discord it means a stolen account and a script. `RateLimits.Kind.DISCORD_ACTION`, set by
`discordActionsPerMinute`, counts every write from Discord — commands, report and appeal buttons —
so a ban from Discord counts against both limits. Staff chat is not an action and is not counted.

**The rate-limit refusal had never said anything.** Its message was several strings joined with `+`
and `.formatted(...)` bound only to the last of them, so every refusal since Phase 2 read
"Rate limit: %d %s(s) a minute. Try again in %d second(s)". Every test checked that the limit held and
none read the message. `DiscordCommandTests` looked for the words "Discord action" and found the
placeholders instead. Fixed with brackets, pinned by `RateLimitMessageTest`, and a scan of every
`.formatted(` call for the same shape found no other — the scan does find this one when it is put back.

**Names are resolved as in game.** Commands name players, so they go through `KnownPlayers.resolve`:
an exact name, or a prefix matching one player; several matches are refused with the candidates.
Autocomplete offers names only to a linked account that holds something, so it is not a way to list
who plays on the server, and it gives up after two seconds rather than miss Discord's three.

**Lookups are audited.** `/staff history` in game writes a command row; so does every read from
Discord, naming the Discord account.

---

## A card per case

**Date:** 2026-09-17

Asked for: "when a case is made we need a case channel with proper embeds to freeze the player and
just to see all the stuff that is in a case with proper embeds like we have on reports".

**Drawn from a snapshot, not from events.** Reports have a card because the report events carry
everything the card shows. A case changes in many more ways: signals, evidence, links, notes,
assignment, status. Rebuilding its state from those in the companion would be a second case model.
So StaffCore sends `CaseUpdated` with the case as `/staff case` shows it (`DiscordCase`, from
`CaseSnapshots`, which that command now uses too) after every committed change, and only while
somebody is listening. The companion posts the card the first time it sees a case and edits it after
that.

**The case's thread moves under the card.** With a cases channel, the card takes the `case:<id>` thread
key. Alerts and reports link to the case instead of starting its thread, so there is one place a case
is discussed. A case that already had a thread, from before the channel was set, keeps it, and its
card is posted without one. Without a cases channel nothing changes.

**The buttons are the existing actions.** Details, Evidence, Notes, History and Profile are the same
reads as the slash commands. Freeze and Unfreeze are the same gated calls, and notes and unfreeze now
accept a player's id as well as a name, which is what a button carries. Add Note is new from Discord,
and copies the in-game command it stands for: `/staff case <id> note` writes a line in the case's
history behind `staff.gui`, so `CASE_NOTE` does exactly that. It is not `NOTE`, which writes onto the
player's record behind `staff.notes`.

**A bug it found.** The card's game test closed a case and the card still said open. `CaseStore.read`
called `wasNull()` for `closed_at` after reading `assigned_to`, so the closing time depended on whether
anybody was assigned. That is fixed separately, with its own test.

---

## Leaving while frozen

**Date:** 2026-09-17

Asked for: "if they try to log out and evade, staff is notified … and that shall be added on top of
their case".

**A signal, so it reaches everything a signal reaches.** `FREEZE_EVASION` goes through the same
path as every detector. It is a row on the player's record, it lands in a case, it is announced in
game, and it goes to the Discord alerts channel through `SignalRaised`. Two things differ from the
other types. It joins the player's **newest open case of any kind**, because leaving is part of the
investigation they were frozen for, not a new one. And it is **always loud**, even when it only joins
a case, because somebody has to act on it now. Confidence is 90: certain that it happened, and above
the default threshold, so a player with no case gets one (of kind *Other*, which staff can change).

**What counts as leaving.** By the time Fabric's disconnect event fires, a player who quit and a
player who was kicked look the same. A mixin at the head of
`ServerCommonPacketListenerImpl#disconnect(DisconnectionDetails)` tells them apart: only the server
calls that method, so being called at all means the server ended the connection, and the reason is
kept. A kick, a ban or a login from elsewhere is not reported. A server shutdown is not reported,
whether shown by that reason or by the server no longer running. A timeout comes through the same
method with `disconnect.timeout` and **is** reported, as "lost connection": pulling the network cable
looks exactly like that. If the mixin stops applying, every disconnect of a frozen player reads as
leaving. Staff are then told too often, which is the safe way to be wrong, and the startup check
names the hook.

**What goes in the case.** The detail says who froze them and how long they had been held, so every
freeze path now passes a name: commands, the panel, the staff tools and Discord. The evidence is
the place they were frozen, plus a replay window up to the moment they left when position tracking
is on.

**Coming back.** The freeze was already kept on disk across a logout. Returning now also tells staff,
quietly, and notes it on the open case.

**Nothing auto-punishes.** Leaving while frozen is written down and announced. Whether it deserves a
ban is a person's call.

---

## A frozen player's screen

**Date:** 2026-09-17

Asked for: "their screen should have the blindness effect and a huge text overlay in bold saying
'You are Frozen, Join the discord and contact staff', and remove the text it says when frozen."

**Titles, not chat.** The old freeze sent two chat lines, then a reminder every ten seconds. Chat
scrolls away under whatever the player is typing, and it is hidden entirely for a player who has
chat off. A title sits in the middle of the screen and cannot be scrolled past. Blindness removes
anything else to look at, and any reason to try walking. So the title says **YOU ARE FROZEN** in
bold, the subtitle says to join the Discord and contact staff, and the action bar gives the
`discordInvite` and says that leaving is reported.

**Sent again, not once.** A title fades, a milk bucket clears effects, and a client that
reconnects has forgotten both. Blindness is topped up every second, with a few seconds on it; the
title is resent every two seconds, with no fade-in so it never flickers. On release the titles are
cleared and the blindness removed.

**Only our blindness.** StaffCore's blindness is ambient, with no particles and no icon. On release
only blindness that still looks like that is removed. A player who drank a potion of blindness
before being frozen keeps it: vanilla keeps the longer effect and the non-ambient flag.

**One line of chat, on purpose.** A title cannot be clicked, and a player told to join a Discord
needs a way to get there. When `discordInvite` is set, the invite is sent once, as a clickable link.
That is the only chat line; the old warnings are gone.

**Tested** with the packets a test player is actually sent, which `Harness.sent` now collects.

---

## Gate 5

**Date:** 2026-09-17

The brief's last gate, for the Discord companion. Each condition, and what shows it.

**"Discord cannot perform any action the same account could not perform in game. Test the bypass
attempts explicitly."** What a Discord user may do is the smaller of their role mapping and what
their linked account resolves in game, read again on every request. The attempts, and where each is
tried:

| Attempt | Test |
|---|---|
| A role mapping a node the account lacks in game | `DiscordAccessTests.whatDiscordAllowsIsTheSmallerOfRolesAndTheGame` |
| Being an operator in game, with no role for it | `DiscordAuthorityTest` |
| An unlinked account with an admin role | `DiscordAccessTests.anUnlinkedAccountHoldsNothingWhateverItsRoles` |
| A banned staff member unbanning themselves from Discord | `DiscordAccessTests.aBannedAccountCanDoNothingFromDiscord` |
| A wildcard or unknown node in the role mapping | `DiscordSettingsTest`, `DiscordAuthorityTest` |
| An IP ban, rollback or inventory edit from Discord | `DiscordAccessTests.ipBansRollbacksAndInventoryEditsRefuseDiscordWhateverItHolds` |
| Approving a staged action from Discord | `BypassAttemptTest` |
| Outrunning the rate limit, or punishing upwards | `DiscordCommandTests` |
| A permissions mod refusing a node to an operator who has a role for it | `DiscordBypassTests` (new) |
| An offline account whose permissions mod has not loaded it yet | `DiscordBypassTests` (new) |
| Promotion or demotion in game between two requests | `DiscordAccessTests` |

The sweep found one real bypass, in game rather than from Discord. `Actor.has` counted being an
operator as holding every node. The node set is resolved through `Permissions.check`, so an operator
the settings let through already has every node. The extra clause only mattered for operators the
settings had refused: with `operatorsBypass` off, or a permissions mod saying no, they still passed
the rate-limit exemption and the IP-ban check. `has` now reads the node set alone, and
`ActorBoundaryTest` pins it. Discord actors were never built with the flag, so the Discord side was
not exposed.

**"Killing the bot mid-punishment leaves the punishment applied and the embed queued."** Two halves,
two tests. `DiscordBypassTests.aBotThatDiesMidPunishmentLeavesThePunishmentApplied` registers a
listener that hangs and one that throws, then bans through the real service. The ban is in force
when the call returns, the call does not wait on the hung listener, and a listener after the dead
one is still told. Delivering events on the calling thread fails it. `DiscordBotQueueTest` kills the
companion's connection, publishes punishments, and checks they wait in the queue, bounded, and are
posted once each when the connection is back.

**"Token never appears in any log, export, or error path. Grep for it in test output."**
`DiscordBotTest` hands the test token to a library logger, to exceptions and their causes, and to
a status line, and checks the log and `/staff status` for it. It also runs the bot through a
settings rewrite, a thread-book write and a full personal export, and checks every file except
the token's own. The companion's build then runs `checkTestOutputForToken` after the tests. It
searches every result file, report and line of captured output for the token, and fails the build
if it is there. The token is built at runtime in both places, so the repository holds no
token-shaped literal. The search was shown to work by planting the token in a result file.

---

## Posts wait when the bot cannot post

**Date:** 2026-09-17

Phase 5.6: "The bot being unreachable is normal, not exceptional. Queue outbound embeds with bounded
backpressure and drop oldest with a logged count when full. Never block a punishment on a Discord
write."

**The punishment was already safe; the post was not.** StaffCore's `EventBus` has always handed
events to listeners on a thread of its own, with its own bounded queue, and `StaffCoreApiTest`
proves a hung listener does not hold up whatever raised the event. The companion then threw away
any post made while it was not connected and counted it. Nothing was queued.

**`PostQueue`.** The router offers posts to a queue instead of the connection. The queue posts one
at a time on the companion's worker, and only while the connection says `readyToPost`: connected,
in the guild, with its channels made and checked since connecting. The connection wakes the queue
once its channels are set up and whenever JDA's status goes back to `CONNECTED`. It says it is ready
before it wakes the queue, and the queue reads that under its lock, so a wake cannot be lost between
a turn ending and a reconnect.

- **Bounded, oldest first.** `outboundQueueSize` (500, 50–10000). A full queue drops its oldest post
  for each new one. The first drop is logged at once, then a count at most once a minute, so an
  outage costs a few log lines. The newest posts are the ones staff are about to look for.
- **What counts as passing.** A failure while the connection is down puts the post back at the front
  and costs no try, since an outage is not the post's fault. A Discord server error or a network error
  while connected also puts it back, then waits five seconds; after five such tries it is given up and
  logged. Any other failure, such as a missing permission, is counted and dropped at once, as before,
  because trying again changes nothing.
- **One post per turn.** Each post is its own task on the worker, so a button click is not stuck
  behind a whole backlog.
- **Memory only.** Keeping the queue across restarts would mean writing embeds to disk, and a post
  about a ban from before a restart is still in `#punishments` history once it is made. Not worth a
  second file. Posts waiting at shutdown are lost, which is now the known limit.

**Status.** `/staff status` and the panel list waiting, dropped and given-up posts only when there
are any. The startup diagnostic logs one line on the bot, a tick after the server starts, because
the companion reads its settings in the same event after the hook check.

**Tests.** `PostQueueTest` covers order, the bound, the log rate, a disconnect mid-post, server
errors, a broken post, a hung post, and a stopped worker. `DiscordBotQueueTest` runs it through the
whole bot: connection killed, punishments published through StaffCore's event thread, queued and
bounded, then made once each when the connection is back.

---

## The permissions API is a library, not a permissions mod

**Date:** 2026-09-17

Reported from a fresh server: `/staff` missing for an operator, `permissions.json` never written,
and the log saying the file was ignored because a permissions API was present.

**What was wrong.** StaffCore took fabric-permissions-api being on the classpath to mean a
permissions mod was in charge, ignored its own groups file, and called the API's two-argument
`check`, which turns "nobody has an answer" into "no". The API is a library that many mods ship
inside their own jars. With no LuckPerms behind it, every node was refused to everybody, and the
`/staff` root, which needs any staff node, was hidden. Fabric API 26.2 has its own permission
module in a different package, so it was not the cause.

**The order now.** The API's `getPermissionValue` is asked first. `TRUE` or `FALSE` is a
permissions mod speaking and wins. `DEFAULT` falls through to `permissions.json`, then the operator
level, exactly as on a server without the API. The file is always loaded and written. This is the
convention most Fabric mods follow (`check(source, node, level)`), and it changes one thing on a
LuckPerms server: an operator now holds the StaffCore nodes LuckPerms leaves unset, as
`operatorsBypass` says. Setting `operatorsBypass` to false in the file closes that, as it always has.

**Offline accounts are asked too.** "Offline with a permissions plugin is refused", under
[Who a Discord user is allowed to be](#who-a-discord-user-is-allowed-to-be), assumed the API could
only answer about connected players. In fact the API has an offline check.
It answers with a future, because a permissions mod may have to load the account first. Nothing
waits for it. A finished future is an answer. An unfinished one means "cannot say", and the Discord
gate and the rank guard refuse it, as before. With nothing listening, the future is already finished.

**Why this was not caught.** No test ran with the API on the classpath. The game-test run now
carries the real fabric-permissions-api 0.7.0 with nothing listening, so every game test runs the
way the reported server does. `PermissionApiTests` adds a stand-in permissions mod for the
precedence and offline cases. With the old reading put back, 32 game tests fail.

---

## Replays as maps in Discord

**Date:** 2026-09-17

Phase 6.8, "is there a way to embed the replays as evidence, something that can be viewed in Discord as
well?"

**Not a video.** A replay in game is the client rendering the world around a camera; the server has no
renderer, and building one to encode video would be a game engine of its own. What the server does have
is where the player was and what they changed, so that is what Discord gets: a map from above.

**Shapes only.** Drawing text needs fonts, and the Java a server runs on often has none — the failure is
an error at the first string, on the one host nobody tested. Lines, dots and squares need nothing. So the
picture carries no words, and the message it is attached to says the window, the coordinates, the grid
and scale-bar sizes and the counts. The path is coloured by time through blue, violet, magenta and yellow;
a straight blend from blue to yellow goes grey half way, and red is taken by broken blocks. Gaps over ten
seconds and jumps over sixteen blocks are left unjoined, because a teleport drawn as a line is a path that
was never walked, and only the dimension the player spent most time in is drawn, with the rest said.

**Behind `staff.replay`, and never kept.** In game, filing a replay against a case is not allowed to become
a way to watch one without that node, and position history is personal data with its own retention. The
map is the same data, so the same rules: `VIEW_REPLAY` on both sides, audited, and the picture is drawn on
request and sent privately. It is not posted in the case thread and not kept as a file, because either
would outlive `positionRetentionDays`; replay evidence stays a pointer, and past retention it says there
is nothing to draw.

**Off the tick.** The permission check is on the server thread; reconstructing the window and reading the
block log run on a pool thread, and the drawing on the companion's. The track is thinned to four thousand
points, keeping the first and last, and block changes are capped at two thousand, with both said.

---

## A punishment panel for admins

**Date:** 2026-09-17

Phase 6.7, "a separate punishment panel that uses StaffCore punishment ladders in Discord, only admin can
use".

**"Admin" is a permission, not a Discord role.** The brief rules out implicit admin, and a Discord role
must not become Minecraft authority. So the panel has its own node, `discord.punishpanel`, needed in game
and in `roleNodes` like every other Discord operation. It sits outside `staff.punish.*` on purpose: the
starter moderator group holds that wildcard, and would otherwise have the panel. The starter admin group
has it; permissions file v2 adds it to an admin group still exactly as shipped and leaves an edited one
alone, saying so. The punishment a rung picks still needs its own node, so the panel never issues
anything its user could not issue by name.

**The same ladder as the game.** The rung comes from `countForOffence`, the reason is the offence's label,
and the offence id is recorded, as the punish screen does. The menu only offers offences whose rung the
person may give; the rest are listed as not theirs.

**A confirmation is tied to what it showed.** It carries the prior count it was drawn with, and the
punishment is issued only if the count is still that when Confirm is pressed — a second moderator acting
in between, or a stale menu, issues nothing. Confirmations are random twelve-byte tokens held for five
minutes, only for the Discord account that drew them, and dropped when used. The menu choice is read
again from the server rather than trusted from the menu.

**The channel is the admins'.** Made private to the roles whose mapping lists the panel node, not to every
staff role, so moderators do not look at a button that will refuse them. With no such role, only the bot
and server administrators see it.

---

## An evidence locker in Discord

**Date:** 2026-09-16

Phase 6.6, "evidence locker messages and attachment records".

**Two ways in, one door.** `/staff evidence-add` takes a file and a note; a message menu, *Add to case
evidence*, takes a message with everything on it. Discord hands a bot a message's text in that
interaction whatever its intents, so this works without Message Content Intent. The form that asks for
the case loses the message, so the message is held for fifteen minutes under the person and message id,
at most two hundred at once. Both end in `fileEvidence`, behind `ADD_EVIDENCE` — `staff.gui`, the node the
in-game evidence commands sit behind — and the Discord action limit.

**Kept, not linked.** Discord's attachment links are signed and expire, so a link is not evidence a
month later. The companion downloads each file on its own thread, only after `mayFileEvidence` has said
yes, and never more than `evidenceMaxMegabytes` of it: the size Discord reports is checked first and the
stream is cut off past the limit anyway. The file is named by its SHA-256, which both removes anything a
person typed from the path and keeps a file filed twice once, and it keeps its extension only if it is a
kind a person opens; everything else is `.bin`, so nothing kept runs on a double-click. The hash is
recorded, so a kept file can be shown not to have changed.

**StaffCore checks what the companion says it kept.** The companion writes the file and reports its
path. StaffCore accepts only `CASEID/<64 hex>.<ext>`, resolves it inside the evidence folder, checks the
file is there and the path carries the reported hash, and otherwise records the file as not kept. That
check is a file lookup on the server thread; it is one stat per file, at most ten.

**Where it lives.** `staffcore-evidence` beside the world, like the database, so a copy of the world is
a copy of its evidence. The rows are a `case_evidence` row of the new kind `DISCORD` plus
`evidence_discord` and `evidence_files` (migration 32). Nothing is deleted: retracting hides the
evidence, and its files stay.

**Where it is seen.** In the case's thread, posted with its files as they are filed — files too large for
the server's upload limit are named instead. `/staff evidence <case> item:<n>` attaches them again,
privately. In game, where files cannot be shown, opening it prints the message, the files with their
hashes and a link to the message.

---

## Case ids complete, for staff only

**Date:** 2026-09-16

Phase 6.5, "autofill player names or codes by staff only". Player names already completed for a linked
account holding anything. Case ids now complete too, live cases first, labelled with the player and
what the case is about — which is exactly why they are offered only to a linked account allowed to look
at cases from both sides, the game and `roleNodes`. A list of case ids is a list of who is suspected of
what. Appeal codes do not complete for anybody: they are the player's, and staff have no command that
takes one.

---

## A public appeal channel with a button

**Date:** 2026-09-16

Phase 6.4. "A separate channel where players can type /appeal or click on some embed."

**The one channel the bot makes public.** `appealIntakeChannelId` used to refuse `"create"`, because
every channel the bot made was private. Players need to see this one, so it is made outside the
StaffCore category with its own overwrites: everybody may view and read history, and use application
commands as they may anywhere; nobody but the bot may send messages or start threads. Reactions stay on: Discord refuses an
override for a permission the bot does not hold, and the invite link does not grant it. A channel for one message and
a button is not a place for players to argue in public, and it is not where staff discuss anything —
appeals are still posted to the private `#appeals`. A top-level channel already called `appeal` is used
rather than a second made, as for the category's channels.

**The panel is posted once.** It is remembered in the thread book under `panel:appeal` and edited to the
current wording on every start, which also refreshes its age, so the book's ninety-day forgetting never
reaches it. If the book was lost, the channel's last fifty messages are searched for the bot's own
message carrying the button before a new one is posted. Pinning is tried and allowed to fail: it needs a
permission the bot is not asked for, and the panel works unpinned.

**The button asks for the code in the form.** `/appeal` takes the code as an option and opens a form for
the reason; a button has nowhere to type, so its form asks for both. Both end in the same
`fileAppeal`, with the same attempt counting. `/appeal` is restricted to the channel only once it
exists, so a `"create"` that failed does not tell players to use a channel that is not there.

---

## A staff chat channel by default

**Date:** 2026-09-16

Phase 6.3. `staffChatChannelId` was the one channel left empty, because reading what staff type needs
Message Content Intent, a privileged intent switched on by hand in the developer portal, and a bot that
asks for it without it being on is refused at login — the whole bot, not just the bridge.

**It is `create` now, and the refusal is survived.** When Discord closes the connection with "disallowed
intents", the bot connects again, once, without message content. Everything else works: posts, buttons,
commands, and game lines reaching the channel. Only reading typed lines is lost, and `/staffchat` covers
it, because a command carries what was typed whatever the intents. `/staff status` and the panel say the
intent is off, and a line typed in the channel gets a reply saying to use `/staffchat` — at most every
five minutes, and deleted after one, so the channel does not fill with them. `GUILD_MESSAGES` is kept
without the content intent so that reply is possible at all.

**`/staffchat` posts the line itself.** A typed line is already in the channel; a command is not, so once
StaffCore has taken it the bot posts it there, name and all, with mentions off. It is the same call a
typed line makes, so the same link, permission and rate checks.

Files written by earlier builds keep `""`; the guide says to change it.

---

## Back after a ban

**Date:** 2026-09-16

Phase 6.2: staff are told when somebody joins for the first time since a ban of theirs ended.

**Noticed once, as a state on the ban.** `punishments.return_noticed_at` is set on the first join after
the ban ran out or was lifted, and the announcement is made only if this join was the one to set it —
so two joins close together, or a second server on the same file, cannot both announce it. Migration 31
marks every ban already over, because the alternative is an upgrade that announces every player who was
ever banned, one join at a time, for months. With `notifyReturningPlayers` off, bans are still
marked on the join and nothing is said, so switching it back on does not replay the returns that
happened meanwhile.

**Bans only.** A mute that ends never kept anybody out, so there is no return to notice. IP bans are a
separate table about addresses, not accounts, and are not included.

**Where it goes.** In game, to staff with alerts on, through the alerts module, which also mirrors it to
the webhook. To Discord, as a `PlayerReturned` event the bot posts in the alerts channel, the one staff
watch for what needs attention. The ban's case, if it has one, gets a note, which reaches the case's
thread the way every case note does.

**The ban type names are written out** in the SQL rather than read from the enum, because migration 31
runs before the game's items exist and the enum is built from them. `ReturnWatchTypesTest` fails if a
ban type is added and the list is not.

---

## Appeal codes end with a decision

**Date:** 2026-09-16

Phase 6.1. A code used to last as long as its punishment, so a decided appeal could be filed again with
the same code once a fixed wait was over.

**A table of codes, not a changing column.** Every code a punishment has had is a row in
`appeal_codes`, with when it was issued, when it starts working, and — once it stops — when, by whom
and why. The punishment row keeps the code it was issued with, untouched. Migration 30 copies every
existing code in as issued, so an upgrade changes nothing a player holds. Accepting retires the code; a
rejection retires it and issues a new one in the same transaction, so a punishment never has two
working codes or none by accident. A close or a stale appeal leaves the code alone: nobody decided.

**The wait is chosen by whoever rejects.** "Once the staff tells them to wait a certain period" — so a
rejection asks, from Discord as a number of days and in game as a choice of seven, with
`appealCooldownDays` filled in or marked. The wait is carried by the new code's `usable_from`, not
recomputed from the last rejection, so changing the setting later does not move a wait somebody was
already given. It is bounded to a year; a longer wait is a punishment of its own.

**The new code is shown at once, with its date.** A banned player's only view is the ban screen, which
they photograph. Showing the code only once it works would mean coming back to photograph it again, so
the screen shows it immediately with the day it starts working. A muted player sees the same line when
chat refuses them, and the rejection message if they are online.

**A code from before the table** that somehow missed the copy still works through the punishment row —
until the punishment has a code in the table, which means a decision has replaced it.

---

## One folder for configuration, and the bot on the panel

**Date:** 2026-09-16

Before the 1.1.0 release: every file an owner edits moves into `config/staffcore/`, the staff panel's
Discord section says whether the bot is running, and two things that kept CI red are fixed.

**`config/staffcore/`, with the old files moved in.** Four files sharing a prefix, loose among every
other mod's, is a folder that had not been made. The settings keep the name `staffcore.json`, which
the documentation and the brief both use; the others drop the prefix the folder now carries:
`permissions.json`, `discord.json`, `discord.token`. A server upgrading has the old files moved on
the first start and the log says so. A move and not a copy, so the token is never in two places, and
a rename keeps the token file's permissions. When both exist the folder's copy is used and the old
one is left alone with a warning, because it is somebody's file and may differ. When the move fails —
a read-only folder, a file where the folder should be — the old file is used where it is, so the
owner loses a tidy layout rather than their settings. The world's data stays beside the world: it
belongs to the world, not to the server's configuration.

The companion does its own move, with the same rules, rather than calling into StaffCore's internals:
`ApiBoundaryTest` keeps it to the published API, which gains `configFolder()` for where the folder is.

**The panel shows what the bot says about itself.** The companion is a separate mod, so the panel
cannot look at the bot; the bot reports a `DiscordBotStatus` through the API, and `/staff status` now
prints the same record, so the two cannot disagree. It carries a phase, the one-line summary, the
problems, each channel and the ping, and nothing else: no token, no address, and no message a library
wrote. A report that throws is shown as "not working" with the exception's class and never its
message, as status lines already were. "Off" and "not set up" are different phases, because a bot the
owner switched off and a bot that cannot start look the same in a log and need opposite responses.

The section used to be for `staff.reload` only. Linking is for every staff member, so the section is
now open at `staff.gui`, and what only an owner acts on — channels, who is linked, the webhook, and the
detail of what is wrong — is locked inside it, the way every other section shows what somebody lacks.
Everybody sees whether the bot runs and that something is wrong; only an owner sees what. Linking from
the panel runs `/staff discord link` as the player, so it is the same permission check and audit row.

**API version 4.** `DiscordBotStatus`, `reportDiscordBot`, `configFolder`, and the 5.5 calls on
`DiscordAccess`, which were added without a bump.

**The boot check had failed on every push for weeks, on an em dash.** CI's boot check fails when
StaffCore writes a non-ASCII console line, because a Windows console on a legacy code page shows it as
mojibake. The anti-xray startup line — "does not prevent it — an obfuscating mod…" — broke that on
every boot, and the red build became background. The hook names, printed only when a hook breaks,
carried dashes too, and no clean boot could have shown them. `ConsoleTextTest` reads the source
instead: every literal inside a logger call, and every literal in the files whose sentences are handed
to one. It fails with the dash put back. The companion has the same test.

**A grief log read could repaint the screen inside the click that asked for it.** The log's
background reads hand their result back with `server.execute`, which on the server thread runs at
once. A read quick enough to finish before its callback was attached therefore ran the callback inside
the caller: a click that drew "nothing left to roll back" on a row, then asked for a fresh page, had
the fresh page — without that row — drawn over it in the same handler. CI met this now and then as a
failing game test. Making every read finish at once reproduced it every time; with the hand-back always
queued, the same forced run passes. The warning is also no longer followed by an immediate re-read,
which would have taken the row away before anybody could read it; the two-second refresh does that.

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

### 26.2 renames found while building the chunk-send hook

- `ChunkPos.asLong(int, int)` is **`ChunkPos.pack`** in 26.2, and there is a
  `pack(BlockPos)` overload worth using directly.
- `ChunkPos.x` and `ChunkPos.z` are **private**. Use the instance method `pack()` when you want
  a key, which is usually what the caller wanted anyway.
- The chunk-send funnel is `net.minecraft.server.network.PlayerChunkSender#sendChunk`, private
  static, taking `(ServerGamePacketListenerImpl, ServerLevel, LevelChunk)`. Note the package:
  `server.network`, not `server.level` where the rest of the chunk machinery lives.

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
| Every node fell back to op level without a permissions mod | `config/staffcore/permissions.json` — real groups, `/staff perms` |
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
| A player who handed back a whole stack was still booked as owing it | The item is read before the stack is shrunk; an emptied stack reads as air |
| Debts were booked for spilled items that despawned or burnt and nobody had | Only what the pickup log shows the player picking up is owed |
| Decoys sat wherever the player first dug, so going deeper brought none | Veins left out of reach are retired and replaced near the player |
| Decoy heights followed the player rather than where diamonds generate | Heights are drawn from the game's own `ore_diamond*` placement: bottom of the world to Y 16, thickest at the bottom |
| Several commands did the same job (`panel`, `lookup`, `say`, `notes add`, three undos) | One command per job; `/staff undo <ref>` undoes everything |
| A case closed as actioned did not say what the player got or why | Closing picks the punishment, and its type and reason go into the case history |
| Case commands crashed when run from the console | They read the source's name, not the player's |
| Mining visible ore in a cave was flagged as x-ray | A vein touching open space is not hidden; the sweep leaves cave air and cave-wall ore out of its odds |
| X-ray judged only what was found, never how the player dug | Each tunnel leg is weighed against the directions not taken, and an alert from ore needs the two to agree |
| Rate-limit refusals printed "%d %s(s) a minute" with nothing filled in | The message is formatted as a whole; a test reads it |
| The CI boot check failed on every push, on an em dash in a startup line | Console lines are ASCII, and a test reads every logged literal |
| A grief log row could lose its "nothing left" warning in the same click | Background reads always report back on a later task |
| Config files sat loose in `config/` | `config/staffcore/`, older files moved in on first start |
| The panel's Discord section said the bridge was one-way, and only owners could open it | It shows whether the bot runs, and every staff member can link from it |

---

## Adding a module

```java
public class ExampleModule implements Module {
    @Override public String id() { return "example"; }
    @Override public void onEnable() { /* register events here */ }
}
```

Register it in `StaffCore.registerModules()`, add a typed accessor to `Mods`, add any nodes
to `Nodes`, and add an entry to the section it belongs in, in `StaffSections`
(`SectionCoverageTest` fails on a screen nothing opens). Menus extend `Gui` (fixed layout) or
`PagedGui<T>` (28-entry list with page controls) — describe the screen in `build()` and
call `render()` at the end of your constructor. Draw the back arrow with `backButton`, never
by hand: it follows the viewer's path and takes a fallback for when there is none.
