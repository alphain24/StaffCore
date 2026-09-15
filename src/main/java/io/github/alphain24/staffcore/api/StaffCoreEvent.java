package io.github.alphain24.staffcore.api;

import java.util.UUID;

/**
 * Something that happened in StaffCore, as a companion mod is told it.
 * <p>
 * Records of plain values, and only the values a companion is allowed to see. There is no field
 * here for anybody's address, session or anything else StaffCore keeps for investigating its own
 * staff: those never leave the server, and the surest way to keep them from being posted
 * somewhere is for them not to be in the thing that gets posted. A test fails if one is added.
 */
public sealed interface StaffCoreEvent {

	/** When it happened, in epoch milliseconds. */
	long at();

	/**
	 * A punishment was issued.
	 *
	 * @param priorPunishments how many the player had before this one
	 * @param expiresAt        null for permanent
	 * @param caseId           the case it was issued from, or null when issued directly
	 */
	record PunishmentIssued(long at, long id, UUID targetId, String targetName, int priorPunishments,
			String type, String reason, String staffName, Long expiresAt, String caseId)
			implements StaffCoreEvent {}

	/** A punishment was lifted. It is marked reversed, never deleted. */
	record PunishmentReversed(long at, long id, UUID targetId, String targetName, String type,
			String reversedBy, String reason, String caseId) implements StaffCoreEvent {}

	/**
	 * A player filed a report.
	 *
	 * @param caseId the case the report opened or joined, or null when it did neither
	 */
	record ReportFiled(long at, long id, UUID targetId, String targetName, int priorPunishments,
			String reporterName, String reason, String caseId) implements StaffCoreEvent {}

	/**
	 * A report changed hands or was closed.
	 *
	 * @param status {@code CLAIMED}, {@code OPEN} or {@code RESOLVED}
	 */
	record ReportChanged(long at, long id, String status, String staffName) implements StaffCoreEvent {}

	/**
	 * A detector raised a signal.
	 *
	 * @param type       the signal type's name, {@code XRAY}, {@code REPORT} and so on
	 * @param confidence 0-99, the severity the case model and the alert threshold work in
	 * @param summary    what staff are told about it in game
	 * @param caseId     the case it landed in, or null when it was too weak to open one
	 */
	record SignalRaised(long at, String type, UUID subjectId, String subjectName, int confidence,
			String summary, String caseId, boolean openedCase) implements StaffCoreEvent {}

	/**
	 * A staff member opened a case by hand. Cases a signal opens arrive as
	 * {@link SignalRaised} with {@code openedCase} set instead.
	 *
	 * @param category the kind of case, as shown in game
	 */
	record CaseOpened(long at, String caseId, UUID subjectId, String subjectName, String category,
			String openedBy, String summary) implements StaffCoreEvent {}

	/**
	 * Something was written into a case's history by a person: a note, an assignment, a change of
	 * status.
	 *
	 * @param kind   {@code note}, {@code assigned}, or the new status: {@code investigating},
	 *               {@code actioned}, {@code cleared}, {@code stale}, {@code open}
	 * @param closed true when this closed the case
	 */
	record CaseChanged(long at, String caseId, String kind, String actor, String body, boolean closed)
			implements StaffCoreEvent {}

	/**
	 * A player appealed a punishment.
	 *
	 * @param punishmentAt     when the appealed punishment was issued
	 * @param evidenceCount    evidence filed on the punishment's case, 0 when it has none
	 * @param linkedDiscordId  the Discord account linked to the player, or null
	 * @param caseId           the punishment's case, or null
	 */
	record AppealFiled(long at, long id, UUID playerId, String playerName, long punishmentId,
			String punishmentType, String punishmentReason, String punishmentBy, long punishmentAt,
			String text, int evidenceCount, String linkedDiscordId, String caseId)
			implements StaffCoreEvent {}

	/**
	 * An appeal was decided.
	 *
	 * @param verdict {@code ACCEPTED} or {@code REJECTED}
	 */
	record AppealDecided(long at, long id, String verdict, String staffName) implements StaffCoreEvent {}

	/**
	 * A staff member did something that went into the audit log.
	 *
	 * @param action the command or screen action, as recorded
	 * @param target the player it names, when it names one StaffCore knows; otherwise null
	 */
	record StaffAction(long at, String staffName, String action, String target, String caseId)
			implements StaffCoreEvent {}

	/**
	 * A line in staff chat.
	 *
	 * @param fromDiscord true when it came in through a companion, so it is not sent back out
	 */
	record StaffChat(long at, String senderName, String message, boolean fromDiscord)
			implements StaffCoreEvent {}
}
