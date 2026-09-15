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

	/** A player filed a report. */
	record ReportFiled(long at, long id, UUID targetId, String targetName, int priorPunishments,
			String reporterName, String reason) implements StaffCoreEvent {}

	/**
	 * A report changed hands or was closed.
	 *
	 * @param status {@code CLAIMED}, {@code OPEN} or {@code RESOLVED}
	 */
	record ReportChanged(long at, long id, String status, String staffName) implements StaffCoreEvent {}

	/**
	 * A detector raised a signal.
	 *
	 * @param confidence 0-99, the severity the case model and the alert threshold work in
	 * @param summary    what staff are told about it in game
	 * @param caseId     the case it landed in, or null when it was too weak to open one
	 */
	record SignalRaised(long at, String type, UUID subjectId, String subjectName, int confidence,
			String summary, String caseId, boolean openedCase) implements StaffCoreEvent {}

	/**
	 * A staff member did something that went into the audit log.
	 *
	 * @param action the command or screen action, as recorded
	 */
	record StaffAction(long at, String staffName, String action, String caseId)
			implements StaffCoreEvent {}

	/**
	 * A line in staff chat.
	 *
	 * @param fromDiscord true when it came in through a companion, so it is not sent back out
	 */
	record StaffChat(long at, String senderName, String message, boolean fromDiscord)
			implements StaffCoreEvent {}
}
