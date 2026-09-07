# Changelog

## 1.0.0 — unreleased

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
