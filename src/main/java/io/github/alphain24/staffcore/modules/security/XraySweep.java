package io.github.alphain24.staffcore.modules.security;

import io.github.alphain24.staffcore.StaffCore;
import io.github.alphain24.staffcore.config.StaffConfig;
import io.github.alphain24.staffcore.module.Mods;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * X-ray analysis, run against stored data on a worker rather than on the tick loop.
 *
 * <h2>What moved, and what could not</h2>
 * The old sweep queried {@code block_log} for every online player from inside
 * {@code END_SERVER_TICK}. On a busy server that is a database round trip per player, on the
 * thread that has sixteen milliseconds to do everything else — and it grew with the player
 * count, so it was worst exactly when the server could least afford it.
 * <p>
 * The read is now off-thread. One part cannot be: counting the ore still standing in the rock
 * around an excavation means reading block states, and a {@code ServerLevel} may only be
 * touched from the server thread. Doing it anywhere else is a data race that shows up as a
 * crash weeks later in somebody else's log.
 * <p>
 * So the shape is: worker reads the log and builds the geometry, server thread does a bounded
 * census, worker-side arithmetic finishes it. The census is the only part on the tick and it is
 * counted and reported, because "no detection on the server thread" is a claim that needs a
 * number behind it rather than an assurance.
 *
 * <h2>Why the census is needed at all</h2>
 * The ores a player took are in the log. The ores they walked past are not — and the second
 * number is what makes the first mean anything. Finding four diamonds is unremarkable if there
 * were forty in reach and damning if there were four. Only the world knows, and only for what
 * is still there, which is why this counts what remains and adds back what was removed.
 *
 * <h2>Ore on a cave wall is not a draw</h2>
 * The arithmetic assumes every block was chosen blind. Ore showing on a cave wall was not — it
 * was seen and walked to — and a cave's air is not rock anybody could have dug. Both used to
 * count: somebody mining the diamonds, gold and redstone along a cave took nearly every "ore in
 * the volume" in very few "blocks", which is exactly the shape of cheating, and was flagged for
 * it. The census now leaves out open space the player did not dig themselves, and any ore that
 * touched such space, from the population, the ore and what was found.
 */
public final class XraySweep {
	private XraySweep() {}

	/** What one segment of one player's session came to. */
	public record Finding(String player, String world, int band, int population, int ores,
			int drawn, int found, double pValue) {

		/** How much of the ore that was there they came away with. */
		public double foundFraction() {
			return ores == 0 ? 0 : (double) found / ores;
		}

		public String headline() {
			return "took " + found + " of " + ores + " ore in " + drawn + " blocks ("
					+ Hypergeometric.describe(pValue) + ")";
		}
	}

	/**
	 * Where one sweep spent its time, and how much of the world it had to read.
	 * <p>
	 * {@code blocksRead} is the one figure here that means the same thing on every machine.
	 * The three microsecond counts describe this run on this hardware while it was doing
	 * whatever else it was doing; the block count describes the work, and it is the number
	 * Gate 3's claim is actually about.
	 */
	public record Timing(long readMicros, long censusMicros, long mathMicros, int blocksRead,
			int players) {

		public long totalMicros() {
			return readMicros + censusMicros + mathMicros;
		}
	}

	/**
	 * Findings, and what producing them cost.
	 * <p>
	 * Returned rather than stashed in a static. There used to be a {@code lastTiming} field
	 * holding the most recent measurement, read by exactly one test and nothing else — which
	 * made it test-only global state living in production code, and gave a read-after-write
	 * race between any two sweeps in flight. Two gametests running at once could easily have
	 * one of them measuring the other's work and never know.
	 */
	public record Swept(List<Finding> findings, Timing timing) {}

	/**
	 * Reads the window on a worker, then finishes on the server thread.
	 * <p>
	 * Returns immediately. Nothing here waits for the database, because waiting for the
	 * database is precisely what this replaced.
	 */
	public static void run(MinecraftServer server, java.util.function.Consumer<List<Finding>> onDone) {
		if (server == null || !StaffCore.storage().isReady()) return;

		long windowMs = Math.max(1, StaffConfig.get().xraySweepMinutes) * 60_000L * 6;
		long start = System.nanoTime();

		Map<String, List<Excavation.Dig>> nothing = Map.of();
		Mods.grief().readOffThread(server, () -> loadDigs(windowMs), nothing, byPlayer -> {
			long readMicros = (System.nanoTime() - start) / 1000;
			onDone.accept(analyse(server, byPlayer, readMicros).findings());
		});
	}

	/**
	 * Every relevant break in the window, grouped by player.
	 * <p>
	 * Runs on the worker. Creative breaks are excluded: a staff member in creative flattening
	 * a build is not mining, and their block count would swamp everybody else's.
	 */
	static Map<String, List<Excavation.Dig>> loadDigs(long windowMs) {
		return loadDigs(windowMs, null);
	}

	/**
	 * @param onlyPlayer one name, or null for everybody
	 *                   <p>
	 *                   Filtered in the query rather than afterwards. Reading the whole
	 *                   server's mining to answer a question about one player is the shape of
	 *                   thing that is invisible until somebody runs it on a database with a
	 *                   year of history in it.
	 */
	static Map<String, List<Excavation.Dig>> loadDigs(long windowMs, String onlyPlayer) {
		Map<String, List<Excavation.Dig>> byPlayer = new LinkedHashMap<>();
		if (!StaffCore.storage().isReady()) return byPlayer;

		String sql = """
				SELECT player_name, block, world, x, y, z, created_at
				FROM block_log
				WHERE action = 'BREAK' AND created_at >= ?
				  AND (gamemode IS NULL OR gamemode <> 'creative')
				"""
				+ (onlyPlayer == null ? "" : "  AND player_name = ? COLLATE NOCASE"
						+ System.lineSeparator())
				+ "ORDER BY player_name, created_at";

		try (PreparedStatement ps = StaffCore.storage().conn().prepareStatement(sql)) {
			ps.setLong(1, System.currentTimeMillis() - windowMs);
			if (onlyPlayer != null) ps.setString(2, onlyPlayer);

			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					byPlayer.computeIfAbsent(rs.getString("player_name"), k -> new ArrayList<>())
							.add(new Excavation.Dig(rs.getString("block"), rs.getString("world"),
									rs.getInt("x"), rs.getInt("y"), rs.getInt("z"),
									rs.getLong("created_at")));
				}
			}
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Xray] could not read the block log", e);
		}
		return byPlayer;
	}

	/**
	 * Censuses each segment and scores it. Runs on the server thread, and is measured.
	 * <p>
	 * The arithmetic could go back to the worker for another hop. It does not, because a
	 * p-value is a few dozen floating-point operations and the hop would cost more than the
	 * sum it saves — a round trip to be seen doing the right thing is not the right thing.
	 */
	private static Swept analyse(MinecraftServer server,
			Map<String, List<Excavation.Dig>> byPlayer, long readMicros) {

		List<Finding> findings = new ArrayList<>();
		StaffConfig cfg = StaffConfig.get();
		long censusNanos = 0;
		long mathNanos = 0;
		int blocksRead = 0;

		for (Map.Entry<String, List<Excavation.Dig>> entry : byPlayer.entrySet()) {
			for (Excavation.Segment segment : Excavation.segment(entry.getValue())) {

				// Gated on volume rather than on how many blocks were broken. A player who
				// removed thirty blocks from a pocket of forty has drawn almost all of it, and
				// the arithmetic on a population that small produces confident nonsense —
				// which is where every false positive in the old detector lived. Checked again
				// after the census, once the cave air is out of it.
				if (segment.population() < cfg.xrayMinimumVolume) continue;

				ServerLevel level = levelFor(server, segment.world());
				if (level == null) continue;

				Scored scored = scored(level, entry.getKey(), segment, cfg.xrayMinimumVolume);
				censusNanos += scored.censusNanos();
				mathNanos += scored.mathNanos();
				blocksRead += scored.read();
				if (scored.finding() != null) findings.add(scored.finding());
			}
		}

		return new Swept(findings, new Timing(readMicros, censusNanos / 1000,
				mathNanos / 1000, blocksRead, byPlayer.size()));
	}

	/** One segment, scored, with what it cost. */
	private record Scored(Finding finding, int read, long censusNanos, long mathNanos) {}

	/** Census and arithmetic for one segment — the one path both the sweep and a test take. */
	private static Scored scored(ServerLevel level, String player, Excavation.Segment segment,
			int minimumVolume) {

		long censusStart = System.nanoTime();
		Census census = census(level, segment);
		long censusNanos = System.nanoTime() - censusStart;

		long mathStart = System.nanoTime();
		Blind blind = blind(segment.population(), segment.drawn(), segment.found(), census);
		double p = Hypergeometric.atLeast(blind.population(), blind.ores(), blind.drawn(),
				blind.found());
		long mathNanos = System.nanoTime() - mathStart;

		Finding finding = blind.population() >= minimumVolume && blind.ores() > 0 && blind.found() > 0
				? new Finding(player, segment.world(), segment.band(), blind.population(),
						blind.ores(), blind.drawn(), blind.found(), p)
				: null;
		return new Scored(finding, census.read(), censusNanos, mathNanos);
	}

	/**
	 * Scores one set of breaks against the world as it stands, with a volume floor of the caller's
	 * choosing. The sweep's own path, for a test that builds a dig smaller than the configured
	 * floor.
	 *
	 * @return the finding, or null when there is nothing in it to report
	 */
	public static Finding score(ServerLevel level, String player, java.util.Collection<Excavation.Dig> digs,
			int minimumVolume) {
		for (Excavation.Segment segment : Excavation.segment(digs)) {
			Finding finding = scored(level, player, segment, minimumVolume).finding();
			if (finding != null) return finding;
		}
		return null;
	}

	/**
	 * Scores one player on demand, synchronously.
	 * <p>
	 * The same path the sweep takes, for one name and one window. Sharing it is the point:
	 * two scorers that agree today are two scorers that drift, and the first anybody hears of
	 * the drift is a staff member seeing one number on a screen and a different one in a case.
	 * <p>
	 * Synchronous because a staff member typed a command and is waiting for the answer. The
	 * read is one indexed query for one player, which is a different proposition from the
	 * sweep's scan of everybody.
	 */
	public static List<Finding> forPlayer(MinecraftServer server, String player, long windowMs) {
		return sweepOne(server, player, windowMs).findings();
	}

	/**
	 * As {@link #forPlayer}, returning what the sweep cost as well as what it found.
	 * <p>
	 * Separate entry point so the ordinary callers keep a simple return type and the one that
	 * cares about cost gets it handed back rather than reading it out of a shared field
	 * afterwards.
	 */
	public static Swept sweepOne(MinecraftServer server, String player, long windowMs) {
		if (server == null || player == null || !StaffCore.storage().isReady()) {
			return new Swept(List.of(), new Timing(0, 0, 0, 0, 0));
		}

		long start = System.nanoTime();
		Map<String, List<Excavation.Dig>> theirs = loadDigs(windowMs, player);

		// Timed even though this path is synchronous. Reporting nought would read as free
		// rather than as unmeasured, and this is the number Gate 3 is about.
		return analyse(server, theirs, (System.nanoTime() - start) / 1000);
	}

	/**
	 * Why this player has produced no finding, in the words of whichever reason applies.
	 * <p>
	 * Silence is the normal state, which makes a quiet server and a broken feature look
	 * identical from the outside. There are only ever three reasons — nothing mined, too small
	 * a volume to say anything about, or a result that is simply unremarkable — and naming the
	 * one that applies is the difference between trusting the tool and assuming it never ran.
	 */
	public static String whyNothing(MinecraftServer server, String player, long windowMs) {
		List<Excavation.Dig> theirs = loadDigs(windowMs, player).values().stream()
				.findFirst().orElse(null);

		if (theirs == null || theirs.isEmpty()) {
			return "They have broken nothing in this window, so there is nothing to score.";
		}

		int biggest = Excavation.segment(theirs).stream()
				.mapToInt(Excavation.Segment::population).max().orElse(0);
		int floor = StaffConfig.get().xrayMinimumVolume;

		if (biggest < floor) {
			return "Their largest dig reaches " + biggest + " blocks of rock, under the "
					+ floor + " this needs. Below that the arithmetic is confident and "
					+ "meaningless, which is worse than saying nothing.";
		}
		return "Enough digging to score, and nothing in it is more than chance would give.";
	}

	/**
	 * What the rock around an excavation holds, and what of it anybody could see.
	 *
	 * @param remaining        target ore still standing
	 * @param read             blocks read to find out
	 * @param open             positions that are open space the player did not dig — a cave, a
	 *                         ravine, somebody else's tunnel
	 * @param visibleFound     ore they took that was touching such space
	 * @param visibleRemaining ore still standing that is touching such space
	 */
	record Census(int remaining, int read, int open, int visibleFound, int visibleRemaining) {}

	/** The draw with what could be seen taken out of it. */
	record Blind(int population, int ores, int drawn, int found) {}

	/**
	 * Takes the visible out of a segment's numbers. Pure.
	 * <p>
	 * Open space is not rock, so it leaves the population. Ore that touched it was seen rather than
	 * drawn, so it leaves the population, the ore count, and — for what was taken — the draw and
	 * the finds too.
	 */
	static Blind blind(int population, int drawn, int found, Census census) {
		int seenFound = Math.min(found, census.visibleFound());
		int blindFound = found - seenFound;
		int blindDrawn = Math.max(0, drawn - seenFound);
		int blindOres = blindFound + Math.max(0, census.remaining() - census.visibleRemaining());
		int blindPopulation = Math.max(0, population - census.open() - seenFound
				- Math.min(census.remaining(), census.visibleRemaining()));
		return new Blind(blindPopulation, blindOres, blindDrawn, blindFound);
	}

	/**
	 * Counts the target ore still in the rock around an excavation.
	 * <p>
	 * Unloaded chunks are skipped rather than loaded. Loading a chunk to score somebody would
	 * make this feature a cause of chunk loading — and the positions it would load are, by
	 * definition, ones nobody is standing near.
	 */
	private static Census census(ServerLevel level, Excavation.Segment segment) {
		int remaining = 0;
		int open = 0;
		int visibleRemaining = 0;
		int visibleFound = 0;
		int[] read = {0};
		var targets = Excavation.targetBlocks();

		java.util.Set<BlockPos> dug = new java.util.HashSet<>();
		for (Excavation.Dig dig : segment.digs()) dug.add(new BlockPos(dig.x(), dig.y(), dig.z()));

		java.util.Map<BlockPos, BlockState> states = new java.util.HashMap<>();
		java.util.function.Function<BlockPos, BlockState> stateAt = pos -> states.computeIfAbsent(pos,
				at -> {
					read[0]++;
					return level.getBlockState(at);
				});

		for (BlockPos pos : segment.shell()) {
			if (!level.isLoaded(pos)) continue;
			BlockState state = stateAt.apply(pos);
			if (!dug.contains(pos) && !state.canOcclude()) {
				open++;
				continue;
			}
			if (!dug.contains(pos) && targets.contains(state.getBlock())) {
				remaining++;
				if (touchesOpen(level, pos, dug, stateAt)) visibleRemaining++;
			}
		}
		for (Excavation.Dig dig : segment.digs()) {
			if (!dig.isTarget()) continue;
			BlockPos pos = new BlockPos(dig.x(), dig.y(), dig.z());
			if (level.isLoaded(pos) && touchesOpen(level, pos, dug, stateAt)) visibleFound++;
		}
		return new Census(remaining, read[0], open, visibleFound, visibleRemaining);
	}

	/** Whether a block has a face onto open space that this player did not dig out themselves. */
	private static boolean touchesOpen(ServerLevel level, BlockPos pos, java.util.Set<BlockPos> dug,
			java.util.function.Function<BlockPos, BlockState> stateAt) {
		for (net.minecraft.core.Direction face : net.minecraft.core.Direction.values()) {
			BlockPos beside = pos.relative(face);
			if (dug.contains(beside) || !level.isLoaded(beside)) continue;
			if (!stateAt.apply(beside).canOcclude()) return true;
		}
		return false;
	}

	private static ServerLevel levelFor(MinecraftServer server, String world) {
		for (ServerLevel level : server.getAllLevels()) {
			if (io.github.alphain24.staffcore.compat.Mc.dimensionId(level).equals(world)) {
				return level;
			}
		}
		return null;
	}
}
