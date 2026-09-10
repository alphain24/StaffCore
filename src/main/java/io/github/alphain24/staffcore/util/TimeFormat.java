package io.github.alphain24.staffcore.util;

import io.github.alphain24.staffcore.config.StaffConfig;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Time, in the two shapes staff actually need.
 * <p>
 * Relative answers "is this current?" and absolute answers "which incident was this?". A line
 * carrying only one of them is a line somebody has to do arithmetic on: "3 hours ago" is
 * useless in a ticket written tomorrow, and "2026-09-06 14:22" is useless when you are trying
 * to work out whether the thing you are looking at happened during the shift you are reading
 * about. {@link #full} prints both, and that is what chat output should use.
 *
 * <h2>Timezones</h2>
 * Stored times are epoch milliseconds and log lines stay UTC, because a log is read later by
 * somebody in another place and a log that renders in the writer's local time is a log with a
 * silent offset in it. Only display is converted, through {@code displayTimezone}, and the
 * zone is always named in the output so there is never a timestamp whose meaning depends on
 * knowing the config.
 */
public final class TimeFormat {
	private TimeFormat() {}

	/** Absolute times are always written in the display zone and always name it. */
	private static final DateTimeFormatter STAMP =
			DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

	/**
	 * The configured display zone, or UTC if it is unset or unusable.
	 * <p>
	 * Falling back rather than throwing: a bad zone id in a config file should cost you a
	 * preference, not the ability to read a punishment record. The startup check reports it.
	 */
	public static ZoneId zone() {
		return zoneOf(StaffConfig.get().displayTimezone);
	}

	/** Resolves a zone id, or null when it is not one. Used by the startup validation. */
	public static ZoneId zoneOrNull(String id) {
		if (id == null || id.isBlank()) return ZoneId.of("UTC");
		try {
			return ZoneId.of(id.trim());
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static ZoneId zoneOf(String id) {
		ZoneId zone = zoneOrNull(id);
		return zone == null ? ZoneId.of("UTC") : zone;
	}

	/** {@code 2026-09-06 14:22 UTC}. Short, scannable, and unambiguous about its zone. */
	public static String stamp(long epochMillis) {
		ZoneId zone = zone();
		Instant at = Instant.ofEpochMilli(epochMillis);
		return STAMP.withZone(zone).format(at) + " " + abbreviation(zone, at);
	}

	/**
	 * {@code 14:22:07} in the configured zone.
	 * <p>
	 * For places where a full stamp would wrap and the date is already established — a
	 * scoreboard row, a progress line. Never for a log entry or a chat line somebody might
	 * read tomorrow, where a time with no date is a time that could be any day.
	 */
	public static String clock(long epochMillis) {
		return java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")
				.withZone(zone()).format(Instant.ofEpochMilli(epochMillis));
	}

	/** As {@link #stamp}, forced to UTC. For log lines, which are read from anywhere. */
	public static String utcStamp(long epochMillis) {
		return STAMP.withZone(ZoneId.of("UTC")).format(Instant.ofEpochMilli(epochMillis)) + " UTC";
	}

	/**
	 * {@code 3 hours ago (2026-09-06 14:22 UTC)} — the form every chat line should use.
	 * <p>
	 * Both halves, always, because each answers a question the other cannot.
	 */
	public static String full(long epochMillis) {
		return words(epochMillis) + " (" + stamp(epochMillis) + ")";
	}

	/** {@code 7d 4h}, {@code 12m}, {@code 45s}. Never more than two units. */
	public static String duration(long millis) {
		if (millis <= 0) return "0s";

		long seconds = millis / 1000;
		long days = seconds / 86400;
		long hours = (seconds % 86400) / 3600;
		long minutes = (seconds % 3600) / 60;
		long secs = seconds % 60;

		if (days > 0) return hours > 0 ? days + "d " + hours + "h" : days + "d";
		if (hours > 0) return minutes > 0 ? hours + "h " + minutes + "m" : hours + "h";
		if (minutes > 0) return secs > 0 ? minutes + "m " + secs + "s" : minutes + "m";
		return secs + "s";
	}

	/**
	 * {@code 6 days 20 hours}, {@code 7 days}, {@code 30 minutes} — a length, spelled out.
	 * <p>
	 * The compact form above is for lore lines, where the width of a tooltip is the
	 * constraint. This one is for sentences a player reads once, under stress, on a ban
	 * screen: {@code 7d} is a unit somebody has to decode and {@code 7 days} is not.
	 * <p>
	 * Two units rather than one, and no rounding. A single rounded unit has to choose between
	 * understating and overstating, and both are wrong in a way somebody notices: ten days
	 * rounded up to "2 weeks" overstates a ban by four days, and rounded down to "1 week"
	 * understates it by three. The remainder is not noise here — it is the difference between
	 * a player who comes back when they were told to and one who concludes they were lied to.
	 */
	public static String length(long millis) {
		long seconds = Math.max(0, millis) / 1000;
		long days = seconds / 86_400;
		long hours = (seconds % 86_400) / 3_600;
		long minutes = (seconds % 3_600) / 60;
		long secs = seconds % 60;

		if (days > 0) return hours > 0 ? plural(days, "day") + " " + plural(hours, "hour")
				: plural(days, "day");
		if (hours > 0) return minutes > 0 ? plural(hours, "hour") + " " + plural(minutes, "minute")
				: plural(hours, "hour");
		if (minutes > 0) return secs > 0 ? plural(minutes, "minute") + " " + plural(secs, "second")
				: plural(minutes, "minute");
		return plural(secs, "second");
	}

	/** {@code 3d ago}, {@code just now}. The compact relative form, for lore. */
	public static String ago(long epochMillis) {
		long delta = System.currentTimeMillis() - epochMillis;
		if (delta < 60_000) return "just now";
		return duration(delta) + " ago";
	}

	/**
	 * {@code 3 hours ago}, {@code in 2 days}, {@code just now}.
	 * <p>
	 * One coarse unit here on purpose, unlike {@link #length}. This says roughly when
	 * something was, and it sits beside an exact timestamp in {@link #full} — so precision is
	 * the other half's job, and spending words on it here would only make the line long.
	 */
	public static String words(long epochMillis) {
		long delta = System.currentTimeMillis() - epochMillis;
		if (Math.abs(delta) < 60_000) return "just now";
		return delta > 0 ? coarse(delta) + " ago" : "in " + coarse(-delta);
	}

	/** The largest unit that gives a whole number, rounded down. Roughly, and says so. */
	private static String coarse(long millis) {
		long seconds = millis / 1000;
		if (seconds < 3_600) return plural(seconds / 60, "minute");
		if (seconds < 86_400) return plural(seconds / 3_600, "hour");
		if (seconds < 604_800) return plural(seconds / 86_400, "day");
		if (seconds < 2_592_000) return plural(seconds / 604_800, "week");
		if (seconds < 31_536_000) return plural(seconds / 2_592_000, "month");
		return plural(seconds / 31_536_000, "year");
	}

	/** {@code 6h 12m left} or {@code permanent}. */
	public static String remaining(Long expiresAt) {
		if (expiresAt == null) return "permanent";
		long left = expiresAt - System.currentTimeMillis();
		return left <= 0 ? "expired" : duration(left) + " left";
	}

	// ------------------------------------------------------------------ plumbing

	private static String plural(long n, String unit) {
		return n + " " + unit + (n == 1 ? "" : "s");
	}

	/** {@code UTC}, {@code GMT+05:30}. Named so no timestamp is ambiguous. */
	private static String abbreviation(ZoneId zone, Instant at) {
		String id = zone.getId();
		if (id.equals("UTC") || id.equals("Z")) return "UTC";
		return zone.getRules().getOffset(at).getId().equals("Z")
				? "UTC" : "GMT" + zone.getRules().getOffset(at).getId();
	}
}
