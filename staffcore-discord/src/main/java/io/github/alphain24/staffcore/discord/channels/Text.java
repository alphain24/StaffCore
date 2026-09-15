package io.github.alphain24.staffcore.discord.channels;

import java.util.Locale;

/**
 * Turning StaffCore's text into Discord's, safely.
 * <p>
 * Almost everything posted to Discord started as something a player typed: a report reason, an
 * appeal, a name. Discord reads markdown, masked links and mentions out of text, so a report
 * reason of {@code @everyone [click here](https://…)} would ping the whole server with a link
 * dressed as something else. Every piece of such text goes through {@link #escape} before it is
 * put into a message, and every message is sent with mentions switched off as well — two locks,
 * because a missed escape is the kind of bug nobody notices until it is used.
 */
public final class Text {
	private Text() {}

	/** Zero-width space: breaks {@code @everyone} and friends without changing how they read. */
	private static final String BREAK = "​";

	/** Markdown, link and mention syntax as literal text. Safe on null, which becomes empty. */
	public static String escape(String text) {
		if (text == null) return "";
		StringBuilder out = new StringBuilder(text.length() + 8);
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			switch (c) {
				case '\\', '*', '_', '~', '`', '|', '>', '<', '[', ']', '(', ')', '#', ':' -> out.append('\\').append(c);
				case '@' -> out.append('@').append(BREAK);
				default -> out.append(Character.isISOControl(c) && c != '\n' ? ' ' : c);
			}
		}
		return out.toString();
	}

	/** At most {@code max} characters, ending in an ellipsis when cut. */
	public static String clip(String text, int max) {
		if (text == null) return "";
		if (text.length() <= max) return text;
		return text.substring(0, Math.max(0, max - 1)) + "…";
	}

	/** Escaped, then clipped, so a cut never lands in the middle of an escape. */
	public static String safe(String text, int max) {
		String escaped = escape(text);
		if (escaped.length() <= max) return escaped;
		String cut = escaped.substring(0, Math.max(0, max - 1));
		if (cut.endsWith("\\")) cut = cut.substring(0, cut.length() - 1);
		return cut + "…";
	}

	/** A time every reader sees in their own timezone: the date and time, then how long ago. */
	public static String when(long epochMillis) {
		long seconds = epochMillis / 1000;
		return "<t:" + seconds + ":f> (<t:" + seconds + ":R>)";
	}

	/** Just "3 hours ago" / "in 2 days", in the reader's own terms. */
	public static String relative(long epochMillis) {
		return "<t:" + epochMillis / 1000 + ":R>";
	}

	/** A length of time in at most two units: "7 days", "1 day 6 hours", "30 minutes". */
	public static String duration(long millis) {
		long minutes = Math.max(0, millis) / 60_000L;
		long days = minutes / 1440;
		long hours = (minutes % 1440) / 60;
		long mins = minutes % 60;
		if (days > 0) return plural(days, "day") + (hours > 0 ? " " + plural(hours, "hour") : "");
		if (hours > 0) return plural(hours, "hour") + (mins > 0 ? " " + plural(mins, "minute") : "");
		return plural(Math.max(1, mins), "minute");
	}

	private static String plural(long n, String unit) {
		return n + " " + unit + (n == 1 ? "" : "s");
	}

	/** A punishment type as a reader would say it. */
	public static String punishment(String type) {
		if (type == null) return "Punishment";
		return switch (type) {
			case "BAN" -> "Ban";
			case "TEMPBAN" -> "Temporary ban";
			case "MUTE" -> "Mute";
			case "TEMPMUTE" -> "Temporary mute";
			case "WARN" -> "Warning";
			case "KICK" -> "Kick";
			default -> words(type);
		};
	}

	/** A signal type as a reader would say it. */
	public static String signal(String type) {
		if (type == null) return "Signal";
		return switch (type) {
			case "XRAY" -> "X-ray";
			case "ALT_MATCH" -> "Linked account";
			case "REPORT" -> "Player report";
			case "ANTICHEAT" -> "Anti-cheat";
			case "CANARY" -> "Canary block";
			default -> words(type);
		};
	}

	/** {@code MASS_GRIEF} as "Mass grief". */
	static String words(String constant) {
		String lower = constant.toLowerCase(Locale.ROOT).replace('_', ' ');
		return lower.isEmpty() ? lower : Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
	}
}
