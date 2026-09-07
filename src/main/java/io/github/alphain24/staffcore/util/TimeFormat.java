package io.github.alphain24.staffcore.util;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Short, scannable time strings for lore lines — no seconds, no timezone noise. */
public final class TimeFormat {
	private TimeFormat() {}

	private static final DateTimeFormatter STAMP =
			DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

	public static String stamp(long epochMillis) {
		return STAMP.format(Instant.ofEpochMilli(epochMillis));
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

	/** {@code 3d ago}, {@code just now}. */
	public static String ago(long epochMillis) {
		long delta = System.currentTimeMillis() - epochMillis;
		if (delta < 60_000) return "just now";
		return duration(delta) + " ago";
	}

	/** {@code 6h 12m left} or {@code permanent}. */
	public static String remaining(Long expiresAt) {
		if (expiresAt == null) return "permanent";
		long left = expiresAt - System.currentTimeMillis();
		return left <= 0 ? "expired" : duration(left) + " left";
	}
}
