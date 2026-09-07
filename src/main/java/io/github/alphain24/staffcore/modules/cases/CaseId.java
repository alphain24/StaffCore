package io.github.alphain24.staffcore.modules.cases;

import io.github.alphain24.staffcore.util.ShortId;

/**
 * Short case identifiers that survive being read aloud.
 * <p>
 * A case id is not a database key that happens to be visible. It is the thing staff type into
 * chat, paste into Discord, and say to each other over voice while looking at two different
 * screens. That makes the alphabet the whole design: an id containing both {@code O} and
 * {@code 0} is one somebody will eventually mistype into a lookup that then reports "no such
 * case", and they will conclude the case is gone rather than that they typed it wrong.
 * <p>
 * The alphabet and the folding live in {@link ShortId}, which appeal codes share — see there
 * for why those four letters are missing.
 * <p>
 * Eight characters from a 32-symbol alphabet is 40 bits. That is roughly a one-in-a-million
 * chance of a collision at fifteen hundred cases and a one-in-a-thousand chance at fifty
 * thousand, so the generator checks rather than trusting the arithmetic — a duplicate id would
 * silently attach evidence to the wrong investigation, which is the worst failure this class
 * has available.
 * <p>
 * <b>Not sequential.</b> Sequential ids leak how many cases a server has opened, and invite
 * guessing at neighbours. Neither is catastrophic, and neither is worth accepting for nothing.
 */
public final class CaseId {
	private CaseId() {}

	/** Long enough that collisions need checking rather than assuming; short enough to type. */
	public static final int LENGTH = 8;

	/** A fresh id. Callers must still confirm it is unused — see {@code CaseStore}. */
	public static String generate() {
		return ShortId.generate(LENGTH);
	}

	/**
	 * Tidies what somebody typed into what was generated.
	 * <p>
	 * Case-insensitive, and the ambiguous characters are folded to their intended twins rather
	 * than rejected: somebody reading {@code 0} aloud will produce {@code O} at the other end
	 * about half the time, and refusing that is refusing the person rather than the typo. This
	 * is why those four letters are absent from the alphabet — it leaves them free to mean
	 * something unambiguous here.
	 *
	 * @return the canonical form, or null when it could not be one of ours
	 */
	public static String normalise(String typed) {
		return ShortId.normalise(typed, LENGTH);
	}

	/** Whether this is something {@link #normalise} would accept. */
	public static boolean isValid(String typed) {
		return normalise(typed) != null;
	}
}
