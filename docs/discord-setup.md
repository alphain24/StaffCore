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
[5. Copy the ids](#5-copy-the-ids-you-need) ·
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
- A Discord server where you can manage the server (to invite a bot and set channel permissions).
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
https://discord.com/oauth2/authorize?client_id=APPLICATION_ID&scope=bot+applications.commands&permissions=309237730304
```

That asks for exactly these permissions and nothing more:

| Permission | Why |
|---|---|
| View Channels | To find the channels you give it |
| Send Messages | To post |
| Embed Links | Posts are embeds |
| Read Message History | To find a thread again once Discord has archived it |
| Create Public Threads | Every report, appeal and case gets a thread |
| Send Messages in Threads | To say what happened in each thread |

It needs no administrator or moderation permissions, and should not be given any.

If your staff channels are private, the bot cannot see them until you let it in: open each
channel's **Edit Channel → Permissions**, add the bot (or its role), and allow the six permissions
above.

---

## 5. Copy the ids you need

Discord ids are long numbers. To copy them, turn on **Developer Mode** first:
*User Settings → Advanced → Developer Mode*.

| You need | How to copy it |
|---|---|
| Server id (`guildId`) | Right-click the server icon → **Copy Server ID** |
| Channel ids | Right-click the channel → **Copy Channel ID** |
| Role ids (`roleNodes`) | *Server Settings → Roles*, right-click the role → **Copy Role ID** |

A set of channels that works well — make whichever you want, every one is optional:

| Channel | Who should see it |
|---|---|
| `#punishments` | Staff (or everyone, if you post punishments publicly) |
| `#reports` | Staff |
| `#alerts` | Staff |
| `#appeals` | Staff |
| `#appeal-here` | Everyone — where players run `/appeal` (optional) |
| `#staff-log` | Senior staff |
| `#staff-chat` | Staff |

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
  "punishmentsChannelId": "444444444444444401",
  "reportsChannelId": "444444444444444402",
  "alertsChannelId": "444444444444444403",
  "appealsChannelId": "444444444444444404",
  "appealIntakeChannelId": "444444444444444405",
  "staffLogChannelId": "444444444444444406",
  "staffChatChannelId": "444444444444444407",
  "discordAlertSeverity": 70,
  "serverName": "",
  "playerHeadUrl": "https://mc-heads.net/avatar/{uuid}/64"
}
```

- **`enabled`** — `true` to start the bot.
- **`guildId`** — your server id.
- **Channel ids** — leave any you do not want as `""`. That kind of post is simply not made.
- **`roleNodes`** — which StaffCore permissions each Discord role may use from Discord, by role id.
  The example has a *Helper* role and a *Moderator* role.

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

Restart the Minecraft server. Then, in game, run:

```
/staff status
```

You want a line like:

```
Discord: connected as StaffCore in Your Server
```

In Discord, type `/` in your server and you should see the bot's commands: `/link`, `/unlink`,
`/whoami`, and `/appeal` if you set an appeals channel. If they do not show up, press `Ctrl+R` in
Discord to reload it.

If the status says anything else, see [Troubleshooting](#troubleshooting).

---

## 8. Link your staff accounts

Every staff member links their own Discord account once. Nothing but reading the channels works
until they do.

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
2. Make `#appeals` **staff-only**. Appeals and staff discussion go there.
3. Optionally make a public channel such as `#appeal-here` and put its id in
   `appealIntakeChannelId`. `/appeal` then only works there. Players need **View Channel** and
   **Use Application Commands** in it — both are on for `@everyone` by default.
4. Tell players to allow **direct messages from server members** (*Server name → Privacy Settings*).
   Questions from staff and the verdict reach them by direct message, and they answer by replying
   to the bot. If their direct messages are off, the appeal's thread tells staff they did not hear.

---

## Updating

Replace **both** jars together, from the same build, then restart. Your settings, token and links are
kept. If only one is replaced, the log says
`This companion was built for StaffCore API version …` and the bot does not start.

To switch the bot off without removing it, set `"enabled": false` and restart. Links are kept for
when you switch it back on.

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
| `… channel … is not a text channel in …` | That id is wrong, is a category, voice or forum channel, or is a private channel the bot cannot see. |
| `the bot cannot send messages in the … channel` | Give the bot the permissions from step 4 in that channel. |
| `appeal intake channel … is not a text channel…` | Fix `appealIntakeChannelId`, or empty it. |
| `posts Discord refused: …` | The log line `A post was not made (…)` names the reason — usually `MISSING_PERMISSIONS` in one channel. |
| `posts not made because the bot was not connected: …` | Something happened while the bot was offline or still connecting. Those posts are not sent later. |
| `direct messages players did not receive: …` | Those players have direct messages off. Each appeal's thread says which. |
| `warning: the token file is readable by every user on this machine` | Restrict the file — see [step 3](#3-make-the-bot-in-discord). |
| `disabled: built for a different StaffCore API version` | The two jars are from different builds. Replace both — see [Updating](#updating). |

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
