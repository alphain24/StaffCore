package io.github.alphain24.staffcore.modules.grief;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The window arithmetic the mass-grief detector and its test share, and the test's own rules.
 */
class GriefRehearsalTest {

	private static final long WINDOW = 20_000L;

	@Test
	@DisplayName("breaks inside the window add up; one after it starts a new window")
	void theWindow() {
		BreakBurst burst = BreakBurst.advance(null, 1_000, WINDOW);
		assertEquals(1, burst.count());

		burst = BreakBurst.advance(burst, 5_000, WINDOW);
		burst = BreakBurst.advance(burst, 21_000, WINDOW);
		assertEquals(3, burst.count(), "exactly at the edge still counts");

		burst = BreakBurst.advance(burst, 21_001, WINDOW);
		assertEquals(1, burst.count());
		assertEquals(21_001, burst.windowStart());
	}

	@Test
	@DisplayName("a blast that jumps past the bar still crosses it, once")
	void crossingNotEquality() {
		// The old check was count == bar. 100 blocks, then a 40-block blast, is 140: never
		// equal to 120, so the detector watched it go past and said nothing.
		BreakBurst hands = BreakBurst.advance(null, 0, WINDOW, 100, "by hand");
		BreakBurst blast = BreakBurst.advance(hands, 1_000, WINDOW, 40, "TNT");
		assertTrue(BreakBurst.crossed(blast, 40, 120));

		BreakBurst after = BreakBurst.advance(blast, 2_000, WINDOW, 40, "TNT");
		assertFalse(BreakBurst.crossed(after, 40, 120), "a burst alerts once, not per blast");

		BreakBurst exact = BreakBurst.advance(BreakBurst.advance(null, 0, WINDOW, 119, "by hand"),
				1, WINDOW);
		assertTrue(BreakBurst.crossed(exact, 1, 120), "landing exactly on the bar still counts");
	}

	@Test
	@DisplayName("the breakdown names the biggest cause first")
	void breakdown() {
		BreakBurst burst = BreakBurst.advance(null, 0, WINDOW);
		burst = BreakBurst.advance(burst, 1, WINDOW, 80, "end crystal");
		burst = BreakBurst.advance(burst, 2, WINDOW, 30, "TNT they placed");
		burst = BreakBurst.advance(burst, 3, WINDOW);

		assertEquals(112, burst.count());
		assertEquals("80 end crystal, 30 TNT they placed, 2 by hand", burst.describe());
	}

	@Test
	@DisplayName("the test passes on the break that reaches the bar, not the one after")
	void reachesOnTheLastBreak() {
		GriefRehearsal test = GriefRehearsal.arm(3, 0);

		GriefRehearsal.Step one = test.onBreak(100, WINDOW);
		assertEquals(GriefRehearsal.Outcome.COUNTING, one.outcome());
		GriefRehearsal.Step two = one.next().onBreak(200, WINDOW);
		assertEquals(GriefRehearsal.Outcome.COUNTING, two.outcome());
		assertEquals(2, two.next().counted());

		GriefRehearsal.Step three = two.next().onBreak(300, WINDOW);
		assertEquals(GriefRehearsal.Outcome.REACHED, three.outcome());
		assertNull(three.next(), "a passed test is over");
	}

	@Test
	@DisplayName("a slow test restarts its count and says so")
	void aLapsedWindowRestarts() {
		GriefRehearsal.Step first = GriefRehearsal.arm(5, 0).onBreak(1_000, WINDOW);
		GriefRehearsal.Step late = first.next().onBreak(1_000 + WINDOW + 1, WINDOW);

		assertEquals(GriefRehearsal.Outcome.RESTARTED, late.outcome());
		assertEquals(1, late.next().counted());
	}

	@Test
	@DisplayName("the very first break is counting, not a restart")
	void theFirstBreakIsNotARestart() {
		assertEquals(GriefRehearsal.Outcome.COUNTING,
				GriefRehearsal.arm(5, 0).onBreak(10, WINDOW).outcome());
	}

	@Test
	@DisplayName("a break after the time limit ends the test without counting")
	void expires() {
		GriefRehearsal test = GriefRehearsal.arm(5, 0);

		GriefRehearsal.Step step = test.onBreak(GriefRehearsal.DURATION_MS + 1, WINDOW);

		assertEquals(GriefRehearsal.Outcome.EXPIRED, step.outcome());
		assertNull(step.next());
	}

	@Test
	@DisplayName("a blast counts every block it takes, and can finish the test in one go")
	void aBlastCountsAllItsBlocks() {
		GriefRehearsal test = GriefRehearsal.arm(10, 0);

		GriefRehearsal.Step hand = test.onBreak(100, WINDOW);
		GriefRehearsal.Step tnt = hand.next().onDestroyed(200, WINDOW, 30, "TNT");

		assertEquals(GriefRehearsal.Outcome.REACHED, tnt.outcome());
		assertEquals(31, tnt.burst().count());
		assertEquals("30 TNT, 1 by hand", tnt.burst().describe());
	}

	@Test
	@DisplayName("the bar is clamped, so a test cannot pass on one break or become the real thing")
	void clamped() {
		assertEquals(GriefRehearsal.MIN_BLOCKS, GriefRehearsal.arm(1, 0).blocks());
		assertEquals(GriefRehearsal.MAX_BLOCKS, GriefRehearsal.arm(10_000, 0).blocks());
	}
}
