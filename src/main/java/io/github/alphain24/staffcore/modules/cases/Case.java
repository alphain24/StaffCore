package io.github.alphain24.staffcore.modules.cases;

import java.util.UUID;

/**
 * One investigation into one player.
 * <p>
 * A case is the thing that turns a scrolling alert channel into something with a memory. It
 * collects the signals about a subject, records every action taken, and stays findable
 * afterwards — so "why was this person banned in March" has an answer that is not somebody's
 * recollection.
 *
 * @param severity the highest confidence among its signals. An integer rather than a label so
 *                 thresholds stay configurable; a label would freeze somebody else's idea of
 *                 what counts as serious into the schema
 */
public record Case(String id, UUID subjectId, String subjectName, Status status, int severity,
		String summary, long openedAt, String openedBy, String assignedTo, Long closedAt,
		String closedBy, String resolution, String serverVersion, String modVersion,
		/**
		 * Why it was closed, as a value. Null for anything closed before the column existed —
		 * which the corpus treats as unknown rather than as innocence, because backfilling it
		 * would invent a judgement nobody made.
		 */
		Resolution resolutionReason) {

	/** Whether this case is evidence the detector was wrong about somebody. */
	public boolean isCorpusNegative() {
		return status == Status.CLEARED && resolutionReason != null
				&& resolutionReason.countsAsNegative();
	}

	/** Opened by the system rather than by a person. */
	public static final String SYSTEM = "SYSTEM";

	/**
	 * Where a case is in its life.
	 * <p>
	 * {@link #STALE} is not a deletion and not a verdict. It means nothing has happened here
	 * for a while, which is worth knowing and is not the same as deciding the player was
	 * innocent — a case that goes quiet because everybody was busy reads exactly like one that
	 * went quiet because there was nothing in it, and only a human can tell those apart.
	 */
	public enum Status {
		/** Signals have arrived; nobody has picked it up. */
		OPEN("open"),
		/** A staff member is working on it. */
		INVESTIGATING("investigating"),
		/** Something was done about it. */
		ACTIONED("actioned"),
		/** Looked at, and there was nothing in it. This is the training data. */
		CLEARED("cleared"),
		/** Went quiet. Never deleted, and never a judgement about the player. */
		STALE("stale");

		private final String stored;

		Status(String stored) {
			this.stored = stored;
		}

		public String stored() {
			return stored;
		}

		public boolean isClosed() {
			return this == ACTIONED || this == CLEARED;
		}

		/** True while the case is still somebody's problem. */
		public boolean isLive() {
			return this == OPEN || this == INVESTIGATING;
		}

		public static Status of(String stored) {
			if (stored != null) {
				for (Status status : values()) {
					if (status.stored.equalsIgnoreCase(stored)) return status;
				}
			}
			// A status this build does not know is treated as live, so a case written by a
			// newer version is still worked on rather than quietly disappearing from the list.
			return OPEN;
		}
	}

	public boolean isOpen() {
		return status.isLive();
	}

	/** {@code A1B2C3D4 — Steve_ (x-ray, 78)} */
	public String headline() {
		return id + " — " + (subjectName == null ? subjectId.toString() : subjectName)
				+ (summary == null || summary.isBlank() ? "" : " (" + summary + ")");
	}
}
