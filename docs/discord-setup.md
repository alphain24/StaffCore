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
[6. Fill in the settings](#6-fill-in-configstaffcorediscordjson) ·
[7. Restart and check](#7-restart-and-check-it-connected) ·
[8. Link staff accounts](#8-link-your-staff-accounts) ·
[9. Appeals](#9-set-up-appeals) ·
[Updating](#updating) · [Troubleshooting](#troubleshooting)

---

## What you need

- A Fabric server for **Minecraft 26.2**, with **Fabric Loader 0.19.3** or newer, **Java 25**, and
  **Fabric API**.
- **Both jars from the same build**: `staffcore-1.2.0.jar` and `staffcore-discord-1.1.0.jar`. The
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
│   ├── staffcore-1.2.0.jar
│   └── staffcore-discord-1.1.0.jar          ← put it here
├── config/
│   └── staffcore/
│       ├── staffcore.json
│       ├── permissions.json
│       ├── discord.json                     ← created on first start
│       └── discord.token                    ← created empty on first start; your token goes in here
└── world/
    └── staffcore-discord/
        └── threads.json                     ← the bot's own memory; leave it alone
```

`config/` is the folder beside `mods/`, not a folder inside it, and every StaffCore file is in
`config/staffcore/` inside that.

> **Upgrading from an earlier build?** It kept these files loose in `config/` as
> `staffcore-discord.json` and `staffcore-discord.token`. They are moved into `config/staffcore/`
> on the first start, as `discord.json` and `discord.token`, and the log says so.

---

## 2. Start the server once

Start the server and let it finish starting. The two files in `config/` are written when the server
has **fully started**, not when the jar is loaded, so wait for the usual `Done` line.

In `logs/latest.log` you should see:

```
[StaffCore Discord] loaded against StaffCore API v5
[StaffCore Discord] Created config/staffcore/discord.token, empty. Paste the bot token into it, …
[StaffCore Discord] Installed and switched off. Set enabled, guildId and the token file to start the bot.
```

The bot is off at this point, and that is expected: nothing happens until you switch it on in step 6.

> **Can't find `discord.token`?** Look inside `config/staffcore/`, not `config/`. If it is not
> there either, create it yourself: a plain text file named exactly `discord.token` in
> `config/staffcore/`. Watch out for Windows hiding the extension and saving it as
> `discord.token.txt` — turn on *File name extensions* in Explorer's *View* menu to check.

You can stop the server now, or leave it running; settings are only read when it starts.

---

## 3. Make the bot in Discord

1. Open the [Discord Developer Portal](https://discord.com/developers/applications) and choose
   **New Application**. Give it the name staff should see, for example *StaffCore*.
2. On the **Bot** page, choose **Reset Token**, confirm, and **Copy** the token.
3. Open `config/staffcore/discord.token` and paste the token in. **Only the token** — no quotes, no
   `token=`, nothing else. Save it.
4. Still on the **Bot** page, under **Privileged Gateway Intents**:
   - Leave **Presence Intent** and **Server Members Intent** off. The bot does not use them.
   - Turn **Message Content Intent** on, so staff can type in `#staff-chat`. If you leave it off the
     bot still works: staff talk there with `/staffchat <message>` instead, and `/staff status` says
     the intent is off.
5. Optional but sensible: turn **Public Bot** off, so nobody but you can add it to a server.
6. On **General Information**, copy the **Application ID**. You need it for the invite link.

**The token is a password for the bot.** Anybody who has it can post as the bot. Never paste it in
Discord, a support channel, a screenshot or `discord.json`. If it ever leaks, press
**Reset Token** again — the old one stops working at once — and put the new one in the file.

On Linux the file is created readable only by the account the server runs as. If you created it
yourself, run:

```bash
chmod 600 config/staffcore/discord.token
```

On a Windows machine shared with other people, open the file's *Properties → Security* and remove
*Users* and *Everyone*. `/staff status` warns you if the file can be read by every user on the
machine.

---

## 4. Invite the bot to your server

Open this link in a browser, with your Application ID in place of `APPLICATION_ID`, and pick your
server:

```
https://discord.com/oauth2/authorize?client_id=APPLICATION_ID&scope=bot+applications.commands&permissions=309506198544
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
| Attach Files | To post files filed as evidence into a case's thread |
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

## 6. Fill in `config/staffcore/discord.json`

Open the file the server wrote and fill it in. A complete example — your ids will differ:

```json
{
  "enabled": true,
  "guildId": "111111111111111111",
  "roleNodes": {
    "222222222222222222": ["report.view", "staff.gui", "staff.history", "staff.notes", "staff.notes.view", "staff.freeze", "staff.chat"],
    "333333333333333333": ["report.view", "staff.gui", "staff.history", "staff.notes", "staff.notes.view", "staff.freeze", "staff.chat", "staff.appeals", "staff.punish.warn", "staff.punish.mute", "staff.punish.ban", "staff.punish.revoke", "staff.audit", "analytics.stats"],
    "444444444444444444": ["report.view", "staff.gui", "staff.history", "staff.notes", "staff.notes.view", "staff.freeze", "staff.chat", "staff.appeals", "staff.punish.warn", "staff.punish.mute", "staff.punish.ban", "staff.punish.revoke", "staff.audit", "analytics.stats", "discord.punishpanel"]
  },
  "requestTimeoutSeconds": 10,
  "outboundQueueSize": 500,
  "punishmentsChannelId": "create",
  "reportsChannelId": "create",
  "alertsChannelId": "create",
  "casesChannelId": "create",
  "appealsChannelId": "create",
  "appealIntakeChannelId": "create",
  "staffLogChannelId": "create",
  "staffChatChannelId": "create",
  "punishPanelChannelId": "create",
  "discordAlertSeverity": 70,
  "serverName": "",
  "evidenceMaxMegabytes": 25,
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

When the bot connects, it makes a **StaffCore** category and puts every staff channel set to
`"create"` inside it:

| Channel | Setting | What goes there |
|---|---|---|
| `#punishments` | `punishmentsChannelId` | Every ban, mute, warning and kick |
| `#reports` | `reportsChannelId` | Player reports, with buttons |
| `#alerts` | `alertsChannelId` | Detector alerts |
| `#cases` | `casesChannelId` | A card per case, kept up to date, with buttons and the case's thread |
| `#appeals` | `appealsChannelId` | Appeals, with buttons |
| `#staff-log` | `staffLogChannelId` | Every staff command — busy |
| `#staff-chat` | `staffChatChannelId` | Staff chat, both ways |
| `#punish` | `punishPanelChannelId` | The punishment panel — only for roles with `discord.punishpanel` |

**All of them are private.** Nobody can see the category or its channels except:

- the bot,
- the staff roles listed in `roleNodes` — they can read everything, reply in threads, and type in
  `#staff-chat`; `#punish` only for the roles whose list has `discord.punishpanel`,
- anybody with Administrator, as always in Discord.

Threads are as private as their channel, so every report, appeal and case thread is private too.

After that first connection the settings file holds the new channel ids instead of `"create"`, so
restarting never makes them again. You can rename or move the channels, and change their
permissions — the bot goes by id and never touches permissions again. To let another role see them,
add it to the category's permissions in Discord.

**One channel is for players: `#appeal`** (`appealIntakeChannelId`). The bot makes it outside the
category, **public**: everybody can read it and press its button, nobody but the bot can type or
start threads. The bot keeps one message there with an **Appeal** button; see
[step 9](#9-set-up-appeals).

A new settings file sets all seven to `"create"`.

> **Your file was written by an earlier build?** Some channel settings will be `""` — `staffChatChannelId`
> was empty by default before 1.2.0. Change the ones you want to `"create"`.

### `roleNodes`, in plain words

`roleNodes` answers one question: **which of your Discord roles are staff, and what may each role do
through the bot?** With names instead of numbers it reads like this:

```
Discord role "Helper"  →  may claim reports, see history, add notes, freeze, use staff chat
Discord role "Admin"   →  all of that, plus decide appeals
```

In the file, the role is written as its **role id** (the long number from step 5, in quotes), and
what it may do is a list:

```json
"roleNodes": {
  "111111111111111111": ["report.view", "staff.gui", "staff.history", "staff.notes", "staff.notes.view", "staff.freeze", "staff.chat"],
  "222222222222222222": ["report.view", "staff.gui", "staff.history", "staff.notes", "staff.notes.view", "staff.freeze", "staff.chat", "staff.appeals", "staff.punish.warn", "staff.punish.mute", "staff.punish.ban", "staff.punish.revoke", "staff.audit", "analytics.stats"],
  "333333333333333333": ["report.view", "staff.gui", "staff.history", "staff.notes", "staff.notes.view", "staff.freeze", "staff.chat", "staff.appeals", "staff.punish.warn", "staff.punish.mute", "staff.punish.ban", "staff.punish.revoke", "staff.audit", "analytics.stats", "discord.punishpanel"]
}
```

One line per staff role. It does two jobs:

1. **Who can see the private channels.** Only the roles listed here are given access to the channels
   the bot makes.
2. **What each role may do from Discord.** But a role **never gives anybody more than they have in
   game**: somebody with the *Admin* role can decide appeals from Discord only if their linked
   Minecraft account can decide appeals in game. Handing the role to the wrong person grants them
   nothing.

**Not sure what to put?** Give every staff role the full list — the second line above. Because of
point 2, the game still decides what each person can actually do, so listing too much is safe;
listing too little only stops people doing from Discord what they could do in game.

Things that make the bot ignore an entry (the log says which, and why):

- the role's **name** (`"Admin"`) instead of its id;
- a **wildcard** like `"staff.*"` — write each permission out;
- a permission that is misspelled.

The permissions the bot uses:

| Permission | Lets them, from Discord |
|---|---|
| `report.view` | Claim and resolve reports |
| `staff.gui` | Escalate a report; `/staff profile`, `/staff case`, `/staff evidence`, `/staff evidence-add`, *Add to case evidence*, and the Profile and Evidence buttons |
| `staff.history` | `/staff history`, and the History and Punishment buttons |
| `staff.notes` | `/staff note`, and the Add Note and Staff Note buttons |
| `staff.notes.view` | `/staff notes` |
| `staff.freeze` | `/staff freeze`, `/staff unfreeze`, and the Freeze button |
| `staff.chat` | Talk in the bridged staff chat channel |
| `staff.appeals` | Accept, reject and close appeals, and ask the player a question |
| `staff.punish.warn` | `/staff warn` |
| `staff.punish.mute` | `/staff mute` |
| `staff.punish.ban` | `/staff ban` |
| `staff.punish.revoke` | `/staff unmute`, `/staff unban` |
| `staff.audit` | `/staff staff-history` |
| `analytics.stats` | `/staff analytics` |
| `staff.replay` | `/staff replay-map`, and maps of replay evidence. Needs `positionTracking` on in `staffcore.json` |
| `discord.punishpanel` | The punishment panel in `#punish`, and `/staff punish`. Only for admin roles: the roles that list it are the ones that can see `#punish` |

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

with no other `Discord:` lines under it. Or open `/staff` and look at the **Discord** button: it
says `Bot: running`. Inside, the **Bot** entry names what is wrong if anything is, and **Channels**
shows each channel and whether posts reach it.

In Discord you should see the **StaffCore** category with its channels, and typing `/` shows the
bot's commands: `/link`, `/unlink`, `/whoami`, `/appeal` and `/staff`. If the commands do not show
up, press `Ctrl+R` in Discord to reload it.

Open `config/staffcore/discord.json` again: the `"create"` values are now channel ids.

If the status says anything else, see [Troubleshooting](#troubleshooting).

---

## 8. Link your staff accounts

Every staff member links their own Discord account once. Until they do, they can read the channels
their role lets them see, and nothing else.

1. In game: `/staff discord link`, or `/staff` → **Discord** → **Your link**. You get a code like
   `ABCD-EFGH`. It lasts 10 minutes and works once.
2. In your Discord server: `/link ABCD-EFGH`.
3. Check with `/whoami` — it names the Minecraft account and lists what you can use from Discord.

`/staff discord` shows your link, and `/staff discord unlink` ends it. An admin with `staff.perms`
can end somebody else's link with `/staff discord unlink <player>`, which is the quick way to cut
off a Discord account that has been taken over. Admins can see everybody who is linked in
`/staff` → **Discord** → **Linked staff**.

### Using the commands

Type `/staff` in your Discord server and pick a command. Player names and case ids complete as you
type, but only for linked staff: anybody else is offered nothing. Every
answer is private to you; what a command does — a ban, a note — is posted to its channel as usual.

| Command | Needs | Does |
|---|---|---|
| `/staff history <player>` | `staff.history` | Their punishments, newest first |
| `/staff notes <player>` | `staff.notes.view` | Their notes, retracted ones marked |
| `/staff profile <player>` | `staff.gui` | Their standing: punishments, points, notes, what is in force, open cases |
| `/staff case <id>` | `staff.gui` | A case and the latest of its history |
| `/staff evidence <case> [item]` | `staff.gui` | The evidence filed on a case; with `item`, one piece with its files, and a map for a replay |
| `/staff replay-map <player> [minutes] [started] [case]` | `staff.replay` | A map of where a player went and what they broke |
| `/staff evidence-add <case> [file] [note]` | `staff.gui` | File a screenshot, video, log or note as evidence |
| `/staff staff-history <staff> [days]` | `staff.audit` | What a staff member did — never where from |
| `/staff analytics [staff]` | `analytics.stats` | Server totals, and one staff member's or the busiest staff's numbers |
| `/staff warn <player> <reason>` | `staff.punish.warn` | Warn |
| `/staff mute <player> <reason> [duration]` | `staff.punish.mute` | Mute; permanent without a duration |
| `/staff ban <player> <reason> [duration]` | `staff.punish.ban` | Ban; permanent without a duration |
| `/staff unmute <player> [reason]` · `/staff unban <player> [reason]` | `staff.punish.revoke` | Lift it |
| `/staff freeze <player>` · `/staff unfreeze <player>` | `staff.freeze` | Hold or release a player who is online |
| `/staff note <player> <text>` | `staff.notes` | Add a note |

Durations are written the way they are in game: `30m`, `12h`, `7d`, `1h30m`.

### Case cards

Every case has a card in `#cases`: who it is about, its status, kind, severity and assignee, what is in
it, and the latest of its history. The card changes as the case does, and the discussion is in the
thread under it.

| Button | Needs | Does |
|---|---|---|
| Details | `staff.gui` | The case and more of its history, only to you |
| Evidence | `staff.gui` | What is filed on the case |
| Notes · History · Profile | `staff.notes.view` · `staff.history` · `staff.gui` | The player's notes, punishments and standing |
| Freeze · Unfreeze | `staff.freeze` | Hold or release the player, if they are online |
| Add Note | `staff.gui` | A line in the case's history, as `/staff case <id> note` in game |

Freeze, Unfreeze and Add Note are switched off once the case is closed.

A command from Discord meets the same checks as in game — the permission, the punishment rate limit,
the rule against punishing somebody who outranks you — and on top of that everything you change from
Discord counts against `discordActionsPerMinute` in `config/staffcore/staffcore.json` (20 a minute
unless changed). IP bans, rollbacks and inventory edits are not available from Discord at all.

### Replay maps

`/staff replay-map` draws where a player went over a window as a picture, north up, with what they broke
and placed marked on it; the message says how to read it. It needs `staff.replay` and position tracking
switched on (`positionTracking` in `config/staffcore/staffcore.json`). Add `case:` to file the window on a
case as evidence too. The picture is only shown to you and is not kept.

### The punishment panel (admins)

In `#punish`, press **Punish**, type the player's name, pick what they did, and press **Confirm**. The
server's offence ladders decide the punishment from the player's record, the same as the punish screen
in game. `/staff punish <player> <offence>` does the same from any channel. You need
`discord.punishpanel` (the starter admin group has it) and the permission for the punishment itself.

### Filing evidence

- **A file or a note:** `/staff evidence-add`, pick the case, attach the file.
- **A message:** right-click it (long-press on a phone) → **Apps** → **Add to case evidence**, and type
  the case id.

The bot downloads each file and keeps it on the server, in `staffcore-evidence` beside the world —
Discord's own links to files stop working after a while. Files larger than `evidenceMaxMegabytes`
(25 MB) are listed but not kept. The filing is posted in the case's thread with its files, and
`/staff evidence <case> item:<number>` shows it again later.

---

## 9. Set up appeals

With `appealsChannelId` set, banned and muted players can appeal from Discord.

1. In `config/staffcore/staffcore.json`, set `discordInvite` to an invite link for your Discord, so the ban
   screen tells players where to go. With the bot taking appeals, the ban screen tells them to type
   `/appeal` with the appeal code shown underneath.
2. `#appeals` is private to staff. Appeals and staff discussion go there, and players never see it.
3. Players go to **`#appeal`** and press **Appeal**, or type `/appeal` there. The form asks for the
   appeal code and what happened; the form and the bot's answers are visible only to them. To use a
   channel you made instead, put its id in `appealIntakeChannelId` — the bot posts the button there,
   and needs to see, send and read history in it. Empty lets players use `/appeal` in any channel and
   posts no button.
4. Tell players to allow **direct messages from server members** (*Server name → Privacy Settings*).
   Questions from staff and the verdict reach them by direct message, and they answer by replying
   to the bot. If their direct messages are off, the appeal's thread tells staff they did not hear.

**Deciding.** **Reject** asks how many days the player waits before appealing again. Once an appeal is
accepted or rejected, its code stops working; a rejected player gets a new code on their ban screen
(in chat, for a mute), which works from the day you chose.

---

## Updating

Replace **both** jars together, from the same build, then restart. Your settings, token, channels
and links are kept.

**From 1.1.0 to 1.2.0:**

- Open the invite link from [step 4](#4-invite-the-bot-to-your-server) again and pick your server: it adds
  **Attach Files**, which posting evidence files needs. Nothing else changes.
- The bot makes the new `#punish` channel on its next start. Your settings file keeps `""` for
  `appealIntakeChannelId` and `staffChatChannelId`; set them to `"create"` if you want `#appeal` and
  `#staff-chat` made too. Making channels needs **Manage Channels** and **Manage Roles**; give them back
  first if you took them away.
- Add `"discord.punishpanel"` to your admin role in `roleNodes` for the punishment panel.

**From 1.2.0 to 1.3.0:**

- The bot makes `#cases` on its next start, and every case gets a card there the next time it changes.
  To keep case threads in `#alerts` as before, set `"casesChannelId": ""`.

If only one jar is replaced, the log says `This companion was built for StaffCore API version …` and the
bot does not start.

To switch the bot off without removing it, set `"enabled": false` and restart. Links and channels
are kept for when you switch it back on.

---

## Troubleshooting

Everything the bot has to say is in `/staff status` (lines starting `Discord:`), in the **Bot**
entry of `/staff` → **Discord**, and in `logs/latest.log` (lines starting `[StaffCore Discord]`).

| `/staff status` says | What to do |
|---|---|
| `waiting for the server to start` | The server has not finished starting. Wait for it. |
| `off (enabled is false in config/staffcore/discord.json)` | Set `"enabled": true` and restart. |
| `off: config/staffcore/discord.json needs fixing` | The file cannot be read, or `guildId` is not a server id. The line under it says which. |
| `off: There is no token file…` | Create `config/staffcore/discord.token` — see [step 2](#2-start-the-server-once). |
| `off: The token file is empty…` | Paste the token into it — see [step 3](#3-make-the-bot-in-discord). |
| `off: The token file does not hold a bot token…` | You pasted something else. The token is on the **Bot** page, not the Client Secret or Application ID on other pages. |
| `could not log in: Discord refused the token` or `could not start (InvalidTokenException)` | The token was reset or copied wrong. Reset it again and paste the new one. |
| `Message Content Intent is off for this bot…` | The bot connected without it. Staff use `/staffchat` in `#staff-chat`; to type there normally, turn on **Message Content Intent** on the Bot page and restart. |
| `connected as …, but not in the guild named by guildId; invite the bot to that server` | The bot is not in that server, or `guildId` is wrong. Invite it (step 4) or copy the id again. |
| `channels set to "create" were not made: the bot needs Manage Channels and Manage Roles…` | Invite the bot again with the link in [step 4](#4-invite-the-bot-to-your-server) — it updates the permissions — and restart. |
| `the … channel has not been made yet, so nothing is posted there` | Look at the line above it for why. Channels still set to `"create"` are tried again at every start. |
| `making the private channels stopped part way (…)` | The bracket names what Discord refused, such as `MISSING_PERMISSIONS` or `InsufficientPermissionException`: the bot is missing one of the permissions from [step 4](#4-invite-the-bot-to-your-server). Give it back and restart; channels already made are used, not made twice. |
| `role … in roleNodes is not in …` | That role id is wrong or the role was deleted. It was left out of the channels' permissions. |
| `… channel … is not a text channel in …` | That id is wrong, is a category, voice or forum channel, or is a private channel the bot cannot see. |
| `the bot cannot send messages in the … channel` | Give the bot the permissions from step 4 in that channel. |
| `appeal intake channel … is not a text channel…` | Fix `appealIntakeChannelId`, or empty it. |
| `the Appeal button could not be posted in #…` | The bot cannot send or read history in that channel. Give it View Channel, Send Messages, Embed Links and Read Message History there. |
| `the appeal channel could not be made (…)` | As for the private channels: the bot is missing Manage Channels or Manage Roles. |
| `posts Discord refused: …` | The log line `A post was not made (…)` names the reason — usually `MISSING_PERMISSIONS` in one channel. |
| `posts waiting until the bot can post: …` | The bot is disconnected or still connecting. The posts go out, in order, when it can post again. If the number stays up, look at the lines above for why it cannot. |
| `posts dropped because too many were waiting: …` | More than `outboundQueueSize` posts piled up while the bot could not post, so the oldest were dropped. Raise `outboundQueueSize` if your outages are long. |
| `posts given up after Discord kept failing them: …` | Discord answered with server errors five times for those posts. The log line `A Discord post was given up…` names the reason. |
| `posts not made because their channel was not made or found: …` | A channel is still set to `"create"` or its id is wrong. Look at the lines above for why. |
| `direct messages players did not receive: …` | Those players have direct messages off. Each appeal's thread says which. |
| `warning: the token file is readable by every user on this machine` | Restrict the file — see [step 3](#3-make-the-bot-in-discord). |
| `disabled: built for a different StaffCore API version` | The two jars are from different builds. Replace both — see [Updating](#updating). |
| `Both config/staffcore-discord.json and config/staffcore/discord.json exist` (log) | The folder's copy is used. Check nothing in the old file is missing, then delete it. |

**Staff can't see the channels.** Only roles listed in `roleNodes` were given access when the channels
were made. In Discord, add the role to the **StaffCore** category (*Edit Category → Permissions*)
with *View Channel*, *Read Message History* and *Send Messages in Threads*. The channels made with it
are synced to the category and follow; `#staff-chat` has its own permissions, so add the role there
too, with *Send Messages* as well.

**`/link` says the code is not valid.** Codes last 10 minutes and work once. Run
`/staff discord link` again for a new one.

**`/whoami` says you can use nothing.** Either your role is not in `roleNodes`, or your Minecraft
account does not hold those permissions in game. With LuckPerms, the first try after a restart may
say your permissions have not loaded yet; try again in a moment, or join the server.

**A button says you need a permission.** It needs that permission both in `roleNodes` for one of your
roles and in game for your linked account.

**The files never appeared.** Look in `config/staffcore/`. Check that `staffcore-discord-1.1.0.jar`
is in `mods/` beside `staffcore-1.2.0.jar`, that the server finished starting, and search
`logs/latest.log` for `StaffCore Discord`. The staff panel's **Discord** button says
`Bot: not installed` when the jar is not loaded at all.
