package io.github.alphain24.staffcore.modules.replay;

import io.github.alphain24.staffcore.StaffCore;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Where players have been, stored small enough to be worth keeping.
 *
 * <h2>What a row costs, and why that is the whole design</h2>
 * At two samples a second, one player moving continuously for an hour produces 7,200 rows.
 * Ten such players over a week is five million. Every byte per row is five megabytes, so the
 * decisions that would be premature optimisation anywhere else in this mod are the difference
 * between a feature and a disk-space incident here.
 * <p>
 * Three of them do nearly all the work:
 * <ul>
 *   <li><b>The identity lives once per run, not once per sample.</b> A UUID is thirty-six
 *       bytes of text — on its own more than twice the size of everything else on the row.
 *       {@code position_run} carries it, along with the world and the absolute starting
 *       position; {@code position_log} carries only measurements.</li>
 *   <li><b>Samples are deltas in fixed point.</b> A player walking covers about two blocks
 *       between samples, which at 1/32-block resolution is a number SQLite stores in a single
 *       byte. The same figure as a {@code REAL} is eight bytes whatever it holds.</li>
 *   <li><b>Nothing is written while a player stands still.</b> The gap between two timestamps
 *       says they did not move; a row saying the same thing costs as much as a row saying
 *       where they went.</li>
 * </ul>
 *
 * <h2>Runs</h2>
 * A run is one unbroken chain of deltas. It starts at an absolute position and every sample
 * after that is relative to the one before it, so a run is read from its beginning or not at
 * all. A new one begins when a player joins, changes world, teleports, stands still for longer
 * than {@link PositionSampler#RUN_GAP_MS}, or the current one reaches
 * {@link PositionSampler#RUN_MAX_MS}.
 * <p>
 * That last rule is what makes the rest of this tractable. It bounds a run to a minute, which
 * bounds {@code ms} to a two-byte number, bounds how much data one corrupt or purged row can
 * cost, and makes retention a matter of deleting whole runs rather than repairing chains.
 *
 * <h2>ended_at is an upper bound until the run closes</h2>
 * A run's row is written when it starts, with {@code ended_at} set to the latest it could
 * possibly finish, and narrowed to the truth when it closes. It is written that way round
 * because the alternative fails silently: if the server stops mid-run, a row whose
 * {@code ended_at} was still at its starting value would be excluded from every window query
 * that should have found it, and the samples underneath it would be unreachable while
 * remaining on disk. Being too generous costs one extra empty run in a query result. Being too
 * mean loses a minute of a replay and never says so.
 */
public final class PositionLog {
	private PositionLog() {}

	/** Fixed-point resolution: 1/32 of a block, a little over three centimetres. */
	public static final int SCALE = 32;

	/**
	 * A stretch of continuous movement, keyed by its own id.
	 *
	 * @param x0 the absolute position of the run's first sample, in {@link #SCALE} units
	 */
	public record Run(long id, UUID uuid, String name, String world, long startedAt, long endedAt,
			int x0, int y0, int z0, int samples) {}

	/** One reconstructed moment: absolute, in blocks, at a wall-clock time. */
	public record Frame(long at, String world, double x, double y, double z,
			float yaw, float pitch) {}

	/**
	 * A reconstructed window.
	 *
	 * @param runs      how many separate stretches of movement the frames came from. More than
	 *                  one means the player stopped, relogged, teleported or changed world
	 *                  part way through — the gaps between frames are real.
	 * @param truncated whether the window held more samples than {@link #MAX_FRAMES} and was
	 *                  cut short. A silently shortened replay would look exactly like somebody
	 *                  logging off early.
	 */
	public record Track(String subject, List<Frame> frames, int runs, long from, long to,
			boolean truncated) {

		public boolean isEmpty() {
			return frames.isEmpty();
		}

		public Frame first() {
			return frames.isEmpty() ? null : frames.getFirst();
		}

		public Frame last() {
			return frames.isEmpty() ? null : frames.getLast();
		}

		/** How long the recorded movement actually spans, which is not the window asked for. */
		public long span() {
			return isEmpty() ? 0 : last().at() - first().at();
		}
	}

	/**
	 * The most frames one replay will reconstruct.
	 * <p>
	 * Six hours of continuous movement at 2 Hz. Past this the list is larger than anybody is
	 * going to sit and watch, and the memory is real — each frame is a small object but there
	 * can be a great many of them. The cap is reported rather than applied quietly.
	 */
	public static final int MAX_FRAMES = 43_200;

	// ----------------------------------------------------------------- writing

	/**
	 * Opens a run and returns its id, or -1 if it could not be written.
	 * <p>
	 * Called only from the writer thread. {@code endedAt} is the latest this run could
	 * possibly finish, narrowed by {@link #closeRun} when it does.
	 */
	static long openRun(UUID uuid, String name, String world, long startedAt, long latestEnd,
			int x0, int y0, int z0) {

		if (!StaffCore.storage().isReady()) return -1;

		try (PreparedStatement ps = StaffCore.storage().conn().prepareStatement("""
				INSERT INTO position_run (uuid, name, world, started_at, ended_at,
				                          x0, y0, z0, samples)
				VALUES (?,?,?,?,?,?,?,?,0)
				""", Statement.RETURN_GENERATED_KEYS)) {

			ps.setString(1, uuid.toString());
			ps.setString(2, name);
			ps.setString(3, world);
			ps.setLong(4, startedAt);
			ps.setLong(5, latestEnd);
			ps.setInt(6, x0);
			ps.setInt(7, y0);
			ps.setInt(8, z0);
			ps.executeUpdate();

			try (ResultSet keys = ps.getGeneratedKeys()) {
				return keys.next() ? keys.getLong(1) : -1;
			}
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Replay] could not open a position run", e);
			return -1;
		}
	}

	/** One sample. Called only from the writer thread, inside a batch. */
	static void appendSample(PreparedStatement ps, long run, int ms, int dx, int dy, int dz,
			int yaw, int pitch) throws SQLException {

		ps.setLong(1, run);
		ps.setInt(2, ms);
		ps.setInt(3, dx);
		ps.setInt(4, dy);
		ps.setInt(5, dz);
		ps.setInt(6, yaw);
		ps.setInt(7, pitch);
		ps.addBatch();
	}

	static final String INSERT_SAMPLE =
			"INSERT OR REPLACE INTO position_log (run, ms, dx, dy, dz, yaw, pitch) "
					+ "VALUES (?,?,?,?,?,?,?)";

	/** Narrows a run's end to the truth and records how many samples it holds. */
	static void closeRun(long run, long endedAt, int samples) {
		if (run < 0 || !StaffCore.storage().isReady()) return;

		try (PreparedStatement ps = StaffCore.storage().conn().prepareStatement(
				"UPDATE position_run SET ended_at = ?, samples = ? WHERE id = ?")) {
			ps.setLong(1, endedAt);
			ps.setInt(2, samples);
			ps.setLong(3, run);
			ps.executeUpdate();
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Replay] could not close position run {}", run, e);
		}
	}

	// ----------------------------------------------------------------- reading

	/**
	 * Every run of this player's that overlaps the window, oldest first.
	 * <p>
	 * The overlap test is deliberately the generous one: a run that started before the window
	 * and is still going belongs in it, and so does one that started inside it and ran past
	 * the end. Asking only for runs that started inside the window would drop the one the
	 * player was already in when the interesting thing happened, which is usually the one
	 * being looked for.
	 */
	public static List<Run> runsOverlapping(UUID uuid, long from, long to) {
		List<Run> out = new ArrayList<>();
		if (uuid == null || !StaffCore.storage().isReady()) return out;

		try (PreparedStatement ps = StaffCore.storage().conn().prepareStatement("""
				SELECT id, name, world, started_at, ended_at, x0, y0, z0, samples
				  FROM position_run
				 WHERE uuid = ? AND started_at <= ? AND ended_at >= ?
				 ORDER BY started_at
				""")) {
			ps.setString(1, uuid.toString());
			ps.setLong(2, to);
			ps.setLong(3, from);

			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					out.add(new Run(rs.getLong("id"), uuid, rs.getString("name"),
							rs.getString("world"), rs.getLong("started_at"),
							rs.getLong("ended_at"), rs.getInt("x0"), rs.getInt("y0"),
							rs.getInt("z0"), rs.getInt("samples")));
				}
			}
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Replay] could not list position runs", e);
		}
		return out;
	}

	/**
	 * Rebuilds a window into absolute positions.
	 * <p>
	 * Every run that overlaps is walked from its own beginning, because a delta chain has no
	 * other entry point — a window starting half way through a run is still reconstructed from
	 * the run's origin, and the frames before the window are accumulated and then dropped.
	 * That is why runs are capped at a minute: it bounds how much work "half way through"
	 * can mean.
	 * <p>
	 * The gaps between runs are left as gaps. A player who logged off for an hour has an hour
	 * between two frames, and inventing anything to put there would be inventing evidence.
	 */
	public static Track reconstruct(UUID uuid, String subject, long from, long to) {
		List<Frame> frames = new ArrayList<>();
		List<Run> runs = runsOverlapping(uuid, from, to);
		boolean truncated = false;
		int used = 0;

		for (Run run : runs) {
			if (frames.size() >= MAX_FRAMES) {
				truncated = true;
				break;
			}

			int x = run.x0();
			int y = run.y0();
			int z = run.z0();
			boolean any = false;

			try (PreparedStatement ps = StaffCore.storage().conn().prepareStatement(
					"SELECT ms, dx, dy, dz, yaw, pitch FROM position_log WHERE run = ? "
							+ "ORDER BY ms")) {
				ps.setLong(1, run.id());

				try (ResultSet rs = ps.executeQuery()) {
					while (rs.next()) {
						// Accumulated before the window test, never inside it. Skipping the
						// arithmetic for a sample outside the window would leave every frame
						// after it displaced by however far the player moved during the part
						// that was skipped.
						x += rs.getInt("dx");
						y += rs.getInt("dy");
						z += rs.getInt("dz");

						long at = run.startedAt() + rs.getInt("ms");
						if (at < from || at > to) continue;
						if (frames.size() >= MAX_FRAMES) {
							truncated = true;
							break;
						}

						frames.add(new Frame(at, run.world(), x / (double) SCALE,
								y / (double) SCALE, z / (double) SCALE,
								unpackAngle(rs.getInt("yaw")), unpackAngle(rs.getInt("pitch"))));
						any = true;
					}
				}
			} catch (SQLException e) {
				StaffCore.LOGGER.error("[Replay] could not read position run {}", run.id(), e);
			}
			if (any) used++;
		}

		return new Track(subject, frames, used, from, to, truncated);
	}

	/** A stored byte angle back to degrees. */
	static float unpackAngle(int packed) {
		return packed * 360.0f / 256.0f;
	}

	/** Degrees to the signed byte the wire format uses, which is all a replay needs. */
	static int packAngle(float degrees) {
		return (byte) Math.round(degrees * 256.0f / 360.0f);
	}

	// -------------------------------------------------------------- retention

	/**
	 * Deletes runs that started before the cutoff, and their samples.
	 * <p>
	 * Whole runs, by start time, which means a run straddling the cutoff goes entirely — up to
	 * a minute of data slightly newer than the retention window asked for. That is the right
	 * direction to be wrong in for a table of where people have been: deleting a little early
	 * is a smaller mistake than keeping a partial chain that cannot be read from its beginning
	 * anyway.
	 *
	 * @return how many runs were deleted
	 */
	public static int purge(long cutoff) {
		if (!StaffCore.storage().isReady()) return 0;

		int[] deleted = {0};
		// One transaction. A run row without its samples is a hole in a query result; samples
		// without their run are bytes nothing can ever read or delete again.
		StaffCore.storage().inTransaction(conn -> {
			try (PreparedStatement ps = conn.prepareStatement(
					"DELETE FROM position_log WHERE run IN "
							+ "(SELECT id FROM position_run WHERE started_at < ?)")) {
				ps.setLong(1, cutoff);
				ps.executeUpdate();
			}
			try (PreparedStatement ps = conn.prepareStatement(
					"DELETE FROM position_run WHERE started_at < ?")) {
				ps.setLong(1, cutoff);
				deleted[0] = ps.executeUpdate();
			}
		});

		if (deleted[0] > 0) {
			StaffCore.LOGGER.info("[Replay] Purged {} position run(s) past the retention window",
					deleted[0]);
		}
		return deleted[0];
	}

	// -------------------------------------------------------------- reporting

	/**
	 * How much of the database the position log is, for {@code /staff status}.
	 *
	 * @param measured whether {@code bytes} came from SQLite itself or from multiplying the
	 *                 row count by a measured average. The distinction is reported rather than
	 *                 smoothed over: an estimate presented as a measurement is how a number
	 *                 nobody can check ends up in a decision.
	 */
	public record Size(long runs, long samples, long bytes, boolean measured) {

		public String describe() {
			if (samples == 0) return "nothing recorded";

			return "%,d sample(s) in %,d run(s), %s%s".formatted(samples, runs, human(bytes),
					measured ? "" : " (estimated)");
		}

		private static String human(long bytes) {
			if (bytes < 1024) return bytes + " B";
			if (bytes < 1024 * 1024) return "%.1f KB".formatted(bytes / 1024.0);
			return "%.1f MB".formatted(bytes / (1024.0 * 1024.0));
		}
	}

	/**
	 * The average bytes a sample occupies on disk, including its share of the run row.
	 * <p>
	 * Measured, not guessed — see {@code PositionGrowthTest}, which writes a known number of
	 * player-hours and reads the file size. Used only when SQLite cannot be asked directly.
	 */
	public static final int BYTES_PER_SAMPLE = 21;

	public static Size size() {
		if (!StaffCore.storage().isReady()) return new Size(0, 0, 0, true);

		long runs = count("SELECT COUNT(*) FROM position_run");
		long samples = count("SELECT COUNT(*) FROM position_log");

		// dbstat is a compile-time option and this mod does not control which SQLite build a
		// server ends up with, so the exact answer is asked for and the estimate is the
		// fallback rather than the other way round.
		try (Statement st = StaffCore.storage().conn().createStatement();
				ResultSet rs = st.executeQuery("SELECT SUM(pgsize) FROM dbstat WHERE name IN "
						+ "('position_log', 'position_run', 'idx_position_run_lookup')")) {
			if (rs.next()) {
				long bytes = rs.getLong(1);
				if (!rs.wasNull()) return new Size(runs, samples, bytes, true);
			}
		} catch (SQLException noDbstat) {
			// Expected on a build without SQLITE_ENABLE_DBSTAT_VTAB. Not an error.
		}
		return new Size(runs, samples, samples * BYTES_PER_SAMPLE, false);
	}

	private static long count(String sql) {
		try (Statement st = StaffCore.storage().conn().createStatement();
				ResultSet rs = st.executeQuery(sql)) {
			return rs.next() ? rs.getLong(1) : 0;
		} catch (SQLException e) {
			return 0;
		}
	}
}
