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

	/** Where the last sweep spent its time, for {@code /staff status} and for Gate 3. */
	public record Timing(long readMicros, long censusMicros, long mathMicros, int blocksRead,
			int players) {

		public long totalMicros() {
			return readMicros + censusMicros + mathMicros;
		}
	}

	private static volatile Timing lastTiming = new Timing(0, 0, 0, 0, 0);

	public static Timing lastTiming() {
		return lastTiming;
	}

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
			onDone.accept(analyse(server, byPlayer, readMicros));
		});
	}

	/**
	 * Every relevant break in the window, grouped by player.
	 * <p>
	 * Runs on the worker. Creative breaks are excluded: a staff member in creative flattening
	 * a build is not mining, and their block count would swamp everybody else's.
	 */
	static Map<String, List<Excavation.Dig>> loadDigs(long windowMs) {
		Map<String, List<Excavation.Dig>> byPlayer = new LinkedHashMap<>();
		if (!StaffCore.storage().isReady()) return byPlayer;

		try (PreparedStatement ps = StaffCore.storage().conn().prepareStatement("""
				SELECT player_name, block, world, x, y, z, created_at
				FROM block_log
				WHERE action = 'BREAK' AND created_at >= ?
				  AND (gamemode IS NULL OR gamemode <> 'creative')
				ORDER BY player_name, created_at
				""")) {
			ps.setLong(1, System.currentTimeMillis() - windowMs);

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
	private static List<Finding> analyse(MinecraftServer server,
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
				// which is where every false positive in the old detector lived.
				if (segment.population() < cfg.xrayMinimumVolume) continue;

				ServerLevel level = levelFor(server, segment.world());
				if (level == null) continue;

				long censusStart = System.nanoTime();
				Census census = census(level, segment);
				censusNanos += System.nanoTime() - censusStart;
				blocksRead += census.read();

				long mathStart = System.nanoTime();
				int ores = segment.found() + census.remaining();
				double p = Hypergeometric.atLeast(segment.population(), ores,
						segment.drawn(), segment.found());
				mathNanos += System.nanoTime() - mathStart;

				if (ores > 0 && segment.found() > 0) {
					findings.add(new Finding(entry.getKey(), segment.world(), segment.band(),
							segment.population(), ores, segment.drawn(), segment.found(), p));
				}
			}
		}

		lastTiming = new Timing(readMicros, censusNanos / 1000, mathNanos / 1000, blocksRead,
				byPlayer.size());
		return findings;
	}

	/** How much ore is still standing, and how many blocks it took to find out. */
	private record Census(int remaining, int read) {}

	/**
	 * Counts the target ore still in the rock around an excavation.
	 * <p>
	 * Unloaded chunks are skipped rather than loaded. Loading a chunk to score somebody would
	 * make this feature a cause of chunk loading — and the positions it would load are, by
	 * definition, ones nobody is standing near.
	 */
	private static Census census(ServerLevel level, Excavation.Segment segment) {
		int remaining = 0;
		int read = 0;

		for (BlockPos pos : segment.shell()) {
			if (!level.isLoaded(pos)) continue;

			read++;
			BlockState state = level.getBlockState(pos);
			String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK
					.getKey(state.getBlock()).toString();

			if (Excavation.targets().contains(id)) remaining++;
		}
		return new Census(remaining, read);
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
