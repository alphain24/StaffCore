package io.github.alphain24.staffcore.modules.cases;

import java.util.Locale;

/**
 * Why a case was closed, and whether that answer is worth learning from.
 *
 * <h2>The problem this solves</h2>
 * Cleared cases are the training data a detection threshold is validated against: each one is a
 * player the detector flagged and a human then decided was fine. That is only true of some of
 * them.
 * <p>
 * "I looked at the tunnel and they were following a vein they could see" and "nobody got round
 * to this and it aged out" both leave a case marked cleared. They are completely different
 * labels wearing the same status, and only the first is evidence about the detector. With one
 * value for both, the corpus quietly fills with the second kind — because on a busy server the
 * second kind is far more common — and a threshold validated against it drifts towards
 * whatever staff had time for rather than towards what was true.
 * <p>
 * So a clear carries a reason, and only a deliberate one counts as a negative. Everything else
 * is excluded from the corpus rather than counted as innocence.
 *
 * <h2>Excluded is not the same as ignored</h2>
 * An excluded case is still a case, still readable, still in the log. What it is not is a vote
 * about whether the detector was right, because nobody ever formed an opinion on that.
 */
public enum Resolution {

	/**
	 * Somebody looked at the evidence and concluded the player was not cheating.
	 * <p>
	 * The only clear that counts as a negative, and the only one that should. It is a human
	 * saying the detector was wrong about this person, which is exactly the thing a threshold
	 * needs to be measured against.
	 */
	INVESTIGATED_INNOCENT("investigated", "Looked into it — they were not cheating", true),

	/**
	 * Somebody looked and could not tell.
	 * <p>
	 * Excluded, and it is the exclusion most worth having. An inconclusive case is not evidence
	 * the detector was wrong; counting it as one would train the threshold to fire less often
	 * on exactly the cases that are hardest to judge, which is the population where it is most
	 * useful.
	 */
	INVESTIGATED_UNCLEAR("unclear", "Looked into it — could not tell either way", false),

	/**
	 * Closed without anybody investigating.
	 * <p>
	 * Honest and common. Staff triage, and a case closed because there were three more urgent
	 * ones says nothing about the player it was about.
	 */
	NOT_INVESTIGATED("not-investigated", "Closed without investigating", false),

	/** The player left and is not coming back. Says nothing about whether they cheated. */
	SUBJECT_LEFT("left", "Subject left the server", false),

	/** The same incident as another case. Counting it twice would weight one event double. */
	DUPLICATE("duplicate", "Duplicate of another case", false),

	/**
	 * Aged out with no activity.
	 * <p>
	 * Set by the staleness sweep rather than by a person, which is the whole reason it cannot
	 * count: there is no judgement in it at all.
	 */
	STALE("stale", "Went stale — nobody acted on it", false);

	private final String stored;
	private final String label;
	private final boolean corpusNegative;

	Resolution(String stored, String label, boolean corpusNegative) {
		this.stored = stored;
		this.label = label;
		this.corpusNegative = corpusNegative;
	}

	public String stored() {
		return stored;
	}

	public String label() {
		return label;
	}

	/**
	 * Whether this case is evidence that the detector was wrong.
	 * <p>
	 * True for exactly one value. If a second one is ever added here, the thing to ask is
	 * whether a human actually formed an opinion about the player — not whether the case
	 * looks resolved.
	 */
	public boolean countsAsNegative() {
		return corpusNegative;
	}

	/** Whether somebody actually looked, which is a weaker claim than being a negative. */
	public boolean wasInvestigated() {
		return this == INVESTIGATED_INNOCENT || this == INVESTIGATED_UNCLEAR;
	}

	/**
	 * Reads a stored value back.
	 * <p>
	 * Returns null rather than guessing. A row whose reason cannot be read is one the corpus
	 * must leave out — silently mapping it to the one value that counts as a negative is how a
	 * database migration turns into a shifted threshold.
	 */
	public static Resolution of(String stored) {
		if (stored == null) return null;
		String trimmed = stored.trim().toLowerCase(Locale.ROOT);

		for (Resolution resolution : values()) {
			if (resolution.stored.equals(trimmed) || resolution.name().equalsIgnoreCase(trimmed)) {
				return resolution;
			}
		}
		return null;
	}

	/** For the command's suggestions and for the error when somebody mistypes one. */
	public static String names() {
		StringBuilder out = new StringBuilder();
		for (Resolution resolution : values()) {
			if (!out.isEmpty()) out.append(", ");
			out.append(resolution.stored);
		}
		return out.toString();
	}
}
