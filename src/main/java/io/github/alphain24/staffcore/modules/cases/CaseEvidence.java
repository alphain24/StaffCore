package io.github.alphain24.staffcore.modules.cases;

import io.github.alphain24.staffcore.StaffCore;
import io.github.alphain24.staffcore.util.TimeFormat;
import net.minecraft.core.BlockPos;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The things a case can point at and open: a replay of the player, the block damage in an
 * area, a place, an inventory snapshot, the dig an x-ray finding was about.
 *
 * <h2>Pointers, not copies</h2>
 * Evidence names a window and a place; it does not copy the replay or the blocks into the case.
 * Opening it reads the same position history and block log everything else reads, under the
 * same retention. That is deliberate: position history is a record of where people have been,
 * kept for as long as the server owner configured and no longer, and a case is not a way round
 * that. So a replay filed as evidence stops playing once its window is past the retention —
 * and says so when it is opened, rather than showing an empty world.
 *
 * <h2>Nothing is deleted</h2>
 * Evidence filed by mistake is retracted, which hides it from the case and keeps the row and who
 * retracted it. A case is a record of an investigation, and "somebody removed the replay" is
 * part of that record.
 */
public final class CaseEvidence {

	public enum Kind {
		/** The player's movement over a window, watched from inside it. */
		REPLAY("Replay"),
		/** Block changes in an area over a window: the grief log, pre-filtered. */
		BLOCKS("Block damage"),
		/** A place, to go and look at. */
		LOCATION("Location"),
		/** An inventory snapshot, by id. */
		SNAPSHOT("Inventory snapshot"),
		/** The biggest dig in a window, stood in with the path drawn. */
		XRAY_DIG("X-ray dig"),
		/** A message or file from Discord, kept. See DiscordEvidenceStore. */
		DISCORD("From Discord");

		private final String label;

		Kind(String label) {
			this.label = label;
		}

		public String label() {
			return label;
		}

		static Kind of(String stored) {
			for (Kind kind : values()) {
				if (kind.name().equalsIgnoreCase(stored)) return kind;
			}
			return LOCATION;
		}
	}

	/**
	 * One piece of evidence.
	 *
	 * @param ref a kind-specific identifier: the snapshot id for {@link Kind#SNAPSHOT}
	 */
	public record Item(long id, String caseId, Kind kind, String label, UUID subjectId,
			String subjectName, String world, BlockPos pos, int radius, long from, long to,
			String ref, long addedAt, String addedBy) {

		/** "Replay of Steve, 14:02-14:09" — what a list row says. */
		public String describe() {
			return switch (kind) {
				case REPLAY, XRAY_DIG -> kind.label() + " of " + subjectName + ", "
						+ TimeFormat.stamp(from) + " for " + TimeFormat.length(to - from);
				case BLOCKS -> kind.label() + " within " + radius + " of " + where() + ", "
						+ TimeFormat.stamp(from) + " for " + TimeFormat.length(to - from);
				case LOCATION -> kind.label() + " " + where();
				case SNAPSHOT -> kind.label() + " #" + ref + " of " + subjectName;
				case DISCORD -> kind.label() + (label == null || label.isBlank() ? "" : ": " + label);
			};
		}

		public String where() {
			return pos == null ? "?" : pos.getX() + ", " + pos.getY() + ", " + pos.getZ()
					+ (world == null ? "" : " in " + world);
		}
	}

	/** Evidence not yet filed: what a detector or a staff member hands over. */
	public record Draft(Kind kind, String label, UUID subjectId, String subjectName,
			String world, BlockPos pos, int radius, long from, long to, String ref) {

		public static Draft replay(UUID subject, String name, String world, BlockPos near,
				long from, long to, String label) {
			return new Draft(Kind.REPLAY, label, subject, name, world, near, 0, from, to, null);
		}

		public static Draft blocks(UUID subject, String name, String world, BlockPos centre,
				int radius, long from, long to, String label) {
			return new Draft(Kind.BLOCKS, label, subject, name, world, centre, radius, from, to,
					null);
		}

		public static Draft location(UUID subject, String name, String world, BlockPos pos,
				String label) {
			long now = System.currentTimeMillis();
			return new Draft(Kind.LOCATION, label, subject, name, world, pos, 0, now, now, null);
		}

		public static Draft snapshot(UUID subject, String name, long snapshotId, String label) {
			long now = System.currentTimeMillis();
			return new Draft(Kind.SNAPSHOT, label, subject, name, null, null, 0, now, now,
					String.valueOf(snapshotId));
		}

		public static Draft xrayDig(UUID subject, String name, long from, long to, String label) {
			return new Draft(Kind.XRAY_DIG, label, subject, name, null, null, 0, from, to, null);
		}
	}

	public static final String TABLE = """
			CREATE TABLE IF NOT EXISTS case_evidence (
			    id           INTEGER PRIMARY KEY AUTOINCREMENT,
			    case_id      TEXT    NOT NULL,
			    kind         TEXT    NOT NULL,
			    label        TEXT,
			    subject_uuid TEXT,
			    subject_name TEXT,
			    world        TEXT,
			    x INTEGER, y INTEGER, z INTEGER,
			    radius       INTEGER NOT NULL DEFAULT 0,
			    from_ms      INTEGER NOT NULL,
			    to_ms        INTEGER NOT NULL,
			    ref          TEXT,
			    added_at     INTEGER NOT NULL,
			    added_by     TEXT    NOT NULL,
			    retracted_at INTEGER,
			    retracted_by TEXT
			)
			""";

	public static final String INDEX =
			"CREATE INDEX IF NOT EXISTS idx_case_evidence_case ON case_evidence(case_id, added_at)";

	private static boolean ready() {
		return StaffCore.storage().isReady();
	}

	/** Files evidence against a case and records that it was filed. */
	public long add(String caseId, Draft draft, String actor) {
		if (!ready() || caseId == null || draft == null) return -1;
		long[] id = { -1 };
		StaffCore.storage().inTransaction(conn -> id[0] = insert(conn, caseId, draft, actor));
		return id[0];
	}

	/** As {@link #add}, on a connection already inside a transaction. */
	static long insert(Connection conn, String caseId, Draft draft, String actor)
			throws SQLException {

		long id;
		try (PreparedStatement ps = conn.prepareStatement("""
				INSERT INTO case_evidence (case_id, kind, label, subject_uuid, subject_name, world,
				                           x, y, z, radius, from_ms, to_ms, ref, added_at, added_by)
				VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
				""", Statement.RETURN_GENERATED_KEYS)) {
			ps.setString(1, caseId);
			ps.setString(2, draft.kind().name());
			ps.setString(3, draft.label());
			ps.setString(4, draft.subjectId() == null ? null : draft.subjectId().toString());
			ps.setString(5, draft.subjectName());
			ps.setString(6, draft.world());
			if (draft.pos() == null) {
				ps.setNull(7, java.sql.Types.INTEGER);
				ps.setNull(8, java.sql.Types.INTEGER);
				ps.setNull(9, java.sql.Types.INTEGER);
			} else {
				ps.setInt(7, draft.pos().getX());
				ps.setInt(8, draft.pos().getY());
				ps.setInt(9, draft.pos().getZ());
			}
			ps.setInt(10, draft.radius());
			ps.setLong(11, draft.from());
			ps.setLong(12, draft.to());
			ps.setString(13, draft.ref());
			ps.setLong(14, System.currentTimeMillis());
			ps.setString(15, actor);
			ps.executeUpdate();
			try (ResultSet keys = ps.getGeneratedKeys()) {
				id = keys.next() ? keys.getLong(1) : -1;
			}
		}

		try (PreparedStatement ps = conn.prepareStatement(
				"INSERT INTO case_events (case_id, at, actor, kind, body) VALUES (?,?,?,?,?)")) {
			ps.setString(1, caseId);
			ps.setLong(2, System.currentTimeMillis());
			ps.setString(3, actor);
			ps.setString(4, "evidence");
			ps.setString(5, "#" + id + " " + draft.kind().label()
					+ (draft.label() == null ? "" : ": " + draft.label()));
			ps.executeUpdate();
		}
		return id;
	}

	/** Every piece of evidence still on a case, oldest first. */
	public List<Item> forCase(String caseId) {
		List<Item> out = new ArrayList<>();
		if (!ready() || caseId == null) return out;
		try (PreparedStatement ps = StaffCore.storage().conn().prepareStatement("""
				SELECT * FROM case_evidence
				WHERE case_id = ? AND retracted_at IS NULL
				ORDER BY added_at ASC, id ASC
				""")) {
			ps.setString(1, caseId);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) out.add(read(rs));
			}
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Cases] Could not read the evidence for {}", caseId, e);
		}
		return out;
	}

	public Optional<Item> byId(long id) {
		if (!ready()) return Optional.empty();
		try (PreparedStatement ps = StaffCore.storage().conn().prepareStatement(
				"SELECT * FROM case_evidence WHERE id = ? AND retracted_at IS NULL")) {
			ps.setLong(1, id);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? Optional.of(read(rs)) : Optional.empty();
			}
		} catch (SQLException e) {
			return Optional.empty();
		}
	}

	/** Hides a piece of evidence from its case, keeping the row and who did it. */
	public boolean retract(long id, String actor) {
		if (!ready()) return false;
		Optional<Item> item = byId(id);
		if (item.isEmpty()) return false;

		return StaffCore.storage().inTransaction(conn -> {
			try (PreparedStatement ps = conn.prepareStatement(
					"UPDATE case_evidence SET retracted_at = ?, retracted_by = ? WHERE id = ?")) {
				ps.setLong(1, System.currentTimeMillis());
				ps.setString(2, actor);
				ps.setLong(3, id);
				ps.executeUpdate();
			}
			try (PreparedStatement ps = conn.prepareStatement(
					"INSERT INTO case_events (case_id, at, actor, kind, body) VALUES (?,?,?,?,?)")) {
				ps.setString(1, item.get().caseId());
				ps.setLong(2, System.currentTimeMillis());
				ps.setString(3, actor);
				ps.setString(4, "evidence-retracted");
				ps.setString(5, "#" + id + " " + item.get().describe());
				ps.executeUpdate();
			}
		});
	}

	private static Item read(ResultSet rs) throws SQLException {
		String subject = rs.getString("subject_uuid");
		int x = rs.getInt("x");
		BlockPos pos = rs.wasNull() ? null : new BlockPos(x, rs.getInt("y"), rs.getInt("z"));
		return new Item(rs.getLong("id"), rs.getString("case_id"), Kind.of(rs.getString("kind")),
				rs.getString("label"), subject == null ? null : UUID.fromString(subject),
				rs.getString("subject_name"), rs.getString("world"), pos, rs.getInt("radius"),
				rs.getLong("from_ms"), rs.getLong("to_ms"), rs.getString("ref"),
				rs.getLong("added_at"), rs.getString("added_by"));
	}
}
