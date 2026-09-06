package io.github.alphain24.staffcore.permission;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.github.alphain24.staffcore.StaffCore;
import net.fabricmc.loader.api.FabricLoader;

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
 * config/staffcore-permissions.json — a permissions system for servers that do not have one.
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
		out.put("helper", new ArrayList<>(List.of(
				Nodes.STAFF_GUI, Nodes.STAFF_MODE, Nodes.VANISH, Nodes.FREEZE, Nodes.TP,
				Nodes.CHAT, Nodes.ALERTS, "staff.notes.*", Nodes.HISTORY,
				Nodes.REPORT_VIEW, Nodes.INVSEE)));
		out.put("moderator", new ArrayList<>(List.of(
				"@helper", "staff.punish.*", Nodes.PUNISH, Nodes.TP_HERE, Nodes.TP_POS,
				Nodes.SECURITY_CHECK, Nodes.ITEMSCAN, Nodes.SPY, Nodes.ALTS, Nodes.LOGS,
				Nodes.BLOCK_INSPECT, Nodes.GRIEF_SEARCH)));
		out.put("admin", new ArrayList<>(List.of(
				"@moderator", Nodes.UNPUNISH, Nodes.HISTORY_CLEAR, Nodes.INVSEE_EDIT,
				Nodes.ROLLBACK, Nodes.GRIEF_PURGE, "control.*", Nodes.ANALYTICS,
				Nodes.RELOAD, "security.*", Nodes.APPEALS, Nodes.PERMS_ADMIN)));
		return out;
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

		List<String> nodes = expand(group, new ArrayList<>());
		if (nodes.isEmpty()) return null;

		for (String granted : nodes) {
			if (grants(granted, node)) return true;
		}
		return false;
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
		return FabricLoader.getInstance().getConfigDir().resolve("staffcore-permissions.json");
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
					"[StaffCore] A permissions API is present - staffcore-permissions.json is ignored.");
			return;
		}

		Path file = path();
		if (!Files.exists(file)) {
			instance = new PermissionGroups();
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
			StaffCore.LOGGER.info("[StaffCore] Loaded {} permission group(s) for {} player(s).",
					instance.groups.size(), instance.players.size());
		} catch (IOException | RuntimeException e) {
			// Falling back to defaults here would silently promote everyone in the file to
			// whatever the starter groups say, so the file is treated as absent instead.
			instance = null;
			StaffCore.LOGGER.error("[StaffCore] staffcore-permissions.json is unreadable - "
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
			StaffCore.LOGGER.error("[StaffCore] Could not write staffcore-permissions.json", e);
		}
	}
}
