package io.github.alphain24.staffcore.modules.grief;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
	@DisplayName("the bar is clamped, so a test cannot pass on one break or become the real thing")
	void clamped() {
		assertEquals(GriefRehearsal.MIN_BLOCKS, GriefRehearsal.arm(1, 0).blocks());
		assertEquals(GriefRehearsal.MAX_BLOCKS, GriefRehearsal.arm(10_000, 0).blocks());
	}
}
