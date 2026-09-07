package io.github.alphain24.staffcore.util;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Punishment lengths: {@code 30m}, {@code 7d}, {@code 1h30m}, {@code 2w3d}, {@code perm}.
 *
 * <h2>Unparseable input is refused, not interpreted</h2>
 * This used to treat anything it could not read as permanent, on the reasoning that a typo
 * which instantly expires a ban is worse than one that needs undoing. The reasoning was sound
 * and the conclusion was still wrong: both of those are failures, and there is a third option
 * where neither happens. {@code /staff ban Steve 7dd griefing} should say so rather than
 * quietly issue a permanent ban that nobody meant and nobody notices until the appeal.
 * <p>
 * The distinction matters because "permanent" and "I could not read that" arrive at the same
 * place — a null duration — and are completely different intentions. {@link #of} keeps them
 * apart; nothing else can, which is why it is the only way in.
 */
public final class DurationParser {
	private DurationParser() {}

	private static final Pattern PART = Pattern.compile("(\\d+)\\s*([smhdw])");

	/** Every way of saying "no end", so none of them is read as a typo. */
	private static final java.util.Set<String> FOREVER =
			java.util.Set.of("perm", "permanent", "forever", "inf", "infinite", "never");

	/**
	 * What a length string turned out to mean.
	 *
	 * @param millis  the length, or null for permanent — only meaningful when {@code valid}
	 * @param problem why it could not be read, or null when it could
	 */
	public record Parsed(boolean valid, Long millis, String problem) {

		/** Permanent: a real answer, deliberately not the same as an unreadable one. */
		public boolean isPermanent() {
			return valid && millis == null;
		}

		static Parsed permanent() {
			return new Parsed(true, null, null);
		}

		static Parsed of(long millis) {
			return new Parsed(true, millis, null);
		}

		static Parsed rejected(String problem) {
			return new Parsed(false, null, problem);
		}
	}

	/**
	 * Reads a length, or says why it could not.
	 * <p>
	 * The refusal message names what was typed and what would have worked, because the person
	 * reading it is mid-command with a player waiting and "invalid duration" costs them a trip
	 * to the documentation.
	 */
	public static Parsed of(String input) {
		if (input == null) return Parsed.rejected("No length given.");

		String s = input.trim().toLowerCase(Locale.ROOT);
		if (s.isEmpty()) return Parsed.rejected("No length given.");
		if (FOREVER.contains(s)) return Parsed.permanent();

		Matcher m = PART.matcher(s);
		long total = 0;
		int consumed = 0;
		boolean matched = false;

		while (m.find()) {
			// Contiguity matters. "7d griefing" would otherwise read as seven days with the
			// reason silently swallowed, and "7dd" as seven days with a stray character
			// nobody notices — both of which are the command doing something other than what
			// was typed.
			if (m.start() != consumed) break;
			consumed = m.end();
			matched = true;

			total += Long.parseLong(m.group(1)) * switch (m.group(2)) {
				case "s" -> 1_000L;
				case "m" -> 60_000L;
				case "h" -> 3_600_000L;
				case "d" -> 86_400_000L;
				case "w" -> 604_800_000L;
				default -> 0L;
			};
		}

		if (!matched || consumed != s.length()) {
			return Parsed.rejected("\"" + input.trim() + "\" is not a length. Use a number and "
					+ "a unit — 30s, 15m, 6h, 7d, 2w — or several together, like 2w3d. For no "
					+ "end, say perm.");
		}
		if (total <= 0) {
			return Parsed.rejected("\"" + input.trim() + "\" is zero. A punishment that expires "
					+ "immediately is not one; use perm for no end, or give a real length.");
		}
		return Parsed.of(total);
	}

	/**
	 * The length in milliseconds, or null for permanent <em>and</em> for unreadable input.
	 * <p>
	 * Only for the places where the string came from configuration that was validated when it
	 * was written — a preset duration, an offence ladder rung. A string a person just typed
	 * must go through {@link #of}, which can tell the two nulls apart.
	 */
	public static Long parseConfigured(String input) {
		Parsed parsed = of(input);
		return parsed.valid() ? parsed.millis() : null;
	}

	/** Absolute expiry for a length, or null for permanent. Configured strings only. */
	public static Long expiryFor(String input) {
		Long ms = parseConfigured(input);
		return ms == null ? null : System.currentTimeMillis() + ms;
	}
}
