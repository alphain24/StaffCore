# Manual check: the three vanish claims no automated test can make

**Status:** NOT YET RUN
**Run by:** _______________
**Date:** _______________
**Server version / mod version:** _______________
**Result:** ☐ all three pass ☐ check 1 failed ☐ check 2 failed ☐ check 3 failed ☐ inconclusive

---

## Why these three and not the others

Eight vanish behaviours had gametests. Four of them were real. The other four were probed by
switching vanish off entirely and re-running — **they still passed**, which means they were
testing nothing. They have been deleted rather than left occupying the slot where a real check
would go.

All four failed for the same underlying reason: a gametest mock player is not an ordinary
player. It is **permanently in creative** and it is **not in the server's player list**. Those
two facts short-circuit exactly the code paths the tests were aiming at:

| Deleted test | Why it could never fail |
|---|---|
| Mobs do not acquire a vanished player | `Mob.asValidTarget` refuses a creative player before it checks anything else |
| `getNearestPlayer` cannot find a vanished player | A mock player is not in the player list, so it is never returned regardless |
| A vanished player does not press plates | The plate never detected the mock player in the first place |
| Un-vanishing recomputes abilities | With nothing concealed, nothing is restored, so the values trivially matched |

The fourth claim is now pinned properly in code — `RevealResolvesAbilitiesTest` checks that
vanish never assigns an ability directly, and `AbilityStateTest` covers the resolver
behaviourally including the survival case a mock player can never reach.

The other three need a real player in a real player list, in survival. That is this document.

**Time needed: about ten minutes.** You need two accounts.

---

## What you need

1. A test server running StaffCore.
2. **Two accounts** that can be logged in at once — a second account, or a friend. One is
   `STAFF` (needs `staff.vanish`), the other is `WATCHER` and needs nothing.
3. Both in **survival**. This matters: creative short-circuits two of the three behaviours
   below, which is the whole reason the automated versions were worthless.

---

## Step 1 — Set up

1. Log both accounts in.
2. Put both in survival: `/gamemode survival STAFF` and `/gamemode survival WATCHER`.
3. Stand them together somewhere flat and open, away from other players.
4. Confirm `STAFF` is **not** vanished: `/staff status` or just check `WATCHER` can see them.

---

## CHECK 1 — Do mobs stop targeting a vanished player?

This is the behaviour with the least real verification in the whole mod, and the one whose
absence is least visible: a staff member who is still targetable is not obviously broken, they
are just occasionally attacked while invisible.

1. As `STAFF`, in **survival**, find or spawn a hostile mob. A zombie in a dark spot, or
   `/summon zombie ~ ~ ~` while in survival yourself.
2. Stand within a few blocks and **confirm it targets you** — it turns toward you and closes.
   *This is the control. If the zombie never targets you un-vanished, the check below proves
   nothing and you should move somewhere it does.*
3. Run `/staff vanish`.
4. Watch the zombie for about ten seconds.

### Pass

The zombie loses interest and stops pathing toward you. It does not swing at you.

### Fail

The zombie keeps tracking or attacking. `VanishMobTargetMixin` is not applying, or is applying
and not cancelling. Check `/staff status` for a missing hook first — if it reports 19/19 present
and this still fails, the mixin attaches to something that does not run.

**Result of check 1:** ☐ pass ☐ fail  ☐ control failed (zombie never targeted you un-vanished)
**Notes:** _______________________________________________

---

## CHECK 2 — Does a vanished player stop suppressing mob spawning?

`getNearestPlayer` is the choke point almost everything proximity-based runs through: natural
spawning, phantoms, mob AI deciding whether anyone is close. One leak here quietly switches off
the mob farm a staff member happens to be standing in.

1. As `STAFF`, go to a dark area where hostile mobs spawn naturally, or a mob farm if the
   server has one.
2. Un-vanished, stand there for a minute. **Confirm spawning behaves as it normally does** with
   you present — this is the control.
3. `/staff vanish`, and stay in exactly the same place.
4. Wait another minute.

### Pass

Spawning behaves as though nobody is there. In a farm, output resumes or increases; in a dark
area, mobs appear around you as they would with the area empty.

### Fail

Nothing changes — the world still treats you as present. Note that this check is the least
crisp of the three, because spawning is noisy. If the result is ambiguous, say so rather than
recording a pass.

**Result of check 2:** ☐ pass ☐ fail ☐ ambiguous
**Notes:** _______________________________________________

---

## CHECK 3 — Does a vanished player stop pressing pressure plates?

The loudest tell vanish has. A plate clicking with nobody visible on it announces exactly where
somebody is standing.

1. Place a **stone pressure plate** on the ground. Have `WATCHER` stand where they can see it.
2. As `STAFF`, un-vanished, **step on it**. Confirm it depresses and clicks — the control.
3. Step off. `/staff vanish`.
4. Step on it again.

### Pass

The plate does not depress. No sound, no redstone signal, and `WATCHER` sees nothing.

### Fail

The plate fires. Anything wired to it also fires, and the position of an invisible staff member
is now public.

**Result of check 3:** ☐ pass ☐ fail  ☐ control failed (plate never fired un-vanished)
**Notes:** _______________________________________________

---

## Step 2 — Tidy up

`/staff vanish` to un-hide, and put both accounts back to whatever gamemode they were in.

---

## Step 3 — Record the outcome

1. Fill in the header block at the top of this file.
2. If **all three passed**: update the "The port is compile-verified, not runtime-verified"
   entry in `README.md` Known limits to say the vanish behaviours were confirmed by hand, by
   whom, and when.
3. If **any failed**: open an issue with the check number and the notes. Leave Known limits
   alone — it is currently correct that these are unverified, and it should stay correct.
4. If a **control failed** — the zombie never targeted you, the plate never fired — the check
   is inconclusive, not a pass. Record it as inconclusive and try again somewhere the control
   works. A control that does not fire is the same failure this whole document exists because
   of.

---

## What a pass does and does not establish

A pass means these three behaviours work on this version, on this server, for a player in
survival who is in the player list. That is the population the deleted gametests could not
reach.

It does not establish that they keep working after a Minecraft update. These are the checks
worth re-running after one, and this file is the reason that is a ten-minute job rather than a
reconstruction.
