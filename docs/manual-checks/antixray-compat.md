# Manual check: who wins when a bulk anti-xray and StaffCore write to the same block?

**Status:** NOT YET RUN
**Run by:** _______________
**Date:** _______________
**Server version / mod version / anti-xray mod + version:** _______________
**Result:** ☐ StaffCore's decoy survives ☐ the anti-xray overwrites it ☐ they alternate ☐ inconclusive

---

## Why this is now worth ten minutes

StaffCore switches decoys off when it detects a bulk anti-xray, and that rule was **reasoned
about rather than tested**. The stated reason — that both rewrite the same outbound chunk data —
was wrong: StaffCore does not touch chunk data at all. It sends a block update *after* the
chunk arrives, which lands on top of whatever the chunk contained.

So the two might well coexist, and that would be worth having: the anti-xray hides the real ore,
the decoys still catch people. But there is a specific reason to check rather than assume, and
it is the reason this check exists at all.

**Both write to the same positions.** A bulk anti-xray in engine mode 2 sends real block updates
as a player approaches, revealing what it had hidden. StaffCore sends a block update asserting a
decoy, and re-asserts it whenever the chunk is sent. Those are two things writing the same
coordinate over the same connection, and **ordering there decides who wins**. That is exactly
the class of thing this project has just been caught getting wrong by reasoning — a block update
is a delta, and the last delta to arrive is the one the client draws.

There is no way to answer it from the server side. Both mods believe they sent what they sent.

---

## What you need

1. A test server running StaffCore. **Not a production server.**
2. A bulk anti-xray mod — **Meow Anti-Xray** or **AntiXray** (DrexHD). Note the exact version.
3. An x-ray resource pack, and a client.
4. Admin permissions (`staff.reload`, `security.check`).

---

## Step 1 — Turn the override on, and understand what it is

Stop the server. In `config/staffcore.json`:

```jsonc
"canaryForceWithBulkAntiXray": true,   // normally false — NOT a supported mode
"canaryDensity": 20,                   // normally 6
"canaryMaxY": 60,                      // normally 16
"canaryRadius": 24                     // normally 48
```

Start the server. **You should see a warning in the log** saying decoys are being placed anyway,
that this is a compatibility-testing mode, and that signals produced in it are not defensible in
an appeal.

> If there is no warning, the override is not being read and the rest of this check will be
> testing the ordinary configuration. Stop and find out why.

**Why it is not a supported mode**, so you do not come away thinking it is: with an anti-xray
filling the world with fabricated ore, an x-ray user is looking at a screen full of ore that is
not there. Within an hour they learn that nothing the pack shows them is real and stop acting on
any of it. That does not make a decoy hit harder to interpret — **it removes the true
positives**, because nobody walks to a decoy when nobody walks to anything. The value of this
mode is answering the question below, and nothing else.

---

## Step 2 — Get a decoy somewhere you can watch it

1. Dig to about y 40 in ordinary stone, hollow out a small chamber, stand in it ten seconds.
2. `/staff canary`. Write down one coordinate. Call it **D**.
3. Confirm `/staff status` names the anti-xray mod as installed *and* that decoys are on.

---

## CHECK 1 — Is the decoy visible at all with the anti-xray running?

Enable the x-ray resource pack and look toward **D** from a few blocks away.

### Result A — visible

The decoy is drawn. StaffCore's update is landing on top of the anti-xray's chunk data, which
means the two layers compose and the 3.1 auto-disable rule is more conservative than it needs to
be. Go on to check 2.

### Result B — not visible

Something is overwriting it, or the anti-xray's obfuscation is being applied after StaffCore's
update in a way that erases it. Record this and stop — check 2 has nothing to measure.

**Result of check 1:** ☐ visible ☐ not visible
**Notes:** _______________________________________________

---

## CHECK 2 — Does it survive the anti-xray's reveal pass?

This is the one the whole check is for. A bulk anti-xray deobfuscates around a moving player, so
walking toward **D** makes it send real block updates for that area — including, possibly, for
**D** itself.

1. Walk away from **D** until it is out of your view distance.
2. Walk back toward it slowly, watching **D** with the pack on.
3. Get within a few blocks, then back off and approach again two or three times.

### Result A — the decoy holds

It is drawn the whole time, including as you approach. StaffCore's chunk-send re-assert arrives
after whatever the anti-xray sends, and the two coexist. **This is the outcome that makes the
combination worth supporting properly.**

### Result B — the decoy disappears as you approach and does not come back

The anti-xray's reveal update lands after StaffCore's and wins. The chunk-send hook does not
help, because this is not a chunk send — it is a block update racing a block update.

### Result C — it flickers, or comes and goes depending on approach

The worst outcome and the most likely one if the two do interact: order is not deterministic.
A flickering ore trains an observant x-ray user to distrust exactly the blocks meant to catch
them, so this result argues for keeping the auto-disable as it is.

**Result of check 2:** ☐ A: holds ☐ B: overwritten ☐ C: flickers/nondeterministic
**Notes:** _______________________________________________

---

## Step 3 — Put it back

Set `canaryForceWithBulkAntiXray` back to `false` and restore the canary test values. Restart.

**Do not leave this on.** Any canary hit recorded while it was on should be treated as
uninterpretable — note the window in the case if one was opened.

---

## Step 4 — What each result means for the code

| Result | What to do |
|---|---|
| Check 1 not visible | The auto-disable is right for the reason it was written down, by luck. Correct the stated reason in `decisions.md` — it is not about chunk data. |
| Check 2 = A | The auto-disable is more conservative than it needs to be. Consider making it a config choice with the true-positive argument stated, rather than a hard rule. |
| Check 2 = B | The auto-disable is right, and now for a measured reason. Record which mod and version. |
| Check 2 = C | The auto-disable stays, and this is the strongest argument for it: nondeterministic ordering produces a flickering decoy, which is worse than no decoy. |

Whichever it is, replace the reasoning in the "anti-xray companion" note in `decisions.md` with
the measured result, the mod, and the version. The current text is an argument, and this check
is the first thing that can turn it into a finding.

---

## What this does not settle

One mod, one version, one engine mode. A different anti-xray, or the same one configured
differently, can order its updates differently. This is a compatibility data point rather than a
general answer, and it should be re-run against any anti-xray somebody actually intends to
deploy alongside decoys.
