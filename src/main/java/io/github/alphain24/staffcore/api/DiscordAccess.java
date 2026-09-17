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

	// ------------------------------------------------------------------ appeals

	/**
	 * Files an appeal with the code from a ban screen. Open to any Discord account, linked or not —
	 * the code is the permission — and limited per account, wrong codes included.
	 */
	public static CompletableFuture<DiscordResult> fileAppeal(DiscordUser filer, String code, String text) {
		return DiscordGate.fileAppeal(filer, code, text);
	}

	/** The filer's answer to a question staff asked about their appeal. */
	public static CompletableFuture<DiscordResult> replyToAppeal(DiscordUser appellant, String text) {
		return DiscordGate.replyToAppeal(appellant, text);
	}

	/** Accepts an appeal, lifting the punishment it was against and nothing else. */
	public static CompletableFuture<DiscordResult> acceptAppeal(DiscordUser user, long appealId) {
		return DiscordGate.decideAppeal(user, appealId,
				io.github.alphain24.staffcore.modules.appeal.AppealModule.Verdict.ACCEPTED);
	}

	public static CompletableFuture<DiscordResult> rejectAppeal(DiscordUser user, long appealId) {
		return DiscordGate.decideAppeal(user, appealId,
				io.github.alphain24.staffcore.modules.appeal.AppealModule.Verdict.REJECTED);
	}

	/**
	 * Rejects an appeal, and sets how many days the player waits before appealing that punishment again
	 * with the new code their ban screen shows: 0 to {@link #MAX_APPEAL_WAIT_DAYS}.
	 */
	public static CompletableFuture<DiscordResult> rejectAppeal(DiscordUser user, long appealId, int waitDays) {
		return DiscordGate.decideAppeal(user, appealId,
				io.github.alphain24.staffcore.modules.appeal.AppealModule.Verdict.REJECTED, waitDays);
	}

	/** The longest wait a rejection can set. */
	public static final int MAX_APPEAL_WAIT_DAYS = io.github.alphain24.staffcore.modules.appeal.AppealModule.MAX_WAIT_DAYS;

	/**
	 * The wait a rejection sets unless staff choose another, for filling in the form. Read without going
	 * to the server thread, because a form has to open within three seconds; a value changed by a reload
	 * a moment ago is the worst it can be wrong by.
	 */
	public static int defaultAppealWaitDays() {
		return io.github.alphain24.staffcore.config.StaffConfig.get().appealCooldownDays;
	}

	/** Closes an appeal without a verdict: a duplicate, or one about something already sorted out. */
	public static CompletableFuture<DiscordResult> closeAppeal(DiscordUser user, long appealId) {
		return DiscordGate.decideAppeal(user, appealId,
				io.github.alphain24.staffcore.modules.appeal.AppealModule.Verdict.CLOSED);
	}

	/** Asks the player something; they are told, and can answer. */
	public static CompletableFuture<DiscordResult> requestAppealInfo(DiscordUser user, long appealId,
			String question) {
		return DiscordGate.requestAppealInfo(user, appealId, question);
	}

	/** One punishment, whatever state it is in. */
	public static CompletableFuture<DiscordAnswer<DiscordPunishment>> punishment(DiscordUser user,
			long punishmentId) {
		return DiscordGate.punishment(user, punishmentId);
	}

	/** The evidence filed on the case a punishment came from. */
	public static CompletableFuture<DiscordAnswer<List<DiscordEvidence>>> evidence(DiscordUser user,
			long punishmentId) {
		return DiscordGate.evidence(user, punishmentId);
	}

	// ------------------------------------------------------------------ commands, by player name

	/**
	 * Players are named the way they are in game: an exact name, or a prefix that matches only one
	 * player. A name that could be several is refused with the candidates, never guessed between.
	 */
	public static CompletableFuture<DiscordAnswer<DiscordProfile>> profile(DiscordUser user, String player) {
		return DiscordGate.profile(user, player);
	}

	public static CompletableFuture<DiscordAnswer<List<DiscordPunishment>>> history(DiscordUser user, String player) {
		return DiscordGate.history(user, player);
	}

	/** A player's notes, newest first, retracted ones included and marked. The player by name or by id. */
	public static CompletableFuture<DiscordAnswer<List<DiscordNote>>> notes(DiscordUser user, String player) {
		return DiscordGate.notes(user, player);
	}

	public static CompletableFuture<DiscordResult> addNote(DiscordUser user, String player, String text) {
		return DiscordGate.addNote(user, player, text);
	}

	public static CompletableFuture<DiscordResult> freeze(DiscordUser user, String player) {
		return DiscordGate.freeze(user, player);
	}

	/** Releases a frozen player who is online, by name or by id. */
	public static CompletableFuture<DiscordResult> unfreeze(DiscordUser user, String player) {
		return DiscordGate.unfreeze(user, player);
	}

	/**
	 * Bans a player: permanently with no duration, or for {@code 7d}, {@code 12h} and the like. Through the
	 * punishment service with its rate limit and rank guard, and refused with the reason when either says no.
	 */
	public static CompletableFuture<DiscordResult> ban(DiscordUser user, String player, String duration, String reason) {
		return DiscordGate.punish(user, player, "BAN", duration, reason);
	}

	public static CompletableFuture<DiscordResult> mute(DiscordUser user, String player, String duration, String reason) {
		return DiscordGate.punish(user, player, "MUTE", duration, reason);
	}

	public static CompletableFuture<DiscordResult> warn(DiscordUser user, String player, String reason) {
		return DiscordGate.punish(user, player, "WARN", null, reason);
	}

	public static CompletableFuture<DiscordResult> unban(DiscordUser user, String player, String reason) {
		return DiscordGate.lift(user, player, true, reason);
	}

	public static CompletableFuture<DiscordResult> unmute(DiscordUser user, String player, String reason) {
		return DiscordGate.lift(user, player, false, reason);
	}

	/** What a staff member did in the last {@code days} days, without where they did it from. */
	public static CompletableFuture<DiscordAnswer<List<DiscordStaffAction>>> staffHistory(DiscordUser user,
			String staffName, int days) {
		return DiscordGate.staffHistory(user, staffName, days);
	}

	/** A case by its id, with the latest of its history. */
	public static CompletableFuture<DiscordAnswer<DiscordCase>> caseView(DiscordUser user, String caseId) {
		return DiscordGate.caseView(user, caseId);
	}

	/**
	 * Somebody asking staff for help from the public contact channel, as the Minecraft player they named.
	 * Open to anybody; limited to a few requests an hour per account. Staff are told in game.
	 */
	public static CompletableFuture<DiscordAnswer<DiscordHelpInfo>> helpRequest(DiscordUser asker, String minecraftName,
			String text, long requestId) {
		return DiscordGate.helpRequest(asker, minecraftName, text, requestId);
	}

	/** A staff member joining or closing a help request: {@code "join"} or {@code "close"}. */
	public static CompletableFuture<DiscordResult> helpDesk(DiscordUser user, long requestId, String what) {
		return DiscordGate.helpDesk(user, requestId, what);
	}

	/** A line in a case's history, as {@code /staff case <id> note} writes one. */
	public static CompletableFuture<DiscordResult> caseNote(DiscordUser user, String caseId, String text) {
		return DiscordGate.caseNote(user, caseId, text);
	}

	/** The evidence filed on a case, by the case's id. */
	public static CompletableFuture<DiscordAnswer<List<DiscordEvidence>>> caseEvidence(DiscordUser user,
			String caseId) {
		return DiscordGate.caseEvidence(user, caseId);
	}

	/** Server totals, and one staff member's numbers or, with no name, the busiest staff's. */
	public static CompletableFuture<DiscordAnswer<DiscordAnalytics>> analytics(DiscordUser user, String staffName) {
		return DiscordGate.analytics(user, staffName);
	}

	/**
	 * Where a player went between two times, with the blocks they changed, for drawing as a map. Behind
	 * {@code staff.replay} on both sides, as the in-game replay is; audited. Read off the server thread.
	 */
	public static CompletableFuture<DiscordAnswer<DiscordReplayTrack>> replayTrack(DiscordUser user, String player,
			long from, long to) {
		return DiscordGate.replayTrack(user, player, from, to);
	}

	/**
	 * The window a piece of replay or dig evidence names, as a track. Needs {@code staff.replay} as well as
	 * the case permission: being able to read a case is not being allowed to see where somebody went.
	 */
	public static CompletableFuture<DiscordAnswer<DiscordReplayTrack>> replayForEvidence(DiscordUser user,
			String caseId, long evidenceId) {
		return DiscordGate.replayForEvidence(user, caseId, evidenceId);
	}

	/**
	 * Files a window of the case's player's movement as evidence, as {@code /staff case <id> evidence replay}
	 * does: a pointer to the recorded history, never a copy of it.
	 */
	public static CompletableFuture<DiscordResult> fileReplayEvidence(DiscordUser user, String caseId, long from,
			long to) {
		return DiscordGate.fileReplayEvidence(user, caseId, from, to);
	}

	/** The longest window a map is drawn for. */
	public static final long MAX_REPLAY_WINDOW_MS = 6L * 3_600_000L;

	/** The permission the punishment panel needs, for deciding which roles see its channel. */
	public static final String PUNISH_PANEL_NODE = io.github.alphain24.staffcore.permission.Nodes.DISCORD_PUNISH_PANEL;

	/**
	 * Offence ids for autocomplete, with what each is called. Only for a linked account allowed to use the
	 * punishment panel.
	 */
	public static CompletableFuture<List<DiscordSuggestion>> suggestOffences(DiscordUser user, String prefix) {
		return DiscordGate.suggestOffences(user, prefix);
	}

	/**
	 * A player's standing on every offence ladder, for the punishment panel. Behind
	 * {@code discord.punishpanel} on both sides, and audited as a lookup.
	 */
	public static CompletableFuture<DiscordAnswer<DiscordLadder>> ladder(DiscordUser user, String player) {
		return DiscordGate.ladder(user, player);
	}

	/**
	 * Punishes a player by an offence's ladder, as the punish screen in game does: the rung their record
	 * reaches, with the offence as the reason. {@code expectedPriors} is the record the person was shown;
	 * if it has changed since, nothing is issued and they are asked to look again.
	 *
	 * @param player the player's id as {@link DiscordLadder#playerId()} gave it, or a name
	 */
	public static CompletableFuture<DiscordResult> punishByOffence(DiscordUser user, String player, String offenceId,
			int expectedPriors) {
		return DiscordGate.punishByOffence(user, player, offenceId, expectedPriors);
	}

	/**
	 * The folder files kept as evidence go in, beside the world; null before the server has started. A
	 * companion writes a file there before filing it, named as {@link DiscordEvidenceFile#storedPath} says.
	 */
	public static java.nio.file.Path evidenceFolder() {
		return DiscordGate.evidenceFolder();
	}

	/** The most bytes one kept file may have, whatever a companion is configured to allow. */
	public static final long MAX_EVIDENCE_BYTES = 500L * 1024 * 1024;

	/**
	 * Whether this user may file evidence on this case, asked before anything is downloaded. Counts
	 * against nothing. When it may, the message is the case id as stored, for naming files.
	 */
	public static CompletableFuture<DiscordResult> mayFileEvidence(DiscordUser user, String caseId) {
		return DiscordGate.mayFileEvidence(user, caseId);
	}

	/** Files evidence on a case: behind the case permission and the Discord action limit, and audited. */
	public static CompletableFuture<DiscordResult> fileEvidence(DiscordUser user, DiscordEvidenceFiling filing) {
		return DiscordGate.fileEvidence(user, filing);
	}

	/** One piece of a case's evidence, by its evidence number, with any files kept from Discord. */
	public static CompletableFuture<DiscordAnswer<DiscordEvidenceDetail>> evidenceItem(DiscordUser user, String caseId,
			long evidenceId) {
		return DiscordGate.evidenceItem(user, caseId, evidenceId);
	}

	/**
	 * Where a kept file is, or null when the path is not one a companion could have written: never
	 * anywhere outside {@link #evidenceFolder()}.
	 */
	public static java.nio.file.Path keptFile(DiscordEvidenceFile file) {
		return DiscordGate.keptFile(file);
	}

	/**
	 * Case ids starting with a prefix, live cases first, for autocomplete. Empty for anybody not allowed to
	 * look at cases from Discord.
	 */
	public static CompletableFuture<List<DiscordSuggestion>> suggestCases(DiscordUser user, String prefix) {
		return DiscordGate.suggestCases(user, prefix);
	}

	/** Known player names starting with a prefix, for autocomplete. Empty for anybody who holds nothing. */
	public static CompletableFuture<List<String>> suggestPlayers(DiscordUser user, String prefix) {
		return DiscordGate.suggestPlayers(user, prefix);
	}

	/** Every node StaffCore defines, so a companion can reject a role mapping naming anything else. */
	public static Set<String> knownNodes() {
		return Actor.all();
	}
}
