# Installing the StaffCore Discord bot

A step-by-step guide to getting `staffcore-discord` running: where each file goes, how to make the
bot in Discord, how to fill in its settings, and what to do when `/staff status` says something is
wrong. For what every setting does in detail, see the
[Discord companion section of the README](../README.md#discord-companion--staffcore-discord).

**Contents:** [What you need](#what-you-need) ·
[1. Put the jars in mods](#1-put-both-jars-in-mods) ·
[2. Start the server once](#2-start-the-server-once) ·
[3. Make the bot](#3-make-the-bot-in-discord) ·
[4. Invite it](#4-invite-the-bot-to-your-server) ·
[5. Copy the ids](#5-copy-the-two-kinds-of-id-you-need) ·
[6. Fill in the settings](#6-fill-in-configstaffcore-discordjson) ·
[7. Restart and check](#7-restart-and-check-it-connected) ·
[8. Link staff accounts](#8-link-your-staff-accounts) ·
[9. Appeals](#9-set-up-appeals) ·
[Updating](#updating) · [Troubleshooting](#troubleshooting)

---

## What you need

- A Fabric server for **Minecraft 26.2**, with **Fabric Loader 0.19.3** or newer, **Java 25**, and
  **Fabric API**.
- **Both jars from the same build**: `staffcore-1.1.0.jar` and `staffcore-discord-1.0.0.jar`. The
  bot will not start with a StaffCore from a different build.
- A Discord server where you can manage the server (to invite a bot and give it permissions).
- A host that lets the Minecraft server connect out to Discord. Nearly all do; nothing has to be
  opened for incoming connections.

The bot runs inside the Minecraft server. There is nothing else to host and no second program.

---

## 1. Put both jars in `mods/`

Everything lives in your server folder — the one with `server.properties` in it. On a hosting panel
that is the top level of the File Manager.

```
your-server/
├── server.properties
├── mods/
│   ├── fabric-api-….jar
│   ├── staffcore-1.1.0.jar
│   └── staffcore-discord-1.0.0.jar          ← put it here
├── config/
│   ├── staffcore.json
│   ├── staffcore-discord.json               ← created on first start
│   └── staffcore-discord.token              ← created empty on first start; your token goes in here
└── world/
    └── staffcore-discord/
        └── threads.json                     ← the bot's own memory; leave it alone
```

`config/` is the folder beside `mods/`, not a folder inside it.

---

## 2. Start the server once

Start the server and let it finish starting. The two files in `config/` are written when the server
has **fully started**, not when the jar is loaded, so wait for the usual `Done` line.

In `logs/latest.log` you should see:

```
[StaffCore Discord] loaded against StaffCore API v3
[StaffCore Discord] Created config/staffcore-discord.token, empty. Paste the bot token into it, …
[StaffCore Discord] Installed and switched off. Set enabled, guildId and the token file to start the bot.
```

The bot is off at this point, and that is expected: nothing happens until you switch it on in step 6.

> **Can't find `staffcore-discord.token`?** Builds before the one that added this step did not create
> it. Either update to the current jar and start the server again, or create it yourself: a plain
> text file named exactly `staffcore-discord.token` in `config/`. Watch out for Windows hiding the
> extension and saving it as `staffcore-discord.token.txt` — turn on *File name extensions* in
> Explorer's *View* menu to check.

You can stop the server now, or leave it running; settings are only read when it starts.

---

## 3. Make the bot in Discord

1. Open the [Discord Developer Portal](https://discord.com/developers/applications) and choose
   **New Application**. Give it the name staff should see, for example *StaffCore*.
2. On the **Bot** page, choose **Reset Token**, confirm, and **Copy** the token.
3. Open `config/staffcore-discord.token` and paste the token in. **Only the token** — no quotes, no
   `token=`, nothing else. Save it.
4. Still on the **Bot** page, under **Privileged Gateway Intents**:
   - Leave **Presence Intent** and **Server Members Intent** off. The bot does not use them.
   - Turn **Message Content Intent** on **only** if you are going to bridge staff chat
     (`staffChatChannelId`). Without it, a bot with the bridge set up cannot log in.
5. Optional but sensible: turn **Public Bot** off, so nobody but you can add it to a server.
6. On **General Information**, copy the **Application ID**. You need it for the invite link.

**The token is a password for the bot.** Anybody who has it can post as the bot. Never paste it in
Discord, a support channel, a screenshot or `staffcore-discord.json`. If it ever leaks, press
**Reset Token** again — the old one stops working at once — and put the new one in the file.

On Linux the file is created readable only by the account the server runs as. If you created it
yourself, run:

```bash
chmod 600 config/staffcore-discord.token
```

On a Windows machine shared with other people, open the file's *Properties → Security* and remove
*Users* and *Everyone*. `/staff status` warns you if the file can be read by every user on the
machine.

---

## 4. Invite the bot to your server

Open this link in a browser, with your Application ID in place of `APPLICATION_ID`, and pick your
server:

```
https://discord.com/oauth2/authorize?client_id=APPLICATION_ID&scope=bot+applications.commands&permissions=309506165776
```

That asks for exactly these permissions and nothing more:

| Permission | Why |
|---|---|
| View Channels | To find its channels |
| Send Messages | To post |
| Embed Links | Posts are embeds |
| Read Message History | To find a thread again once Discord has archived it |
| Create Public Threads | Every report, appeal and case gets a thread |
| Send Messages in Threads | To say what happened in each thread |
| Manage Channels | To make its private channels |
| Manage Roles | To make those channels private — hidden from everyone except staff |

It needs no administrator permission, and should not be given it.

**Manage Channels and Manage Roles are only for making the channels.** Once the bot has made them
(step 7), you can take those two away in *Server Settings → Roles → the bot's role*. The bot keeps
its access to its own channels, because that access is set on each channel, not on its role. Give
them back if you ever set another channel to `"create"`.

---

## 5. Copy the two kinds of id you need

Discord ids are long numbers. To copy them, turn on **Developer Mode** first:
*User Settings → Advanced → Developer Mode*.

| You need | How to copy it |
|---|---|
| Your server's id (`guildId`) | Right-click the server icon → **Copy Server ID** |
| Each staff role's id (`roleNodes`) | *Server Settings → Roles*, right-click the role → **Copy Role ID** |

You do not need channel ids: **the bot makes its channels for you** (step 6). If you would rather
use channels you have already made, right-click each one → **Copy Channel ID** and use those ids
instead of `"create"`.

---

## 6. Fill in `config/staffcore-discord.json`

Open the file the server wrote and fill it in. A complete example — your ids will differ:

```json
{
  "enabled": true,
  "guildId": "111111111111111111",
  "roleNodes": {
    "222222222222222222": ["staff.history", "report.view", "staff.gui", "staff.notes", "staff.freeze", "staff.chat"],
    "333333333333333333": ["staff.history", "report.view", "staff.gui", "staff.notes", "staff.freeze", "staff.chat", "staff.appeals"]
  },
  "requestTimeoutSeconds": 10,
  "punishmentsChannelId": "create",
  "reportsChannelId": "create",
  "alertsChannelId": "create",
  "appealsChannelId": "create",
  "appealIntakeChannelId": "",
  "staffLogChannelId": "create",
  "staffChatChannelId": "",
  "discordAlertSeverity": 70,
  "serverName": "",
  "playerHeadUrl": "https://mc-heads.net/avatar/{uuid}/64"
}
```

- **`enabled`** — `true` to start the bot.
- **`guildId`** — your server id.
- **`roleNodes`** — your staff roles, by role id, and which StaffCore permissions each may use from
  Discord. The example has a *Helper* role and a *Moderator* role. These roles are also the ones
  that can see the channels the bot makes.
- **Channels** — each is one of three things:

  | Value | What happens |
  |---|---|
  | `"create"` | The bot makes the channel when it connects, and writes its id back into this file in place of `"create"` |
  | a channel id | The bot posts in that existing channel |
  | `""` | That kind of post is not made at all |

### The channels the bot makes

When the bot connects, it makes a **StaffCore** category and puts every channel set to `"create"`
inside it:

| Channel | Setting | What goes there |
|---|---|---|
| `#punishments` | `punishmentsChannelId` | Every ban, mute, warning and kick |
| `#reports` | `reportsChannelId` | Player reports, with buttons |
| `#alerts` | `alertsChannelId` | Detector alerts, and a thread per case |
| `#appeals` | `appealsChannelId` | Appeals, with buttons |
| `#staff-log` | `staffLogChannelId` | Every staff command — busy |
| `#staff-chat` | `staffChatChannelId` | Staff chat, both ways |

**All of them are private.** Nobody can see the category or its channels except:

- the bot,
- the staff roles listed in `roleNodes` — they can read everything, reply in threads, and type in
  `#staff-chat`,
- anybody with Administrator, as always in Discord.

Threads are as private as their channel, so every report, appeal and case thread is private too.

After that first connection the settings file holds the new channel ids instead of `"create"`, so
restarting never makes them again. You can rename or move the channels, and change their
permissions — the bot goes by id and never touches permissions again. To let another role see them,
add it to the category's permissions in Discord.

A new settings file sets the first five to `"create"`. Two are left empty on purpose:

- **`staffChatChannelId`** — bridging staff chat needs **Message Content Intent** turned on
  (step 3). Turn it on first, then set this to `"create"`.
- **`appealIntakeChannelId`** — this one is for players, so it cannot be private and the bot does
  not make it. Leave it empty and players can use `/appeal` in any channel; see
  [step 9](#9-set-up-appeals).

> **Your file was written by an earlier build?** Its channel settings will be `""`. Change the ones
> you want to `"create"`.

### How the role mapping works

A role **never gives anybody a permission they do not have in game**. What someone can do from
Discord is the smaller of what their role lists and what their linked Minecraft account holds in
game. So a role that lists `staff.appeals` lets a moderator decide appeals from Discord only if
their Minecraft account can already do that in game — handing that role to the wrong person grants
them nothing.

Write each permission out in full; wildcards like `staff.*` are refused. The permissions the bot uses
today:

| Permission | Lets them, from Discord |
|---|---|
| `report.view` | Claim and resolve reports |
| `staff.gui` | Escalate a report to a case; see a player's profile and a case's evidence |
| `staff.history` | See a player's punishment history, and a single punishment |
| `staff.notes` | Add a note to a player |
| `staff.freeze` | Freeze a player who is online |
| `staff.chat` | Talk in the bridged staff chat channel |
| `staff.appeals` | Accept, reject and close appeals, and ask the player a question |

Keep the file valid JSON: quotes around every id, commas between entries, none after the last. If
it cannot be read, the bot stays off and the log says so — the file is not replaced.

---

## 7. Restart and check it connected

Restart the Minecraft server. In `logs/latest.log` you should see the channels being made:

```
[StaffCore Discord] connected as StaffCore in Your Server
[StaffCore Discord] Made private channel #punishments in StaffCore.
[StaffCore Discord] Made private channel #reports in StaffCore.
…
```

Then, in game, run:

```
/staff status
```

You want a line like:

```
Discord: connected as StaffCore in Your Server
```

with no other `Discord:` lines under it. In Discord you should see the **StaffCore** category with
its channels, and typing `/` shows the bot's commands: `/link`, `/unlink`, `/whoami`, and `/appeal`.
If the commands do not show up, press `Ctrl+R` in Discord to reload it.

Open `config/staffcore-discord.json` again: the `"create"` values are now channel ids.

If the status says anything else, see [Troubleshooting](#troubleshooting).

---

## 8. Link your staff accounts

Every staff member links their own Discord account once. Until they do, they can read the channels
their role lets them see, and nothing else.

1. In game: `/staff discord link`. You get a code like `ABCD-EFGH`. It lasts 10 minutes and works
   once.
2. In your Discord server: `/link ABCD-EFGH`.
3. Check with `/whoami` — it names the Minecraft account and lists what you can use from Discord.

`/staff discord` shows your link, and `/staff discord unlink` ends it. An admin with `staff.perms`
can end somebody else's link with `/staff discord unlink <player>`, which is the quick way to cut
off a Discord account that has been taken over.

---

## 9. Set up appeals

With `appealsChannelId` set, banned and muted players can appeal from Discord.

1. In `config/staffcore.json`, set `discordInvite` to an invite link for your Discord, so the ban
   screen tells players where to go. With the bot taking appeals, the ban screen tells them to type
   `/appeal` with the appeal code shown underneath.
2. `#appeals` is private to staff. Appeals and staff discussion go there, and players never see it.
3. Players type `/appeal` in **any channel they can see** — `#general` is fine. The form and the
   bot's answers are visible only to them. If you want appeals made in one particular channel, make
   a public channel yourself (for example `#appeal-here`) and put its id in `appealIntakeChannelId`.
4. Tell players to allow **direct messages from server members** (*Server name → Privacy Settings*).
   Questions from staff and the verdict reach them by direct message, and they answer by replying
   to the bot. If their direct messages are off, the appeal's thread tells staff they did not hear.

---

## Updating

Replace **both** jars together, from the same build, then restart. Your settings, token, channels
and links are kept. If only one is replaced, the log says
`This companion was built for StaffCore API version …` and the bot does not start.

To switch the bot off without removing it, set `"enabled": false` and restart. Links and channels
are kept for when you switch it back on.

---

## Troubleshooting

Everything the bot has to say is in `/staff status` (lines starting `Discord:`) and in
`logs/latest.log` (lines starting `[StaffCore Discord]`).

| `/staff status` says | What to do |
|---|---|
| `waiting for the server to start` | The server has not finished starting. Wait for it. |
| `off (enabled is false in config/staffcore-discord.json)` | Set `"enabled": true` and restart. |
| `off: There is no token file…` | Create `config/staffcore-discord.token` — see [step 2](#2-start-the-server-once). |
| `off: The token file is empty…` | Paste the token into it — see [step 3](#3-make-the-bot-in-discord). |
| `off: The token file does not hold a bot token…` | You pasted something else. The token is on the **Bot** page, not the Client Secret or Application ID on other pages. |
| `could not log in: Discord refused the token` or `could not start (InvalidTokenException)` | The token was reset or copied wrong. Reset it again and paste the new one. |
| `stopped: staffChatChannelId needs Message Content Intent…` | Turn on **Message Content Intent** on the Bot page, or empty `staffChatChannelId`. Restart. |
| `connected as …, but not in the guild named by guildId` | The bot is not in that server, or `guildId` is wrong. Invite it (step 4) or copy the id again. |
| `channels set to "create" were not made: the bot needs Manage Channels and Manage Roles…` | Invite the bot again with the link in [step 4](#4-invite-the-bot-to-your-server) — it updates the permissions — and restart. |
| `the … channel has not been made yet, so nothing is posted there` | Look at the line above it for why. Channels still set to `"create"` are tried again at every start. |
| `making the private channels stopped part way (…)` | The bracket names what Discord refused, such as `MISSING_PERMISSIONS` or `InsufficientPermissionException`: the bot is missing one of the permissions from [step 4](#4-invite-the-bot-to-your-server). Give it back and restart; channels already made are used, not made twice. |
| `role … in roleNodes is not in …` | That role id is wrong or the role was deleted. It was left out of the channels' permissions. |
| `… channel … is not a text channel in …` | That id is wrong, is a category, voice or forum channel, or is a private channel the bot cannot see. |
| `the bot cannot send messages in the … channel` | Give the bot the permissions from step 4 in that channel. |
| `appeal intake channel … is not a text channel…` | Fix `appealIntakeChannelId`, or empty it. |
| `posts Discord refused: …` | The log line `A post was not made (…)` names the reason — usually `MISSING_PERMISSIONS` in one channel. |
| `posts not made because the bot was not connected: …` | Something happened while the bot was offline, still connecting, or before its channels existed. Those posts are not sent later. |
| `direct messages players did not receive: …` | Those players have direct messages off. Each appeal's thread says which. |
| `warning: the token file is readable by every user on this machine` | Restrict the file — see [step 3](#3-make-the-bot-in-discord). |
| `disabled: built for a different StaffCore API version` | The two jars are from different builds. Replace both — see [Updating](#updating). |

**Staff can't see the channels.** Only roles listed in `roleNodes` were given access when the channels
were made. In Discord, add the role to the **StaffCore** category (*Edit Category → Permissions*)
with *View Channel*, *Read Message History* and *Send Messages in Threads*. The channels made with it
are synced to the category and follow; `#staff-chat` has its own permissions, so add the role there
too, with *Send Messages* as well.

**`/link` says the code is not valid.** Codes last 10 minutes and work once. Run
`/staff discord link` again for a new one.

**`/whoami` says you can use nothing.** Either your role is not in `roleNodes`, or your Minecraft
account does not hold those permissions in game. With LuckPerms, permissions can only be read while
you are online, so join the server and try again.

**A button says you need a permission.** It needs that permission both in `roleNodes` for one of your
roles and in game for your linked account.

**The files never appeared.** Check that `staffcore-discord-1.0.0.jar` is in `mods/` beside
`staffcore-1.1.0.jar`, that the server finished starting, and search `logs/latest.log` for
`StaffCore Discord`.
