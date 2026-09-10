package io.github.alphain24.staffcore.modules.replay;

import io.github.alphain24.staffcore.StaffCore;
import io.github.alphain24.staffcore.config.StaffConfig;
import io.github.alphain24.staffcore.storage.Storage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gate 4: how much disk an hour of one player actually costs.
 *
 * <h2>Why this is measured rather than estimated</h2>
 * The whole shape of this feature — two tables, deltas in fixed point, no rows while standing
 * still, {@code WITHOUT ROWID} — was chosen to make a number small. A design argued for on size
 * and never weighed is an argument, not a result, and this project has already found that a
 * threshold justified by reasoning rather than measurement is usually justifying the wrong
 * thing.
 * <p>
 * So this writes a known number of player-hours into a real SQLite file and asks the file how
 * big it got. The figure it prints is the one quoted in {@code docs/decisions.md} and in the
 * handbook, and the ceiling it asserts is loose enough not to be brittle and tight enough to
 * catch a change that doubles the cost — which is what dropping {@code WITHOUT ROWID}, or
 * storing the deltas as {@code REAL}, or putting the UUID back on every row would each do.
 *
 * <h2>Two figures, because one would mislead</h2>
 * <b>Continuous movement</b> is the worst case and the one to budget with: a player who never
 * stops for an hour. <b>A realistic session</b> mixes walking with standing still, which is what
 * players actually do and what the storage rules were written for. Quoting only the first would
 * frighten people off a feature that costs a third of that in practice; quoting only the second
 * would understate what a busy server can hit.
 */
class PositionGrowthTest {

	@TempDir
	Path world;

	private Storage storage;

	@BeforeEach
	void open() {
		storage = StaffCore.storage();
		storage.open(world);
		PositionSampler.forgetAll();
		StaffConfig.get().positionTracking = true;
	}

	@AfterEach
	void close() {
		PositionSampler.forgetAll();
		StaffConfig.get().positionTracking = false;
		if (storage != null) storage.close();
	}

	/** The logical size of the database, in bytes, straight from SQLite. */
	private long databaseBytes() {
		try (Statement st = storage.conn().createStatement()) {
			long pages = 0;
			long pageSize = 0;
			try (ResultSet rs = st.executeQuery("PRAGMA page_count")) {
				if (rs.next()) pages = rs.getLong(1);
			}
			try (ResultSet rs = st.executeQuery("PRAGMA page_size")) {
				if (rs.next()) pageSize = rs.getLong(1);
			}
			return pages * pageSize;
		} catch (SQLException e) {
			throw new AssertionError("could not measure the database", e);
		}
	}

	/**
	 * Writes one player-hour and returns what it cost.
	 *
	 * @param movingFraction how much of the hour they spend actually going somewhere
	 */
	private long playerHour(UUID player, long startAt, double movingFraction) {
		long before = databaseBytes();
		Random random = new Random(player.getLeastSignificantBits());

		double x = 100;
		double y = 64;
		double z = 200;
		int samples = 2 * 60 * 60;

		// Alternating stretches of walking and standing, rather than a coin flip per sample.
		// Players move in runs, and it is runs that the encoder's rules are about — a random
		// scatter would restart the chain constantly and measure a case nobody produces.
		int stretch = 0;
		boolean moving = true;

		for (int i = 0; i < samples; i++) {
			if (stretch-- <= 0) {
				moving = random.nextDouble() < movingFraction;
				stretch = 20 + random.nextInt(60);
			}
			if (moving) {
				// About four blocks a second, wandering — a walk, not a straight line.
				x += (random.nextDouble() - 0.5) * 4;
				z += (random.nextDouble() - 0.5) * 4;
				y += (random.nextDouble() - 0.5) * 0.4;
			}
			PositionSampler.record(player, "Subject", "minecraft:overworld",
					(int) Math.round(x * PositionLog.SCALE),
					(int) Math.round(y * PositionLog.SCALE),
					(int) Math.round(z * PositionLog.SCALE),
					random.nextInt(256) - 128, random.nextInt(64) - 32, startAt + i * 500L);
		}
		// flush(), not write(). write() takes a bounded batch — 4096 rows — and hands the
		// rest back to the worker, which does not exist here. Using it clipped both scenarios
		// to the same 4096 samples and made them look almost identical, which is how the
		// first version of this measurement came out saying stillness saved nine percent.
		PositionSampler.flush();
		return databaseBytes() - before;
	}

	/** The figure the documentation quotes. Loose enough not to be brittle. */
	private static final long CEILING_PER_HOUR = 400 * 1024;

	@Test
	@DisplayName("Gate 4: an hour of continuous movement costs less than the documented ceiling")
	void continuousMovement() {
		long bytes = playerHour(UUID.randomUUID(), 1_700_000_000_000L, 1.0);
		long samples = count("SELECT COUNT(*) FROM position_log");

		System.out.printf("[Gate 4] continuous movement: %,d bytes for %,d samples "
				+ "(%.1f bytes/sample, %.0f KB per player-hour)%n",
				bytes, samples, bytes / (double) samples, bytes / 1024.0);

		assertTrue(samples > 7000,
				"only " + samples + " samples were written for an hour of continuous movement "
						+ "at 2 Hz, so this measured something other than a full player-hour");
		assertTrue(bytes < CEILING_PER_HOUR,
				"an hour of continuous movement cost " + bytes / 1024 + " KB, past the "
						+ CEILING_PER_HOUR / 1024 + " KB this feature is documented at. "
						+ "Something in the storage shape changed — a rowid table, REAL "
						+ "columns, or the UUID back on every row would each roughly double "
						+ "it. Re-measure and update docs/decisions.md, or put it back.");
	}

	@Test
	@DisplayName("Gate 4: a realistic session costs meaningfully less, because stillness is free")
	void aRealisticSession() {
		long moving = playerHour(UUID.randomUUID(), 1_700_000_000_000L, 1.0);
		long movingRows = count("SELECT COUNT(*) FROM position_log");

		PositionSampler.forgetAll();
		long mixed = playerHour(UUID.randomUUID(), 1_800_000_000_000L, 0.5);
		long mixedRows = count("SELECT COUNT(*) FROM position_log") - movingRows;

		System.out.printf("[Gate 4] half the hour standing still: %,d bytes (%.0f KB) in %,d "
				+ "rows, against %,d bytes in %,d rows moving throughout%n",
				mixed, mixed / 1024.0, mixedRows, moving, movingRows);

		// The rule that makes this true is "no row while standing still". If somebody removes
		// it the feature still works perfectly and quietly costs twice as much, which is the
		// kind of change nothing else here would notice.
		assertTrue(mixed < moving * 0.8,
				"a player standing still for half an hour cost " + mixed + " bytes against "
						+ moving + " for one who never stopped. Stillness is supposed to be "
						+ "free — the gap between two timestamps already records it. Check "
						+ "that the movement threshold still discards a stationary player.");
	}

	@Test
	@DisplayName("the estimate used when SQLite cannot be asked is close to the truth")
	void theFallbackEstimateIsHonest() {
		// /staff status reports an exact figure from dbstat when the SQLite build has it, and
		// falls back to rows times BYTES_PER_SAMPLE when it does not. A constant that has
		// drifted from reality would make that fallback a confident wrong number, which is
		// worse than the "(estimated)" label admits.
		long bytes = playerHour(UUID.randomUUID(), 1_700_000_000_000L, 1.0);
		long samples = count("SELECT COUNT(*) FROM position_log");
		double actual = bytes / (double) samples;
		System.out.printf("[Gate 4] %.1f bytes per sample against a documented %d%n",
				actual, PositionLog.BYTES_PER_SAMPLE);

		assertTrue(actual < PositionLog.BYTES_PER_SAMPLE * 2.0
						&& actual > PositionLog.BYTES_PER_SAMPLE * 0.4,
				"a sample really costs %.1f bytes but PositionLog.BYTES_PER_SAMPLE says %d. "
						.formatted(actual, PositionLog.BYTES_PER_SAMPLE)
						+ "That constant is what /staff status reports when dbstat is not "
						+ "available, so it is being shown to server owners as a size.");
	}

	private long count(String sql) {
		try (Statement st = storage.conn().createStatement(); ResultSet rs = st.executeQuery(sql)) {
			return rs.next() ? rs.getLong(1) : -1;
		} catch (SQLException e) {
			throw new AssertionError(sql, e);
		}
	}

	// ------------------------------------------------------------------ restart

	@Test
	@DisplayName("Gate 4: a window spanning a restart replays as one window")
	void aReplaySpansARestart() {
		UUID subject = UUID.randomUUID();
		long t0 = 1_700_000_000_000L;

		// Before the restart.
		for (int i = 0; i <= 10; i++) {
			PositionSampler.record(subject, "Subject", "minecraft:overworld",
					(100 + i) * PositionLog.SCALE, 64 * PositionLog.SCALE,
					200 * PositionLog.SCALE, 0, 0, t0 + i * 500L);
		}
		PositionSampler.flush();

		// The restart. The connection goes and every encoder with it, which is exactly what
		// happens to a running server: the in-memory chain is lost and the rows are not.
		storage.close();
		PositionSampler.forgetAll();
		storage.open(world);

		// After it. A new run, because nothing survived to continue the old one.
		long t1 = t0 + 300_000;
		for (int i = 0; i <= 10; i++) {
			PositionSampler.record(subject, "Subject", "minecraft:overworld",
					(500 + i) * PositionLog.SCALE, 70 * PositionLog.SCALE,
					900 * PositionLog.SCALE, 0, 0, t1 + i * 500L);
		}
		PositionSampler.flush();

		PositionLog.Track track = PositionLog.reconstruct(subject, "Subject", t0 - 1000,
				t1 + 60_000);

		assertEquals(22, track.frames().size(),
				"a window spanning a restart came back with " + track.frames().size()
						+ " frames instead of 22. The stretch on the far side of the restart "
						+ "is on disk either way — if it is missing here it is unreachable, "
						+ "which is the failure the run row's upper-bound ended_at exists to "
						+ "prevent.");
		assertEquals(2, track.runs(), "the two sides should be separate runs");

		assertEquals(100, track.first().x(), 1.0 / PositionLog.SCALE, "wrong start");
		assertEquals(510, track.last().x(), 1.0 / PositionLog.SCALE,
				"the stretch after the restart reconstructed from the wrong origin");
	}

	@Test
	@DisplayName("Gate 4: a run the server died in the middle of is still reachable")
	void aCrashedRunIsNotLost() {
		// The case the restart test above cannot reach, because it shuts down cleanly. If the
		// process is killed, nothing calls closeRun, so ended_at keeps the upper bound it was
		// opened with. A query that trusted ended_at as a promise would find the run; one that
		// had written started_at into it would silently exclude it and leave the samples on
		// disk with nothing able to read them.
		UUID subject = UUID.randomUUID();
		long t0 = 1_700_000_000_000L;

		for (int i = 0; i <= 10; i++) {
			PositionSampler.record(subject, "Subject", "minecraft:overworld",
					(100 + i) * PositionLog.SCALE, 64 * PositionLog.SCALE,
					200 * PositionLog.SCALE, 0, 0, t0 + i * 500L);
		}
		PositionSampler.write();

		// Killed: the encoder is dropped without closeRun ever running.
		PositionSampler.forgetAll();

		long stillOpen = count("SELECT COUNT(*) FROM position_run WHERE samples = 0");
		assertTrue(stillOpen > 0,
				"every run was closed, so this test is not exercising the crash case at all");

		PositionLog.Track track = PositionLog.reconstruct(subject, "Subject", t0 - 1000,
				t0 + 60_000);
		assertEquals(11, track.frames().size(),
				"a run that was never closed came back empty. Its samples are on disk and "
						+ "nothing can reach them.");
	}
}
