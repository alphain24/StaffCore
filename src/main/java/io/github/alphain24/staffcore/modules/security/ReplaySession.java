package io.github.alphain24.staffcore.modules.security;

import io.github.alphain24.staffcore.StaffCore;
import io.github.alphain24.staffcore.compat.Mc;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Where a staff member was standing before they went to look at somebody else's mine.
 *
 * <h2>Why this is on disk</h2>
 * A replay puts somebody in spectator, in a dimension they did not walk to, at coordinates they
 * did not choose. Every way of leaving has to put them back, and there are five: the exit
 * command, disconnecting, dying, walking into another dimension, and the server going down
 * underneath them.
 * <p>
 * Only the last needs persistence, and it is the one that matters most — an in-memory record
 * loses the way home exactly when nobody can ask for it, leaving a staff member permanently in
 * spectator at the bottom of a stranger's excavation. Every other failure at least has somebody
 * present to complain to.
 *
 * <h2>The order everything happens in</h2>
 * Written before anything about the player changes, and deleted only after they are demonstrably
 * back. That is the same rule the staff-mode stash follows and for the same reason: the
 * recoverable failure is restoring twice, and the unrecoverable one is having nothing to restore
 * from. If this row survives a restore that half worked, the next attempt still knows where they
 * were.
 *
 * <h2>What is not stashed</h2>
 * The inventory. Spectator cannot pick anything up, drop anything, or be hit, so there is
 * nothing to protect it from — and putting an inventory through the stash machinery to solve a
 * problem that does not exist would be a second path into the one piece of code that can lose
 * somebody's items. The gamemode, the position and the vanish flag are the whole of it.
 */
public final class ReplaySession {
	private ReplaySession() {}

	/**
	 * Somewhere to come back to.
	 *
	 * @param caseId the case this was opened from, or null for an ad-hoc look
	 */
	public record Prior(UUID staff, String caseId, String subject, GameType gameMode,
			String world, double x, double y, double z, float yaw, float pitch,
			boolean vanished, long startedAt) {}

	/**
	 * Records where somebody is, before they are moved.
	 * <p>
	 * Returns false when it could not be written, and the caller must then not move them.
	 * Sending a staff member into spectator with no way back recorded is the one outcome this
	 * class exists to prevent, and a database that is briefly unavailable is a much better
	 * reason to refuse than to proceed.
	 */
	public static boolean remember(ServerPlayer staff, String caseId, String subject) {
		if (staff == null || !StaffCore.storage().isReady()) return false;

		try (PreparedStatement ps = StaffCore.storage().conn().prepareStatement("""
				INSERT INTO replay_session (uuid, case_id, subject, prior_gamemode, prior_world,
				                            prior_x, prior_y, prior_z, prior_yaw, prior_pitch,
				                            prior_vanished, started_at)
				VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
				ON CONFLICT(uuid) DO NOTHING
				""")) {
			ps.setString(1, staff.getUUID().toString());
			ps.setString(2, caseId);
			ps.setString(3, subject);
			ps.setString(4, staff.gameMode().getName());
			ps.setString(5, Mc.dimensionId(staff.level()));
			ps.setDouble(6, staff.getX());
			ps.setDouble(7, staff.getY());
			ps.setDouble(8, staff.getZ());
			ps.setFloat(9, staff.getYRot());
			ps.setFloat(10, staff.getXRot());
			ps.setInt(11, io.github.alphain24.staffcore.module.Mods.vanish().isVanished(staff)
					? 1 : 0);
			ps.setLong(12, System.currentTimeMillis());

			// DO NOTHING rather than REPLACE. A second /staff xray while already replaying
			// must not overwrite the way home with the spectator position they are standing
			// at — that is how somebody ends up restored into the middle of the mine.
			return ps.executeUpdate() >= 0;
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Replay] could not record where {} was standing",
					Mc.name(staff), e);
			return false;
		}
	}

	/** Where this staff member was, or null when they are not replaying. */
	public static Prior of(UUID staff) {
		if (staff == null || !StaffCore.storage().isReady()) return null;

		try (PreparedStatement ps = StaffCore.storage().conn().prepareStatement(
				"SELECT * FROM replay_session WHERE uuid = ?")) {
			ps.setString(1, staff.toString());
			try (ResultSet rs = ps.executeQuery()) {
				if (!rs.next()) return null;

				return new Prior(staff, rs.getString("case_id"), rs.getString("subject"),
						gameMode(rs.getString("prior_gamemode")), rs.getString("prior_world"),
						rs.getDouble("prior_x"), rs.getDouble("prior_y"), rs.getDouble("prior_z"),
						rs.getFloat("prior_yaw"), rs.getFloat("prior_pitch"),
						rs.getInt("prior_vanished") == 1, rs.getLong("started_at"));
			}
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Replay] could not read a replay session", e);
			return null;
		}
	}

	/** Whether this staff member is part way through a replay. */
	public static boolean isReplaying(UUID staff) {
		return of(staff) != null;
	}

	/**
	 * Forgets the way home.
	 * <p>
	 * Called only once somebody is actually back. Clearing it first would make a restore that
	 * threw halfway into a staff member with no record of where they were.
	 */
	public static void clear(UUID staff) {
		if (staff == null || !StaffCore.storage().isReady()) return;

		try (PreparedStatement ps = StaffCore.storage().conn().prepareStatement(
				"DELETE FROM replay_session WHERE uuid = ?")) {
			ps.setString(1, staff.toString());
			ps.executeUpdate();
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Replay] could not clear a replay session", e);
		}
	}

	/** Every session still open. Read at startup, to put people back after a restart. */
	public static java.util.List<UUID> open() {
		java.util.List<UUID> out = new java.util.ArrayList<>();
		if (!StaffCore.storage().isReady()) return out;

		try (PreparedStatement ps = StaffCore.storage().conn().prepareStatement(
				"SELECT uuid FROM replay_session");
				ResultSet rs = ps.executeQuery()) {
			while (rs.next()) {
				try {
					out.add(UUID.fromString(rs.getString(1)));
				} catch (IllegalArgumentException malformed) {
					// One unreadable row should cost that row rather than everybody's way back.
				}
			}
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Replay] could not list open replay sessions", e);
		}
		return out;
	}

	/**
	 * Survival, for a stored value that means nothing.
	 * <p>
	 * Not spectator. A null or unrecognised gamemode restored as spectator would leave
	 * somebody in exactly the state this is meant to get them out of, which is the failure
	 * that hides — they are back where they started and still cannot touch anything.
	 */
	private static GameType gameMode(String stored) {
		if (stored != null) {
			for (GameType type : GameType.values()) {
				if (type.getName().equalsIgnoreCase(stored)) return type;
			}
		}
		return GameType.SURVIVAL;
	}
}
