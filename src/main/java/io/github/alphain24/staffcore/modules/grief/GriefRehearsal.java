package io.github.alphain24.staffcore.modules.grief;

/**
 * One staff member's test of the mass-grief alert: a lower bar, their own count, and a time
 * limit.
 *
 * <h2>Why a test needs its own bar</h2>
 * The real threshold is 120 blocks in 20 seconds — six a second, held — which is the point:
 * a detector that fired on strip-mining would be ignored within a week. It also means nobody
 * can check the alert works by hand, and an alert nobody can check looks exactly like one that
 * is broken.
 *
 * <h2>What it proves, and what it deliberately does not do</h2>
 * The staff member breaks real blocks, so the break hook, the window arithmetic and in-game
 * delivery are the real ones. At the bar, a test alert goes out instead of a signal:
 * <ul>
 *   <li><b>No case.</b> A case opened against staff by a test, and then cleared, would count
 *   as a detector false positive in the corpus the thresholds are justified against.</li>
 *   <li><b>No Discord.</b> A test alert read in a channel, without the context of whoever ran
 *   it, is a false incident.</li>
 *   <li><b>The real detector keeps counting.</b> A test that blinded the thing it tests would
 *   be a way to grief unseen for two minutes.</li>
 * </ul>
 * The signal-to-case step is not skipped for lack of a test: {@code SignalAnnouncementTest} and
 * the case gametests cover it without needing anybody to break a block.
 *
 * @param blocks    the bar for this test
 * @param expiresAt when the test lapses if the bar is not reached
 * @param burst     the count so far, or {@code null} before the first break
 */
record GriefRehearsal(int blocks, long expiresAt, BreakBurst burst) {

	/** Long enough to find somewhere to dig and do it; short enough to not be forgotten. */
	static final long DURATION_MS = 120_000L;

	/** Enough that a stray break or two does not pass it, few enough to do in seconds. */
	static final int DEFAULT_BLOCKS = 10;

	/** Fewer and the test proves nothing about counting; more and it is the real detector. */
	static final int MIN_BLOCKS = 2;
	static final int MAX_BLOCKS = 100;

	enum Outcome {
		/** The test ran out before this break. It is over and this break did not count. */
		EXPIRED,
		/** Counted, bar not reached. */
		COUNTING,
		/** Counted as the first of a new window, because the last one lapsed. */
		RESTARTED,
		/** This break reached the bar. The test is over. */
		REACHED
	}

	/**
	 * @param next  the test after this change, or null once it is over
	 * @param burst the count after this change, for saying what reached the bar
	 */
	record Step(Outcome outcome, GriefRehearsal next, BreakBurst burst) {}

	static GriefRehearsal arm(int blocks, long now) {
		return new GriefRehearsal(Math.clamp(blocks, MIN_BLOCKS, MAX_BLOCKS),
				now + DURATION_MS, null);
	}

	/** What one block broken by hand does to this test. */
	Step onBreak(long now, long windowMs) {
		return onDestroyed(now, windowMs, 1, "by hand");
	}

	/**
	 * What {@code destroyed} blocks at once does to this test — one for a break, dozens for a
	 * blast. {@code next} is null once the test is over.
	 * <p>
	 * Not called {@code blocks}: that is this record's bar, and a parameter of the same name
	 * would quietly compare the count against itself.
	 */
	Step onDestroyed(long now, long windowMs, int destroyed, String how) {
		if (now > expiresAt) {
			return new Step(Outcome.EXPIRED, null, burst);
		}

		BreakBurst advanced = BreakBurst.advance(burst, now, windowMs, destroyed, how);
		if (advanced.count() >= blocks) {
			return new Step(Outcome.REACHED, null, advanced);
		}

		GriefRehearsal next = new GriefRehearsal(blocks, expiresAt, advanced);
		boolean restarted = burst != null && advanced.windowStart() != burst.windowStart();
		return new Step(restarted ? Outcome.RESTARTED : Outcome.COUNTING, next, advanced);
	}

	int counted() {
		return burst == null ? 0 : burst.count();
	}
}
