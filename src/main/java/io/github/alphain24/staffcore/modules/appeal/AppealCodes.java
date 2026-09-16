package io.github.alphain24.staffcore.modules.appeal;

import io.github.alphain24.staffcore.StaffCore;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Every appeal code a punishment has had, and whether each still works.
 * <p>
 * A code used to belong to its punishment for as long as the punishment lasted, so an appeal that had
 * been decided could be filed again with the same code once the wait was over, and the wait was the
 * same for everybody. Now a decision ends the code. An accepted appeal lifts the punishment, and its
 * code stops working with it. A rejected one ends the code and issues a new one that works from the
 * day staff chose, and that new code is what the ban screen shows. A close without a verdict, or an
 * appeal gone stale, leaves the code as it was: nobody decided anything.
 * <p>
 * Rows are never deleted. A code that stopped working is retired, with when, by whom and why, so a
 * player quoting an old code can be told what happened to it.
 */
public final class AppealCodes {

	public static final String TABLE = """
			CREATE TABLE IF NOT EXISTS appeal_codes (
			    code          TEXT    PRIMARY KEY,
			    punishment_id INTEGER NOT NULL,
			    issued_at     INTEGER NOT NULL,
			    usable_from   INTEGER NOT NULL,
			    retired_at    INTEGER,
			    retired_by    TEXT,
			    retired_why   TEXT
			)
			""";
	public static final String INDEX =
			"CREATE INDEX IF NOT EXISTS idx_appeal_codes_punishment ON appeal_codes(punishment_id, retired_at)";

	/** Every code issued before this table existed, as issued and usable from the day it was. */
	public static final String BACKFILL = """
			INSERT OR IGNORE INTO appeal_codes (code, punishment_id, issued_at, usable_from)
			SELECT appeal_code, id, created_at, created_at FROM punishments
			WHERE appeal_code IS NOT NULL AND appeal_code <> ''
			""";

	/** Where a code stands. */
	public enum State {
		/** It files an appeal now. */
		USABLE,
		/** It will, from {@link Code#usableFrom}: staff rejected an appeal and set a wait. */
		WAITING,
		/** It never will again: the appeal it was used for was decided. */
		RETIRED
	}

	/**
	 * One code.
	 *
	 * @param legacy true for a code only the punishment row knows about, from before this table
	 */
	public record Code(String code, long punishmentId, long issuedAt, long usableFrom, Long retiredAt,
			String retiredWhy, boolean legacy) {

		public State state(long now) {
			if (retiredAt != null) return State.RETIRED;
			return usableFrom > now ? State.WAITING : State.USABLE;
		}
	}

	/** Records a code for a punishment. Part of issuing it, on the same connection. */
	public static void issue(Connection c, String code, long punishmentId, long issuedAt, long usableFrom)
			throws SQLException {
		try (PreparedStatement ps = c.prepareStatement(
				"INSERT INTO appeal_codes (code, punishment_id, issued_at, usable_from) VALUES (?,?,?,?)")) {
			ps.setString(1, code);
			ps.setLong(2, punishmentId);
			ps.setLong(3, issuedAt);
			ps.setLong(4, usableFrom);
			ps.executeUpdate();
		}
	}

	/** What a typed code is, or null when it is not one this server issued. */
	public Code lookup(String typed) {
		String code = AppealCode.normalise(typed);
		Connection c = conn();
		if (code == null || c == null) return null;
		try (PreparedStatement ps = c.prepareStatement("SELECT * FROM appeal_codes WHERE code=?")) {
			ps.setString(1, code);
			try (ResultSet rs = ps.executeQuery()) {
				if (rs.next()) return read(rs);
			}
			// A code the punishment row has and this table does not: issued before the table, on a
			// database the backfill missed. Usable as it always was — unless the punishment has codes
			// here now, in which case a decision has already replaced it.
			long punishmentId;
			long createdAt;
			try (PreparedStatement legacy = c.prepareStatement(
					"SELECT id, created_at FROM punishments WHERE appeal_code=?")) {
				legacy.setString(1, code);
				try (ResultSet rs = legacy.executeQuery()) {
					if (!rs.next()) return null;
					punishmentId = rs.getLong("id");
					createdAt = rs.getLong("created_at");
				}
			}
			boolean replaced = tracked(punishmentId);
			return new Code(code, punishmentId, createdAt, createdAt, replaced ? createdAt : null,
					replaced ? "replaced by a newer code" : null, true);
		} catch (SQLException e) {
			StaffCore.LOGGER.warn("[Appeal] could not look up an appeal code: {}", e.getMessage());
			return null;
		}
	}

	/** The code a punishment has now, usable or waiting, or null when it has none left. */
	public Code current(long punishmentId) {
		Connection c = conn();
		if (c == null) return null;
		try (PreparedStatement ps = c.prepareStatement("SELECT * FROM appeal_codes WHERE punishment_id=? "
				+ "AND retired_at IS NULL ORDER BY issued_at DESC LIMIT 1")) {
			ps.setLong(1, punishmentId);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? read(rs) : null;
			}
		} catch (SQLException e) {
			StaffCore.LOGGER.warn("[Appeal] could not read punishment {}'s appeal code: {}", punishmentId,
					e.getMessage());
			return null;
		}
	}

	/** Whether the table knows any code of this punishment's. */
	private boolean tracked(long punishmentId) {
		Connection c = conn();
		if (c == null) return false;
		try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM appeal_codes WHERE punishment_id=? LIMIT 1")) {
			ps.setLong(1, punishmentId);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next();
			}
		} catch (SQLException e) {
			return false;
		}
	}

	/**
	 * Ends every working code a punishment has.
	 *
	 * @return how many were ended
	 */
	public int retire(long punishmentId, String by, String why) {
		Connection c = conn();
		if (c == null) return 0;
		try (PreparedStatement ps = c.prepareStatement("UPDATE appeal_codes SET retired_at=?, retired_by=?, "
				+ "retired_why=? WHERE punishment_id=? AND retired_at IS NULL")) {
			ps.setLong(1, System.currentTimeMillis());
			ps.setString(2, by);
			ps.setString(3, why);
			ps.setLong(4, punishmentId);
			return ps.executeUpdate();
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Appeal] could not retire punishment {}'s appeal code", punishmentId, e);
			return 0;
		}
	}

	/**
	 * Ends the working code and issues a new one that works from {@code usableFrom}, in one transaction:
	 * a punishment is never left with two working codes, or with none because the second half failed.
	 *
	 * @return the new code, or null when it could not be made
	 */
	public Code rotate(long punishmentId, String by, String why, long usableFrom) {
		if (conn() == null) return null;
		String code = AppealCode.generate();
		long now = System.currentTimeMillis();
		boolean done;
		try {
			done = StaffCore.storage().inTransaction(c -> {
				try (PreparedStatement ps = c.prepareStatement("UPDATE appeal_codes SET retired_at=?, "
						+ "retired_by=?, retired_why=? WHERE punishment_id=? AND retired_at IS NULL")) {
					ps.setLong(1, now);
					ps.setString(2, by);
					ps.setString(3, why);
					ps.setLong(4, punishmentId);
					ps.executeUpdate();
				}
				issue(c, code, punishmentId, now, usableFrom);
			});
		} catch (RuntimeException e) {
			StaffCore.LOGGER.error("[Appeal] could not issue a new appeal code for punishment {}", punishmentId, e);
			return null;
		}
		return done ? new Code(code, punishmentId, now, usableFrom, null, null, false) : null;
	}

	private static Code read(ResultSet rs) throws SQLException {
		long retired = rs.getLong("retired_at");
		Long retiredAt = rs.wasNull() ? null : retired;
		return new Code(rs.getString("code"), rs.getLong("punishment_id"), rs.getLong("issued_at"),
				rs.getLong("usable_from"), retiredAt, rs.getString("retired_why"), false);
	}

	private static Connection conn() {
		return StaffCore.storage().isReady() ? StaffCore.storage().conn() : null;
	}
}
