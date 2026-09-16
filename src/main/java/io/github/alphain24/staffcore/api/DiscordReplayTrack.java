package io.github.alphain24.staffcore.api;

import java.util.List;
import java.util.UUID;

/**
 * Where a player went over a window, and the blocks they broke and placed: what a map of a replay is
 * drawn from. Read from the recorded position history, so a window past its retention comes back empty.
 * Never kept by whoever asked: it is personal data with a retention of its own.
 *
 * @param points           the recorded positions, in time order, thinned to at most {@link #MAX_POINTS}
 * @param changes          the blocks changed, oldest first, at most {@link #MAX_CHANGES}
 * @param runs             separate stretches of movement; more than one means gaps are real
 * @param truncated        whether the movement was longer than a replay reconstructs
 * @param changesTruncated whether there were more block changes than are listed
 */
public record DiscordReplayTrack(UUID playerId, String playerName, long from, long to, List<Point> points,
		List<Change> changes, int runs, boolean truncated, boolean changesTruncated) {

	public static final int MAX_POINTS = 4000;
	public static final int MAX_CHANGES = 2000;

	public DiscordReplayTrack {
		points = points == null ? List.of() : List.copyOf(points);
		changes = changes == null ? List.of() : List.copyOf(changes);
	}

	/** One recorded position, in blocks. */
	public record Point(long at, String world, double x, double y, double z) {}

	/**
	 * One block the player changed.
	 *
	 * @param broke true for a block broken, false for one placed
	 * @param block the block's id, such as {@code minecraft:diamond_ore}
	 */
	public record Change(long at, String world, int x, int y, int z, boolean broke, String block) {}
}
