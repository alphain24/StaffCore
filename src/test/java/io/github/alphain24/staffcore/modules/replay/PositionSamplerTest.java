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
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What actually reaches the disk, and what comes back out.
 *
 * <h2>What these are really checking</h2>
 * A position log is a delta chain, and a delta chain has a specific way of being wrong: every
 * error is permanent and cumulative. Drop one sample, mis-order two, or skip the arithmetic for
 * a sample you were not going to draw, and every frame after it is displaced by that amount for
 * the rest of the run. Nothing about the result looks broken — it is a smooth, plausible path
 * through the world that the player never walked.
 * <p>
 * That is why these tests compare reconstructed coordinates against the ones that went in,
 * rather than counting rows. A row count says the writer ran. Only the round trip says the
 * replay shows where somebody was.
 *
 * <h2>Driven through the real decisions</h2>
 * Everything here goes in through {@link PositionSampler#record}, which is the same entry point
 * the tick loop uses. The rules about standing still, restarting on a gap and refusing to
 * encode a teleport as movement are the code under test, not conditions the test arranges for
 * itself.
 */
class PositionSamplerTest {

	@TempDir
	Path world;

	private Storage storage;
	private final UUID subject = UUID.nameUUIDFromBytes("position-subject".getBytes());

	/** A fixed origin, so an assertion failure prints coordinates rather than noise. */
	private static final long T0 = 1_700_000_000_000L;

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

	/** Feeds one reading in blocks, at the given offset from T0. */
	private void move(double x, double y, double z, long msFromStart) {
		PositionSampler.record(subject, "Subject", "minecraft:overworld",
				(int) Math.round(x * PositionLog.SCALE),
				(int) Math.round(y * PositionLog.SCALE),
				(int) Math.round(z * PositionLog.SCALE),
				0, 0, T0 + msFromStart);
	}

	private PositionLog.Track replay(long fromMs, long toMs) {
		PositionSampler.write();
		return PositionLog.reconstruct(subject, "Subject", T0 + fromMs, T0 + toMs);
	}

	@Test
	@DisplayName("a walked path comes back as the path that was walked")
	void theRoundTrip() {
		for (int i = 0; i <= 20; i++) {
			move(100 + i * 1.5, 64, 200 - i * 0.75, i * 500L);
		}

		PositionLog.Track track = replay(-1000, 30_000);
		assertEquals(21, track.frames().size(),
				"every sample of a continuously moving player should come back");

		for (int i = 0; i <= 20; i++) {
			PositionLog.Frame frame = track.frames().get(i);
			// One 1/32 unit of quantisation, and nothing beyond it. A drifting delta chain
			// fails here on the later frames while the early ones still look right, which is
			// exactly how this class of bug presents.
			assertEquals(100 + i * 1.5, frame.x(), 1.0 / PositionLog.SCALE,
					"frame " + i + " came back at the wrong x — a delta chain that has "
							+ "drifted is wrong from the point of the mistake onward");
			assertEquals(200 - i * 0.75, frame.z(), 1.0 / PositionLog.SCALE,
					"frame " + i + " came back at the wrong z");
			assertEquals(T0 + i * 500L, frame.at(), "frame " + i + " came back at the wrong time");
		}
	}

	@Test
	@DisplayName("standing still writes nothing at all")
	void stillnessCostsNoRows() {
		move(100, 64, 200, 0);
		for (int i = 1; i <= 40; i++) {
			// Jitter below the movement threshold, alternating so it is a real comparison
			// against the threshold rather than an exact-equality check that any positive
			// threshold would pass.
			move(100 + (i % 2 == 0 ? 0.02 : -0.02), 64, 200, i * 500L);
		}

		PositionLog.Track track = replay(-1000, 60_000);
		assertEquals(1, track.frames().size(),
				"a player who did not move produced " + track.frames().size() + " rows. The "
						+ "gap between two timestamps already says they stayed where they "
						+ "were, and this is the largest table in the database.");
	}

	@Test
	@DisplayName("the stillness rule does not also swallow real movement")
	void theControlForStillness() {
		// Without this, a movement threshold set absurdly high — or a comparison with the
		// wrong sign — would make the test above pass by writing nothing ever.
		move(100, 64, 200, 0);
		move(100.5, 64, 200, 500);

		assertEquals(2, replay(-1000, 10_000).frames().size(),
				"half a block of movement was discarded as standing still, so the test above "
						+ "cannot tell stillness from a sampler that records nothing");
	}

	@Test
	@DisplayName("a gap in the samples becomes a gap in the replay, not a straight line")
	void aGapEndsTheRun() {
		move(100, 64, 200, 0);
		move(102, 64, 200, 500);
		// Logged off, or stood still for a long while. Either way they were not walking.
		move(400, 70, 900, 120_000);
		move(402, 70, 900, 120_500);

		PositionLog.Track track = replay(-1000, 200_000);
		assertEquals(4, track.frames().size(), "every sample should still be present");
		assertEquals(2, track.runs(),
				"the two stretches were joined into one chain. A single run across that gap "
						+ "encodes a 300-block delta as ordinary movement, and the replay "
						+ "draws somebody crossing the map in half a second.");

		assertEquals(400, track.frames().get(2).x(), 1.0 / PositionLog.SCALE,
				"the position after the gap is wrong, which means the second stretch was "
						+ "reconstructed from the first stretch's origin");
	}

	@Test
	@DisplayName("a teleport is not encoded as movement")
	void aTeleportRestartsTheChain() {
		move(100, 64, 200, 0);
		move(101, 64, 200, 500);
		// Same second, 500 blocks away. A /tp, a portal, or an end-gateway.
		move(600, 64, 700, 1000);

		PositionLog.Track track = replay(-1000, 10_000);
		assertEquals(2, track.runs(),
				"a 500-block jump inside half a second was recorded as a delta. The replay "
						+ "then shows a player travelling in a straight line at a speed no "
						+ "legitimate movement reaches, which reads as evidence and is not.");
		assertEquals(600, track.frames().getLast().x(), 1.0 / PositionLog.SCALE);
	}

	@Test
	@DisplayName("a run is cut before ms outgrows two bytes")
	void runsAreBounded() {
		for (int i = 0; i * 500L <= PositionSampler.RUN_MAX_MS * 2L; i++) {
			move(100 + i * 0.5, 64, 200, i * 500L);
		}

		PositionLog.Track track = replay(-1000, 300_000);
		assertTrue(track.runs() >= 3,
				"a player moving for two minutes without a break produced " + track.runs()
						+ " run(s). Runs are capped at a minute so ms stays small, so a lost "
						+ "chain costs a minute rather than a session, and so retention can "
						+ "delete whole runs.");

		// And the path is still continuous across the cut, which is the thing that would
		// break if a restart forgot to carry the absolute position over.
		PositionLog.Frame last = track.frames().getLast();
		double expected = 100 + (track.frames().size() - 1) * 0.5;
		assertEquals(expected, last.x(), 1.0 / PositionLog.SCALE,
				"the path is discontinuous across a run boundary");
	}

	@Test
	@DisplayName("a window starting mid-run is still reconstructed from the run's origin")
	void aPartialWindowIsNotDisplaced() {
		// The subtle one. A delta chain has no entry point but its beginning, so frames before
		// the window still have to be accumulated and then dropped. Skipping the arithmetic
		// for a sample nobody is going to look at displaces every frame after it by however
		// far the player moved during the part that was skipped — and the result is a smooth,
		// plausible path through the wrong part of the world.
		for (int i = 0; i <= 20; i++) {
			move(100 + i, 64, 200, i * 500L);
		}

		PositionLog.Track track = replay(5_000, 30_000);
		assertFalse(track.isEmpty(), "the window found nothing to reconstruct");

		PositionLog.Frame first = track.first();
		assertEquals(110, first.x(), 1.0 / PositionLog.SCALE,
				"the first frame of a mid-run window came back at x=" + first.x()
						+ " instead of 110. The deltas before the window were not "
						+ "accumulated, so the whole replay is offset by the distance "
						+ "travelled before it started.");
	}

	@Test
	@DisplayName("runs are found by overlap, not by having started inside the window")
	void anAlreadyRunningStretchIsFound() {
		// The run starts well before the window. Asking only for runs that began inside it
		// would drop the one the player was already in when the interesting thing happened,
		// which is usually the one somebody is looking for.
		for (int i = 0; i <= 10; i++) {
			move(100 + i, 64, 200, i * 500L);
		}

		List<PositionLog.Run> runs = PositionLog.runsOverlapping(subject, T0 + 3_000, T0 + 4_000);
		PositionSampler.write();
		runs = PositionLog.runsOverlapping(subject, T0 + 3_000, T0 + 4_000);

		assertEquals(1, runs.size(),
				"a run that started before the window and was still going was not found");
	}

	@Test
	@DisplayName("nothing is written for a player nobody asked to track")
	void trackingOffWritesNothing() {
		StaffConfig.get().positionTracking = false;
		assertFalse(PositionSampler.enabled(),
				"position tracking reports itself enabled with the config key off");
	}
}
