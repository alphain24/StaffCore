package io.github.alphain24.staffcore.modules.grief;

import io.github.alphain24.staffcore.StaffCore;
import io.github.alphain24.staffcore.compat.Mc;
import io.github.alphain24.staffcore.config.StaffConfig;
import io.github.alphain24.staffcore.util.ItemCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * A way back from a rollback.
 * <p>
 * Rollback is the most destructive thing StaffCore does and, until now, the only destructive
 * thing with no way out. A ban is revoked, a confiscation is handed back, a vaulted item is
 * one click from its owner — but a rollback run with the radius set too wide overwrote
 * whatever was standing there and that was the end of it. The predictable result is staff
 * who hesitate to repair grief at all, which costs more than the occasional bad call.
 * <p>
 * A point is captured <em>before</em> anything is written: the block that is about to be
 * overwritten, and the contents of any container about to be replaced. Undo replays that
 * backwards. It is deliberately not clever — no attempt to work out whether the world has
 * moved on since — because a restore point that silently declines to restore is worse than
 * one that puts back exactly what it recorded and says so.
 */
public final class RollbackPoints {

	/** One rollback that can be undone. */
	public record Point(long id, String staff, String scope, String world, BlockPos centre,
			int radius, long windowMs, int changes, long createdAt, Long undoneAt, String undoneBy) {

		public boolean isUndone() {
			return undoneAt != null;
		}

		public String describe() {
			return "%s rolled back %d change(s) %s at %d, %d, %d".formatted(
					staff, changes, scope == null ? "by everyone" : "by " + scope,
					centre.getX(), centre.getY(), centre.getZ());
		}
	}

	/** One block a rollback overwrote, and what was there first. */
	private record Change(String world, BlockPos pos, String priorBlock, String priorState,
			Long logId) {}

	// ------------------------------------------------------------------ capturing

	/**
	 * Opens a restore point and returns its id, or 0 when one cannot be recorded.
	 * <p>
	 * A zero is not fatal: the caller rolls back anyway. Refusing to repair grief because
	 * the undo log is unavailable would be letting the safety net dictate the rescue.
	 */
	public long open(ServerLevel level, String staff, String scope, BlockPos centre,
			int radius, long windowMs, long at) {

		Connection c = conn();
		if (c == null) return 0L;

		try (PreparedStatement ps = c.prepareStatement(
				"INSERT INTO rollback_point "
						+ "(staff_name, scope, world, x, y, z, radius, window_ms, created_at) "
						+ "VALUES (?,?,?,?,?,?,?,?,?)",
				java.sql.Statement.RETURN_GENERATED_KEYS)) {

			ps.setString(1, staff);
			ps.setString(2, scope);
			ps.setString(3, Mc.dimensionId(level));
			ps.setInt(4, centre.getX());
			ps.setInt(5, centre.getY());
			ps.setInt(6, centre.getZ());
			ps.setInt(7, radius);
			ps.setLong(8, windowMs);
			// Supplied rather than generated here, so the container contents written under
			// this timestamp are findable again by exactly the same number.
			ps.setLong(9, at);
			ps.executeUpdate();

			try (ResultSet keys = ps.getGeneratedKeys()) {
				return keys.next() ? keys.getLong(1) : 0L;
			}
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Rollback] Could not open a restore point", e);
			return 0L;
		}
	}

	/**
	 * Records what is at one position immediately before the rollback overwrites it.
	 * <p>
	 * Called once per block, on the server thread, in the middle of a rollback loop — so it
	 * batches nothing and does the smallest possible amount of work. The container contents
	 * are written under the point's own timestamp into {@code container_snapshot}, reusing
	 * the same shape a broken chest already uses.
	 */
	public void capture(long pointId, long pointTime, ServerLevel level, BlockPos pos, Long logId) {
		if (pointId == 0L) return;

		// Read now, write later. The state has to be read before the rollback overwrites it,
		// but writing each row as we go means one commit per block — and a four-thousand
		// block rollback then spends its time on four thousand fsyncs while a staff member
		// waits. Buffered here and flushed as one transaction in close().
		BlockState prior = level.getBlockState(pos);
		String world = Mc.dimensionId(level);
		buffered.add(new Change(world, pos.immutable(), Mc.blockId(prior.getBlock()),
				Mc.stateToString(prior), logId));

		// Anything already sitting in a container here is about to be replaced along with
		// the block. Without this, undoing a rollback would put an empty chest back where a
		// full one had been — the same failure rollback itself used to have.
		if (level.getBlockEntity(pos) instanceof Container container) {
			for (int slot = 0; slot < container.getContainerSize(); slot++) {
				ItemStack stack = container.getItem(slot);
				if (stack.isEmpty()) continue;
				// Encoded here because it needs the registry, and copied because the very
				// next thing that happens to this container is being replaced.
				bufferedContents.add(new Content(world, pos.immutable(), slot,
						ItemCodec.encode(level.getServer(), stack.copy()), stack.getCount(), pointTime));
			}
		}
	}

	/** One buffered container stack, waiting to be written with the rest. */
	private record Content(String world, BlockPos pos, int slot, String item, int count, long at) {}

	private final List<Change> buffered = new ArrayList<>();
	private final List<Content> bufferedContents = new ArrayList<>();

	/**
	 * Writes everything the rollback captured, as one atomic change.
	 * <p>
	 * All of it or none of it: a restore point covering half the blocks it claims to would
	 * undo a rollback into a state that never existed, which is worse than having no restore
	 * point at all.
	 */
	public void close(long pointId, int changes) {
		if (pointId == 0L) {
			clearBuffers();
			return;
		}

		List<Change> changeRows = List.copyOf(buffered);
		List<Content> contentRows = List.copyOf(bufferedContents);
		clearBuffers();

		boolean ok = StaffCore.storage().inTransaction(c -> {
			try (PreparedStatement ps = c.prepareStatement(
					"INSERT INTO rollback_change (point_id, world, x, y, z, prior_block, prior_state, log_id) "
							+ "VALUES (?,?,?,?,?,?,?,?)")) {
				for (Change change : changeRows) {
					ps.setLong(1, pointId);
					ps.setString(2, change.world());
					ps.setInt(3, change.pos().getX());
					ps.setInt(4, change.pos().getY());
					ps.setInt(5, change.pos().getZ());
					ps.setString(6, change.priorBlock());
					ps.setString(7, change.priorState());
					if (change.logId() == null) ps.setNull(8, java.sql.Types.INTEGER);
					else ps.setLong(8, change.logId());
					ps.addBatch();
				}
				ps.executeBatch();
			}

			if (!contentRows.isEmpty()) {
				try (PreparedStatement ps = c.prepareStatement(
						"INSERT INTO container_snapshot (world, x, y, z, slot, item, count, created_at) "
								+ "VALUES (?,?,?,?,?,?,?,?)")) {
					for (Content content : contentRows) {
						ps.setString(1, content.world());
						ps.setInt(2, content.pos().getX());
						ps.setInt(3, content.pos().getY());
						ps.setInt(4, content.pos().getZ());
						ps.setInt(5, content.slot());
						ps.setString(6, content.item());
						ps.setInt(7, content.count());
						ps.setLong(8, content.at());
						ps.addBatch();
					}
					ps.executeBatch();
				}
			}

			try (PreparedStatement ps = c.prepareStatement(
					"UPDATE rollback_point SET changes = ? WHERE id = ?")) {
				ps.setInt(1, changes);
				ps.setLong(2, pointId);
				ps.executeUpdate();
			}
		});

		if (!ok) {
			// The rollback itself already happened; only the ability to undo it was lost.
			// Saying so is the difference between "undo does nothing" and "undo is missing
			// and here is why".
			StaffCore.LOGGER.error("[Rollback] Restore point {} could not be written - "
					+ "that rollback cannot be undone.", pointId);
			discard(pointId);
		}
	}

	/** Throws away a point that captured nothing worth keeping. */
	public void discard(long pointId) {
		clearBuffers();
		if (pointId == 0L) return;

		Connection c = conn();
		if (c == null) return;
		try (PreparedStatement del = c.prepareStatement("DELETE FROM rollback_point WHERE id = ?")) {
			del.setLong(1, pointId);
			del.executeUpdate();
		} catch (SQLException e) {
			StaffCore.LOGGER.warn("[Rollback] Could not discard an empty restore point: {}",
					e.getMessage());
		}
	}

	private void clearBuffers() {
		buffered.clear();
		bufferedContents.clear();
	}

	// -------------------------------------------------------------------- reading

	/** Recent points, newest first. */
	public List<Point> recent(int limit) {
		List<Point> out = new ArrayList<>();
		Connection c = conn();
		if (c == null) return out;

		try (PreparedStatement ps = c.prepareStatement(
				"SELECT * FROM rollback_point ORDER BY created_at DESC LIMIT ?")) {
			ps.setInt(1, limit);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) out.add(read(rs));
			}
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Rollback] Could not list restore points", e);
		}
		return out;
	}

	/** How many rollbacks could still be taken back. Cheap enough for a button label. */
	public int undoableCount() {
		Connection c = conn();
		if (c == null) return 0;
		try (PreparedStatement ps = c.prepareStatement(
				"SELECT COUNT(*) FROM rollback_point WHERE undone_at IS NULL");
				ResultSet rs = ps.executeQuery()) {
			return rs.next() ? rs.getInt(1) : 0;
		} catch (SQLException e) {
			return 0;
		}
	}

	public Point byId(long id) {
		Connection c = conn();
		if (c == null) return null;
		try (PreparedStatement ps = c.prepareStatement("SELECT * FROM rollback_point WHERE id = ?")) {
			ps.setLong(1, id);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? read(rs) : null;
			}
		} catch (SQLException e) {
			return null;
		}
	}

	/** The newest point that has not been undone, or null. */
	public Point mostRecentUndoable() {
		Connection c = conn();
		if (c == null) return null;
		try (PreparedStatement ps = c.prepareStatement(
				"SELECT * FROM rollback_point WHERE undone_at IS NULL "
						+ "ORDER BY created_at DESC LIMIT 1");
				ResultSet rs = ps.executeQuery()) {
			return rs.next() ? read(rs) : null;
		} catch (SQLException e) {
			return null;
		}
	}

	// --------------------------------------------------------------------- undoing

	/** What an undo managed. */
	public record UndoResult(int restored, int skipped) {
		public boolean didNothing() {
			return restored == 0;
		}
	}

	/**
	 * Puts the world back the way it was before one rollback.
	 * <p>
	 * Replays the captured prior states and re-fills any container that had contents. The
	 * {@code block_log} rows the rollback retired are un-retired, so the history stops
	 * claiming a repair that has since been reversed.
	 */
	public UndoResult undo(ServerLevel level, Point point, String by) {
		Connection c = conn();
		if (c == null || point == null || point.isUndone()) return new UndoResult(0, 0);

		List<Change> changes = new ArrayList<>();
		try (PreparedStatement ps = c.prepareStatement(
				"SELECT * FROM rollback_change WHERE point_id = ?")) {
			ps.setLong(1, point.id());
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					Long logId = rs.getObject("log_id") == null ? null : rs.getLong("log_id");
					changes.add(new Change(rs.getString("world"),
							new BlockPos(rs.getInt("x"), rs.getInt("y"), rs.getInt("z")),
							rs.getString("prior_block"), rs.getString("prior_state"), logId));
				}
			}
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Rollback] Could not read a restore point", e);
			return new UndoResult(0, 0);
		}

		int restored = 0;
		int skipped = 0;
		List<Long> unretire = new ArrayList<>();

		for (Change change : changes) {
			Block block = Mc.blockFromId(change.priorBlock());
			if (block == null) {
				skipped++;
				continue;
			}

			// The exact state where we have it, the default where we do not — a point taken
			// before the column existed still undoes, it just cannot restore orientation.
			BlockState exact = Mc.stateFromString(level.getServer(), change.priorState());
			level.setBlockAndUpdate(change.pos(),
					exact != null && exact.getBlock() == block ? exact : block.defaultBlockState());
			if (block != Blocks.AIR) {
				refill(level, change.pos(), point.createdAt());
			}
			if (change.logId() != null) unretire.add(change.logId());
			restored++;
		}

		// Once every block is back, anything still claiming an absent partner has really
		// lost one. Same reasoning as the rollback itself — judge the pairing at the end.
		for (Change change : changes) {
			Mc.normalizeChestPair(level, change.pos());
		}

		unretire(unretire);

		// The debt existed because the blocks were back. They are broken again, so nobody
		// gained anything from a repair that no longer stands and there is nothing left to
		// collect. Leaving it would bill somebody for work that has just been undone.
		int forgiven = StaffCore.pending().cancelFor("ROLLBACK", point.id());
		if (forgiven > 0) {
			StaffCore.LOGGER.info("[Rollback] Undo cancelled {} item(s) still owed from that "
					+ "rollback", forgiven);
		}

		markUndone(point.id(), by);
		return new UndoResult(restored, skipped);
	}

	/** Puts back what was in a container before the rollback replaced it. */
	private void refill(ServerLevel level, BlockPos pos, long pointTime) {
		if (!(level.getBlockEntity(pos) instanceof Container container)) return;

		Connection c = conn();
		if (c == null) return;

		String sql = """
				SELECT slot, item FROM container_snapshot
				WHERE world = ? AND x = ? AND y = ? AND z = ? AND created_at = ?
				""";
		try (PreparedStatement ps = c.prepareStatement(sql)) {
			ps.setString(1, Mc.dimensionId(level));
			ps.setInt(2, pos.getX());
			ps.setInt(3, pos.getY());
			ps.setInt(4, pos.getZ());
			ps.setLong(5, pointTime);

			try (ResultSet rs = ps.executeQuery()) {
				boolean any = false;
				while (rs.next()) {
					ItemStack stack = ItemCodec.decode(level.getServer(), rs.getString("item"));
					int slot = rs.getInt("slot");
					if (stack.isEmpty() || slot < 0 || slot >= container.getContainerSize()) continue;
					container.setItem(slot, stack);
					any = true;
				}
				if (any) container.setChanged();
			}
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Rollback] Could not refill a container on undo", e);
		}
	}

	private void unretire(List<Long> logIds) {
		if (logIds.isEmpty()) return;
		Connection c = conn();
		if (c == null) return;

		try (PreparedStatement ps = c.prepareStatement(
				"UPDATE block_log SET rolled_back = 0 WHERE id = ?")) {
			for (long id : logIds) {
				ps.setLong(1, id);
				ps.addBatch();
			}
			ps.executeBatch();
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Rollback] Could not un-retire log rows", e);
		}
	}

	private void markUndone(long pointId, String by) {
		Connection c = conn();
		if (c == null) return;
		try (PreparedStatement ps = c.prepareStatement(
				"UPDATE rollback_point SET undone_at = ?, undone_by = ? WHERE id = ?")) {
			ps.setLong(1, System.currentTimeMillis());
			ps.setString(2, by);
			ps.setLong(3, pointId);
			ps.executeUpdate();
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Rollback] Could not mark a restore point undone", e);
		}
	}

	// -------------------------------------------------------------------- purging

	/**
	 * Drops points past the retention window, and the container contents they held.
	 * <p>
	 * Undo is a safety net for a mistake noticed soon after it is made, not an archive. A
	 * week is long enough for somebody to log in and complain.
	 */
	public void purge() {
		int days = StaffConfig.get().rollbackPointRetentionDays;
		if (days <= 0) return;

		Connection c = conn();
		if (c == null) return;

		long cutoff = System.currentTimeMillis() - days * 86_400_000L;
		try {
			try (PreparedStatement ps = c.prepareStatement(
					"DELETE FROM container_snapshot WHERE created_at IN "
							+ "(SELECT created_at FROM rollback_point WHERE created_at < ?)")) {
				ps.setLong(1, cutoff);
				ps.executeUpdate();
			}
			try (PreparedStatement ps = c.prepareStatement(
					"DELETE FROM rollback_change WHERE point_id IN "
							+ "(SELECT id FROM rollback_point WHERE created_at < ?)")) {
				ps.setLong(1, cutoff);
				ps.executeUpdate();
			}
			try (PreparedStatement ps = c.prepareStatement(
					"DELETE FROM rollback_point WHERE created_at < ?")) {
				ps.setLong(1, cutoff);
				int gone = ps.executeUpdate();
				if (gone > 0) {
					StaffCore.LOGGER.info("[Rollback] Purged {} restore point(s) older than {} days",
							gone, days);
				}
			}
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Rollback] restore point purge failed", e);
		}
	}

	// ------------------------------------------------------------------- plumbing

	private static Point read(ResultSet rs) throws SQLException {
		Long undoneAt = rs.getObject("undone_at") == null ? null : rs.getLong("undone_at");
		return new Point(
				rs.getLong("id"), rs.getString("staff_name"), rs.getString("scope"),
				rs.getString("world"),
				new BlockPos(rs.getInt("x"), rs.getInt("y"), rs.getInt("z")),
				rs.getInt("radius"), rs.getLong("window_ms"), rs.getInt("changes"),
				rs.getLong("created_at"), undoneAt, rs.getString("undone_by"));
	}

	private static Connection conn() {
		return StaffCore.storage().isReady() ? StaffCore.storage().conn() : null;
	}
}
