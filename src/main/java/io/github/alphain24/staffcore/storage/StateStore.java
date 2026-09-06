package io.github.alphain24.staffcore.storage;

import io.github.alphain24.staffcore.StaffCore;
import io.github.alphain24.staffcore.util.ItemCodec;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The state that used to die with the process: vanish, freeze anchors, staff mode and the
 * inventory a staff member is standing on while on duty.
 * <p>
 * The stash is the one that mattered. Holding a staff member's real inventory only in
 * memory meant a restart while anyone was clocked on destroyed their items, and the mod
 * could do nothing but apologise afterwards. Now the stash is written the moment they clock
 * on and cleared only once it has been handed back, so the failure mode is a duplicate
 * restore attempt rather than a loss.
 */
public final class StateStore {

	/** Everything StaffCore remembers about one player between restarts. */
	public record State(
			boolean vanished,
			boolean staffMode,
			String priorGamemode,
			boolean frozen,
			String freezeWorld,
			Vec3 freezeAt
	) {
		public static State empty() {
			return new State(false, false, null, false, null, null);
		}
	}

	// ------------------------------------------------------------------- reading

	/** Every stored state, read once at server start. */
	public Map<UUID, State> loadAll() {
		Map<UUID, State> out = new HashMap<>();
		Connection c = conn();
		if (c == null) return out;

		try (PreparedStatement ps = c.prepareStatement("SELECT * FROM staff_state");
				ResultSet rs = ps.executeQuery()) {
			while (rs.next()) {
				Vec3 anchor = null;
				if (rs.getObject("freeze_x") != null) {
					anchor = new Vec3(rs.getDouble("freeze_x"), rs.getDouble("freeze_y"), rs.getDouble("freeze_z"));
				}
				out.put(UUID.fromString(rs.getString("uuid")), new State(
						rs.getInt("vanished") == 1,
						rs.getInt("staff_mode") == 1,
						rs.getString("prior_gamemode"),
						rs.getInt("frozen") == 1,
						rs.getString("freeze_world"),
						anchor));
			}
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[State] Could not read stored state", e);
		}
		return out;
	}

	// ------------------------------------------------------------------- writing

	public void setVanished(UUID player, boolean vanished) {
		update(player, "vanished", vanished ? 1 : 0);
	}

	public void setStaffMode(UUID player, boolean on, String priorGamemode) {
		Connection c = conn();
		if (c == null) return;
		upsert(player);
		try (PreparedStatement ps = c.prepareStatement(
				"UPDATE staff_state SET staff_mode=?, prior_gamemode=?, updated_at=? WHERE uuid=?")) {
			ps.setInt(1, on ? 1 : 0);
			ps.setString(2, priorGamemode);
			ps.setLong(3, System.currentTimeMillis());
			ps.setString(4, player.toString());
			ps.executeUpdate();
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[State] staff mode write failed", e);
		}
	}

	public void setFrozen(UUID player, String world, Vec3 anchor) {
		Connection c = conn();
		if (c == null) return;
		upsert(player);
		try (PreparedStatement ps = c.prepareStatement(
				"UPDATE staff_state SET frozen=?, freeze_world=?, freeze_x=?, freeze_y=?, freeze_z=?, "
						+ "updated_at=? WHERE uuid=?")) {
			boolean frozen = anchor != null;
			ps.setInt(1, frozen ? 1 : 0);
			ps.setString(2, world);
			if (frozen) {
				ps.setDouble(3, anchor.x);
				ps.setDouble(4, anchor.y);
				ps.setDouble(5, anchor.z);
			} else {
				ps.setNull(3, java.sql.Types.REAL);
				ps.setNull(4, java.sql.Types.REAL);
				ps.setNull(5, java.sql.Types.REAL);
			}
			ps.setLong(6, System.currentTimeMillis());
			ps.setString(7, player.toString());
			ps.executeUpdate();
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[State] freeze write failed", e);
		}
	}

	private void update(UUID player, String column, int value) {
		Connection c = conn();
		if (c == null) return;
		upsert(player);
		try (PreparedStatement ps = c.prepareStatement(
				"UPDATE staff_state SET " + column + "=?, updated_at=? WHERE uuid=?")) {
			ps.setInt(1, value);
			ps.setLong(2, System.currentTimeMillis());
			ps.setString(3, player.toString());
			ps.executeUpdate();
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[State] {} write failed", column, e);
		}
	}

	private void upsert(UUID player) {
		Connection c = conn();
		if (c == null) return;
		try (PreparedStatement ps = c.prepareStatement(
				"INSERT OR IGNORE INTO staff_state (uuid, updated_at) VALUES (?,?)")) {
			ps.setString(1, player.toString());
			ps.setLong(2, System.currentTimeMillis());
			ps.executeUpdate();
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[State] upsert failed", e);
		}
	}

	// --------------------------------------------------------------------- stash

	public void saveStash(MinecraftServer server, UUID player, ItemStack[] contents) {
		Connection c = conn();
		if (c == null) return;

		clearStash(player);
		try (PreparedStatement ps = c.prepareStatement(
				"INSERT INTO stash (uuid, slot, item, stashed_at) VALUES (?,?,?,?)")) {
			long now = System.currentTimeMillis();
			for (int slot = 0; slot < contents.length; slot++) {
				ItemStack stack = contents[slot];
				if (stack == null || stack.isEmpty()) continue;
				ps.setString(1, player.toString());
				ps.setInt(2, slot);
				ps.setString(3, ItemCodec.encode(server, stack));
				ps.setLong(4, now);
				ps.addBatch();
			}
			ps.executeBatch();
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[State] stash write failed", e);
		}
	}

	/** Null when there is no stash on record — distinct from an empty one. */
	public ItemStack[] loadStash(MinecraftServer server, UUID player, int size) {
		Connection c = conn();
		if (c == null) return null;

		ItemStack[] contents = new ItemStack[size];
		java.util.Arrays.fill(contents, ItemStack.EMPTY);
		boolean found = false;

		try (PreparedStatement ps = c.prepareStatement("SELECT slot, item FROM stash WHERE uuid=?")) {
			ps.setString(1, player.toString());
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					found = true;
					int slot = rs.getInt("slot");
					if (slot >= 0 && slot < size) {
						contents[slot] = ItemCodec.decode(server, rs.getString("item"));
					}
				}
			}
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[State] stash read failed", e);
			return null;
		}
		return found ? contents : null;
	}

	public boolean hasStash(UUID player) {
		Connection c = conn();
		if (c == null) return false;
		try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM stash WHERE uuid=? LIMIT 1")) {
			ps.setString(1, player.toString());
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next();
			}
		} catch (SQLException e) {
			return false;
		}
	}

	public void clearStash(UUID player) {
		Connection c = conn();
		if (c == null) return;
		try (PreparedStatement ps = c.prepareStatement("DELETE FROM stash WHERE uuid=?")) {
			ps.setString(1, player.toString());
			ps.executeUpdate();
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[State] stash clear failed", e);
		}
	}

	private static Connection conn() {
		return StaffCore.storage().isReady() ? StaffCore.storage().conn() : null;
	}
}
