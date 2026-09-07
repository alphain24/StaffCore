package io.github.alphain24.staffcore.modules.grief;

import io.github.alphain24.staffcore.StaffCore;
import io.github.alphain24.staffcore.compat.Mc;
import io.github.alphain24.staffcore.config.StaffConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

/**
 * Who picked what up off the ground, and where.
 * <p>
 * Recovering items by looking for them is unreliable in three separate ways, and all three
 * bite at once on a real server. Chunks unload the moment nobody is standing near them, and
 * an entity in an unloaded chunk is on disk rather than in memory, so a scan simply does not
 * see it. Drops despawn after five minutes. And anything somebody has already pocketed is
 * invisible to a scan by definition — which made looting a death pile a reliable way to keep
 * the items through a rollback, because the only inventory ever checked was the offender's.
 * <p>
 * So the pickup is recorded when it happens. Recovery then becomes a query rather than a
 * search: it does not care whether the chunk is loaded, whether the item still exists, or how
 * many thousand blocks away the scene is.
 * <p>
 * The cost is one row per pickup, written on the grief worker rather than the tick thread,
 * and kept for {@code pickupLogRetentionMinutes} — hours rather than the weeks the block log
 * keeps, because this only has to outlive the gap between somebody looting a scene and staff
 * putting it right.
 */
public final class PickupWatch {

	/** One recorded pickup. */
	public record Pickup(long id, UUID playerId, String playerName, String item, int count,
			int x, int y, int z, long at) {}

	private ExecutorService worker;

	void attach(ExecutorService worker) {
		this.worker = worker;
	}

	// -------------------------------------------------------------------- writing

	/**
	 * Records that a player picked something up.
	 * <p>
	 * Called from the item-pickup hook with the amount that actually made it into the
	 * inventory — never the amount offered, because a full inventory takes some and leaves
	 * the rest on the floor, and charging for what somebody did not receive is how a repair
	 * turns into a punishment.
	 */
	public void onPickup(ServerPlayer player, ItemStack stack, int count, BlockPos where) {
		if (count <= 0 || stack == null || stack.isEmpty()) return;
		if (!StaffConfig.get().logItemPickups) return;
		if (worker == null || !StaffCore.storage().isReady()) return;

		String item = Mc.itemId(stack.getItem());
		String world = Mc.dimensionId(player.level());
		UUID id = player.getUUID();
		String name = Mc.name(player);
		long now = System.currentTimeMillis();
		int x = where.getX();
		int y = where.getY();
		int z = where.getZ();

		worker.execute(() -> {
			try (PreparedStatement ps = StaffCore.storage().conn().prepareStatement(
					"INSERT INTO pickup_log "
							+ "(uuid, player_name, item, count, world, x, y, z, created_at) "
							+ "VALUES (?,?,?,?,?,?,?,?,?)")) {
				ps.setString(1, id.toString());
				ps.setString(2, name);
				ps.setString(3, item);
				ps.setInt(4, count);
				ps.setString(5, world);
				ps.setInt(6, x);
				ps.setInt(7, y);
				ps.setInt(8, z);
				ps.setLong(9, now);
				ps.executeUpdate();
			} catch (SQLException | RuntimeException e) {
				StaffCore.LOGGER.error("[Grief] pickup log failed", e);
			}
		});
	}

	// -------------------------------------------------------------------- reading

	/**
	 * Everybody who picked something up in an area, newest first.
	 * <p>
	 * A square rather than a circle, matching how every other area query here works, and
	 * bounded by time so an old scene cannot be charged for a new one.
	 *
	 * @param except a player to leave out — usually the person the items belong to, who is
	 *               entitled to pick up their own death drops and must not be billed for it
	 */
	public List<Pickup> near(String world, BlockPos centre, int radius, long windowMs,
			String except) {

		List<Pickup> out = new ArrayList<>();
		Connection c = conn();
		if (c == null) return out;

		String sql = "SELECT * FROM pickup_log WHERE world = ? AND created_at >= ? "
				+ "AND reclaimed = 0 AND x BETWEEN ? AND ? AND z BETWEEN ? AND ? "
				+ (except == null ? "" : "AND player_name <> ? ")
				+ "ORDER BY created_at DESC LIMIT 2000";

		try (PreparedStatement ps = c.prepareStatement(sql)) {
			int i = 1;
			ps.setString(i++, world);
			ps.setLong(i++, System.currentTimeMillis() - windowMs);
			ps.setInt(i++, centre.getX() - radius);
			ps.setInt(i++, centre.getX() + radius);
			ps.setInt(i++, centre.getZ() - radius);
			ps.setInt(i++, centre.getZ() + radius);
			if (except != null) ps.setString(i, except);

			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					out.add(new Pickup(rs.getLong("id"),
							UUID.fromString(rs.getString("uuid")),
							rs.getString("player_name"),
							rs.getString("item"),
							rs.getInt("count"),
							rs.getInt("x"), rs.getInt("y"), rs.getInt("z"),
							rs.getLong("created_at")));
				}
			}
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Grief] pickup lookup failed", e);
		}
		return out;
	}

	/**
	 * How much of what is owed each player picked up at a scene, biggest first.
	 * <p>
	 * Only counts what is actually being asked for, and never more than was owed of it —
	 * somebody who pocketed sixty-four cobblestone at a scene where five are missing owes
	 * five.
	 */
	public Map<String, Map<Item, Integer>> whoTook(String world, BlockPos centre, int radius,
			long windowMs, String except, Map<Item, Integer> owed) {

		Map<String, Map<Item, Integer>> byPlayer = new LinkedHashMap<>();
		if (owed.isEmpty()) return byPlayer;

		Map<Item, Integer> budget = new java.util.HashMap<>(owed);

		for (Pickup pickup : near(world, centre, radius, windowMs, except)) {
			Item item = Mc.itemFromId(pickup.item(), null);
			if (item == null) continue;

			Integer left = budget.get(item);
			if (left == null || left <= 0) continue;

			int take = Math.min(left, pickup.count());
			budget.put(item, left - take);
			byPlayer.computeIfAbsent(pickup.playerName(), who -> new java.util.HashMap<>())
					.merge(item, take, Integer::sum);
		}
		return byPlayer;
	}

	/** Marks rows as settled so a second rollback over the same scene cannot charge twice. */
	public void retire(String world, BlockPos centre, int radius, long windowMs, String except) {
		Connection c = conn();
		if (c == null) return;

		String sql = "UPDATE pickup_log SET reclaimed = 1 WHERE world = ? AND created_at >= ? "
				+ "AND x BETWEEN ? AND ? AND z BETWEEN ? AND ? "
				+ (except == null ? "" : "AND player_name <> ?");

		try (PreparedStatement ps = c.prepareStatement(sql)) {
			int i = 1;
			ps.setString(i++, world);
			ps.setLong(i++, System.currentTimeMillis() - windowMs);
			ps.setInt(i++, centre.getX() - radius);
			ps.setInt(i++, centre.getX() + radius);
			ps.setInt(i++, centre.getZ() - radius);
			ps.setInt(i++, centre.getZ() + radius);
			if (except != null) ps.setString(i, except);
			ps.executeUpdate();
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Grief] could not retire pickup rows", e);
		}
	}

	/**
	 * Drops rows past the retention window.
	 * <p>
	 * Kept in hours rather than the weeks the block log keeps. This only has to outlive the
	 * gap between somebody looting a scene and staff putting it right, and it is the highest
	 * volume table in the mod by a wide margin — a player mining picks up something most
	 * seconds.
	 */
	public void purge() {
		int minutes = StaffConfig.get().pickupLogRetentionMinutes;
		Connection c = conn();
		if (minutes <= 0 || c == null) return;

		try (PreparedStatement ps = c.prepareStatement(
				"DELETE FROM pickup_log WHERE created_at < ?")) {
			ps.setLong(1, System.currentTimeMillis() - minutes * 60_000L);
			int gone = ps.executeUpdate();
			if (gone > 0) {
				StaffCore.LOGGER.info("[Grief] Purged {} pickup row(s) older than {} minutes",
						gone, minutes);
			}
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Grief] pickup purge failed", e);
		}
	}

	private static Connection conn() {
		return StaffCore.storage().isReady() ? StaffCore.storage().conn() : null;
	}
}
