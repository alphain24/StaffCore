package io.github.alphain24.staffcore.discord.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * {@code config/staffcore/discord.json}: everything about the bot except its token.
 * <p>
 * Written with defaults the first time the server starts with the companion installed. The bot
 * stays off until {@link #enabled} is set, a guild is named and the token file exists, so
 * installing the jar changes nothing by itself.
 * <p>
 * Every key is checked at startup and each problem is logged as a sentence naming the key, what
 * is in it, and what the companion is doing instead.
 */
public final class DiscordSettings {

	public static final String FILE_NAME = "discord.json";
	/** What earlier builds called it, loose in {@code config/}. */
	public static final String LEGACY_FILE_NAME = "staffcore-discord.json";
	/** The file as an owner finds it from the server folder. */
	public static final String SHOWN = "config/staffcore/" + FILE_NAME;

	/**
	 * Whether the bot connects to Discord at all.
	 * <p>
	 * Off by default. Turning it on with a guild and a token starts the bot at the next server
	 * start, and staff can then link their accounts. Turning it off stops the bot at the next start;
	 * existing links are kept, and nothing can be done through them while it is off.
	 */
	public boolean enabled = false;

	/**
	 * The id of the Discord server (guild) the bot works in.
	 * <p>
	 * The bot answers commands from this guild only, and reads roles only from this guild. A role
	 * with a mapped id cannot exist anywhere else — Discord ids are unique — but a bot invited to a
	 * second server would otherwise answer there with nobody from this server watching. Changing it
	 * moves the bot's commands to the new guild at the next start.
	 */
	public String guildId = "";

	/**
	 * Which StaffCore permissions each Discord role may use from Discord, by role id.
	 * <p>
	 * Explicit and narrowing only. Each role names the nodes it allows, one by one; wildcards are
	 * refused, and a role that is not listed allows nothing. Whatever a role allows is further
	 * limited to what the member's linked Minecraft account holds in game, so mapping a node here
	 * never gives anybody a permission they do not already have — it decides which of their
	 * permissions they may use from Discord. Removing a node takes effect on the next request.
	 * <p>
	 * Example: {@code "123456789012345678": ["staff.history", "staff.punish.mute"]}.
	 */
	public Map<String, List<String>> roleNodes = new LinkedHashMap<>();

	/**
	 * How many seconds a Discord request waits for the server to answer before telling the user it
	 * timed out, from 2 to 60.
	 * <p>
	 * Higher suits a server that is often under heavy load; the request still happens if the server
	 * gets to it later, so this changes only what the Discord user is told. Discord itself gives an
	 * interaction fifteen minutes once acknowledged.
	 */
	public int requestTimeoutSeconds = 10;

	/**
	 * How many posts may wait while the bot cannot post, from 50 to 10000.
	 * <p>
	 * Posts made while the bot is disconnected, still connecting or being answered with Discord server
	 * errors wait, in order, and are made when it can post again. Past this many, the oldest is dropped
	 * for each new one, and the drops are logged and counted in {@code /staff status}. Higher keeps
	 * more of a long outage, at the cost of the memory the waiting posts hold (a few kilobytes each);
	 * lower keeps less. Waiting posts are not kept when the server stops.
	 */
	public int outboundQueueSize = 500;

	// ---- channels ------------------------------------------------------------
	//
	// Each is one of three things. A channel id in guildId posts there. "create" has the bot make the
	// channel when it connects - private, under a StaffCore category, seen only by the bot and the staff
	// roles in roleNodes; the players' appeal and contact channels alone are public, under their own
	// category - and write its id back here in place
	// of "create". Empty means that kind of post
	// is not made. Set any of the first five and StaffCore's own discordWebhookUrl stops posting once the
	// bot connects, so nothing arrives twice. The bot needs to see, send messages, embed links and
	// create public threads in each; to make channels it also needs Manage Channels and Manage Roles.

	/** The value that asks for a channel to be made. */
	public static final String CREATE = "create";

	/**
	 * Where punishments are posted: reason, staff, the player with their prior count, duration,
	 * expiry, case and punishment id. A reversal edits the post. Empty posts none.
	 */
	public volatile String punishmentsChannelId = CREATE;

	/**
	 * Where reports are posted, each with a thread and buttons to claim, resolve, escalate, look at
	 * the player's profile and history, add a note and freeze them. Claiming and resolving edit the
	 * post. Empty posts none, and reports then reach Discord only as alerts.
	 */
	public volatile String reportsChannelId = CREATE;

	/**
	 * Where detector signals are posted, and where every case gets its thread. A signal is posted
	 * when it reaches {@link #discordAlertSeverity} or when it opens a case; weaker signals about a
	 * case that already has a thread are added to the thread instead. Empty posts none, and cases
	 * get no threads unless a report opened them.
	 */
	public volatile String alertsChannelId = CREATE;

	/**
	 * Where every case gets a card: who it is about, its status, kind, severity and assignee, what is in
	 * it and the latest of its history, with buttons for the details, the evidence, the player's notes,
	 * history and profile, freezing and releasing them, and adding a note to the case. The card is edited
	 * whenever the case changes, and the case's thread is under it. Set, cases get no threads in the
	 * alerts channel or under reports. Empty posts no cards, and cases keep their threads there.
	 */
	public volatile String casesChannelId = CREATE;

	/**
	 * Where appeals are posted for staff, each with a thread and buttons to accept, reject, ask the
	 * player something and close; a verdict edits the post. Setting it also turns on {@code /appeal}
	 * for players, and the bot then reads its direct messages, which is where a player answers a
	 * question about their appeal and hears the verdict. Empty takes no appeals from Discord.
	 */
	public volatile String appealsChannelId = CREATE;

	/**
	 * The players' appeal channel: the one {@code /appeal} is answered in, and where the bot keeps a
	 * message with an Appeal button that opens the form. Appeals are still posted to
	 * {@link #appealsChannelId}, which stays private to staff.
	 * <p>
	 * {@code "create"} makes a public channel, {@code #appeal}, that everybody can read and nobody but the
	 * bot can type in; the button and {@code /appeal} work there. A channel id uses one you made. Empty
	 * answers {@code /appeal} in any channel and posts no button.
	 */
	public volatile String appealIntakeChannelId = CREATE;

	/**
	 * The public contact channel, {@code #contact-staff}: a message with a Contact Staff button, for a player
	 * who needs a person — frozen in game and told to come here, or stuck. Pressing it asks who they are in
	 * game and what they need, opens a private thread for them, tells staff in game, and posts the request
	 * to {@link #helpRequestsChannelId} with a Join button that adds a staff member to the thread.
	 * <p>
	 * {@code "create"} makes it under {@link #appealIntakeCategory}, readable by everybody, typed in by nobody
	 * but the bot; players write only in their own threads. It needs the bot to be allowed to create private
	 * threads, which the setup guide's invite link asks for. A channel id uses one you made. Empty takes no
	 * requests.
	 */
	public volatile String contactStaffChannelId = CREATE;

	/**
	 * Where the requests from {@link #contactStaffChannelId} arrive, private to staff: who asked, as whom,
	 * whether the Discord account is really that player, whether they are online, frozen or banned, their
	 * case, and Join, Close, Profile, History, Freeze and Unfreeze buttons. Joining and closing need
	 * {@code report.view}. Empty takes no requests.
	 */
	public volatile String helpRequestsChannelId = CREATE;

	/**
	 * The public category the players' channels go under — the appeal channel and the contact channel — such
	 * as {@code "Help"} or {@code "Staff Help"}: found by name, or made visible to everybody when there is
	 * none. A players' appeal channel already made outside any category is moved into it on the next start;
	 * one an owner put in a category of their own is left there. Empty puts the channels at the top of the
	 * server, with no category. At most 100 characters.
	 */
	public String appealIntakeCategory = "Help";

	/**
	 * Where every audited staff action is posted: who, what, the player it names, when, and its
	 * case. This is one post per command on a busy server. Empty posts none.
	 */
	public volatile String staffLogChannelId = CREATE;

	/**
	 * A channel bridged with staff chat in game, both ways. Lines from Discord are marked
	 * {@code [Discord]} in game and only linked staff holding {@code staff.chat} are bridged.
	 * <p>
	 * Reading what is typed there needs Message Content Intent, a privileged intent switched on for the
	 * bot in the developer portal. With it off the bot still connects, without it: game lines still reach
	 * the channel, and staff talk back with {@code /staffchat} there instead of typing. Empty bridges
	 * nothing.
	 */
	public volatile String staffChatChannelId = CREATE;

	/**
	 * The punishment panel's channel: a message with a Punish button that punishes by the server's
	 * offence ladders. {@code "create"} makes it private to the bot and to the roles in
	 * {@link #roleNodes} that list {@code discord.punishpanel} — the admin roles, as a new server sets it
	 * up — rather than to every staff role. Using the panel needs that permission in game too, and the
	 * permission for whatever the ladder picks. Empty posts no panel.
	 */
	public volatile String punishPanelChannelId = CREATE;

	/**
	 * The lowest signal confidence, 0 to 100, posted to the alerts channel on its own.
	 * <p>
	 * Signals are scored like cases are: StaffCore opens a case at 70 by default. Lower posts more of
	 * what staff are told in game; higher keeps the channel to the findings most likely to matter. A
	 * signal that opens a case is posted whatever this says, because the case needs its thread.
	 */
	public int discordAlertSeverity = 70;

	/**
	 * The largest file, in megabytes, kept when staff file a Discord file or message as case evidence.
	 * Kept files go in {@code staffcore-evidence} beside the world and are never deleted, so this is what
	 * bounds how much disk one filing can take. A larger file is still recorded, with a note that it was
	 * not kept. 0 keeps no files at all and records only their names and sizes. 0 to 500.
	 */
	public int evidenceMaxMegabytes = 25;

	/**
	 * A name shown on report posts, for a network where several servers share one Discord. Empty
	 * shows nothing.
	 */
	public String serverName = "";

	/**
	 * The picture of a player's head shown on posts about them, with {@code {uuid}} where their id
	 * goes. Discord fetches the image, so the service named here sees player ids and nothing else.
	 * Empty shows no heads.
	 */
	public String playerHeadUrl = "https://mc-heads.net/avatar/{uuid}/64";

	/** The channel id for this kind of post, or empty when it is not posted. */
	public String channelId(io.github.alphain24.staffcore.discord.channels.Outbound.Channel channel) {
		String id = switch (channel) {
			case PUNISHMENTS -> punishmentsChannelId;
			case REPORTS -> reportsChannelId;
			case ALERTS -> alertsChannelId;
			case CASES -> casesChannelId;
			case APPEALS -> appealsChannelId;
			case STAFF_LOG -> staffLogChannelId;
			case STAFF_CHAT -> staffChatChannelId;
			case HELP_REQUESTS -> helpRequestsChannelId;
			case PUNISH_PANEL -> punishPanelChannelId;
		};
		return id == null ? "" : id;
	}

	/** Whether this channel is waiting for the bot to make it. */
	public boolean toCreate(io.github.alphain24.staffcore.discord.channels.Outbound.Channel channel) {
		return CREATE.equals(channelId(channel));
	}

	/** The settings key for a channel, as it is written in the file. */
	public static String key(io.github.alphain24.staffcore.discord.channels.Outbound.Channel channel) {
		return switch (channel) {
			case PUNISHMENTS -> "punishmentsChannelId";
			case REPORTS -> "reportsChannelId";
			case ALERTS -> "alertsChannelId";
			case CASES -> "casesChannelId";
			case APPEALS -> "appealsChannelId";
			case STAFF_LOG -> "staffLogChannelId";
			case STAFF_CHAT -> "staffChatChannelId";
			case HELP_REQUESTS -> "helpRequestsChannelId";
			case PUNISH_PANEL -> "punishPanelChannelId";
		};
	}

	/** Where these settings were read from, for writing a made channel's id back. Not a setting. */
	transient Path source;

	/**
	 * Whether the bot is off because this file is wrong rather than because {@link #enabled} says so:
	 * unreadable, or naming no usable guild. The staff panel tells the two apart.
	 */
	public transient boolean needsFixing;

	/**
	 * Puts the ids of channels the bot made in place of {@code "create"}, here and in the file.
	 * <p>
	 * The file is changed key by key rather than rewritten from these settings. These have been through
	 * {@link #validate}, which drops a wildcard or a mistyped role; writing them back would quietly delete
	 * the owner's mistakes along with the evidence of them, and the next start's warning with it.
	 *
	 * @return why the file could not be changed, or null when it was
	 */
	/** As {@link #recordCreated}, for the public contact channel. */
	public String recordContactCreated(String id) {
		contactStaffChannelId = id;
		return writeKeys(Map.of("contactStaffChannelId", id));
	}

	/** As {@link #recordCreated}, for the players' appeal channel. */
	public String recordIntakeCreated(String id) {
		appealIntakeChannelId = id;
		return writeKeys(Map.of("appealIntakeChannelId", id));
	}

	public String recordCreated(Map<io.github.alphain24.staffcore.discord.channels.Outbound.Channel, String> ids) {
		if (ids.isEmpty()) return null;
		ids.forEach((channel, id) -> {
			switch (channel) {
				case PUNISHMENTS -> punishmentsChannelId = id;
				case REPORTS -> reportsChannelId = id;
				case ALERTS -> alertsChannelId = id;
				case CASES -> casesChannelId = id;
				case APPEALS -> appealsChannelId = id;
				case STAFF_LOG -> staffLogChannelId = id;
				case STAFF_CHAT -> staffChatChannelId = id;
				case HELP_REQUESTS -> helpRequestsChannelId = id;
				case PUNISH_PANEL -> punishPanelChannelId = id;
			}
		});
		Map<String, String> byKey = new LinkedHashMap<>();
		ids.forEach((channel, id) -> byKey.put(key(channel), id));
		return writeKeys(byKey);
	}

	/** Sets these keys in the file and leaves every other line as the owner wrote it. */
	private String writeKeys(Map<String, String> values) {
		if (source == null) return null;
		try {
			com.google.gson.JsonObject root = Files.isRegularFile(source)
					? com.google.gson.JsonParser.parseString(Files.readString(source, StandardCharsets.UTF_8)).getAsJsonObject()
					: new com.google.gson.JsonObject();
			values.forEach(root::addProperty);
			Path temp = source.resolveSibling(source.getFileName() + ".tmp");
			try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
				GSON.toJson(root, writer);
			}
			try {
				Files.move(temp, source, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
						java.nio.file.StandardCopyOption.ATOMIC_MOVE);
			} catch (IOException atomicUnsupported) {
				Files.move(temp, source, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			}
			return null;
		} catch (IOException | RuntimeException e) {
			return SHOWN + " could not be updated with the new channel ids (" + e.getClass().getSimpleName()
					+ "). They are used until the server stops; put them in the file by hand, or the channels are "
					+ "found by name again at the next start.";
		}
	}

	/** Whether any channel StaffCore's webhook would otherwise post to is set. */
	public boolean postsToChannels() {
		return !punishmentsChannelId.isEmpty() || !reportsChannelId.isEmpty() || !alertsChannelId.isEmpty()
				|| !appealsChannelId.isEmpty() || !staffLogChannelId.isEmpty();
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final Pattern SNOWFLAKE = Pattern.compile("\\d{15,22}");

	/** What loading found: the settings to use, and anything wrong with the file. */
	public record Loaded(DiscordSettings settings, List<String> problems) {}

	/**
	 * Reads the file, writing it with defaults if it is not there, and checks every key.
	 * <p>
	 * A file that cannot be parsed is not overwritten: it is somebody's configuration with a typo
	 * in it, and replacing it with defaults would throw away the part they got right.
	 *
	 * @param knownNodes every node StaffCore defines
	 */
	public static Loaded load(Path file, Set<String> knownNodes) {
		List<String> problems = new ArrayList<>();
		DiscordSettings settings = new DiscordSettings();

		if (Files.isRegularFile(file)) {
			try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				DiscordSettings read = GSON.fromJson(reader, DiscordSettings.class);
				if (read != null) settings = read;
			} catch (IOException | JsonParseException e) {
				problems.add(SHOWN + " could not be read (" + e.getClass().getSimpleName() + "). The "
						+ "bot stays off until it is fixed; the file was left as it is.");
				settings.enabled = false;
				settings.needsFixing = true;
				return new Loaded(settings, problems);
			}
		} else {
			try {
				Files.createDirectories(file.getParent());
				try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
					GSON.toJson(settings, writer);
				}
			} catch (IOException e) {
				problems.add(SHOWN + " could not be written with defaults ("
						+ e.getClass().getSimpleName() + ").");
			}
		}

		problems.addAll(settings.validate(knownNodes));
		settings.source = file;
		return new Loaded(settings, problems);
	}

	/**
	 * Checks every key, correcting in place what can be corrected, and says what it did.
	 * <p>
	 * A bad role mapping entry is dropped rather than kept: an unknown node would grant nothing
	 * anyway, and a wildcard is refused because it would grant whatever StaffCore adds next.
	 */
	public List<String> validate(Set<String> knownNodes) {
		List<String> problems = new ArrayList<>();

		if (guildId == null) guildId = "";
		guildId = guildId.strip();
		if (enabled && !SNOWFLAKE.matcher(guildId).matches()) {
			problems.add("guildId is \"" + guildId + "\", which is not a Discord server id. The bot "
					+ "stays off. Turn on Developer Mode in Discord, right-click the server icon and "
					+ "choose Copy Server ID.");
			enabled = false;
			needsFixing = true;
		}

		if (requestTimeoutSeconds < 2 || requestTimeoutSeconds > 60) {
			int was = requestTimeoutSeconds;
			requestTimeoutSeconds = Math.max(2, Math.min(60, requestTimeoutSeconds));
			problems.add("requestTimeoutSeconds is " + was + ", which is outside 2-60. Using "
					+ requestTimeoutSeconds + ".");
		}

		if (outboundQueueSize < 50 || outboundQueueSize > 10_000) {
			int was = outboundQueueSize;
			outboundQueueSize = Math.max(50, Math.min(10_000, outboundQueueSize));
			problems.add("outboundQueueSize is " + was + ", which is outside 50-10000. Using "
					+ outboundQueueSize + ".");
		}

		Map<String, List<String>> kept = new LinkedHashMap<>();
		if (roleNodes != null) {
			for (Map.Entry<String, List<String>> entry : roleNodes.entrySet()) {
				String role = entry.getKey() == null ? "" : entry.getKey().strip();
				if (!SNOWFLAKE.matcher(role).matches()) {
					problems.add("roleNodes has \"" + role + "\", which is not a role id. Ignoring that "
							+ "entry. Copy the id with Developer Mode on: Server Settings, Roles, "
							+ "right-click the role, Copy Role ID.");
					continue;
				}
				List<String> nodes = new ArrayList<>();
				for (String node : entry.getValue() == null ? List.<String>of() : entry.getValue()) {
					String n = node == null ? "" : node.strip();
					if (n.contains("*")) {
						problems.add("roleNodes." + role + " has \"" + n + "\". Wildcards are not "
								+ "accepted: name each permission, so a node StaffCore adds later is "
								+ "not granted to Discord without anybody deciding to. Ignoring it.");
					} else if (!knownNodes.contains(n)) {
						problems.add("roleNodes." + role + " has \"" + n + "\", which is not a StaffCore "
								+ "permission. Ignoring it.");
					} else if (!nodes.contains(n)) {
						nodes.add(n);
					}
				}
				kept.put(role, List.copyOf(nodes));
			}
		}
		roleNodes = kept;

		punishmentsChannelId = channel("punishmentsChannelId", punishmentsChannelId, true, problems);
		reportsChannelId = channel("reportsChannelId", reportsChannelId, true, problems);
		alertsChannelId = channel("alertsChannelId", alertsChannelId, true, problems);
		casesChannelId = channel("casesChannelId", casesChannelId, true, problems);
		appealsChannelId = channel("appealsChannelId", appealsChannelId, true, problems);
		staffLogChannelId = channel("staffLogChannelId", staffLogChannelId, true, problems);
		staffChatChannelId = channel("staffChatChannelId", staffChatChannelId, true, problems);
		helpRequestsChannelId = channel("helpRequestsChannelId", helpRequestsChannelId, true, problems);
		contactStaffChannelId = channel("contactStaffChannelId", contactStaffChannelId, true, problems);
		if (!contactStaffChannelId.isEmpty() && helpRequestsChannelId.isEmpty()) {
			problems.add("contactStaffChannelId is set but helpRequestsChannelId is not, so requests would reach "
					+ "nobody and the contact channel is not used. Set helpRequestsChannelId too.");
		}
		punishPanelChannelId = channel("punishPanelChannelId", punishPanelChannelId, true, problems);
		appealIntakeChannelId = channel("appealIntakeChannelId", appealIntakeChannelId, true, problems);
		appealIntakeCategory = appealIntakeCategory == null ? ""
				: appealIntakeCategory.replaceAll("\\p{Cntrl}", " ").strip();
		if (appealIntakeCategory.length() > 100) {
			appealIntakeCategory = appealIntakeCategory.substring(0, 100).strip();
			problems.add("appealIntakeCategory is longer than Discord allows. Using the first 100 characters.");
		}
		if (!appealIntakeChannelId.isEmpty() && appealsChannelId.isEmpty()) {
			problems.add("appealIntakeChannelId is set but appealsChannelId is not, so there is nowhere to post "
					+ "appeals and /appeal is not offered. Set appealsChannelId too.");
		}

		if (evidenceMaxMegabytes < 0 || evidenceMaxMegabytes > 500) {
			int was = evidenceMaxMegabytes;
			evidenceMaxMegabytes = Math.max(0, Math.min(500, evidenceMaxMegabytes));
			problems.add("evidenceMaxMegabytes is " + was + ", which is outside 0-500. Using "
					+ evidenceMaxMegabytes + ".");
		}

		if (discordAlertSeverity < 0 || discordAlertSeverity > 100) {
			int was = discordAlertSeverity;
			discordAlertSeverity = Math.max(0, Math.min(100, discordAlertSeverity));
			problems.add("discordAlertSeverity is " + was + ", which is outside 0-100. Using "
					+ discordAlertSeverity + ".");
		}

		if (serverName == null) serverName = "";
		serverName = serverName.strip();
		if (serverName.length() > 64) {
			serverName = serverName.substring(0, 64);
			problems.add("serverName is longer than 64 characters. Using the first 64.");
		}

		if (playerHeadUrl == null) playerHeadUrl = "";
		playerHeadUrl = playerHeadUrl.strip();
		if (!playerHeadUrl.isEmpty()
				&& (!playerHeadUrl.startsWith("https://") || !playerHeadUrl.contains("{uuid}"))) {
			problems.add("playerHeadUrl is \"" + playerHeadUrl + "\", which is not an https address with "
					+ "{uuid} in it. Showing no heads.");
			playerHeadUrl = "";
		}

		if (enabled && roleNodes.values().stream().allMatch(List::isEmpty)) {
			problems.add("roleNodes maps no role to any permission, so linked staff can link and check "
					+ "who they are but can use nothing from Discord.");
		}
		return problems;
	}

	/**
	 * A channel id, {@code "create"} where the bot may make it, or empty. Anything else is reported and
	 * treated as empty, so nothing is posted.
	 */
	private static String channel(String key, String value, boolean creatable, List<String> problems) {
		String id = value == null ? "" : value.strip();
		if (id.isEmpty() || SNOWFLAKE.matcher(id).matches()) return id;
		if (CREATE.equalsIgnoreCase(id) && creatable) return CREATE;
		problems.add(key + " is \"" + id + "\", which is not a channel id. Posting nothing there. Copy the "
				+ "id with Developer Mode on: right-click the channel, Copy Channel ID.");
		return "";
	}
}
