package io.github.alphain24.staffcore.gui;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which case a staff member was looking at when they went to punish its subject.
 * <p>
 * The punishment screens take a player, not a case, and threading a case id through the
 * offence, reason and duration screens would touch every one of them for one piece of context.
 * So the case screen leaves a note here and the two places a punishment is finally applied read
 * it back. The punishment is then linked to the case and noted in its log, exactly as
 * {@code /staff punish ... --case} does.
 * <p>
 * Only for the same subject, and only for a while. A note left by opening a griefing case and
 * wandering off must not attach a ban issued an hour later, about somebody else, to it.
 */
public final class PunishFromCase {
	private PunishFromCase() {}

	private static final long FRESH_MS = 15 * 60_000L;

	private record Note(String caseId, UUID subject, long at) {}

	private static final Map<UUID, Note> NOTES = new ConcurrentHashMap<>();

	/** The viewer is about to punish this case's subject. */
	public static void remember(UUID viewer, String caseId, UUID subject) {
		NOTES.put(viewer, new Note(caseId, subject, System.currentTimeMillis()));
	}

	/** The case to link a punishment of {@code subject} to, or null. */
	public static String caseFor(UUID viewer, UUID subject) {
		Note note = NOTES.get(viewer);
		if (note == null || subject == null) return null;
		if (System.currentTimeMillis() - note.at() > FRESH_MS || !note.subject().equals(subject)) {
			NOTES.remove(viewer, note);
			return null;
		}
		return note.caseId();
	}

	public static void forget(UUID viewer) {
		NOTES.remove(viewer);
	}
}
