package io.github.alphain24.staffcore.util;

import java.security.SecureRandom;
import java.util.Locale;

/**
 * Identifiers that survive being read aloud, typed from a screenshot, and mistyped.
 * <p>
 * A case id and an appeal code are not database keys that happen to be visible. They are the
 * things staff say to each other over voice while looking at two different screens, and that a
 * banned player transcribes by hand from a photograph of a disconnect screen. That makes the
 * alphabet the whole design: an id containing both {@code O} and {@code 0} is one somebody
 * will eventually mistype into a lookup that reports "no such thing", and they will conclude
 * the thing is gone rather than that they typed it wrong.
 * <p>
 * So this is Crockford's base32 — the digits plus the letters, minus {@code I}, {@code L},
 * {@code O} and {@code U}. The first three go because they are unreadable next to {@code 1}
 * and {@code 0}; {@code U} goes because dropping it means no id can accidentally spell an
 * obscenity, which matters for something staff read out.
 * <p>
 * Their absence is what makes {@link #normalise} possible: those four letters are free to mean
 * something unambiguous, so a {@code O} typed for a {@code 0} is folded rather than rejected.
 * Refusing it would be refusing the person rather than the typo.
 * <p>
 * <b>Never sequential.</b> Sequential ids leak how many of a thing a server has, and invite
 * guessing at neighbours. Neither is catastrophic and neither is worth accepting for nothing.
 */
public final class ShortId {
	private ShortId() {}

	/** Crockford base32: no {@code I}, {@code L}, {@code O} or {@code U}. */
	public static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

	/**
	 * {@link SecureRandom} rather than {@link java.util.Random}.
	 * <p>
	 * A case id only needs to be unique, but an appeal code needs to be unguessable, and the
	 * two share this generator. Using the predictable one here would make appeal codes
	 * enumerable from a single observed value — which is the failure where somebody else's
	 * appeal gets filed by a stranger.
	 */
	private static final SecureRandom RANDOM = new SecureRandom();

	/** {@code length} random symbols. Callers that need uniqueness must still check. */
	public static String generate(int length) {
		StringBuilder id = new StringBuilder(length);
		for (int i = 0; i < length; i++) {
			id.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
		}
		return id.toString();
	}

	/**
	 * Tidies what somebody typed into what was generated.
	 * <p>
	 * Case-insensitive; hyphens and spaces are dropped, so a code printed in readable groups
	 * can be typed back either way; and the four ambiguous letters fold to their intended
	 * twins.
	 *
	 * @return the canonical form, or null when it could not be one of ours
	 */
	public static String normalise(String typed, int length) {
		if (typed == null) return null;

		String trimmed = typed.trim().toUpperCase(Locale.ROOT).replace("-", "").replace(" ", "");
		if (trimmed.length() != length) return null;

		StringBuilder out = new StringBuilder(length);
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

	/** Breaks a code into groups of four, so a person can copy it without losing their place. */
	public static String grouped(String id) {
		if (id == null) return null;

		StringBuilder out = new StringBuilder(id.length() + id.length() / 4);
		for (int i = 0; i < id.length(); i++) {
			if (i > 0 && i % 4 == 0) out.append('-');
			out.append(id.charAt(i));
		}
		return out.toString();
	}
}
