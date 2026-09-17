# Changelog

## 1.3.0 — unreleased

In progress. Ships with `staffcore-discord-1.2.0.jar`; install both jars from the same release.

### Fixed

- **A closed case with nobody assigned read as still open**, and an open case somebody was assigned to as
  closed. The player's risk profile missed recently actioned cases because of it, and the cases menu
  and Discord's case view showed the wrong closing time.

### Added

- **A cases channel in Discord.** Every case gets a card in `#cases` (`casesChannelId`, made
  automatically): who, status, kind, severity, assignee, what is in it and the latest of its history,
  edited whenever the case changes, with the case's thread under it. It has Details, Evidence, Notes,
  History, Profile, Freeze, Unfreeze and Add Note buttons. Add Note writes into the case's history
  behind `staff.gui`, as `/staff case <id> note` does. Set `casesChannelId` to `""` to keep case threads
  in `#alerts`.
- **Contacting staff from Discord.** A public `#contact-staff` channel under Help
  (`contactStaffChannelId`) has a **Contact Staff** button. It asks who the player is in game and what they
  need, and opens a private thread only they and the staff who join can see. Staff are told in game,
  loudly if the player is frozen. The request appears in the private `#help-requests`
  (`helpRequestsChannelId`), which says whether the Discord account is that player's and whether they are
  online, frozen or banned. Its buttons are Join, Close, Profile, History, Freeze and Unfreeze. Join and
  Close need `report.view`. Each account can have one request open and ask three times an hour.
- **`#appeal` goes under a public Help category** (`appealIntakeCategory`, `Help` by default; `""` keeps it at
  the top). An existing category of that name is used; an `#appeal` the bot made at the top of the server
  is moved into it. Its message is now numbered steps. A settings file that still has
  `"appealIntakeChannelId": ""` needs `"create"` for the channel to be made.
- **Leaving while frozen is reported.** Staff are told in game and in the Discord alerts channel, and it
  is added to the player's open case, of any kind, with where they were frozen and who froze them. A
  case is opened when they have none. Kicks, bans and shutdowns are not reported; a dropped
  connection is. Coming back frozen is noted on the case too.

### Changed

- **A frozen player's screen goes dark**, with **YOU ARE FROZEN** in bold across it, *Join our Discord
  and contact staff* under it, and the `discordInvite` above the hotbar. It stays until they are
  released. The freeze warnings in chat are gone; the invite is sent once as a link to click, when
  `discordInvite` is set.

## 1.2.0 — 2026-09-17

Discord extras, a permissions fix for servers without LuckPerms, and the last of the Discord brief:
posts that wait for the bot and Gate 5. Ships with `staffcore-discord-1.1.0.jar`; install both jars
from the same release.

### Upgrading from 1.1.0

- **If `/staff` was missing for everybody**, operators included, and the log said
  `permissions.json is ignored`: that is the permissions fix below. Update, restart, and
  `config/staffcore/permissions.json` is written.
- **With LuckPerms**, operators now hold the StaffCore permissions LuckPerms leaves unset, as most
  Fabric mods do. To stop that, set `"operatorsBypass": false` in `config/staffcore/permissions.json`,
  or set those permissions to false in LuckPerms.
- The database gains three migrations (30–32): appeal codes are copied into their own table as they
  are, bans already over are marked so they are not announced as returns, and two tables for Discord
  evidence are added. Nothing is deleted.
- The permissions file goes to v2: an admin group still exactly as shipped gains `discord.punishpanel`.
- **Discord:** open the invite link from the setup guide again, which adds **Attach Files**. The bot
  makes `#punish` on its next start (Manage Channels and Manage Roles needed for that). Existing settings
  files keep `""` for the staff chat and appeal channels; set them to `"create"` to have them made.

### Added

- **Staff are told when a banned player comes back**: the first join after a ban ran out or was
  lifted is announced in game, in the Discord alerts channel and in the ban's case, once per ban.
  `notifyReturningPlayers` (on). Bans already over when you upgrade are not announced.
- **`/staffchat <message>`** in the Discord staff chat channel.
- **A public `#appeal` channel** in Discord with an **Appeal** button that opens the appeal form (code
  and reason); `/appeal` works there too. `appealIntakeChannelId` accepts `create` and is `create` in
  new settings files.
- **Replays as maps in Discord**: `/staff replay-map <player>` draws where a player went and what they broke
  and placed as a picture, with the window and counts in the message; `/staff evidence <case> item:<n>`
  draws replay evidence the same way. Needs `staff.replay` and `positionTracking`; the picture is never
  kept, so it cannot outlive the position history's retention.
- **A punishment panel in Discord, for admins**: a private `#punish` channel with a Punish button, and
  `/staff punish <player> <offence>`, both punishing by the server's offence ladders with a confirmation
  that issues nothing if the player's record changed. Needs the new `discord.punishpanel` permission,
  which the starter admin group gets (permissions file v2) and moderators do not.
- **An evidence locker in Discord**: `/staff evidence-add` files a file or note on a case, and a message's
  *Add to case evidence* menu files the message with its files. Files are downloaded and kept beside the
  world, named by SHA-256, up to `evidenceMaxMegabytes` (25); the filing is posted in the case's thread,
  `/staff evidence <case> item:<n>` shows it again, and it opens in game as *From Discord*.
- **Case ids complete as you type** in Discord's `/staff case` and `/staff evidence`, for linked staff
  allowed to look at cases.

### Fixed

- **StaffCore locked everybody out, operators included, when another mod shipped the permissions
  API.** Many mods ship fabric-permissions-api inside their jar. StaffCore took that to mean a
  permissions mod was installed. It ignored `permissions.json` and never wrote it, and it read "no
  answer" as "no", so `/staff` was missing for everyone. Now a node a permissions mod such as
  LuckPerms sets still wins, and anything it leaves unset goes to `permissions.json`, then op level.
  On a LuckPerms server, operators now hold the StaffCore nodes LuckPerms leaves unset unless
  `operatorsBypass` is off.
- **A new server's database logged seventeen "missing column" warnings on its first start.** It was
  repaired at once and worked, but the log said it was damaged. New databases are now created with
  every column and index the upgrades add, including the index appeal codes are looked up by.
- **An operator refused a node could still use it in two places.** The rate-limit exemption and the
  IP-ban check counted being an operator as holding every node, even with `operatorsBypass` off or a
  permissions mod saying no. They now go by the permissions the operator actually holds, like
  everything else.
- **Offline accounts are now read through the permissions API too.** If LuckPerms has not loaded an
  account yet, a Discord request or an offline staff punishment is refused with "try again in a
  moment" instead of "join the server".

### Changed

- **Discord posts wait while the bot cannot post**, instead of being dropped. A post made while the bot
  is disconnected, still connecting or getting Discord server errors is queued and made in order once
  it can post. The queue holds `outboundQueueSize` posts (new, 500, 50–10000); past that the oldest are
  dropped, logged once a minute at most and counted in `/staff status`. Posts still waiting when the
  server stops are not kept. The startup log now has a line on where the bot stands.
- **The Discord staff chat channel is made by default** (`staffChatChannelId` is `create` in new
  settings files). Without Message Content Intent the bot now connects anyway, and staff use
  `/staffchat` in that channel; before, the bot could not log in at all.
- **An appeal code stops working once its appeal is accepted or rejected.** Rejecting asks how many
  days the player must wait (0–365, `appealCooldownDays` by default), in Discord and in game; the
  punishment gets a new code, shown on the ban screen with the day it starts working and in chat to a
  muted player. Closing an appeal without a verdict leaves the code working. Existing codes are kept
  as they are.

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
