package io.github.alphain24.staffcore.permission;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.github.alphain24.staffcore.StaffCore;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * config/staffcore/permissions.json — a permissions system for servers that do not have one.
 * <p>
 * Without a permissions mod every StaffCore node used to fall back to the vanilla moderator
 * level, which made all staff equally powerful: a trainee helper could revoke bans and roll
 * back the world because there was no way to express that they should not. It also made
 * testing misleading, since an opped alt passes every check and so looks like it is seeing
 * through vanish when the feature is working perfectly.
 * <p>
 * Installing LuckPerms is still the better answer and nothing here competes with it — this
 * file is consulted only when no permissions API is present, and is ignored entirely the
 * moment one is. What it buys is that "I have not set up LuckPerms yet" stops meaning
 * "everybody who is op is an admin".
 * <p>
 * Players are keyed by UUID or by name; names are convenient and UUIDs are correct, so both
 * work and a UUID wins where an account has been renamed.
 */
public final class PermissionGroups {

	/**
	 * What this file was last written by, so a changed starter group can reach an existing install.
	 * <p>
	 * The same problem {@code StaffConfig.configVersion} solves. The starter groups are written
	 * once, on first run, and editing {@link #defaultGroups} afterwards changes nothing for a
	 * server that already has the file. See {@link #migrate} for what moves and what never does.
	 */
	public int configVersion = 0;

	/** The version this build writes. Raise it when a starter group changes and should propagate. */
	private static final int CURRENT_VERSION = 2;

	/** Groups, in the shape the handbook has always suggested setting them up. */
	public Map<String, List<String>> groups = defaultGroups();

	/** Player UUID or name, lowercased, to group name. */
	public Map<String, String> players = new LinkedHashMap<>();

	/**
	 * What an unlisted player gets. Empty means nothing, which is the point — a server
	 * using this file wants staff powers to come from the file, not from being op.
	 */
	public String defaultGroup = "";

	/**
	 * Whether an operator still passes every check.
	 * <p>
	 * On by default so adding this file cannot lock an admin out of their own server before
	 * they have put themselves in it. Turn it off once the groups are set up and op stops
	 * being a way around them.
	 */
	public boolean operatorsBypass = true;

	private static Map<String, List<String>> defaultGroups() {
		Map<String, List<String>> out = new LinkedHashMap<>();
		// staff.notes and staff.notes.* both, for the reason moderator lists staff.punish beside
		// staff.punish.*: a wildcard covers what is beneath a node and not the node itself, and
		// staff.notes is the one /staff note checks.
		out.put("helper", new ArrayList<>(List.of(
				Nodes.STAFF_GUI, Nodes.STAFF_MODE, Nodes.VANISH, Nodes.FREEZE, Nodes.TP,
				Nodes.CHAT, Nodes.ALERTS, Nodes.NOTES, "staff.notes.*", Nodes.HISTORY,
				Nodes.REPORT_VIEW, Nodes.INVSEE)));
		out.put("moderator", new ArrayList<>(List.of(
				"@helper", "staff.punish.*", Nodes.PUNISH, Nodes.TP_HERE, Nodes.TP_POS,
				Nodes.SECURITY_CHECK, Nodes.ITEMSCAN, Nodes.SPY, Nodes.ALTS, Nodes.LOGS,
				Nodes.BLOCK_INSPECT, Nodes.GRIEF_SEARCH)));
		out.put("admin", new ArrayList<>(List.of(
				"@moderator", Nodes.UNPUNISH, Nodes.HISTORY_CLEAR, Nodes.INVSEE_EDIT,
				Nodes.ROLLBACK, Nodes.GRIEF_PURGE, "control.*", Nodes.ANALYTICS,
				Nodes.RELOAD, "security.*", Nodes.APPEALS, Nodes.PERMS_ADMIN, Nodes.REPLAY,
				Nodes.DISCORD_PUNISH_PANEL)));
		return out;
	}

	/**
	 * The helper group exactly as every build before v1 wrote it.
	 * <p>
	 * Literals rather than {@link Nodes} constants on purpose: this is a record of what is on
	 * disk on servers that ran an older build, and it has to keep matching those files even if
	 * a constant is renamed later.
	 */
	/** The admin group exactly as every build before v2 wrote it. Literals, for the reason above. */
	private static final List<String> ADMIN_BEFORE_V2 = List.of(
			"@moderator", "staff.punish.revoke", "staff.history.clear", "security.invsee.edit",
			"grief.rollback", "grief.purge", "control.*", "analytics.stats",
			"staff.reload", "security.*", "staff.appeals", "staff.perms", "staff.replay");

	private static final List<String> HELPER_BEFORE_V1 = List.of(
			"staff.gui", "staff.mode", "staff.vanish", "staff.freeze", "staff.tp",
			"staff.chat", "staff.alerts", "staff.notes.*", "staff.history",
			"report.view", "security.invsee");

	/**
	 * Brings a file written by an older build forward when a starter group has changed.
	 * <p>
	 * The rule is {@code StaffConfig}'s: a group is changed only while it still holds the old
	 * default exactly, entry for entry and in order. A group an owner has edited in any way is
	 * left as it is, and the log says what it would have needed. Every change is logged too.
	 * <p>
	 * The version is what keeps that promise after the first boot. Without it, an owner who
	 * takes {@code staff.notes} back out of helper would leave a group matching the old default
	 * again, and would have it put back on every restart.
	 *
	 * @return true when anything changed and the file should be rewritten
	 */
	boolean migrate() {
		if (configVersion >= CURRENT_VERSION) return false;
		int from = configVersion;

		// v1: helper gained staff.notes. The starter group had staff.notes.* only, which covers
		// staff.notes.view and staff.notes.remove but not staff.notes, so no helper — and through
		// @helper no moderator or admin either — could write a note. Only operators could.
		if (from < 1 && groups != null && groups.containsKey("helper")) {
			List<String> helper = groups.get("helper");
			if (HELPER_BEFORE_V1.equals(helper)) {
				List<String> upgraded = new ArrayList<>(helper);
				upgraded.add(upgraded.indexOf("staff.notes.*"), Nodes.NOTES);
				groups.put("helper", upgraded);
				StaffCore.LOGGER.info("[StaffCore] Permissions upgrade: added staff.notes to the "
						+ "helper group, so helpers can write notes with /staff note. If helpers "
						+ "were meant not to, take it back out of config/staffcore/permissions.json.");
			} else if (!holds("helper", Nodes.NOTES)) {
				StaffCore.LOGGER.warn("[StaffCore] Permissions upgrade: the helper group has been "
						+ "edited, so it was left as it is. It does not grant staff.notes, which "
						+ "/staff note needs - add \"staff.notes\" to it in "
						+ "config/staffcore/permissions.json if helpers should write notes.");
			}
		}

		// v2: admin gained discord.punishpanel, the Discord punishment panel.
		if (from < 2 && groups != null && groups.containsKey("admin")) {
			List<String> admin = groups.get("admin");
			if (ADMIN_BEFORE_V2.equals(admin)) {
				List<String> upgraded = new ArrayList<>(admin);
				upgraded.add(Nodes.DISCORD_PUNISH_PANEL);
				groups.put("admin", upgraded);
				StaffCore.LOGGER.info("[StaffCore] Permissions upgrade: added discord.punishpanel to the "
						+ "admin group, for the punishment panel in Discord.");
			} else if (!holds("admin", Nodes.DISCORD_PUNISH_PANEL)) {
				StaffCore.LOGGER.info("[StaffCore] Permissions upgrade: the admin group has been edited, so "
						+ "it was left as it is. Add \"discord.punishpanel\" to whichever group should use "
						+ "the punishment panel in Discord.");
			}
		}

		configVersion = CURRENT_VERSION;
		StaffCore.LOGGER.info("[StaffCore] Permissions file upgraded from v{} to v{}.", from,
				CURRENT_VERSION);
		return true;
	}

	/** Whether a group, with everything it inherits, covers a node. */
	private boolean holds(String group, String node) {
		for (String granted : expand(group, new ArrayList<>())) {
			if (grants(granted, node)) return true;
		}
		return false;
	}

	// ------------------------------------------------------------------- resolving

	/**
	 * Whether a player holds a node, or null when this file has nothing to say about them.
	 * <p>
	 * Null rather than false on purpose: "not configured" and "configured to be denied" are
	 * different answers, and collapsing them would mean writing this file at all silently
	 * revoked whatever the op fallback used to grant.
	 */
	public Boolean check(UUID uuid, String name, String node) {
		String group = groupFor(uuid, name);
		if (group == null) return null;

		// A named group is an answer even when it turns out to be empty or to name nothing.
		//
		// This used to return null for both, which reads as "this file has no opinion" and
		// falls through to the vanilla operator level. So a typo in a group name did not deny
		// anybody — it promoted them. Somebody assigned to "moderater" got operator level,
		// which on most servers is more than "moderator" would have given them, and the file
		// looked correctly configured while doing the opposite of what it said.
		if (!groups.containsKey(group.toLowerCase(Locale.ROOT))) {
			StaffCore.LOGGER.warn("[StaffCore] {} is assigned to \"{}\", which is not a group in "
					+ "config/staffcore/permissions.json. Denying, and they hold nothing until it is "
					+ "spelled the same as one of: {}",
					name, group, String.join(", ", groups.keySet()));
			return false;
		}

		return holds(group, node);
	}

	/**
	 * Every node a player actually ends up with, and what granted each one.
	 * <p>
	 * Ambiguity is this file's failure mode. Wildcards, inheritance and a default group are
	 * three ways for a node to arrive without anybody having written it down next to that
	 * player's name, and "why can this person do that" is otherwise answered by reading JSON
	 * and simulating the resolver in your head.
	 */
	public record Explanation(String group, boolean groupExists, List<String> grants,
			List<String> problems) {}

	public Explanation explain(UUID uuid, String name) {
		String group = groupFor(uuid, name);
		List<String> problems = new ArrayList<>();

		if (group == null) {
			problems.add("not listed in the file, and defaultGroup is empty");
			return new Explanation(null, false, List.of(), problems);
		}
		if (!groups.containsKey(group.toLowerCase(Locale.ROOT))) {
			problems.add("assigned to \"" + group + "\", which is not a group in this file");
			return new Explanation(group, false, List.of(), problems);
		}

		List<String> grants = new ArrayList<>();
		for (String granted : expand(group, new ArrayList<>())) {
			String via = granted.endsWith(".*")
					? granted + "   (wildcard: everything under " + granted.substring(0, granted.length() - 1) + ")"
					: granted;
			if (!grants.contains(via)) grants.add(via);
		}
		if (grants.isEmpty()) problems.add("the group is defined but lists no nodes");
		return new Explanation(group, true, grants, problems);
	}

	/**
	 * Checks the file makes sense, at load rather than at resolution.
	 * <p>
	 * A cycle in {@code @other} inheritance was already survivable — the resolver keeps a
	 * seen-list so it cannot hang — but surviving it meant silently resolving to a partial
	 * node set, which is a permission bug that presents as a permission working sometimes.
	 * Reporting it once at load costs nothing and is the only moment anybody is looking.
	 *
	 * @return one line per problem, empty when the file is coherent
	 */
	public List<String> validate() {
		List<String> problems = new ArrayList<>();

		for (String group : groups.keySet()) {
			List<String> chain = new ArrayList<>();
			String cycle = findCycle(group, chain);
			if (cycle != null) {
				problems.add("Inheritance cycle: " + String.join(" -> ", chain) + " -> " + cycle);
			}
			for (String entry : groups.getOrDefault(group, List.of())) {
				if (entry.startsWith("@") && !groups.containsKey(
						entry.substring(1).toLowerCase(Locale.ROOT))) {
					problems.add("Group \"" + group + "\" inherits from \"" + entry.substring(1)
							+ "\", which does not exist");
				}
			}
		}

		for (var entry : players.entrySet()) {
			if (!groups.containsKey(entry.getValue().toLowerCase(Locale.ROOT))) {
				problems.add("Player \"" + entry.getKey() + "\" is in group \"" + entry.getValue()
						+ "\", which does not exist - they will hold nothing");
			}
		}

		if (defaultGroup != null && !defaultGroup.isBlank()
				&& !groups.containsKey(defaultGroup.toLowerCase(Locale.ROOT))) {
			problems.add("defaultGroup is \"" + defaultGroup + "\", which does not exist");
		}
		return problems;
	}

	/** Walks the inheritance chain, returning the group that closes a loop. */
	private String findCycle(String group, List<String> chain) {
		String key = group == null ? null : group.toLowerCase(Locale.ROOT);
		if (key == null) return null;
		if (chain.contains(key)) return key;

		chain.add(key);
		for (String entry : groups.getOrDefault(key, List.of())) {
			if (!entry.startsWith("@")) continue;
			String found = findCycle(entry.substring(1), chain);
			if (found != null) return found;
		}
		chain.remove(key);
		return null;
	}

	private String groupFor(UUID uuid, String name) {
		String byUuid = uuid == null ? null : players.get(uuid.toString());
		if (byUuid != null) return byUuid;

		String byName = name == null ? null : players.get(name.toLowerCase(Locale.ROOT));
		if (byName != null) return byName;

		return defaultGroup == null || defaultGroup.isBlank() ? null : defaultGroup;
	}

	/**
	 * A group's nodes, with {@code @other} entries replaced by that group's nodes.
	 * <p>
	 * Inheritance is what makes the three suggested groups readable — "moderator is helper
	 * plus these six" rather than helper's eleven nodes copied out again and drifting apart
	 * the first time one changes. {@code seen} stops a cycle turning a typo into a hang.
	 */
	private List<String> expand(String group, List<String> seen) {
		if (group == null || seen.contains(group)) return List.of();
		seen.add(group);

		List<String> declared = groups.get(group.toLowerCase(Locale.ROOT));
		if (declared == null) return List.of();

		List<String> out = new ArrayList<>();
		for (String entry : declared) {
			if (entry.startsWith("@")) out.addAll(expand(entry.substring(1), seen));
			else out.add(entry);
		}
		return out;
	}

	/** Whether one granted entry covers a node. {@code *} and {@code staff.*} both work. */
	private static boolean grants(String granted, String node) {
		if (granted == null) return false;
		if (granted.equals("*") || granted.equals(node)) return true;
		if (!granted.endsWith(".*")) return false;

		String prefix = granted.substring(0, granted.length() - 1);   // keep the trailing dot
		return node.startsWith(prefix);
	}

	// ------------------------------------------------------------------ management

	/** Assigns a player to a group, or clears them when {@code group} is null. */
	public void assign(String key, String group) {
		String normalised = key.toLowerCase(Locale.ROOT);
		if (group == null) players.remove(normalised);
		else players.put(normalised, group.toLowerCase(Locale.ROOT));
		save();
	}

	public boolean hasGroup(String group) {
		return group != null && groups.containsKey(group.toLowerCase(Locale.ROOT));
	}

	// -------------------------------------------------------------- load and save

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static PermissionGroups instance;

	/** Null until {@link #load()} has run, and whenever a permissions API is in charge. */
	public static PermissionGroups get() {
		return instance;
	}

	private static Path path() {
		return io.github.alphain24.staffcore.config.ConfigFolder.permissions();
	}

	/**
	 * Reads the file, writing defaults on first run.
	 * <p>
	 * Skipped entirely when a permissions API is present. Two systems both answering the
	 * same question is how a server ends up with a permission that works in one place and
	 * not another, and the API is the one people expect to win.
	 */
	public static void load(boolean permissionsApiPresent) {
		if (permissionsApiPresent) {
			instance = null;
			StaffCore.LOGGER.info(
					"[StaffCore] A permissions API is present - config/staffcore/permissions.json is ignored.");
			return;
		}

		Path file = path();
		if (!Files.exists(file)) {
			instance = new PermissionGroups();
			instance.configVersion = CURRENT_VERSION;
			save();
			StaffCore.LOGGER.info("[StaffCore] No permissions mod found - wrote starter groups to {}",
					file);
			StaffCore.LOGGER.info("[StaffCore] Assign staff with /staff perms set <player> <group>.");
			return;
		}

		try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			PermissionGroups loaded = GSON.fromJson(r, PermissionGroups.class);
			instance = loaded != null ? loaded : new PermissionGroups();
			if (instance.groups == null) instance.groups = defaultGroups();
			if (instance.players == null) instance.players = new LinkedHashMap<>();
			// Written back straight away, or the upgrade would run again on every boot and the
			// file would go on saying something the server is not doing.
			if (instance.migrate()) save();
			StaffCore.LOGGER.info("[StaffCore] Loaded {} permission group(s) for {} player(s).",
					instance.groups.size(), instance.players.size());

			// Boot is the one moment anybody reads this. A cycle or a mistyped group name is
			// otherwise found by a staff member discovering they cannot do their job.
			for (String problem : instance.validate()) {
				StaffCore.LOGGER.error("[StaffCore] config/staffcore/permissions.json: {}", problem);
			}
		} catch (IOException | RuntimeException e) {
			// Falling back to defaults here would silently promote everyone in the file to
			// whatever the starter groups say, so the file is treated as absent instead.
			instance = null;
			StaffCore.LOGGER.error("[StaffCore] config/staffcore/permissions.json is unreadable - "
					+ "falling back to operator level", e);
		}
	}

	public static void save() {
		if (instance == null) return;
		Path file = path();
		try {
			Files.createDirectories(file.getParent());
			try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
				GSON.toJson(instance, w);
			}
		} catch (IOException e) {
			StaffCore.LOGGER.error("[StaffCore] Could not write config/staffcore/permissions.json", e);
		}
	}
}
