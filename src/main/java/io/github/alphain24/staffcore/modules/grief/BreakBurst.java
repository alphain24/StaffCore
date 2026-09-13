package io.github.alphain24.staffcore.modules.grief;

/**
 * Breaks counted inside a rolling window — the arithmetic the mass-grief detector and its
 * test run share.
 * <p>
 * Shared on purpose. A test with its own copy of the counting would pass while the detector's
 * copy was broken, and "the test passed" would then be the least trustworthy thing on the
 * server.
 *
 * @param windowStart when the first break in this window happened
 * @param count       breaks since then, including that one
 */
record BreakBurst(long windowStart, int count) {

	/**
	 * The burst after one more break.
	 * <p>
	 * A break more than {@code windowMs} after the window opened starts a new window rather
	 * than sliding the old one. That undercounts a player who breaks steadily across a
	 * boundary, and it is the behaviour the default threshold was chosen against, so it stays.
	 */
	static BreakBurst advance(BreakBurst previous, long now, long windowMs) {
		if (previous == null || now - previous.windowStart() > windowMs) {
			return new BreakBurst(now, 1);
		}
		return new BreakBurst(previous.windowStart(), previous.count() + 1);
	}
}
