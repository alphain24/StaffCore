package io.github.alphain24.staffcore.api;

import io.github.alphain24.staffcore.api.internal.DiscordGate;
import io.github.alphain24.staffcore.permission.Actor;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * What a Discord companion may ask about a Discord user: who they are here, whether they may do
 * something, and linking their account.
 *
 * <h2>The rules this enforces, so a companion does not have to</h2>
 * <ul>
 *   <li>Nothing without a link. An unlinked Discord account holds no permissions at all.</li>
 *   <li>What a linked user may do is the smaller of what their roles map to and what their
 *       Minecraft account holds in game, read again for every request. A role can narrow; it can
 *       never add.</li>
 *   <li>A banned Minecraft account can do nothing from Discord.</li>
 *   <li>IP bans, rollbacks and inventory edits are refused to anybody acting from Discord, in the
 *       services that run them.</li>
 * </ul>
 * Every call returns at once with a future. The work happens on the server thread; the future
 * completes there, so a companion should move anything slow it chains onto it back to its own
 * threads.
 */
public final class DiscordAccess {
	private DiscordAccess() {}

	/** Who this Discord user is to StaffCore right now. */
	public static CompletableFuture<DiscordStanding> standing(DiscordUser user) {
		return DiscordGate.standing(user);
	}

	/**
	 * Whether this user may do this, for deciding what to offer them. The action checks again
	 * when it runs.
	 */
	public static CompletableFuture<DiscordDecision> check(DiscordUser user, DiscordOperation operation) {
		return DiscordGate.check(user, operation);
	}

	/** Completes a link started in game with {@code /staff discord link}. */
	public static CompletableFuture<DiscordLinkResult> link(DiscordUser user, String code) {
		return DiscordGate.link(user, code);
	}

	/** Ends this Discord user's link, if they have one. */
	public static CompletableFuture<DiscordLinkResult> unlink(DiscordUser user) {
		return DiscordGate.unlink(user);
	}

	// ------------------------------------------------------------------ reports

	/** Claims a report, taking it over from whoever held it, as clicking it in the queue does. */
	public static CompletableFuture<DiscordResult> claimReport(DiscordUser user, long reportId) {
		return DiscordGate.claimReport(user, reportId);
	}

	public static CompletableFuture<DiscordResult> resolveReport(DiscordUser user, long reportId) {
		return DiscordGate.resolveReport(user, reportId);
	}

	/** Hands a report to the player's case, opening one if they have none, and marks it investigating. */
	public static CompletableFuture<DiscordResult> escalateReport(DiscordUser user, long reportId) {
		return DiscordGate.escalateReport(user, reportId);
	}

	// ------------------------------------------------------------------ players

	public static CompletableFuture<DiscordAnswer<DiscordProfile>> profile(DiscordUser user, UUID playerId) {
		return DiscordGate.profile(user, playerId);
	}

	/** Their punishments, newest first, at most twenty. */
	public static CompletableFuture<DiscordAnswer<List<DiscordPunishment>>> history(DiscordUser user,
			UUID playerId) {
		return DiscordGate.history(user, playerId);
	}

	/** A note on their record, attached to their open case if they have one. */
	public static CompletableFuture<DiscordResult> addNote(DiscordUser user, UUID playerId, String text) {
		return DiscordGate.addNote(user, playerId, text);
	}

	/** Freezes a player who is online. Refused for somebody already frozen, and for somebody offline. */
	public static CompletableFuture<DiscordResult> freeze(DiscordUser user, UUID playerId) {
		return DiscordGate.freeze(user, playerId);
	}

	/** A line into staff chat in game, marked as coming from Discord. */
	public static CompletableFuture<DiscordResult> staffChat(DiscordUser user, String message) {
		return DiscordGate.staffChat(user, message);
	}

	/** Every node StaffCore defines, so a companion can reject a role mapping naming anything else. */
	public static Set<String> knownNodes() {
		return Actor.all();
	}
}
