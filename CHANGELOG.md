# Changelog

## 1.2.0 — unreleased

In progress. Ships with `staffcore-discord-1.1.0.jar`.

## 1.1.0 — 2026-09-16

Cases, staff accountability, x-ray rebuilt on ground truth, session replay, and a Discord bot.
Ships with the first release of the Discord companion, `staffcore-discord-1.0.0.jar`; install both
jars from the same release or neither.

### Upgrading from 1.0.0

- **Config files move into `config/staffcore/`** on the first start: `staffcore.json` keeps its
  name, `staffcore-permissions.json` becomes `permissions.json`. Nothing is lost and the log says
  what moved. If a file is in both places, the folder's copy is used and the old one is left for
  you to delete.
- The database upgrades itself in place, to schema version 29. Every migration only adds;
  nothing is deleted.
- New settings are written into your config with their defaults, and the log names each one.

### Added

- **Cases.** Every detector emits a *signal* instead of its own alert, and signals group into
  cases about one player, with evidence you can open, an assignee, and the punishment each ended
  in. A case board with open and solved tabs. `/staff cases`, `/staff case <id>`. Case ids are
  eight characters, readable aloud. Punishments record the case they came from.
- **Staff accountability.** An audit log of what each staff member did, rate limits on
  punishments and other destructive actions, two-person approval for IP bans, and a rank guard:
  staff cannot punish anybody whose permissions are not a strict subset of theirs.
- **Notes are never deleted**, only retracted with a reason, and the warning ladder suggests the
  next step rather than taking it.
- **IP bans**, a banned-players screen, and a screen of who punished whom.
- **X-ray, rebuilt.** Decoy ore veins in sealed rock, sent to each player and counted when
  uncovered; a live score of sealed diamond veins against what honest mining uncovers, as a
  p-value rather than a score; ore that was already visible in a cave no longer counts; and an
  alert from ore needs the way the player's tunnels turned to agree. Cleared cases are kept as
  labelled data with a reason. Works alongside AntiXray or Meow Anti-Xray, and says so at startup.
- **Session replay.** `/staff replay` shows where a player went and the damage they did, even
  after it was rolled back, through a camera the client can smooth. Position history is kept for
  a configurable time and counted as personal data.
- **Rollback.** Shift-click one row of the grief log to roll back just that; container contents
  restored when a chest was broken, stolen items taken back, and explosion drops billed as they
  actually fell. Mass grief counts TNT, crystals, beds and
  respawn anchors. `/staff grief test` proves the mass-grief alert arrives.
- **Screens.** The staff panel is seven sections instead of twenty-five buttons, and the back
  arrow goes one screen back along the path you took. A player's file is a fixed grid. New
  screens for the risk profile, teleport history, owed items, replay, decoys and the x-ray report.
- **The ban screen** shows an appeal code, and tells players to `/appeal` in Discord when the bot
  takes appeals.
- **The Discord companion** (`staffcore-discord`, a second jar): staff link their Discord account
  to their Minecraft account; punishments, reports, alerts, appeals and the staff log are posted to
  private channels the bot makes itself, each report, appeal and case with a thread; buttons on
  reports and appeals; `/appeal` for banned players; `/staff` slash commands for looking players up
  and punishing them; and staff chat bridged both ways. What anybody can do from Discord is the
  smaller of what their Discord roles allow and what their Minecraft account holds in game. IP
  bans, rollbacks and inventory edits never run from Discord. The token lives in its own file and
  is never logged. [Installation guide](docs/discord-setup.md).
- **The panel's Discord section** shows whether the bot is running, what is wrong, each channel,
  and who is linked, and lets every staff member link their account.
- **A published API** (`io.github.alphain24.staffcore.api`, version 4) for companion mods: events,
  status lines and the Discord calls, every one through the same checks as the game.
- `discordActionsPerMinute` (20): every change made from Discord counts against it, on top of the
  in-game limits.

### Fixed

- **Rate-limit refusals never said anything useful**: they printed `%d %s(s) a minute` with
  nothing filled in.
- **Accepting an appeal lifted every ban and mute the player had**, not just the one appealed.
- **New settings never appeared in an existing config** unless the file was deleted.
- **The CI boot check failed on every push**, because a startup line contained an em dash.
  Console lines are ASCII now, and a test checks every logged string.
- A grief log row could lose its "nothing left to roll back" warning in the same click that drew
  it; this also made one game test fail now and then.
- A broken chest that was rolled back came back empty.
- Contraband alerts reached nobody, because "no case" was read as "say nothing"; three alerts
  spoke twice or not at all.
- The replay camera sat at the player's feet and looked through the floor, and the replay
  disconnected the viewer a second after it opened.
- Decoys were assumed to still be on the client after a chunk reload; they are sent again.
- Database transactions on the server thread and the log writer could see each other's work.
- Illegal enchantments, grief replays, appeals and container rollback: four smaller bugs.

### Changed

- **Config files live in `config/staffcore/`** (see Upgrading).
- The panel's Discord section is open to every staff member with `staff.gui`; channels, linked
  accounts and the webhook stay behind `staff.reload`.
- The starter helper group can write notes (`staff.notes`).
- Rollback is one button and one confirm, and asks whether to put back only what was broken.

### Known limits

- **A Discord post made while the bot is disconnected is dropped**, and counted in
  `/staff status`, rather than queued for when it reconnects.
- Decoy blocks and the replay overlay are verified from the packets sent, not by somebody watching
  a client; the scripts to check are in [docs/manual-checks](docs/manual-checks/).
- See [Known limits](README.md#known-limits) for the rest.

## 1.0.0

First release. Everything below is from the correctness and trust pass that preceded it.

### Fixed

- **X-ray detector alerted on honest mining.** Measured against 200 generated honest sessions
  and 40 guided ones: the worst honest pattern scored 66 against an alert line of 55. Retuned
  to floor 200 / alert 65 / notice 55. ([decisions.md](docs/decisions.md#x-ray-thresholds))
- **Raising the sample floor made it blind, not careful.** Guided mining breaks *less* cover,
  so cheats are the small sessions; at a floor of 400 every cheat was missed.
- **Vanished players were still pushable.** The hook was on `Entity.isPushable`, which
  `LivingEntity` overrides — it applied cleanly and never ran. Found by a gametest.
- **Permissions fell through to operator level** for an unknown group, an empty group, and any
  player not in the file when `operatorsBypass` was off. A typo in a group name promoted
  somebody instead of denying them.
- Documentation disagreed with the code in nine places, including `/staffcore reload` (which
  does not exist), duplicate command rows with the wrong permission nodes, and a handbook JSON
  block that could not be pasted.

### Added

- **`InventoryGateway`** — one door for every write into a player's inventory. Snapshot, audit
  row and transaction on all four paths; refuses when the hooks it depends on are broken.
- **`/staff owed undo <id>`** — gives back what a rollback debit took, once.
- **`/staff preview`** now shows who would be charged what, including offline players.
- **`/staff perms explain <player>`** — the fully resolved node set and what granted each entry.
- **Three mixin tiers.** Hooks the grief log and rollback depend on now hard-disable their
  feature instead of degrading silently. Two of them were not being checked at all.
- **Connection data retention** (`connectionRetentionDays`, 90) and **hashed addresses**
  (`hashConnectionAddresses`, on). Alt matching is unaffected; the plaintext goes.
- **24 gametests** — vanish, maintenance, the vault, invsee and rollback debits, run against a
  real server in CI.
- **Crash-consistency tests** — a forked JVM is killed mid-write and the database checked.
- **`rootAliases`** (off) — `/vanish`, `/freeze` and friends at the root, only where free.

### Changed

- **Staff mode defaults to survival**, not creative. Creative is why four containment layers
  exist; survival removes the item source instead. Creative is still available.
- **`rollbackChasesBankedLoot` and `rollbackReclaimsFromStaff` now default off.** Both correct,
  both surprising. Existing servers keep current behaviour via config migration v3.
- **`/staff export` redacts addresses** unless asked, behind a confirm.
- Package moved to `io.github.alphain24.staffcore`; Loom pinned to a release.
- README cut from 827 lines to 423; the reasoning moved to
  [docs/decisions.md](docs/decisions.md).

### Removed

- **`autoBanEvaders`.** An automatic ban on a shared address is wrong some of the time, the
  people it is wrong about are strangers, and it would be wrong while nobody was watching.
- `ItemDebit` — replaced by `InventoryGateway`, so there is no second entrance.

### Known limits

- X-ray thresholds are measured against generated mining patterns, not real player data.
  `XrayReplay` scores a real database when one is supplied.
- Alt detection is a lead, never a verdict. Nothing bans anybody automatically.
