package io.github.alphain24.staffcore.modules.report;

import io.github.alphain24.staffcore.StaffCore;
import io.github.alphain24.staffcore.config.StaffConfig;
import io.github.alphain24.staffcore.module.Module;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Player-filed reports with a claimable staff queue. */
public class ReportModule implements Module {

	@Override
	public String id() {
		return "report";
	}

	@Override
	public String displayName() {
		return "Reports";
	}

	private final Map<UUID, Long> lastReport = new HashMap<>();

	public record Report(long id, String targetName, UUID targetUuid, String reporter,
			String reason, String status, String claimedBy, long createdAt) {}

	public enum Result { OK, ON_COOLDOWN, DUPLICATE, UNAVAILABLE }

	// ------------------------------------------------------------------- filing

	public Result file(UUID reporter, String reporterName, UUID target, String targetName, String reason) {
		Connection c = conn();
		if (c == null) return Result.UNAVAILABLE;

		long now = System.currentTimeMillis();
		long cooldown = StaffConfig.get().reportCooldownSeconds * 1000L;
		Long last = lastReport.get(reporter);
		if (last != null && now - last < cooldown) return Result.ON_COOLDOWN;

		// One open report per target. A second reporter still gets a "thanks" (their
		// report is real information) but we don't split staff attention across duplicates.
		if (hasOpenReport(target)) {
			lastReport.put(reporter, now);
			return Result.DUPLICATE;
		}

		String sql = """
				INSERT INTO reports (target_uuid, target_name, reporter_uuid, reporter_name, reason, status, created_at)
				VALUES (?,?,?,?,?, 'OPEN', ?)
				""";
		try (PreparedStatement ps = c.prepareStatement(sql)) {
			ps.setString(1, target.toString());
			ps.setString(2, targetName);
			ps.setString(3, reporter.toString());
			ps.setString(4, reporterName);
			ps.setString(5, reason);
			ps.setLong(6, now);
			ps.executeUpdate();
			lastReport.put(reporter, now);
			return Result.OK;
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Report] file failed", e);
			return Result.UNAVAILABLE;
		}
	}

	private boolean hasOpenReport(UUID target) {
		Connection c = conn();
		if (c == null) return false;
		try (PreparedStatement ps = c.prepareStatement(
				"SELECT 1 FROM reports WHERE target_uuid=? AND status='OPEN' LIMIT 1")) {
			ps.setString(1, target.toString());
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next();
			}
		} catch (SQLException e) {
			return false;
		}
	}

	// ------------------------------------------------------------------- reading

	/** Open first (oldest at the top), then claimed. Resolved reports drop out. */
	public List<Report> queue() {
		List<Report> out = new ArrayList<>();
		Connection c = conn();
		if (c == null) return out;

		try (PreparedStatement ps = c.prepareStatement(
				"SELECT * FROM reports WHERE status IN ('OPEN','CLAIMED') "
						+ "ORDER BY CASE status WHEN 'OPEN' THEN 0 ELSE 1 END, created_at ASC")) {
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) out.add(map(rs));
			}
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Report] queue failed", e);
		}
		return out;
	}

	public int openCount() {
		Connection c = conn();
		if (c == null) return 0;
		try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM reports WHERE status='OPEN'")) {
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? rs.getInt(1) : 0;
			}
		} catch (SQLException e) {
			return 0;
		}
	}

	// ------------------------------------------------------------------- actions

	public boolean claim(long id, String staffName) {
		return update("UPDATE reports SET status='CLAIMED', claimed_by=? WHERE id=? AND status='OPEN'",
				staffName, id);
	}

	public boolean resolve(long id, String staffName) {
		return update("UPDATE reports SET status='RESOLVED', claimed_by=COALESCE(claimed_by, ?) WHERE id=?",
				staffName, id);
	}

	public boolean unclaim(long id) {
		return update("UPDATE reports SET status='OPEN', claimed_by=NULL WHERE id=? AND status='CLAIMED'",
				null, id);
	}

	private boolean update(String sql, String staffName, long id) {
		Connection c = conn();
		if (c == null) return false;
		try (PreparedStatement ps = c.prepareStatement(sql)) {
			if (staffName == null) {
				ps.setLong(1, id);
			} else {
				ps.setString(1, staffName);
				ps.setLong(2, id);
			}
			return ps.executeUpdate() > 0;
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Report] update failed", e);
			return false;
		}
	}

	// ------------------------------------------------------------------ plumbing

	private Report map(ResultSet rs) throws SQLException {
		return new Report(
				rs.getLong("id"),
				rs.getString("target_name"),
				UUID.fromString(rs.getString("target_uuid")),
				rs.getString("reporter_name"),
				rs.getString("reason"),
				rs.getString("status"),
				rs.getString("claimed_by"),
				rs.getLong("created_at"));
	}

	private static Connection conn() {
		return StaffCore.storage().isReady() ? StaffCore.storage().conn() : null;
	}
}
