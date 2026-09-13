package io.github.alphain24.staffcore.modules.replay;

import io.github.alphain24.staffcore.StaffCore;
import io.github.alphain24.staffcore.compat.Mc;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the world looked like along the path, and what changed it.
 *
 * <h2>Why an overlay rather than a list</h2>
 * A replay without this shows a player walking through a world as it is <em>now</em>. That is
 * quietly the wrong world. Every hole they made is already there when they arrive at it, every
 * block they placed is already standing, and a rollback somebody ran afterwards has erased the
 * whole thing. Watching somebody tunnel through a tunnel that is already open tells you
 * nothing, and it looks exactly like watching them tunnel.
 * <p>
 * So the blocks they changed are painted back to how they were, and un-painted as the clock
 * reaches each change. A wall is standing until they break it. A tower is absent until they
 * build it. Only the positions that changed are touched — everything else is the real world,
 * which is correct, because everything else did not change.
 *
 * <h2>The direction each kind is drawn in</h2>
 * <ul>
 *   <li><b>BREAK</b> — the block existed before and does not after. Painted with its logged
 *       state until its moment, then painted as <em>air</em> until the next thing that is known
 *       to have happened at that spot — somebody placing a block there, or the rollback that
 *       put it back — and only then released to whatever is really there.</li>
 *   <li><b>PLACE</b> — the block did not exist before and does after. Painted as air until its
 *       moment, then released.</li>
 * </ul>
 *
 * <h2>Why a break keeps its hole</h2>
 * Breaks used to be released at their moment, the same as places, on the reasoning that the
 * world as it is now is the truth from then on. It is not the truth about <em>then</em>. Grief
 * gets rolled back, usually before anybody replays it, so releasing a TNT crater showed the
 * blast happen and the wall still standing — the damage the replay was opened to look at was
 * the one thing it could not show. A break's hole is now drawn for as long as it really was a
 * hole, and handed back to the real world at the moment that stopped being true.
 * <p>
 * A place is still released at its moment. The removals that follow a placed block are the ones
 * the log does not see — TNT is primed away, sand falls, a piston moves it — so holding a placed
 * block would draw it long after it was gone.
 *
 * <h2>Client-side only, like everything else here</h2>
 * Painted with {@code ClientboundBlockUpdatePacket} through {@link ReplayStage}. The world is
 * never touched, nobody else sees any of it, and a server that stops mid-replay leaves nothing
 * to repair.
 */
public final class PathEvents {
	private PathEvents() {}

	/**
	 * How many changes one replay will draw.
	 * <p>
	 * Every one of these is a block update packet sent on entry and another on release, so the
	 * cap is about what a connection should be asked to carry rather than about memory. Two
	 * thousand covers an ordinary session comfortably; past it the picture is a wall of blocks
	 * and the individual event stops being visible anyway.
	 */
	public static final int MAX_EVENTS = 2000;

	/**
	 * One logged change, with the state to show before it happens.
	 *
	 * @param before what to paint until {@code at} — the broken block for a BREAK, air for a
	 *               PLACE. Never null: a row whose state could not be parsed falls back to the
	 *               block's default rather than being dropped, because a missing block in the
	 *               reconstruction is invisible and a slightly wrong one is not.
	 */
	public record Change(long at, BlockPos pos, String world, String action, String block,
			BlockState before, long releaseAt) {

		/** A change with nothing known after it beyond its own moment: released on the spot. */
		public Change(long at, BlockPos pos, String world, String action, String block,
				BlockState before) {
			this(at, pos, world, action, block, before, at);
		}

		public boolean isBreak() {
			return "BREAK".equals(action);
		}

		/** Whether this break's hole is still the truth at {@code clock}. */
		boolean holeStandsAt(long clock) {
			return isBreak() && clock >= at && clock < releaseAt;
		}
	}

	/** "Nothing is known to have filled this hole before the replay ends." */
	public static final long NEVER = Long.MAX_VALUE;

	/**
	 * Everything this player changed in the window, oldest first.
	 * <p>
	 * Reads {@code block_log} directly rather than through the grief module's area queries,
	 * because the question is different: those ask what happened <em>here</em>, and this asks
	 * what one person did <em>anywhere</em> between two times. Bounded by {@link #MAX_EVENTS}
	 * in the SQL rather than afterwards, so a player who spent six hours quarrying does not
	 * pull a hundred thousand rows into memory to throw away.
	 */
	public static List<Change> inWindow(MinecraftServer server, String playerName, String world,
			long from, long to) {

		List<Change> out = new ArrayList<>();
		if (!StaffCore.storage().isReady() || playerName == null) return out;
		List<Long> ids = new ArrayList<>();

		try (PreparedStatement ps = StaffCore.storage().conn().prepareStatement("""
				SELECT id, x, y, z, action, block, state, created_at
				  FROM block_log
				 WHERE player_name = ? AND world = ? AND created_at BETWEEN ? AND ?
				   AND action IN ('BREAK', 'PLACE')
				 ORDER BY created_at
				 LIMIT ?
				""")) {
			ps.setString(1, playerName);
			ps.setString(2, world);
			ps.setLong(3, from);
			ps.setLong(4, to);
			ps.setInt(5, MAX_EVENTS);

			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					String action = rs.getString("action");
					ids.add(rs.getLong("id"));
					out.add(new Change(rs.getLong("created_at"),
							new BlockPos(rs.getInt("x"), rs.getInt("y"), rs.getInt("z")),
							world, action, rs.getString("block"),
							"BREAK".equals(action)
									? stateOf(server, rs.getString("state"), rs.getString("block"))
									: Blocks.AIR.defaultBlockState()));
				}
			}
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Replay] could not read the block log for a replay", e);
			return out;
		}

		try {
			return withReleases(StaffCore.storage().conn(), world, from, to, out, ids);
		} catch (SQLException e) {
			// Without the aftermath every hole is held to the end of the replay. Wrong where
			// somebody repaired it, and still far closer to the truth than a wall that stands
			// straight through the blast that destroyed it.
			StaffCore.LOGGER.error("[Replay] could not read what happened after the damage", e);
			List<Change> held = new ArrayList<>(out.size());
			for (Change change : out) {
				held.add(change.isBreak() ? withRelease(change, NEVER) : change);
			}
			return held;
		}
	}

	/**
	 * Works out when each break stopped being a hole, within the window.
	 * <p>
	 * Two things end one. Any later change at that position, by anybody — a repair is usually
	 * somebody else's. Or the rollback that put the block back, which writes no block-log row of
	 * its own, so it is read from the restore points. An undone rollback is ignored: undoing it
	 * made the hole real again, and the later break or place is what the log then shows.
	 * <p>
	 * Two queries, not one per break: a TNT crater is hundreds of breaks, and this runs before
	 * the replay starts.
	 */
	private static List<Change> withReleases(java.sql.Connection conn, String world, long from,
			long to, List<Change> changes, List<Long> ids) throws SQLException {

		if (changes.stream().noneMatch(Change::isBreak)) return changes;

		int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
		for (Change change : changes) {
			if (!change.isBreak()) continue;
			BlockPos p = change.pos();
			minX = Math.min(minX, p.getX()); maxX = Math.max(maxX, p.getX());
			minY = Math.min(minY, p.getY()); maxY = Math.max(maxY, p.getY());
			minZ = Math.min(minZ, p.getZ()); maxZ = Math.max(maxZ, p.getZ());
		}

		// Every change in that box during the window, by anybody, grouped by position.
		Map<Long, List<Long>> timesAt = new java.util.HashMap<>();
		try (PreparedStatement ps = conn.prepareStatement("""
				SELECT x, y, z, created_at FROM block_log
				 WHERE world = ? AND created_at BETWEEN ? AND ?
				   AND x BETWEEN ? AND ? AND y BETWEEN ? AND ? AND z BETWEEN ? AND ?
				   AND action IN ('BREAK', 'PLACE')
				 ORDER BY created_at
				 LIMIT ?
				""")) {
			ps.setString(1, world);
			ps.setLong(2, from);
			ps.setLong(3, to);
			ps.setInt(4, minX); ps.setInt(5, maxX);
			ps.setInt(6, minY); ps.setInt(7, maxY);
			ps.setInt(8, minZ); ps.setInt(9, maxZ);
			ps.setInt(10, MAX_AFTERMATH_ROWS);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					long key = BlockPos.asLong(rs.getInt("x"), rs.getInt("y"), rs.getInt("z"));
					timesAt.computeIfAbsent(key, k -> new ArrayList<>()).add(rs.getLong("created_at"));
				}
			}
		}

		// When a rollback put each of these rows back, if one did during the window.
		Map<Long, Long> restoredAt = new java.util.HashMap<>();
		try (PreparedStatement ps = conn.prepareStatement("""
				SELECT c.log_id, p.created_at
				  FROM rollback_change c JOIN rollback_point p ON p.id = c.point_id
				 WHERE c.world = ? AND p.created_at BETWEEN ? AND ? AND p.undone_at IS NULL
				   AND c.log_id IS NOT NULL
				""")) {
			ps.setString(1, world);
			ps.setLong(2, from);
			ps.setLong(3, to);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					restoredAt.merge(rs.getLong(1), rs.getLong(2), Math::min);
				}
			}
		}

		List<Change> out = new ArrayList<>(changes.size());
		for (int i = 0; i < changes.size(); i++) {
			Change change = changes.get(i);
			if (!change.isBreak()) {
				out.add(change);
				continue;
			}
			long release = restoredAt.getOrDefault(ids.get(i), NEVER);
			List<Long> times = timesAt.get(change.pos().asLong());
			if (times != null) {
				for (long t : times) {
					if (t > change.at()) {
						release = Math.min(release, t);
						break;
					}
				}
			}
			out.add(withRelease(change, release));
		}
		return out;
	}

	private static Change withRelease(Change change, long releaseAt) {
		return new Change(change.at(), change.pos(), change.world(), change.action(),
				change.block(), change.before(), releaseAt);
	}

	/**
	 * How much of the surrounding log is read to find what ended each hole. Generous: the box
	 * covers everything the player broke, which on a busy server is other people's work too.
	 * Past it, a hole that was repaired is drawn a little too long, never too short.
	 */
	static final int MAX_AFTERMATH_ROWS = 50_000;

	/**
	 * The logged state, or the block's default, or stone.
	 * <p>
	 * Three fallbacks deep on purpose. A row can predate the {@code state} column, or hold a
	 * state this version can no longer parse, or name a block a mod used to provide — and in
	 * every one of those cases the useful answer is "something was here", not nothing. An
	 * un-drawn break is indistinguishable from a break that never happened, which is the worse
	 * error by a long way in a picture somebody is reading as evidence.
	 */
	private static BlockState stateOf(MinecraftServer server, String serialized, String block) {
		BlockState exact = Mc.stateFromString(server, serialized);
		if (exact != null) return exact;

		BlockState byName = Mc.stateFromString(server, block);
		return byName != null ? byName : Blocks.STONE.defaultBlockState();
	}

	/**
	 * What to paint at a given moment, given the changes that have not happened yet.
	 * <p>
	 * Kept as a plain function of a list and a time so it can be checked without a world. The
	 * failure it guards against is the one that would make the overlay actively misleading: a
	 * change released too early shows a hole before it was dug, and one released too late shows
	 * a wall the player has already walked through.
	 */
	public static Map<BlockPos, BlockState> at(List<Change> changes, long clock) {
		Map<BlockPos, BlockState> painted = new LinkedHashMap<>();

		// First the holes that are still holes, then the "before" of whatever is still to come.
		for (BlockPos hole : holesAt(changes, clock)) {
			painted.put(hole, Blocks.AIR.defaultBlockState());
		}

		for (Change change : changes) {
			// Strictly after. A change whose moment is exactly now has happened, so what it
			// describes belongs to the world from this instant on and is not painted.
			if (change.at() <= clock) continue;

			// putIfAbsent, and this is the whole rule: a position shows the "before" of the
			// earliest change still to come. That is what was standing there at this instant,
			// because every change before it has already happened.
			//
			// It depends on the list being in time order, which inWindow guarantees. Two
			// changes at one position — broken, replaced, broken again — read correctly from
			// it: half way between the first break and the placement, the earliest change
			// still to come is that placement, and its "before" is air. So the overlay paints
			// air there, which is not the same as painting nothing. If somebody rolled the
			// area back last week the world has a block there now, and painting nothing would
			// show it standing at a moment when it was rubble.
			painted.putIfAbsent(change.pos(), change.before());
		}
		return painted;
	}

	/**
	 * The positions that are holes at this moment: broken already, and not yet filled by anything
	 * known to have happened after.
	 * <p>
	 * The latest change at a position that has already happened is the one that describes it
	 * now, so a later place at the same spot ends an earlier break rather than the other way
	 * round. Positions only, so the rule can be checked without a block registry.
	 */
	static java.util.Set<BlockPos> holesAt(List<Change> changes, long clock) {
		Map<BlockPos, Change> latestPassed = new LinkedHashMap<>();
		for (Change change : changes) {
			if (change.at() <= clock) latestPassed.put(change.pos(), change);
		}
		java.util.Set<BlockPos> holes = new java.util.LinkedHashSet<>();
		for (Change change : latestPassed.values()) {
			if (change.holeStandsAt(clock)) holes.add(change.pos());
		}
		return holes;
	}

	/** How many of these have already happened at a given moment. For the sidebar. */
	public static int passed(List<Change> changes, long clock) {
		int done = 0;
		for (Change change : changes) {
			if (change.at() <= clock) done++;
		}
		return done;
	}
}
