package dev.lebron.staffcore.modules.appeal;

import dev.lebron.staffcore.StaffCore;
import dev.lebron.staffcore.module.Module;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Somewhere for a punished player to argue their case.
 * <p>
 * Without this the Discord bridge is one-way: staff can announce a ban but the player has
 * nowhere to answer, so the only move left to them is an alt. An appeal turns that into a
 * conversation with a record attached — and a rejected appeal is itself useful evidence
 * the next time the same person turns up.
 * <p>
 * Muted players can still appeal. A mute stops them talking in chat; it is not meant to
 * stop them contesting the mute.
 */
public class AppealModule implements Module {

	@Override
	public String id() {
		return "appeal";
	}

	@Override
	public String displayName() {
		return "Appeals";
	}

	public record Appeal(long id, UUID targetUuid, String targetName, String text,
			String status, String handledBy, String verdict, long createdAt, Long handledAt) {}

	public enum Result { OK, ALREADY_OPEN, UNAVAILABLE }

	// -------------------------------------------------------------------- filing

	public Result file(UUID target, String targetName, String text) {
		Connection c = conn();
		if (c == null) return Result.UNAVAILABLE;
		if (hasOpen(target)) return Result.ALREADY_OPEN;

		try (PreparedStatement ps = c.prepareStatement(
				"INSERT INTO appeals (target_uuid, target_name, text, status, created_at) "
						+ "VALUES (?,?,?,'OPEN',?)")) {
			ps.setString(1, target.toString());
			ps.setString(2, targetName);
			ps.setString(3, text);
			ps.setLong(4, System.currentTimeMillis());
			ps.executeUpdate();
			return Result.OK;
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Appeal] file failed", e);
			return Result.UNAVAILABLE;
		}
	}

	public boolean hasOpen(UUID target) {
		Connection c = conn();
		if (c == null) return false;
		try (PreparedStatement ps = c.prepareStatement(
				"SELECT 1 FROM appeals WHERE target_uuid=? AND status='OPEN' LIMIT 1")) {
			ps.setString(1, target.toString());
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next();
			}
		} catch (SQLException e) {
			return false;
		}
	}

	// ------------------------------------------------------------------- reading

	/** Open appeals, oldest first — the same fairness rule as the report queue. */
	public List<Appeal> queue() {
		return query("SELECT * FROM appeals WHERE status='OPEN' ORDER BY created_at ASC", null);
	}

	public List<Appeal> forPlayer(UUID target) {
		return query("SELECT * FROM appeals WHERE target_uuid=? ORDER BY created_at DESC",
				target.toString());
	}

	public int openCount() {
		return count("SELECT COUNT(*) FROM appeals WHERE status='OPEN'", null);
	}

	public int openCountFor(UUID target) {
		return count("SELECT COUNT(*) FROM appeals WHERE target_uuid=? AND status='OPEN'",
				target.toString());
	}

	// ------------------------------------------------------------------ verdicts

	public boolean accept(long id, String staffName) {
		return close(id, staffName, "ACCEPTED");
	}

	public boolean reject(long id, String staffName) {
		return close(id, staffName, "REJECTED");
	}

	private boolean close(long id, String staffName, String verdict) {
		Connection c = conn();
		if (c == null) return false;
		try (PreparedStatement ps = c.prepareStatement(
				"UPDATE appeals SET status='CLOSED', verdict=?, handled_by=?, handled_at=? "
						+ "WHERE id=? AND status='OPEN'")) {
			ps.setString(1, verdict);
			ps.setString(2, staffName);
			ps.setLong(3, System.currentTimeMillis());
			ps.setLong(4, id);
			return ps.executeUpdate() > 0;
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Appeal] verdict failed", e);
			return false;
		}
	}

	// ------------------------------------------------------------------ plumbing

	private List<Appeal> query(String sql, String arg) {
		List<Appeal> out = new ArrayList<>();
		Connection c = conn();
		if (c == null) return out;

		try (PreparedStatement ps = c.prepareStatement(sql)) {
			if (arg != null) ps.setString(1, arg);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					long handled = rs.getLong("handled_at");
					out.add(new Appeal(
							rs.getLong("id"),
							UUID.fromString(rs.getString("target_uuid")),
							rs.getString("target_name"),
							rs.getString("text"),
							rs.getString("status"),
							rs.getString("handled_by"),
							rs.getString("verdict"),
							rs.getLong("created_at"),
							rs.wasNull() || handled == 0 ? null : handled));
				}
			}
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Appeal] query failed", e);
		}
		return out;
	}

	private int count(String sql, String arg) {
		Connection c = conn();
		if (c == null) return 0;
		try (PreparedStatement ps = c.prepareStatement(sql)) {
			if (arg != null) ps.setString(1, arg);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? rs.getInt(1) : 0;
			}
		} catch (SQLException e) {
			return 0;
		}
	}

	private static Connection conn() {
		return StaffCore.storage().isReady() ? StaffCore.storage().conn() : null;
	}
}
