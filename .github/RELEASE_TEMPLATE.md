## Before you download

| | |
|---|---|
| **Minecraft** | 26.2 |
| **Fabric Loader** | 0.19.3 or newer |
| **Java** | **25** |

**Java 25 is the one that catches people out.** Most servers run 17 or 21 and this will not
start on either. Minecraft 26.2 itself requires 25, so the mod cannot ask for less — check
`java -version` before you download.

Server-side only: your players connect with a vanilla client and install nothing.

## Which jar

| Jar | You need it |
|---|---|
| `staffcore-<version>.jar` | Always. The mod. |
| `staffcore-discord-<version>.jar` | Only for the Discord bot. Install it with the StaffCore jar **from this same release**; it will not start beside any other. |

## Installing

1. Install Fabric Loader 0.19.3+ for Minecraft 26.2.
2. Drop [Fabric API](https://modrinth.com/mod/fabric-api) into `mods/`.
3. Drop `staffcore-<version>.jar` into `mods/`, and the Discord jar too if you want the bot.
4. Start the server, then run `/staff`.

Settings are written to `config/staffcore/` on the first start. Files an earlier version left in
`config/` are moved there automatically. SQLite is bundled inside the jar, so there is nothing
else to install and no database to set up.

For the Discord bot, follow the **[installation guide](https://github.com/alphain24/StaffCore/blob/main/docs/discord-setup.md)**:
it covers making the bot, the token, the private channels it creates and the role permissions.
`/staff` → **Discord** shows whether it is running.

## What you get

Twenty-one modules behind one `/staff` command: punishments with an appeal queue, cases that
collect what the detectors find, a staff audit log with rate limits and two-person approval, a
grief log with rollback and a preview that draws the blocks it would change, inventory
snapshots, vanish that actually hides you, staff mode with an inventory stash, x-ray detection
with decoy veins, session replay, alt detection, a bridge for findings from an anti-cheat you
already run, and a Discord bot that staff can act from with the permissions they have in game.

- **[What changed](https://github.com/alphain24/StaffCore/blob/main/CHANGELOG.md)**
- **[Handbook](https://alphain24.github.io/StaffCore/handbook.html)** — the full guide
- **[Decisions](https://github.com/alphain24/StaffCore/blob/main/docs/decisions.md)** — why things are the way they are, with the measurements

## Verified before this was tagged

Every release build runs the full suite, boots a real server and asserts every hook applied,
and runs the game tests against real players, mobs and blocks. A tag cannot skip any of it.

## Known limits

Read [Known limits](https://github.com/alphain24/StaffCore#known-limits) before deploying. The
short version: alt detection is a lead and never a verdict, the x-ray check is a heuristic
tuned against generated mining patterns rather than real player data, nothing here bans anybody
automatically, and Discord posts still waiting for the bot when the server stops are lost.
