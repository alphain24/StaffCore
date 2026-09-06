package io.github.alphain24.staffcore.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses punishment lengths: {@code 30m}, {@code 7d}, {@code 1h30m}, {@code perm}.
 * Returns a duration in milliseconds, or {@code null} for permanent.
 */
public final class DurationParser {
	private DurationParser() {}

	private static final Pattern PART = Pattern.compile("(\\d+)\\s*([smhdw])");

	public static Long parse(String input) {
		if (input == null) return null;
		String s = input.trim().toLowerCase(java.util.Locale.ROOT);
		if (s.isEmpty() || s.equals("perm") || s.equals("permanent") || s.equals("forever")) {
			return null;
		}

		Matcher m = PART.matcher(s);
		long total = 0;
		boolean matched = false;
		while (m.find()) {
			matched = true;
			long n = Long.parseLong(m.group(1));
			total += switch (m.group(2)) {
				case "s" -> n * 1_000L;
				case "m" -> n * 60_000L;
				case "h" -> n * 3_600_000L;
				case "d" -> n * 86_400_000L;
				case "w" -> n * 604_800_000L;
				default -> 0L;
			};
		}
		// Unparseable input is treated as permanent rather than as "zero seconds", because
		// a typo that silently expires a ban instantly is worse than one that needs undoing.
		return matched && total > 0 ? total : null;
	}

	/** Absolute expiry timestamp for a spec, or {@code null} for permanent. */
	public static Long expiryFor(String input) {
		Long ms = parse(input);
		return ms == null ? null : System.currentTimeMillis() + ms;
	}
}
