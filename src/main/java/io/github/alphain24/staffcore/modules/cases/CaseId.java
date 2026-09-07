package io.github.alphain24.staffcore.modules.cases;

import java.security.SecureRandom;

/**
 * Short case identifiers that survive being read aloud.
 * <p>
 * A case id is not a database key that happens to be visible. It is the thing staff type into
 * chat, paste into Discord, and say to each other over voice while looking at two different
 * screens. That makes the alphabet the whole design: an id containing both {@code O} and
 * {@code 0} is one somebody will eventually mistype into a lookup that then reports "no such
 * case", and they will conclude the case is gone rather than that they typed it wrong.
 * <p>
 * So this is Crockford's base32 alphabet — the digits plus the letters, minus {@code I},
 * {@code L}, {@code O} and {@code U}. The first three go because they are unreadable next to
 * {@code 1} and {@code 0}; {@code U} goes because dropping it means no id can accidentally
 * spell an obscenity, which matters for something staff read out.
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

	/**
	 * Crockford base32: no {@code I}, {@code L}, {@code O} or {@code U}.
	 * <p>
	 * The exclusions are what make an id safe to dictate. Anything that reads ambiguously in a
	 * chat font, or that a person could transcribe wrongly from speech, is not in here.
	 */
	static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

	/** Long enough that collisions need checking rather than assuming; short enough to type. */
	public static final int LENGTH = 8;

	private static final SecureRandom RANDOM = new SecureRandom();

	/** A fresh id. Callers must still confirm it is unused — see {@code CaseStore}. */
	public static String generate() {
		StringBuilder id = new StringBuilder(LENGTH);
		for (int i = 0; i < LENGTH; i++) {
			id.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
		}
		return id.toString();
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
		if (typed == null) return null;

		String trimmed = typed.trim().toUpperCase(java.util.Locale.ROOT)
				.replace("-", "").replace(" ", "");
		if (trimmed.length() != LENGTH) return null;

		StringBuilder out = new StringBuilder(LENGTH);
		for (char c : trimmed.toCharArray()) {
			char folded = switch (c) {
				case 'O' -> '0';
				case 'I', 'L' -> '1';
				case 'U' -> 'V';
				default -> c;
			};
			if (ALPHABET.indexOf(folded) < 0) return null;
			out.append(folded);
		}
		return out.toString();
	}

	/** Whether this is something {@link #normalise} would accept. */
	public static boolean isValid(String typed) {
		return normalise(typed) != null;
	}
}
