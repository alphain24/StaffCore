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
 *       state until its moment, then released to whatever is really there.</li>
 *   <li><b>PLACE</b> — the block did not exist before and does after. Painted as air until its
 *       moment, then released.</li>
 * </ul>
 * Both are released rather than replaced, and that word is doing work: after the moment passes,
 * the viewer sees the world as the server actually has it. If somebody rolled the area back
 * last week, the replay shows the break happening and then shows the restored block, which is
 * the truth about both events rather than a guess about one.
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
			BlockState before) {

		public boolean isBreak() {
			return "BREAK".equals(action);
		}
	}

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

		try (PreparedStatement ps = StaffCore.storage().conn().prepareStatement("""
				SELECT x, y, z, action, block, state, created_at
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
		}
		return out;
	}

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

	/** How many of these have already happened at a given moment. For the sidebar. */
	public static int passed(List<Change> changes, long clock) {
		int done = 0;
		for (Change change : changes) {
			if (change.at() <= clock) done++;
		}
		return done;
	}
}
