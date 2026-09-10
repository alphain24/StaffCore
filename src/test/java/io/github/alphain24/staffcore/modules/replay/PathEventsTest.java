package io.github.alphain24.staffcore.modules.replay;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The overlay shows the world as it was at that instant, not before and not after.
 *
 * <h2>Why the timing is the whole test</h2>
 * A replay without an overlay shows the world as it is now, which is quietly the wrong world:
 * every hole the player made is already open when they arrive at it. The overlay fixes that by
 * painting the changed blocks back and releasing each one as the clock reaches it.
 * <p>
 * Which means the overlay is only worth having if the release is exactly on time. Released
 * early, the viewer sees a hole before it was dug and concludes somebody tunnelled to something
 * they could already see. Released late, they see a player walk through a wall. Both are
 * plausible pictures of something that did not happen, shown to somebody deciding whether to
 * punish a player.
 * <p>
 * {@code at()} is a plain function of a list and a time for exactly this reason: the timing can
 * be checked without a world, a server, or a client to draw it.
 */
class PathEventsTest {

	private static final long T0 = 1_700_000_000_000L;
	private static final String WORLD = "minecraft:overworld";

	/**
	 * A change with a stand-in state.
	 * <p>
	 * Null rather than a real {@link BlockState}, because constructing one needs a registry and
	 * nothing here looks at the value — every assertion is about which positions are painted
	 * and when. {@code inWindow} is the code that decides what state to use, and it has its own
	 * three-deep fallback for exactly the rows this cannot build.
	 */
	private static PathEvents.Change broke(long at, int x) {
		return new PathEvents.Change(at, new BlockPos(x, 64, 0), WORLD, "BREAK", "stone", null);
	}

	private static PathEvents.Change placed(long at, int x) {
		return new PathEvents.Change(at, new BlockPos(x, 64, 0), WORLD, "PLACE", "stone", null);
	}

	@Test
	@DisplayName("a block is painted until its moment and released after it")
	void releaseIsOnTime() {
		List<PathEvents.Change> changes = List.of(broke(T0 + 1000, 10));

		assertTrue(PathEvents.at(changes, T0).containsKey(new BlockPos(10, 64, 0)),
				"the block was not painted before it was broken, so the viewer sees the hole "
						+ "from the start and the break itself is invisible");
		assertTrue(PathEvents.at(changes, T0 + 999).containsKey(new BlockPos(10, 64, 0)),
				"the block was released a moment early — the hole opens before the swing");
		assertFalse(PathEvents.at(changes, T0 + 1000).containsKey(new BlockPos(10, 64, 0)),
				"the block was still painted at the instant it was broken, so the player is "
						+ "shown mining through a wall that is still standing");
		assertFalse(PathEvents.at(changes, T0 + 5000).containsKey(new BlockPos(10, 64, 0)),
				"the block never released at all");
	}

	@Test
	@DisplayName("a tunnel opens up one block at a time, in order")
	void changesReleaseInOrder() {
		List<PathEvents.Change> changes = List.of(
				broke(T0 + 1000, 1), broke(T0 + 2000, 2), broke(T0 + 3000, 3),
				broke(T0 + 4000, 4));

		assertEquals(4, PathEvents.at(changes, T0).size(), "nothing broken yet, so all four");
		assertEquals(2, PathEvents.at(changes, T0 + 2500).size(),
				"two blocks should be gone and two still standing");
		assertEquals(0, PathEvents.at(changes, T0 + 9000).size(), "all four should be gone");

		assertEquals(2, PathEvents.passed(changes, T0 + 2500), "the sidebar count disagrees");
	}

	@Test
	@DisplayName("a block placed is absent until it is placed")
	void placementsRunTheOtherWay() {
		// The mirror image, and the direction that is easy to get backwards. A placed block
		// exists now, so the world already shows it — the overlay has to hide it until its
		// moment or the viewer watches somebody build a tower that was already standing.
		List<PathEvents.Change> changes = List.of(placed(T0 + 1000, 10));

		Map<BlockPos, BlockState> before = PathEvents.at(changes, T0);
		assertTrue(before.containsKey(new BlockPos(10, 64, 0)),
				"a block that had not been placed yet was left showing, so the replay shows "
						+ "the finished build from the first frame");
		assertFalse(PathEvents.at(changes, T0 + 2000).containsKey(new BlockPos(10, 64, 0)),
				"the placed block was never released, so it stays hidden after it was built");
	}

	@Test
	@DisplayName("a position changed several times shows what was there at each instant")
	void everyMomentShowsItsOwnState() {
		// Broken, replaced, broken again, all at one position. The rule is that a position
		// shows the "before" of the earliest change still to come, and this is the case that
		// pins it: between the first break and the placement, the block is rubble.
		BlockPos pos = new BlockPos(5, 64, 0);
		List<PathEvents.Change> changes = List.of(
				broke(T0 + 1000, 5), placed(T0 + 2000, 5), broke(T0 + 3000, 5));

		assertEquals(1, PathEvents.at(changes, T0).size(), "one position, so one painted block");

		// Half way between the break and the placement, the position must be painted as air —
		// which is not the same as not painting it. If somebody rolled this area back
		// afterwards, the world has a block there now, and leaving it unpainted would show it
		// standing at a moment when it was rubble.
		Map<BlockPos, BlockState> midway = PathEvents.at(changes, T0 + 1500);
		assertTrue(midway.containsKey(pos),
				"the position was left showing the real world at a moment when the block had "
						+ "been broken and not yet replaced. A rollback since would then be "
						+ "drawn as though the block had never come down.");
		assertEquals(changes.get(1).before(), midway.get(pos),
				"the position is painted with the wrong state — it should be whatever was "
						+ "there just before the next change, not the state from before the "
						+ "window started");

		// And after the last change it is released to the real world for good.
		assertFalse(PathEvents.at(changes, T0 + 4000).containsKey(pos),
				"a position was still painted after every change to it had happened");
	}

	@Test
	@DisplayName("no changes means nothing is drawn, and nothing throws")
	void theEmptyCase() {
		assertNotNull(PathEvents.at(List.of(), T0));
		assertEquals(0, PathEvents.at(List.of(), T0).size());
		assertEquals(0, PathEvents.passed(List.of(), T0));
	}

	@Test
	@DisplayName("BREAK and PLACE are told apart")
	void kindsAreDistinguished() {
		assertTrue(broke(T0, 1).isBreak());
		assertFalse(placed(T0, 1).isBreak(),
				"a placement was classified as a break, which paints it in the wrong "
						+ "direction: a block that should be hidden until its moment is "
						+ "instead shown until its moment");
	}
}
