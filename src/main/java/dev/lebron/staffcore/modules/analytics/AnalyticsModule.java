package dev.lebron.staffcore.modules.analytics;

import dev.lebron.staffcore.StaffCore;
import dev.lebron.staffcore.module.Module;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;

/**
 * Reads the punishment, report and command logs to answer "who is actually working?".
 * <p>
 * Deliberately blunt: it counts actions, and counting actions rewards volume. Read the
 * leaderboard as a prompt to go look at someone's history, not as a performance review —
 * the staff member with two bans and forty resolved reports is doing the harder job.
 */
public class AnalyticsModule implements Module {

	@Override
	public String id() {
		return "analytics";
	}

	@Override
	public String displayName() {
		return "Analytics";
	}

	/**
	 * One staff member's numbers.
	 *
	 * @param reportsHandled  reports they claimed, whatever happened next
	 * @param reportsResolved reports they claimed that actually reached a conclusion —
	 *                        claiming a queue and leaving it claimed is the failure mode
	 *                        a raw count cannot see
	 * @param overturned      punishments of theirs later revoked, which is the closest thing
	 *                        the database holds to "was this call right"
	 * @param medianResponseMs typical time between a report arriving and them claiming it,
	 *                         or 0 with nothing to measure. Median rather than mean, because
	 *                         one report claimed after a fortnight would swamp an average
	 */
	public record StaffStat(String name, int punishments, int reportsHandled, int reportsResolved,
			int overturned, long medianResponseMs, int commands, long lastSeen) {

		public int total() {
			return punishments + reportsHandled;
		}

		/** Share of claimed reports that were seen through, 0-100. */
		public int followThrough() {
			return reportsHandled == 0 ? 100 : reportsResolved * 100 / reportsHandled;
		}

		/** Share of their punishments later revoked, 0-100. */
		public int overturnRate() {
			return punishments == 0 ? 0 : overturned * 100 / punishments;
		}
	}

	/**
	 * One staff member's whole record in a single object.
	 * <p>
	 * The leaderboard answers "who is doing the most", which is a ranking and a blunt one.
	 * This answers "what has this person actually been doing", which is the question anybody
	 * reviewing a colleague — or defending themselves — really has. A total of forty actions
	 * means one thing if it is forty warnings and quite another if it is forty bans.
	 *
	 * @param byPunishment how many of each punishment type, biggest first
	 * @param byCommand    which staff commands they reach for, biggest first
	 * @param recent       their last few logged commands, newest first
	 */
	public record Snapshot(StaffStat stat, LinkedHashMap<String, Integer> byPunishment,
			LinkedHashMap<String, Integer> byCommand, List<Activity> recent) {

		/** Whether there is anything at all on file for this person. */
		public boolean isEmpty() {
			return stat.total() == 0 && stat.commands() == 0;
		}
	}

	/** One logged command, for the recent-activity strip. */
	public record Activity(String command, long at) {}

	/** Everything known about one staff member, in one pass. */
	public Snapshot snapshot(String staffName) {
		return new Snapshot(
				forStaff(staffName),
				tally("SELECT type, COUNT(*) FROM punishments WHERE staff_name=? GROUP BY type",
						staffName),
				// The first word of the command, so "/staff punish Bob" and "/staff punish Ann"
				// count as the same tool being used twice rather than two different things.
				tally("SELECT command, COUNT(*) FROM command_log WHERE staff_name=? "
						+ "GROUP BY command ORDER BY COUNT(*) DESC LIMIT 40", staffName),
				recentActivity(staffName, 20));
	}

	/** A grouped count query, ordered biggest first. */
	private LinkedHashMap<String, Integer> tally(String sql, String staffName) {
		Map<String, Integer> raw = new HashMap<>();
		Connection c = conn();
		if (c == null) return new LinkedHashMap<>();

		try (PreparedStatement ps = c.prepareStatement(sql)) {
			ps.setString(1, staffName);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					String key = rs.getString(1);
					if (key == null) continue;
					raw.merge(shorten(key), rs.getInt(2), Integer::sum);
				}
			}
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Analytics] breakdown query failed", e);
		}

		LinkedHashMap<String, Integer> sorted = new LinkedHashMap<>();
		raw.entrySet().stream()
				.sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
				.forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
		return sorted;
	}

	/**
	 * Collapses a logged command to the tool it used.
	 * <p>
	 * The log stores whole command lines, which are useful to read and useless to count —
	 * every one is unique because every one names a different player.
	 */
	private static String shorten(String command) {
		if (!command.startsWith("/")) return command;
		String[] parts = command.split("\s+");
		return parts.length >= 2 ? parts[0] + " " + parts[1] : parts[0];
	}

	private List<Activity> recentActivity(String staffName, int limit) {
		List<Activity> out = new ArrayList<>();
		Connection c = conn();
		if (c == null) return out;

		try (PreparedStatement ps = c.prepareStatement(
				"SELECT command, created_at FROM command_log WHERE staff_name=? "
						+ "ORDER BY created_at DESC LIMIT ?")) {
			ps.setString(1, staffName);
			ps.setInt(2, limit);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) out.add(new Activity(rs.getString("command"), rs.getLong("created_at")));
			}
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Analytics] recent activity query failed", e);
		}
		return out;
	}

	public StaffStat forStaff(String staffName) {
		return new StaffStat(staffName,
				count("SELECT COUNT(*) FROM punishments WHERE staff_name=?", staffName),
				count("SELECT COUNT(*) FROM reports WHERE claimed_by=?", staffName),
				count("SELECT COUNT(*) FROM reports WHERE claimed_by=? AND status <> 'OPEN'", staffName),
				count("SELECT COUNT(*) FROM punishments WHERE staff_name=? AND active=0 "
						+ "AND revoked_by IS NOT NULL", staffName),
				medianResponse(staffName),
				count("SELECT COUNT(*) FROM command_log WHERE staff_name=?", staffName),
				lastActivity(staffName));
	}

	/**
	 * Typical delay between a report being filed and this person claiming it.
	 * <p>
	 * Reports do not record when they were claimed, only when they were filed, so the
	 * closest honest measure is against the staff member's own next logged command. That is
	 * an approximation and is presented as one — it separates somebody working the queue as
	 * it fills from somebody clearing it once a week, which is the distinction worth having,
	 * and it does not pretend to more precision than that.
	 */
	private long medianResponse(String staffName) {
		Connection c = conn();
		if (c == null) return 0L;

		String sql = """
				SELECT r.created_at,
				       (SELECT MIN(cl.created_at) FROM command_log cl
				         WHERE cl.staff_name = r.claimed_by AND cl.created_at >= r.created_at)
				       AS responded
				FROM reports r
				WHERE r.claimed_by = ?
				""";
		List<Long> gaps = new ArrayList<>();
		try (PreparedStatement ps = c.prepareStatement(sql)) {
			ps.setString(1, staffName);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					long responded = rs.getLong("responded");
					if (rs.wasNull() || responded <= 0) continue;
					gaps.add(responded - rs.getLong("created_at"));
				}
			}
		} catch (SQLException e) {
			return 0L;
		}

		if (gaps.isEmpty()) return 0L;
		gaps.sort(Long::compare);
		return gaps.get(gaps.size() / 2);
	}

	/** Every staff member who has ever done anything, busiest first. */
	public List<StaffStat> leaderboard(int limit) {
		List<StaffStat> out = new ArrayList<>();
		Connection c = conn();
		if (c == null) return out;

		String sql = """
				SELECT staff_name, COUNT(*) AS n FROM punishments
				WHERE staff_name IS NOT NULL
				GROUP BY staff_name
				ORDER BY n DESC
				LIMIT ?
				""";
		try (PreparedStatement ps = c.prepareStatement(sql)) {
			ps.setInt(1, limit);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					out.add(forStaff(rs.getString("staff_name")));
				}
			}
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Analytics] leaderboard failed", e);
		}
		return out;
	}

	/** Most recent logged command, or 0 when they have never run one. */
	public long lastActivity(String staffName) {
		Connection c = conn();
		if (c == null) return 0L;
		try (PreparedStatement ps = c.prepareStatement(
				"SELECT MAX(created_at) FROM command_log WHERE staff_name=?")) {
			ps.setString(1, staffName);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? rs.getLong(1) : 0L;
			}
		} catch (SQLException e) {
			return 0L;
		}
	}

	public boolean isInactive(String staffName, long sinceMs) {
		long last = lastActivity(staffName);
		return last == 0L || last < System.currentTimeMillis() - sinceMs;
	}

	/** Records a staff command so the activity numbers have something to read. */
	public void logCommand(String staffName, String command) {
		Connection c = conn();
		if (c == null) return;
		try (PreparedStatement ps = c.prepareStatement(
				"INSERT INTO command_log (staff_name, command, created_at) VALUES (?,?,?)")) {
			ps.setString(1, staffName);
			ps.setString(2, command.length() > 256 ? command.substring(0, 256) : command);
			ps.setLong(3, System.currentTimeMillis());
			ps.executeUpdate();
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Analytics] command log failed", e);
		}
	}

	/** Server-wide totals for the analytics header. */
	public record Totals(int punishments, int activeBans, int openReports, int notes) {}

	public Totals totals() {
		return new Totals(
				scalar("SELECT COUNT(*) FROM punishments"),
				scalar("SELECT COUNT(*) FROM punishments WHERE active=1 AND type IN ('BAN','TEMPBAN')"),
				scalar("SELECT COUNT(*) FROM reports WHERE status='OPEN'"),
				scalar("SELECT COUNT(*) FROM notes"));
	}

	// ------------------------------------------------------------------ plumbing

	private int count(String sql, String arg) {
		Connection c = conn();
		if (c == null) return 0;
		try (PreparedStatement ps = c.prepareStatement(sql)) {
			ps.setString(1, arg);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? rs.getInt(1) : 0;
			}
		} catch (SQLException e) {
			return 0;
		}
	}

	private int scalar(String sql) {
		Connection c = conn();
		if (c == null) return 0;
		try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
			return rs.next() ? rs.getInt(1) : 0;
		} catch (SQLException e) {
			return 0;
		}
	}

	private static Connection conn() {
		return StaffCore.storage().isReady() ? StaffCore.storage().conn() : null;
	}
}
