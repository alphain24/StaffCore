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
 * {@code config/staffcore-discord.json}: everything about the bot except its token.
 * <p>
 * Written with defaults the first time the server starts with the companion installed. The bot
 * stays off until {@link #enabled} is set, a guild is named and the token file exists, so
 * installing the jar changes nothing by itself.
 * <p>
 * Every key is checked at startup and each problem is logged as a sentence naming the key, what
 * is in it, and what the companion is doing instead.
 */
public final class DiscordSettings {

	public static final String FILE_NAME = "staffcore-discord.json";

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

	// ---- channels ------------------------------------------------------------
	//
	// Each is a channel id in guildId, and each is optional: empty means that kind of post is not
	// made. Set any of the first five and StaffCore's own discordWebhookUrl stops posting once the
	// bot connects, so nothing arrives twice. The bot needs to see, send messages, embed links and
	// create public threads in each.

	/**
	 * Where punishments are posted: reason, staff, the player with their prior count, duration,
	 * expiry, case and punishment id. A reversal edits the post. Empty posts none.
	 */
	public String punishmentsChannelId = "";

	/**
	 * Where reports are posted, each with a thread and buttons to claim, resolve, escalate, look at
	 * the player's profile and history, add a note and freeze them. Claiming and resolving edit the
	 * post. Empty posts none, and reports then reach Discord only as alerts.
	 */
	public String reportsChannelId = "";

	/**
	 * Where detector signals are posted, and where every case gets its thread. A signal is posted
	 * when it reaches {@link #discordAlertSeverity} or when it opens a case; weaker signals about a
	 * case that already has a thread are added to the thread instead. Empty posts none, and cases
	 * get no threads unless a report opened them.
	 */
	public String alertsChannelId = "";

	/** Where appeals are posted, each with a thread; a verdict edits the post. Empty posts none. */
	public String appealsChannelId = "";

	/**
	 * Where every audited staff action is posted: who, what, the player it names, when, and its
	 * case. This is one post per command on a busy server. Empty posts none.
	 */
	public String staffLogChannelId = "";

	/**
	 * A channel bridged with staff chat in game, both ways. Lines from Discord are marked
	 * {@code [Discord]} in game and only linked staff holding {@code staff.chat} are bridged.
	 * Setting it makes the bot ask Discord for message content, which is a privileged intent: turn on
	 * Message Content Intent for the bot in the developer portal first, or it cannot log in. Empty
	 * bridges nothing and asks for no intents.
	 */
	public String staffChatChannelId = "";

	/**
	 * The lowest signal confidence, 0 to 100, posted to the alerts channel on its own.
	 * <p>
	 * Signals are scored like cases are: StaffCore opens a case at 70 by default. Lower posts more of
	 * what staff are told in game; higher keeps the channel to the findings most likely to matter. A
	 * signal that opens a case is posted whatever this says, because the case needs its thread.
	 */
	public int discordAlertSeverity = 70;

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
			case APPEALS -> appealsChannelId;
			case STAFF_LOG -> staffLogChannelId;
			case STAFF_CHAT -> staffChatChannelId;
		};
		return id == null ? "" : id;
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
				problems.add(FILE_NAME + " could not be read (" + e.getClass().getSimpleName() + "). The "
						+ "bot stays off until it is fixed; the file was left as it is.");
				settings.enabled = false;
				return new Loaded(settings, problems);
			}
		} else {
			try {
				Files.createDirectories(file.getParent());
				try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
					GSON.toJson(settings, writer);
				}
			} catch (IOException e) {
				problems.add(FILE_NAME + " could not be written with defaults ("
						+ e.getClass().getSimpleName() + ").");
			}
		}

		problems.addAll(settings.validate(knownNodes));
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
		}

		if (requestTimeoutSeconds < 2 || requestTimeoutSeconds > 60) {
			int was = requestTimeoutSeconds;
			requestTimeoutSeconds = Math.max(2, Math.min(60, requestTimeoutSeconds));
			problems.add("requestTimeoutSeconds is " + was + ", which is outside 2-60. Using "
					+ requestTimeoutSeconds + ".");
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
								+ "accepted — name each permission, so a node StaffCore adds later is "
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

		punishmentsChannelId = channel("punishmentsChannelId", punishmentsChannelId, problems);
		reportsChannelId = channel("reportsChannelId", reportsChannelId, problems);
		alertsChannelId = channel("alertsChannelId", alertsChannelId, problems);
		appealsChannelId = channel("appealsChannelId", appealsChannelId, problems);
		staffLogChannelId = channel("staffLogChannelId", staffLogChannelId, problems);
		staffChatChannelId = channel("staffChatChannelId", staffChatChannelId, problems);

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

	/** A channel id, or empty. Anything else is reported and treated as empty, so nothing is posted. */
	private static String channel(String key, String value, List<String> problems) {
		String id = value == null ? "" : value.strip();
		if (id.isEmpty() || SNOWFLAKE.matcher(id).matches()) return id;
		problems.add(key + " is \"" + id + "\", which is not a channel id. Posting nothing there. Copy the "
				+ "id with Developer Mode on: right-click the channel, Copy Channel ID.");
		return "";
	}
}
