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

## Installing

1. Install Fabric Loader 0.19.3+ for Minecraft 26.2.
2. Drop [Fabric API](https://modrinth.com/mod/fabric-api) into `mods/`.
3. Drop `staffcore-<version>.jar` into `mods/`.
4. Start the server, then run `/staff`.

SQLite is bundled inside the jar, so there is nothing else to install and no database to set
up.

## What you get

Nineteen modules behind one `/staff` command: punishments with an appeal queue, a grief log
with rollback and a preview that draws the blocks it would change, inventory snapshots,
vanish that actually hides you, staff mode with an inventory stash, an x-ray heuristic, alt
detection, and a bridge for findings from an anti-cheat you already run.

- **[Handbook](docs/handbook.html)** — the full guide
- **[Decisions](docs/decisions.md)** — why things are the way they are, with the measurements

## Verified before this was tagged

Every release build runs the full suite, boots a real server and asserts every hook applied,
and runs the game tests against real players, mobs and blocks. A tag cannot skip any of it.

## Known limits

Read [Known limits](README.md#known-limits) before deploying. The short version: alt detection
is a lead and never a verdict, the x-ray check is a heuristic tuned against generated mining
patterns rather than real player data, and nothing here bans anybody automatically.
