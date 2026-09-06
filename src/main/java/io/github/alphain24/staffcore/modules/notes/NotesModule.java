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

	public record Note(long id, String author, String text, long createdAt) {}

	public boolean add(UUID target, String author, String text) {
		Connection c = conn();
		if (c == null) return false;

		try (PreparedStatement ps = c.prepareStatement(
				"INSERT INTO notes (target_uuid, author_name, text, created_at) VALUES (?,?,?,?)")) {
			ps.setString(1, target.toString());
			ps.setString(2, author);
			ps.setString(3, text);
			ps.setLong(4, System.currentTimeMillis());
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
					out.add(new Note(rs.getLong("id"), rs.getString("author_name"),
							rs.getString("text"), rs.getLong("created_at")));
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
	public boolean remove(long id) {
		Connection c = conn();
		if (c == null) return false;
		try (PreparedStatement ps = c.prepareStatement("DELETE FROM notes WHERE id=?")) {
			ps.setLong(1, id);
			return ps.executeUpdate() > 0;
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Notes] remove failed", e);
			return false;
		}
	}

	/** Removes by the 1-based position shown in the chat listing. */
	public boolean removeByIndex(UUID target, int index) {
		List<Note> notes = list(target);
		if (index < 1 || index > notes.size()) return false;
		return remove(notes.get(index - 1).id());
	}

	private static Connection conn() {
		return StaffCore.storage().isReady() ? StaffCore.storage().conn() : null;
	}
}
