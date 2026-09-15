package io.github.alphain24.staffcore.api;

import io.github.alphain24.staffcore.permission.Nodes;

/**
 * Everything a Discord user can ask StaffCore to do, and the in-game permission each one needs.
 * <p>
 * The node is the one the matching in-game command checks, not a Discord-specific copy of it, so
 * the answer to "who can ban from Discord" is the answer to "who can ban" narrowed by roles.
 * <p>
 * <b>Deliberately absent:</b> IP bans, rollbacks and inventory edits. They are not here to be
 * checked because they are not reachable from Discord at all — and in case something is ever
 * wired up without going through this list, the services that run them refuse a Discord actor
 * themselves.
 */
public enum DiscordOperation {

	// ---- reading
	VIEW_HISTORY(Nodes.HISTORY, false),
	VIEW_STAFF_HISTORY(Nodes.AUDIT, false),
	VIEW_NOTES(Nodes.NOTES_VIEW, false),
	VIEW_CASE(Nodes.STAFF_GUI, false),
	VIEW_EVIDENCE(Nodes.STAFF_GUI, false),
	VIEW_PROFILE(Nodes.STAFF_GUI, false),
	VIEW_ANALYTICS(Nodes.ANALYTICS, false),

	// ---- acting
	BAN(Nodes.BAN, true),
	UNBAN(Nodes.UNPUNISH, true),
	MUTE(Nodes.MUTE, true),
	UNMUTE(Nodes.UNPUNISH, true),
	WARN(Nodes.WARN, true),
	FREEZE(Nodes.FREEZE, true),
	UNFREEZE(Nodes.FREEZE, true),
	NOTE(Nodes.NOTES, true);

	private final String node;
	private final boolean writes;

	DiscordOperation(String node, boolean writes) {
		this.node = node;
		this.writes = writes;
	}

	/** The StaffCore node this needs, in game and here alike. */
	public String node() {
		return node;
	}

	/** Whether this changes anything. Reading still needs a linked account and the node. */
	public boolean writes() {
		return writes;
	}
}
