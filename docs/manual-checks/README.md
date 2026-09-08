# One session, five claims

**Status:** NOT YET RUN
**Run by:** _______________
**Date:** _______________
**Server version / mod version:** _______________
**Outcome:** ☐ all five pass ☐ some failed — see the two scripts for detail

---

## What this is

Five things about StaffCore cannot be checked by any automated test, because they need a person
looking at a screen. They are written up in two scripts:

- [`decoy-visibility.md`](decoy-visibility.md) — two claims about canary blocks
- [`vanish.md`](vanish.md) — three claims about vanish

This file sequences them so the setup happens **once**. Run in this order and nothing has to be
installed, configured or restarted twice. About **twenty-five minutes** end to end.

> Read this page first, do the setup, then work through the two scripts in the order below,
> filling in their result lines as you go. Come back here at the end.

---

## Before you start

### Install

| What | Why |
|---|---|
| A **test server** running StaffCore | The config changes below are not for production |
| **Two accounts** that can be online together | Vanish check 3 needs a second pair of eyes |
| An **x-ray resource pack** for this Minecraft version | Decoy checks 1 and 2. A resource pack, not a cheat client — search "xray resource pack" |
| **No anti-xray mod** | StaffCore turns decoys off entirely when one is present, and will correctly tell you so instead of testing anything |

Confirm the last one before anything else: run `/staff status` and check the anti-xray line
says **"none installed"**. If it names a mod, remove it and restart, or the decoy half of this
session measures nothing.

### Config — set these once, put them back at the end

Stop the server. In `config/staffcore.json`:

| Key | Set to | Normally | Why |
|---|---|---|---|
| `canaryBlocks` | `true` | `true` | Decoys on |
| `canaryDensity` | `20` | `6` | Several appear quickly instead of one every few minutes |
| `canaryMaxY` | `60` | `16` | You can dig a test chamber near the surface |
| `canaryRadius` | `24` | `48` | They land near you rather than scattered |

**Write your current values down before changing them.** Step 4 at the end puts them back, and
the real values are deliberately low so that meeting a decoy by chance stays negligible.

Nothing in `vanish.md` needs config changes.

### Accounts

Start the server, log both accounts in, and put both in **survival**:

```
/gamemode survival STAFF
/gamemode survival WATCHER
```

`STAFF` needs `staff.vanish` and `staff.reload`. `WATCHER` needs nothing.

Survival matters more than it looks — creative short-circuits two of the three vanish
behaviours, which is exactly why the automated versions of those tests were worthless.

---

## The order, and why it is this one

Each step leaves the world in the state the next one needs.

| # | Script | Check | Where you need to be |
|---|---|---|---|
| 1 | `vanish.md` | 3 — pressure plates | Surface, flat ground, both accounts together |
| 2 | `vanish.md` | 1 — mobs stop targeting | Somewhere dark, or `/summon zombie` |
| 3 | `vanish.md` | 2 — spawning resumes | Same dark area — carries straight on from step 2 |
| 4 | `decoy-visibility.md` | 1 — decoy visible through rock | Underground, below y 60, in solid stone |
| 5 | `decoy-visibility.md` | 2 — resync arrives | Same chamber, immediately after step 4 |

The vanish checks come first because they happen at the surface and need no digging. The decoy
checks come last because they need you underground in solid rock, and step 5 continues from
exactly where step 4 leaves you.

Enable the x-ray resource pack only at step 4. Leave it off for the vanish checks — it changes
nothing about them and it makes step 2 harder to judge, because you can see the zombie through
walls.

---

## Working through it

1. Open [`vanish.md`](vanish.md). Do its **check 3**, then **check 1**, then **check 2**. Fill
   in its result lines as you go.
2. Open [`decoy-visibility.md`](decoy-visibility.md). Skip its Step 1 — the config is already
   set. Do its Step 2 onward: get decoys placed, confirm one is a lie, then checks 1 and 2.
3. Come back here.

**Every check has a control step.** Confirm the zombie targets you before vanishing, confirm
the plate fires before vanishing, confirm the server really has stone where the decoy is before
looking with the pack. If a control does not fire, the check is **inconclusive, not a pass** —
record it that way and move somewhere it works. A control that does not fire is the same failure
this whole exercise exists because of.

---

## Afterwards

### 1. Put the config back

Restore `canaryDensity`, `canaryMaxY` and `canaryRadius` to the values you wrote down (`6`, `16`
and `48` if they were untouched) and restart. Leaving the test values in makes decoys common
enough that an honest miner meeting one stops being negligible.

Put both accounts back to whatever gamemode they were in.

### 2. Record the results

Fill in the header of this file and of both scripts. Then, per their Step 5 / Step 3:

- **Both decoy checks passed** — remove the "Decoy blocks are packet-verified, not
  client-verified" entry from `README.md` Known limits, and update the closing note of the
  "Canary false-positive rate" section in `docs/decisions.md`.
- **All three vanish checks passed** — update the "port is compile-verified, not
  runtime-verified" entry in `README.md` Known limits.
- **Anything failed** — leave Known limits exactly as it is and open an issue. It is currently
  correct that these are unverified, and it should stay correct.

---

## If the session fails: what it costs

Not all five are equally load-bearing. Read this before deciding how much to worry.

### One failure invalidates a feature: decoy visibility (decoy check 1)

**If an x-ray client cannot see the decoy, the canary layer detects nothing.** Not "detects
less" — nothing. Nobody can walk to a block their client never drew, so the true-positive rate
is zero, and the measured false-positive rate of zero stops being a result and becomes an
artefact of the same cause.

That would invalidate **item 3.2 in full** and **one of Gate 3's three requirements**. The
honest response is to remove the canary feature or fix it, not to tune it.

It would leave the rest of Phase 3 standing: the p-value detector reads the block log and never
touches canaries, the replay viewer works from the same data, the corpus is about cases rather
than decoys, and the anti-xray detection is a mod-id lookup.

### The other four are bugs, not invalidations

- **Resync (decoy check 2)** — decoys still work; the failure is a ghost block left on one
  client. Annoying and fixable, and the retirement rule that the false-positive measurement is
  actually about still holds server-side.
- **Vanish checks 1, 2 and 3** — a different feature. If they fail, vanish leaks in that
  specific way and needs fixing, but nothing about Phase 3's x-ray work depends on any of them.

### So, plainly

**If you only have time to run one check, run decoy visibility.** It is the single result that
decides whether a whole feature exists or does not, and it is currently the largest unverified
claim in the project.
