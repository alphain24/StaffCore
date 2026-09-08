package io.github.alphain24.staffcore.modules.cases;

import java.util.UUID;

/**
 * One thing a detection subsystem noticed about one player.
 * <p>
 * Before this, every subsystem shouted into staff chat on its own terms: x-ray printed a
 * percentage, alt detection printed a link, contraband printed an item name. Each was
 * reasonable alone and together they were a scrolling list with no memory. Nothing connected
 * three separate half-suspicions about the same player into a reason to look, and a signal
 * anybody missed was gone.
 * <p>
 * A signal is the same observation as a durable row that can be attached to a case. The
 * detector's job ends at "here is what I saw and how sure I am"; deciding whether that is
 * worth a human's attention is the case model's job, and separating the two is what stops
 * every detector inventing its own idea of "important".
 *
 * @param confidence    0-100, and comparable across types on purpose — a 70 from x-ray and a
 *                      70 from an anti-cheat should mean roughly the same amount of "go and
 *                      look", or the auto-open threshold means something different depending
 *                      on which detector fired
 * @param evidenceJson  whatever the detector wants to keep, opaque to everything else. The
 *                      case view renders the headline; this is what somebody reads when the
 *                      headline is not enough
 * @param sourceModule  which subsystem produced it, so a detector that starts misbehaving can
 *                      be found and silenced without losing the rest
 */
public record Signal(long id, String caseId, Type type, UUID subjectId, String subjectName,
		long occurredAt, int confidence, String evidenceJson, String sourceModule) {

	/**
	 * What kind of thing was noticed.
	 * <p>
	 * Deliberately coarse. These group signals in the case view and decide nothing on their
	 * own — the confidence does that — so a finer taxonomy would be detail nobody acts on.
	 */
	public enum Type {
		XRAY("x-ray"),
		ALT_MATCH("linked account"),
		CONTRABAND("contraband"),
		REPORT("player report"),
		ANTICHEAT("anti-cheat"),
		MASS_GRIEF("mass grief"),
		CANARY("canary block"),
		OTHER("other");

		private final String label;

		Type(String label) {
			this.label = label;
		}

		public String label() {
			return label;
		}

		/** Parses a stored value, falling back rather than throwing on an unknown one. */
		public static Type of(String stored) {
			if (stored == null) return OTHER;
			for (Type type : values()) {
				if (type.name().equalsIgnoreCase(stored)) return type;
			}
			// A row written by a newer version, read by an older one. Losing the label is
			// better than losing the row.
			return OTHER;
		}
	}

	/** A signal that has not been written yet, and so has no id and no case. */
	public static Signal of(Type type, UUID subjectId, String subjectName, int confidence,
			String evidenceJson, String sourceModule) {

		return new Signal(0, null, type, subjectId, subjectName, System.currentTimeMillis(),
				clamp(confidence), evidenceJson, sourceModule);
	}

	/**
	 * Confidence is a 0-100 scale and callers get it wrong.
	 * <p>
	 * Clamped rather than rejected: a detector reporting 140 has a bug, and refusing the
	 * signal would lose the observation as well as the bug. It arrives as 100 and the
	 * detector's own tests can find the rest.
	 */
	private static int clamp(int confidence) {
		return Math.max(0, Math.min(100, confidence));
	}

	public boolean isAttached() {
		return caseId != null && !caseId.isBlank();
	}

	/** One line for the case view. */
	public String headline() {
		return type.label() + " (" + confidence + "%)";
	}
}
