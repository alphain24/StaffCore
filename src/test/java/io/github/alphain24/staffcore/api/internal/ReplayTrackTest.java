package io.github.alphain24.staffcore.api.internal;

import io.github.alphain24.staffcore.api.DiscordReplayTrack;
import io.github.alphain24.staffcore.modules.replay.PositionLog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A replay window as the track a map is drawn from: thinned when long, whole when short, and always ending
 * where the player did.
 */
class ReplayTrackTest {

	private static final String WORLD = "minecraft:overworld";

	private static PositionLog.Track walk(int frames) {
		List<PositionLog.Frame> out = new ArrayList<>();
		for (int i = 0; i < frames; i++) out.add(new PositionLog.Frame(1_000L + i * 500L, WORLD, i, 64, 0, 0, 0));
		return new PositionLog.Track("Steve", out, 1, 1_000L, 1_000L + frames * 500L, false);
	}

	@Test
	@DisplayName("a short window keeps every frame")
	void shortIsWhole() {
		DiscordReplayTrack track = DiscordGate.assemble(UUID.randomUUID(), "Steve", 0, 1, walk(50), List.of(), false);
		assertEquals(50, track.points().size());
		assertEquals(49, track.points().get(49).x());
	}

	@Test
	@DisplayName("a long window is thinned to the limit and still ends at the last frame")
	void longIsThinned() {
		PositionLog.Track walk = walk(43_200);
		DiscordReplayTrack track = DiscordGate.assemble(UUID.randomUUID(), "Steve", 0, 1, walk, List.of(), true);
		assertTrue(track.points().size() <= DiscordReplayTrack.MAX_POINTS, "points: " + track.points().size());
		assertTrue(track.points().size() > DiscordReplayTrack.MAX_POINTS / 2, "thinned far more than needed");
		assertEquals(walk.last().at(), track.points().get(track.points().size() - 1).at(), "the end was dropped");
		assertEquals(walk.first().at(), track.points().get(0).at(), "the start was dropped");
		assertTrue(track.changesTruncated());
	}

	@Test
	@DisplayName("exactly the limit is kept whole")
	void atTheLimit() {
		DiscordReplayTrack track = DiscordGate.assemble(UUID.randomUUID(), "Steve", 0, 1,
				walk(DiscordReplayTrack.MAX_POINTS), List.of(), false);
		assertEquals(DiscordReplayTrack.MAX_POINTS, track.points().size());
	}
}
