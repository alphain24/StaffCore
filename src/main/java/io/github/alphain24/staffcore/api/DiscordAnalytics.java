package io.github.alphain24.staffcore.api;

import java.util.List;

/**
 * The analytics summary, as a Discord user with the analytics permission may read it.
 *
 * @param staff one staff member when one was asked about, otherwise the busiest, most first
 */
public record DiscordAnalytics(int punishments, int activeBans, int openReports, int notes, List<Staff> staff) {

	/**
	 * One staff member's numbers.
	 *
	 * @param overturned       their punishments later lifted
	 * @param medianResponseMs typical time from a report arriving to them claiming it, 0 when unmeasured
	 * @param lastSeen         their last logged command, 0 when never
	 */
	public record Staff(String name, int punishments, int reportsHandled, int reportsResolved, int overturned,
			long medianResponseMs, int commands, long lastSeen) {}

	public DiscordAnalytics {
		staff = staff == null ? List.of() : List.copyOf(staff);
	}
}
