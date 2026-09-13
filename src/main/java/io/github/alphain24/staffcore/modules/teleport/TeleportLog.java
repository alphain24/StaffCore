package io.github.alphain24.staffcore.modules.teleport;

import io.github.alphain24.staffcore.StaffCore;
import io.github.alphain24.staffcore.compat.Mc;
import io.github.alphain24.staffcore.module.Mods;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where players have teleported from and to.
 *
 * <h2>Why a jump detector and not hooks</h2>
 * A player is moved by a command, an ender pearl, chorus fruit, a portal, a respawn, another
 * mod's /home, a plugin on a proxy — each through a different path in the game, several of them
 * changing between versions. Hooking every one would be a list that is incomplete the day it is
 * written. Instead, once a tick, each online player's position is compared with the tick before:
 * a change of world, or a jump of {@value #JUMP_BLOCKS} blocks or more, is a teleport, whatever
 * caused it. Nothing in survival moves a player that far in a twentieth of a second — elytra
 * with rockets is under two blocks a tick.
 * <p>
 * The cause is attached where StaffCore knows it: its own staff teleports say who did it and to
 * whom, and a respawn says it was a respawn. Everything else is recorded as a teleport or a
 * change of world, which is the honest description.
 *
 * <h2>Which half runs where</h2>
 * Reading a position has to happen on the server thread, so the comparison does: three doubles
 * per online player per tick, and a string compare for the world. A row, when there is one, is
 * written by the grief log's writer thread.
 *
 * <h2>Kept how long, and shown to whom</h2>
 * As long as the grief log ({@code griefLogRetentionDays}), purged with it. It is a record of
 * where people were, so it is withheld from {@code /staff export} unless personal data is asked
 * for, exactly as position history is, and reading it needs {@code staff.logs}.
 */
public final class TeleportLog {

	/** A jump this far in one tick is a teleport. */
	public static final int JUMP_BLOCKS = 16;

	/** How long a cause given by StaffCore waits for the teleport it describes, in ticks. */
	private static final int HINT_TICKS = 40;

	public static final String TABLE = """
			CREATE TABLE IF NOT EXISTS teleport_log (
			    id          INTEGER PRIMARY KEY AUTOINCREMENT,
			    uuid        TEXT    NOT NULL,
			    name        TEXT,
			    at          INTEGER NOT NULL,
			    cause       TEXT    NOT NULL,
			    actor       TEXT,
			    other_uuid  TEXT,
			    other_name  TEXT,
			    from_world  TEXT,
			    from_x      REAL,
			    from_y      REAL,
			    from_z      REAL,
			    to_world    TEXT,
			    to_x        REAL,
			    to_y        REAL,
			    to_z        REAL
			)
			""";

	public static final String INDEX =
			"CREATE INDEX IF NOT EXISTS idx_teleport_log_player ON teleport_log(uuid, at)";
	public static final String OTHER_INDEX =
			"CREATE INDEX IF NOT EXISTS idx_teleport_log_other ON teleport_log(other_uuid, at)";

	/** What kind of teleport a row is. */
	public enum Cause {
		TELEPORT("teleported"),
		WORLD_CHANGE("changed world"),
		RESPAWN("respawned"),
		STAFF_TO_PLAYER("staff teleport to a player"),
		STAFF_BRING("brought by staff"),
		STAFF_TO_PLACE("staff teleport to a place"),
		STAFF_BACK("staff returned to where they were");

		private final String label;

		Cause(String label) {
			this.label = label;
		}

		public String label() {
			return label;
		}

		static Cause of(String stored) {
			try {
				return valueOf(stored);
			} catch (IllegalArgumentException | NullPointerException e) {
				return TELEPORT;
			}
		}
	}

	/** One teleport. */
	public record Entry(long id, UUID player, String name, long at, Cause cause, String actor,
			UUID other, String otherName, String fromWorld, double fromX, double fromY,
			double fromZ, String toWorld, double toX, double toY, double toZ) {

		public boolean changedWorld() {
			return fromWorld != null && !fromWorld.equals(toWorld);
		}

		/** Straight-line distance, or -1 across worlds where it means nothing. */
		public double distance() {
			if (changedWorld()) return -1;
			double dx = toX - fromX, dy = toY - fromY, dz = toZ - fromZ;
			return Math.sqrt(dx * dx + dy * dy + dz * dz);
		}
	}

	private record Sample(String world, double x, double y, double z) {}

	private record Hint(Cause cause, String actor, UUID other, String otherName, int tick) {}

	/** Server thread only. */
	private final Map<UUID, Sample> last = new ConcurrentHashMap<>();
	private final Map<UUID, Hint> hints = new ConcurrentHashMap<>();

	// ------------------------------------------------------------------ causes

	/**
	 * Says why the next teleport of this player is happening. Called just before StaffCore moves
	 * somebody, so the row names the staff member rather than just "teleported".
	 */
	public void expect(ServerPlayer player, Cause cause, String actor, UUID other, String otherName) {
		if (player == null) return;
		MinecraftServer server = Mc.server(player);
		hints.put(player.getUUID(), new Hint(cause, actor, other, otherName,
				server == null ? 0 : server.getTickCount()));
	}

	// ----------------------------------------------------------------- the tick

	/** Compares every online player with the tick before. Server thread. */
	public void tick(MinecraftServer server) {
		int now = server.getTickCount();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			String world = Mc.dimensionId(player.level());
			Sample here = new Sample(world, player.getX(), player.getY(), player.getZ());
			Sample before = last.put(player.getUUID(), here);
			if (before == null) continue;

			boolean otherWorld = !before.world().equals(world);
			double dx = here.x() - before.x(), dy = here.y() - before.y(), dz = here.z() - before.z();
			if (!otherWorld && dx * dx + dy * dy + dz * dz < (double) JUMP_BLOCKS * JUMP_BLOCKS) {
				continue;
			}

			Hint hint = hints.remove(player.getUUID());
			if (hint != null && now - hint.tick() > HINT_TICKS) hint = null;
			Cause cause = hint != null ? hint.cause() : otherWorld ? Cause.WORLD_CHANGE : Cause.TELEPORT;

			record(player.getUUID(), Mc.name(player), cause, hint == null ? null : hint.actor(),
					hint == null ? null : hint.other(), hint == null ? null : hint.otherName(),
					before, here);
		}
		// Hints nobody used: a teleport that was refused, or never happened.
		hints.values().removeIf(hint -> now - hint.tick() > HINT_TICKS);
	}

	/** A player left; their next position is a join, not a jump. */
	public void forget(UUID player) {
		last.remove(player);
		hints.remove(player);
	}

	private void record(UUID player, String name, Cause cause, String actor, UUID other,
			String otherName, Sample from, Sample to) {
		long at = System.currentTimeMillis();
		Mods.grief().offThread(() -> {
			if (!StaffCore.storage().isReady()) return;
			try (PreparedStatement ps = StaffCore.storage().conn().prepareStatement("""
					INSERT INTO teleport_log (uuid, name, at, cause, actor, other_uuid, other_name,
					                          from_world, from_x, from_y, from_z,
					                          to_world, to_x, to_y, to_z)
					VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
					""")) {
				ps.setString(1, player.toString());
				ps.setString(2, name);
				ps.setLong(3, at);
				ps.setString(4, cause.name());
				ps.setString(5, actor);
				ps.setString(6, other == null ? null : other.toString());
				ps.setString(7, otherName);
				ps.setString(8, from.world());
				ps.setDouble(9, from.x());
				ps.setDouble(10, from.y());
				ps.setDouble(11, from.z());
				ps.setString(12, to.world());
				ps.setDouble(13, to.x());
				ps.setDouble(14, to.y());
				ps.setDouble(15, to.z());
				ps.executeUpdate();
			} catch (SQLException e) {
				StaffCore.LOGGER.warn("[Teleport] Could not record a teleport: {}", e.getMessage());
			}
		});
	}

	// ---------------------------------------------------------------- reading

	/**
	 * A player's teleports, newest first — their own, and the ones staff made to them or of them.
	 */
	public List<Entry> forPlayer(UUID player, int limit) {
		List<Entry> out = new ArrayList<>();
		if (!StaffCore.storage().isReady() || player == null) return out;
		try (PreparedStatement ps = StaffCore.storage().conn().prepareStatement("""
				SELECT * FROM teleport_log WHERE uuid = ? OR other_uuid = ?
				ORDER BY at DESC LIMIT ?
				""")) {
			ps.setString(1, player.toString());
			ps.setString(2, player.toString());
			ps.setInt(3, Math.max(1, limit));
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					String other = rs.getString("other_uuid");
					out.add(new Entry(rs.getLong("id"), UUID.fromString(rs.getString("uuid")),
							rs.getString("name"), rs.getLong("at"), Cause.of(rs.getString("cause")),
							rs.getString("actor"), other == null ? null : UUID.fromString(other),
							rs.getString("other_name"), rs.getString("from_world"),
							rs.getDouble("from_x"), rs.getDouble("from_y"), rs.getDouble("from_z"),
							rs.getString("to_world"), rs.getDouble("to_x"), rs.getDouble("to_y"),
							rs.getDouble("to_z")));
				}
			}
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Teleport] Could not read teleport history", e);
		}
		return out;
	}
}
