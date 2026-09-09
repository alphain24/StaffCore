# Manual check: are decoy blocks actually visible to a cheating client?

**Status:** NOT YET RUN
**Run by:** _______________
**Date:** _______________
**Server version / mod version:** _______________
**Result:** ☐ both pass ☐ check 1 failed ☐ check 2 failed ☐ inconclusive

---

## Why this is the one check that cannot be automated

StaffCore places decoy ore blocks — "canaries" — in solid rock and tells one player's client
they are there. Nothing is changed in the world. A player who goes straight to one has acted on
information only an x-ray client could have given them.

Everything on the server side is tested. The block is chosen to match the surrounding rock, the
packet carries that block at that position, and a resync sends the real block back. What no
automated test can check is the last step: **that a client receiving the packet draws the
block.** That needs a person looking at a screen.

This matters more than it sounds. If the decoy never reaches the client, or reaches it and is
not drawn, then:

- nobody ever walks into one, so the **false-positive rate is zero**, and
- nobody is ever caught by one, so the **true-positive rate is zero**.

The automated corpus measures the first and cannot see the second. A completely broken decoy
layer and a perfect one produce the same clean measurement. Until this check is run, the
measured zero in `docs/decisions.md` means "the retirement rule holds", not "decoys work".

**Time needed: about five minutes**, plus however long it takes to get an x-ray resource pack.

> Running the vanish checks in the same sitting? Start from
> [`README.md`](README.md) instead — it sequences both scripts so the setup happens once.

---

## What you need

1. A test server running StaffCore. **Not a production server** — step 2 changes settings.
2. A Minecraft client on the same version.
3. **An x-ray resource pack.** Any pack that makes stone and deepslate transparent will do;
   search "xray resource pack" for your Minecraft version. You do not need a hacked client or a
   cheat mod — a resource pack is enough and is the safer thing to install.
4. An account with admin permissions on the test server (you need `staff.reload`).

**Do not install an anti-xray mod on the test server.** StaffCore switches decoys off entirely
when one is present, and this check will correctly tell you they are off rather than testing
anything. Run `/staff status` — the anti-xray line should say "none installed".

---

## Step 1 — Make decoys easy to find

Stop the server. In `config/staffcore.json`, set:

```jsonc
"canaryBlocks": true,
"canaryDensity": 20,      // normally 6 — raised so several appear quickly
"canaryMaxY": 60,         // normally 16 — raised so you can dig a test chamber near the surface
"canaryRadius": 24        // normally 48 — tightened so they land near you
```

Start the server and join.

> These are test settings, not recommendations. Put them back afterwards — the real values are
> deliberately low so that meeting a decoy by chance stays negligible.

---

## Step 2 — Get some decoys placed

1. Dig down to about **y 40** in an area of ordinary stone. Avoid caves: decoys are only placed
   in rock with solid blocks on all six sides, so a cave wall has none.
2. Hollow out a small chamber, two or three blocks across, and **stand in it for ten seconds**.
   Decoys are placed on a five-second timer.
3. Run **`/staff canary`**.

**You should see** a count and a list of coordinates, each clickable to fill in a teleport.

- If it says **"Decoys are off"** and names an anti-xray mod — remove that mod and start again.
- If it says **"Nothing placed yet"** — you are probably in a cave or above `canaryMaxY`. Dig a
  little deeper into solid rock and wait again.

Write down one of the coordinates. Call it **D**.

---

## Step 3 — Confirm D is a lie

Before looking with the x-ray pack, confirm the server has ordinary rock at D. In the server
console (not in game), run:

```
staff canary
```

The list is printed with the note that the world has ordinary stone at every one of them. You
can also fly to within a few blocks of D in creative and confirm nothing looks unusual with the
normal resource pack on.

**This step is what makes the check meaningful.** Seeing a diamond ore through rock proves
nothing if there is really a diamond ore there — and the world is full of real ones, which look
identical through the pack. Check the coordinates against the `/staff canary` list rather than
judging by eye.

> **If a decoy in the list is not visible**, that is a bug, not a real-ore mix-up. One has been
> found and fixed already: a decoy used to be sent once and never again, so any chunk reload
> removed it from the client while the server went on listing it. Symptom was decoys working
> "hit or miss". Decoys are now re-sent every few seconds, so give it five seconds after moving
> before concluding one is missing.

---

## CHECK 1 — Is the decoy visible through solid rock?

1. Enable the x-ray resource pack.
2. Stand in your chamber and look toward **D**.

### Pass

You can see an ore block at D through the intervening stone — diamond ore in stone, deepslate
diamond ore in deepslate, or ancient debris in netherrack.

### Fail

You see nothing at D, or you see plain stone. The packet is not reaching the client or is not
being drawn. **The decoy feature does not work**, and the measured false-positive rate in
`decisions.md` is meaningless — record this and stop; check 2 will also be meaningless.

**Result of check 1:** ☐ pass ☐ fail
**Notes:** _______________________________________________

---

## CHECK 2 — Does the real block come back when the decoy is retired?

A decoy is retired the moment anybody breaks a block next to it. The client must be told, or a
diamond ore that does not exist stays on their screen forever — and they will eventually swing
at it.

1. Switch to survival or creative (not spectator — you need to break blocks).
2. Mine toward D and **break the block immediately next to it**, not D itself.
3. Look at D again with the x-ray pack still on.

### Pass

The ore at D is gone. You now see ordinary stone. Running `/staff canary` shows one fewer decoy.

### Fail

The ore is still shown at D after its neighbour was broken. The resync is not arriving. This is
the ghost-block failure: an honest player will eventually mine at that block, and although
StaffCore will not record a hit for it (the decoy was retired server-side), they have been shown
something that does not exist.

**Result of check 2:** ☐ pass ☐ fail
**Notes:** _______________________________________________

---

## Step 4 — Put the config back

Restore `canaryBlocks`, `canaryDensity`, `canaryMaxY` and `canaryRadius` to their previous
values and restart. Leaving the test values in place makes decoys common enough that an honest
miner meeting one stops being negligible.

---

## Step 5 — Record the outcome

1. Fill in the header block at the top of this file — name, date, versions, result.
2. If **both checks passed**: edit `README.md` and remove the "Decoy blocks are packet-verified,
   not client-verified" entry from Known limits, and update the note at the end of the
   "Canary false-positive rate" section in `docs/decisions.md` to say the visibility check was
   run, by whom, and when.
3. If **either check failed**: leave Known limits exactly as it is. Open an issue with the
   result and the notes above. Do not change the false-positive figures — they are still
   correct about the retirement rule, and still say nothing about whether decoys work.

---

## What a pass does and does not establish

A pass means a decoy reaches one client and is drawn there, and that retiring it removes it.
That is the claim the automated tests cannot make.

It does not establish that every x-ray client renders it the same way, that the density is well
chosen, or that the false-positive rate holds on a busy server. Those are separate questions and
this check does not answer them.
