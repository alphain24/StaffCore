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
		try (PreparedStatement ps = c.prepareStatement(sql, java.sql.Statement.RETURN_GENERATED_KEYS)) {
			ps.setString(1, target.toString());
			ps.setString(2, targetName);
			ps.setString(3, reporter.toString());
			ps.setString(4, reporterName);
			ps.setString(5, reason);
			ps.setLong(6, now);
			ps.executeUpdate();
			lastReport.put(reporter, now);

			long reportId = 0;
			try (ResultSet keys = ps.getGeneratedKeys()) {
				if (keys.next()) reportId = keys.getLong(1);
			}
			io.github.alphain24.staffcore.api.StaffCoreApi.publish(
					new io.github.alphain24.staffcore.api.StaffCoreEvent.ReportFiled(now, reportId,
							target, targetName,
							io.github.alphain24.staffcore.module.Mods.punish().historyCount(target),
							reporterName, reason));

			// A player report is a signal like any other, and the one with the best claim to
			// be taken seriously: a human watched something happen and chose to tell somebody.
			// It carries the weight of a report rather than of a heuristic, and it joins an
			// open case about the same player — which is the connection worth having, because
			// "somebody reported them for it" is what turns a marginal detector score into a
			// reason to act.
			// The ten minutes before the report is when whatever was reported happened. Where
			// the player is now is the other half, while they are still there.
			java.util.List<io.github.alphain24.staffcore.modules.cases.CaseEvidence.Draft> drafts =
					new java.util.ArrayList<>();
			net.minecraft.server.MinecraftServer server = io.github.alphain24.staffcore.StaffCore.server();
			net.minecraft.server.level.ServerPlayer online = server == null ? null
					: server.getPlayerList().getPlayer(target);
			String world = online == null ? null
					: io.github.alphain24.staffcore.compat.Mc.dimensionId(online.level());
			drafts.add(io.github.alphain24.staffcore.modules.cases.CaseEvidence.Draft.replay(
					target, targetName, world, online == null ? null : online.blockPosition(),
					now - 10 * 60_000L, now, "the ten minutes before " + reporterName + " reported"));
			if (online != null) {
				drafts.add(io.github.alphain24.staffcore.modules.cases.CaseEvidence.Draft.location(
						target, targetName, world, online.blockPosition(),
						"where " + targetName + " was when reported"));
			}
			io.github.alphain24.staffcore.module.Mods.cases().emit(server,
					io.github.alphain24.staffcore.modules.cases.Signal.Type.REPORT,
					target, targetName,
					io.github.alphain24.staffcore.config.StaffConfig.get().reportSignalConfidence,
					reporterName + " reported: " + reason, "report", drafts);
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
		return changed(id, "CLAIMED", staffName,
				update("UPDATE reports SET status='CLAIMED', claimed_by=? WHERE id=? AND status='OPEN'",
						staffName, id));
	}

	public boolean resolve(long id, String staffName) {
		return changed(id, "RESOLVED", staffName,
				update("UPDATE reports SET status='RESOLVED', claimed_by=COALESCE(claimed_by, ?) WHERE id=?",
						staffName, id));
	}

	public boolean unclaim(long id) {
		return changed(id, "OPEN", null,
				update("UPDATE reports SET status='OPEN', claimed_by=NULL WHERE id=? AND status='CLAIMED'",
						null, id));
	}

	/** Tells companions about a change that happened, and passes the answer through. */
	private static boolean changed(long id, String status, String staffName, boolean happened) {
		if (happened) {
			io.github.alphain24.staffcore.api.StaffCoreApi.publish(
					new io.github.alphain24.staffcore.api.StaffCoreEvent.ReportChanged(
							System.currentTimeMillis(), id, status, staffName));
		}
		return happened;
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
