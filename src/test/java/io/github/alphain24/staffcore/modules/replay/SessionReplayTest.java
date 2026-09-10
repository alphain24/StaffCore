package io.github.alphain24.staffcore.modules.replay;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where the camera goes, and what it is allowed to hide.
 *
 * <h2>The failure worth testing for</h2>
 * A playback that is broken is obvious — the viewer sits still, or ends up in the void.
 * A playback that is <em>misleading</em> is not, and there is one specific way to get there:
 * interpolating across a gap.
 * <p>
 * Position history records movement, so a player who logged off for two hours leaves two
 * samples with two hours between them. Interpolated, that is a staff member watching somebody
 * glide smoothly across four hundred blocks — a perfectly plausible flight that never happened,
 * played back as evidence. The replay has to notice the gap, jump it, and say that it did.
 * <p>
 * So these drive {@link SessionReplay#step}, which is the decision rather than the rendering.
 * It advances the clock and reports what that tick means without needing a player, a world or a
 * server.
 */
class SessionReplayTest {

	private static final long T0 = 1_700_000_000_000L;
	private static final String OVERWORLD = "minecraft:overworld";

	private static PositionLog.Frame frame(long at, double x, String world) {
		return new PositionLog.Frame(at, world, x, 64, 200, 0, 0);
	}

	private static SessionReplay.Playback playback(List<PositionLog.Frame> frames) {
		PositionLog.Track track = new PositionLog.Track("Subject", frames, 1,
				frames.getFirst().at(), frames.getLast().at(), false);
		return new SessionReplay.Playback("Subject", track, frames.getFirst().world());
	}

	/** Half a second apart, one block apart — an ordinary walk. */
	private static List<PositionLog.Frame> walk(int samples) {
		List<PositionLog.Frame> out = new ArrayList<>();
		for (int i = 0; i < samples; i++) out.add(frame(T0 + i * 500L, 100 + i, OVERWORLD));
		return out;
	}

	@Test
	@DisplayName("playback moves along the path between samples")
	void itInterpolates() {
		SessionReplay.Playback playback = playback(walk(4));

		// Half way between the first two samples: 250ms in, at 5 ticks of 50ms.
		SessionReplay.Step step = null;
		for (int i = 0; i < 5; i++) step = SessionReplay.step(playback);

		SessionReplay.Step.Move move = assertInstanceOf(SessionReplay.Step.Move.class, step);
		assertEquals(100.5, move.x(), 0.001,
				"the camera did not move between two samples, so playback either jumps from "
						+ "sample to sample or does not advance at all");
	}

	@Test
	@DisplayName("a long gap is skipped rather than flown across")
	void gapsAreSkipped() {
		// Two hours between the second sample and the third. Interpolated, that is a staff
		// member watching somebody glide 400 blocks in a straight line at constant speed —
		// a flight that never happened, played back as if it had.
		List<PositionLog.Frame> frames = List.of(
				frame(T0, 100, OVERWORLD),
				frame(T0 + 500, 101, OVERWORLD),
				frame(T0 + 7_200_000L, 500, OVERWORLD),
				frame(T0 + 7_200_500L, 501, OVERWORLD));

		SessionReplay.Playback playback = playback(frames);

		SessionReplay.Step.Skip skip = null;
		for (int i = 0; i < 200 && skip == null; i++) {
			if (SessionReplay.step(playback) instanceof SessionReplay.Step.Skip found) {
				skip = found;
			}
		}

		assertTrue(skip != null,
				"a two-hour gap was never reported as a skip. Either the replay is "
						+ "interpolating across it — showing movement that did not happen — or "
						+ "it is playing two hours of real time in which nothing occurs.");
		assertEquals(7_200_000L - 500, skip.gap(),
				"the skip reported the wrong length, so the chat line understates or "
						+ "overstates how much time was removed");
		assertEquals(500, skip.x(), 0.001, "the skip landed somewhere other than the next sample");
	}

	@Test
	@DisplayName("an ordinary walk is never reported as a gap")
	void theControlForGaps() {
		// Without this, a threshold of zero would make the test above pass by treating every
		// pair of samples as a gap — a "replay" that is a slideshow of teleports, each one
		// announced as time removed.
		SessionReplay.Playback playback = playback(walk(20));

		for (int i = 0; i < 200; i++) {
			assertTrue(!(SessionReplay.step(playback) instanceof SessionReplay.Step.Skip),
					"half a second between two samples was treated as a gap worth skipping");
		}
	}

	@Test
	@DisplayName("playback ends at the last sample rather than running off the end")
	void itEnds() {
		SessionReplay.Playback playback = playback(walk(4));

		SessionReplay.Step step = null;
		for (int i = 0; i < 100; i++) step = SessionReplay.step(playback);

		assertInstanceOf(SessionReplay.Step.End.class, step,
				"playback did not end. Past the last sample there is nothing to interpolate "
						+ "towards, and the camera either freezes with no explanation or reads "
						+ "off the end of the list.");
	}

	@Test
	@DisplayName("a world change inside a track is carried, not interpolated through")
	void worldChangesAreSkips() {
		List<PositionLog.Frame> frames = List.of(
				frame(T0, 100, OVERWORLD),
				frame(T0 + 500, 101, OVERWORLD),
				frame(T0 + 60_000, 20, "minecraft:the_nether"),
				frame(T0 + 60_500, 21, "minecraft:the_nether"));

		SessionReplay.Playback playback = playback(frames);

		SessionReplay.Step.Skip skip = null;
		for (int i = 0; i < 200 && skip == null; i++) {
			if (SessionReplay.step(playback) instanceof SessionReplay.Step.Skip found) {
				skip = found;
			}
		}

		assertTrue(skip != null, "a dimension change was not reported as a skip");
		assertTrue(skip.changesWorld(),
				"the skip did not notice the world changed. The camera would stay in the "
						+ "overworld at nether coordinates, and the dimension check would then "
						+ "end the replay as though the viewer had wandered off.");
	}

	@Test
	@DisplayName("angles turn the short way round")
	void anglesDoNotSpin() {
		// 350 to 10 is twenty degrees to the right, not three hundred and forty to the left.
		// Got wrong, a player glancing over their shoulder becomes a full spin on every
		// sample, which reads as the jitter of a cheat client.
		assertEquals(0, SessionReplay.lerpAngle(350, 10, 0.5) % 360, 0.001);
		assertEquals(355, SessionReplay.lerpAngle(350, 10, 0.25), 0.001);
		assertEquals(100, SessionReplay.lerpAngle(90, 110, 0.5), 0.001);
	}

	@Test
	@DisplayName("the sidebar always sends the same number of rows")
	void theSidebarIsFixedHeight() {
		// Nothing tells a client to drop a scoreboard row, so a redraw with fewer lines than
		// last time leaves the old ones on screen. Every caller has to send a constant number.
		SessionReplay.Playback playback = playback(walk(10));
		int rows = SessionReplay.sidebarLines(playback).size();

		for (int i = 0; i < 60; i++) SessionReplay.step(playback);
		assertEquals(rows, SessionReplay.sidebarLines(playback).size(),
				"the sidebar changed height part way through a replay, so the rows it no "
						+ "longer sends stay on screen showing stale values");

		playback.paused = true;
		assertEquals(rows, SessionReplay.sidebarLines(playback).size(), "paused changed it");
		playback.finished = true;
		assertEquals(rows, SessionReplay.sidebarLines(playback).size(), "finished changed it");
	}
}
