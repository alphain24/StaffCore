# Decisions

Why things are the way they are, with the measurements that decided them. Kept separate from
the README so the reasoning is findable without making the front page longer.

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
