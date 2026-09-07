package io.github.alphain24.staffcore.modules.notes;

import io.github.alphain24.staffcore.StaffCore;
import io.github.alphain24.staffcore.module.Module;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Per-player sticky records that follow someone across staff shifts. */
public class NotesModule implements Module {

	@Override
	public String id() {
		return "notes";
	}

	@Override
	public String displayName() {
		return "Notes";
	}

	public record Note(long id, String author, String text, long createdAt, String caseId,
			Long retractedAt, String retractedBy) {

		/** True when a staff member said they no longer stand behind it. */
		public boolean isRetracted() {
			return retractedAt != null;
		}
	}

	public boolean add(UUID target, String author, String text) {
		return add(target, author, text, null);
	}

	/**
	 * Adds a note, optionally tied to the case it came out of.
	 * <p>
	 * The case is optional and stays optional. A staff member who notices something worth
	 * writing down should not have to open an investigation first — most notes are context
	 * rather than evidence, and requiring a case would either produce empty cases or stop the
	 * note being written.
	 */
	public boolean add(UUID target, String author, String text, String caseId) {
		Connection c = conn();
		if (c == null) return false;

		try (PreparedStatement ps = c.prepareStatement(
				"INSERT INTO notes (target_uuid, author_name, text, created_at, case_id) "
						+ "VALUES (?,?,?,?,?)")) {
			ps.setString(1, target.toString());
			ps.setString(2, author);
			ps.setString(3, text);
			ps.setLong(4, System.currentTimeMillis());
			ps.setString(5, caseId);
			ps.executeUpdate();
			return true;
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Notes] add failed", e);
			return false;
		}
	}

	/** Newest first. */
	public List<Note> list(UUID target) {
		List<Note> out = new ArrayList<>();
		Connection c = conn();
		if (c == null) return out;

		try (PreparedStatement ps = c.prepareStatement(
				"SELECT * FROM notes WHERE target_uuid=? ORDER BY created_at DESC")) {
			ps.setString(1, target.toString());
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					long retracted = rs.getLong("retracted_at");
					out.add(new Note(rs.getLong("id"), rs.getString("author_name"),
							rs.getString("text"), rs.getLong("created_at"),
							rs.getString("case_id"),
							rs.wasNull() ? null : retracted, rs.getString("retracted_by")));
				}
			}
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Notes] list failed", e);
		}
		return out;
	}

	public int count(UUID target) {
		Connection c = conn();
		if (c == null) return 0;
		try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM notes WHERE target_uuid=?")) {
			ps.setString(1, target.toString());
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? rs.getInt(1) : 0;
			}
		} catch (SQLException e) {
			return 0;
		}
	}

	/**
	 * Deletes by primary key. The GUI hands us the id straight off the icon it clicked,
	 * so there is no index-drift window between rendering the list and acting on it.
	 */
	/**
	 * Marks a note as withdrawn. <b>Never deletes it.</b>
	 * <p>
	 * This used to be a {@code DELETE}, and the difference matters more than it sounds. A note
	 * said something about a player at a moment, and one that vanishes takes that with it: the
	 * player's record silently improves, and "what did we know at the time" stops having an
	 * answer — which is exactly the question an appeal asks six months later.
	 * <p>
	 * Retracting is also a more honest claim. It says a staff member no longer stands behind
	 * what they wrote, which is different from the note never having existed, and the second
	 * staff member reading the file should be able to tell those apart.
	 *
	 * @param by who withdrew it
	 */
	public boolean retract(long id, String by) {
		Connection c = conn();
		if (c == null) return false;
		try (PreparedStatement ps = c.prepareStatement(
				"UPDATE notes SET retracted_at=?, retracted_by=? WHERE id=? AND retracted_at IS NULL")) {
			ps.setLong(1, System.currentTimeMillis());
			ps.setString(2, by);
			ps.setLong(3, id);
			return ps.executeUpdate() > 0;
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Notes] retract failed", e);
			return false;
		}
	}

	/** Retracts by the 1-based position shown in the chat listing. */
	public boolean retractByIndex(UUID target, int index, String by) {
		List<Note> notes = list(target);
		if (index < 1 || index > notes.size()) return false;
		return retract(notes.get(index - 1).id(), by);
	}

	private static Connection conn() {
		return StaffCore.storage().isReady() ? StaffCore.storage().conn() : null;
	}
}
