# Manual check: does a session replay look like a session?

**Status:** NOT YET RUN
**Run by:** _______________
**Date:** _______________
**Server version / mod version:** _______________
**Result:** ☐ both pass ☐ check 1 failed ☐ check 2 failed ☐ inconclusive

---

## Why these two and nothing else

Everything the replay decides is tested. The window is reconstructed and checked coordinate by
coordinate against what went in, gaps are checked to be skipped rather than flown across, the
block overlay is checked to release each block at exactly the right instant, and the storage
cost is measured against a real file.

What no automated test can check is that any of it **reaches a screen**. Two things stand
between the server being right and a staff member seeing something true:

1. The camera is moved by teleporting a spectator twenty times a second. The server knows it
   sent the packets. Whether the result looks like a player walking or like a slideshow is a
   question about a client.
2. The block overlay is drawn with `ClientboundBlockUpdatePacket` — **the same primitive the
   canary decoys use.** That one was found to be "hit or miss" by a person with an x-ray pack,
   months after every server-side test passed, because a block update is a delta against the
   chunk the client is holding and any chunk resend silently reverts it. The replay re-asserts
   its overlay on a timer for exactly that reason, and that fix has never been watched working.

**Time needed: about ten minutes.** You need two accounts.

> Running the decoy or vanish checks in the same sitting? Start from
> [`README.md`](README.md) instead — it sequences the scripts so the setup happens once.

---

## What you need

1. A test server running StaffCore. **Not a production server** — step 1 changes settings.
2. **Two accounts** that can be online together. One is `STAFF` and needs `staff.replay`
   (it is in the `admin` group by default, not `moderator`). The other is `SUBJECT` and needs
   nothing.
3. Nothing else. No resource pack, no anti-xray considerations — this check is unaffected by
   both.

---

## Step 1 — Turn position tracking on

Stop the server. In `config/staffcore.json`:

```jsonc
"positionTracking": true,     // normally false
"positionSampleHz": 2,        // leave it
"positionRetentionDays": 7    // leave it
```

Start the server and join with both accounts.

> **It only records from now on.** Switching this on does not fill in the past, so there is
> no point trying to replay anything from before this restart.

---

## Step 2 — Give it something to replay

As `SUBJECT`, for about **two minutes**:

1. Walk somewhere — a hundred blocks or so, turning corners. Not in a straight line.
2. **Break about ten blocks** along the way. Dirt, stone, anything. Remember roughly where.
3. **Place about five blocks** somewhere obvious — a small tower is ideal, because you will be
   watching for it to *not* be there.
4. Stand completely still for **at least thirty seconds**, then walk on again. This is the gap.
5. Walk back to where you started.

Then run, as `STAFF`:

```
/staff replay SUBJECT 10m
```

**You should be** put into spectator at the point `SUBJECT` was two minutes ago, with a chat
line naming how much movement it found, and a sidebar in the top right showing a time, some
coordinates, a speed and a percentage.

- If it says **"Position tracking is off"** — the config change did not take. Check the file.
- If it says **"no recorded movement in that window"** — you are replaying a window that
  predates step 1, or `SUBJECT` never moved.

---

## CHECK 1 — Does the camera move like a player?

Watch for thirty seconds without touching anything.

### Pass

You are carried along the route `SUBJECT` walked. The motion is continuous — corners look like
turning, not like jumping. The sidebar time advances and the percentage climbs.

When you reach the stretch where `SUBJECT` stood still, a chat line appears saying roughly
`skipped 30 seconds — no movement recorded`, and playback continues from where they moved
again.

Try `/staff replay speed 4` and then `/staff replay pause`. Both should take effect immediately.

### Fail

Pick whichever describes it:

- **Nothing moves.** The playback driver is not ticking.
- **It jumps between points rather than moving between them.** Interpolation is not happening —
  the replay is showing sample positions only, which at 2 Hz is a slideshow.
- **The thirty seconds of standing still plays out in real time with no chat line.** Gap
  detection is not firing, and a replay of a long session will be mostly waiting.
- **It skips without saying so.** Worse than the above: time is being removed from the
  recording with nothing telling the viewer it happened.

**Result of check 1:** ☐ pass ☐ fail — which of the four: _______________
**Notes:** _______________________________________________

---

## CHECK 2 — Is the world put back, and does it stay put back?

This is the one that shares a failure mode with the decoys.

1. `/staff replay SUBJECT 10m` again (or `/staff replay restart` if you are still in one).
2. Look at where `SUBJECT` **broke** blocks, before the replay clock reaches that moment.
3. Look at where `SUBJECT` **placed** the little tower.
4. Let it play through both, watching.
5. **Fly a few hundred blocks away and come back**, still inside the replay. This forces the
   client to reload those chunks.

### Pass

- Before their moment: the broken blocks are **standing** and the placed tower is **absent**.
- As the clock reaches each one: the block disappears, or the tower block appears, roughly in
  the order `SUBJECT` did it.
- After flying away and back, whatever has not happened yet is **still drawn**. The overlay
  survives a chunk reload.

### Fail

- **The blocks are already broken and the tower is already there from the first frame.** The
  overlay is not reaching the client at all. The replay is showing the world as it is now,
  which is the thing the overlay exists to prevent.
- **It works, then everything reverts after flying away and back.** This is the decoy bug
  exactly: sent once, never re-asserted, silently lost on a chunk resend. The re-assert is on a
  five-second timer, so give it that long before concluding.
- **Blocks release at visibly the wrong time** — a hole opening before the swing, or the player
  walking through a wall.

**Result of check 2:** ☐ pass ☐ fail — which of the three: _______________
**Notes:** _______________________________________________

---

## Step 3 — Get out, and put the config back

`/staff replay exit`. You should be returned to exactly where you were standing before, in your
original gamemode, with the sidebar gone and no leftover blocks drawn anywhere.

> If you are **not** put back, that is a fourth failure and the most serious one — record it.
> Every way out of a replay runs through the same code, so a broken exit is broken for
> disconnects and deaths too.

Then stop the server and set `positionTracking` back to `false` unless you want to keep it.

---

## Step 4 — Record the outcome

1. Fill in the header block at the top of this file.
2. If **both passed**: remove the "Session replay is packet-verified, not client-verified" entry
   from `README.md` Known limits, and note in the
   [position history section](../decisions.md) that the overlay was confirmed by hand, by whom,
   and when.
3. If **either failed**: leave Known limits exactly as it is and open an issue with the check
   number and which variant. It is currently correct that this is unverified.

---

## What a pass does and does not establish

A pass means one client, on this version, draws the overlay and holds it across a chunk reload,
and that the camera reads as movement rather than as a sequence of positions.

It does not establish that the replay is comfortable to watch for an hour, that the speed
controls are the right ones, or that 2 Hz is the right rate. Those are judgements, and this
check does not make them.
