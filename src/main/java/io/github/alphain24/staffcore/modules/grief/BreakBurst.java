package io.github.alphain24.staffcore.modules.grief;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Blocks destroyed inside a rolling window — the arithmetic the mass-grief detector and its
 * test run share.
 * <p>
 * Shared on purpose. A test with its own copy of the counting would pass while the detector's
 * copy was broken, and "the test passed" would then be the least trustworthy thing on the
 * server.
 *
 * @param windowStart when the first block in this window went
 * @param count       blocks since then, including those
 * @param by          the same count split by how — "by hand", "TNT", "end crystal" — in the
 *                    order each first appeared, so the alert can say what was used
 */
record BreakBurst(long windowStart, int count, Map<String, Integer> by) {

	BreakBurst {
		by = Collections.unmodifiableMap(new LinkedHashMap<>(by));
	}

	/**
	 * The burst after {@code blocks} more blocks were destroyed, {@code how}.
	 * <p>
	 * A change more than {@code windowMs} after the window opened starts a new window rather
	 * than sliding the old one. That undercounts a player who breaks steadily across a
	 * boundary, and it is the behaviour the default threshold was chosen against, so it stays.
	 * An explosion counts every block it takes at once, which is the point: one TNT is dozens
	 * of blocks, and a counter that saw it as one event would never reach the bar.
	 */
	static BreakBurst advance(BreakBurst previous, long now, long windowMs, int blocks,
			String how) {

		if (previous == null || now - previous.windowStart() > windowMs) {
			return new BreakBurst(now, blocks, Map.of(how, blocks));
		}
		Map<String, Integer> by = new LinkedHashMap<>(previous.by());
		by.merge(how, blocks, Integer::sum);
		return new BreakBurst(previous.windowStart(), previous.count() + blocks, by);
	}

	/** One block by hand, the common case. */
	static BreakBurst advance(BreakBurst previous, long now, long windowMs) {
		return advance(previous, now, windowMs, 1, "by hand");
	}

	/**
	 * Whether the change that produced {@code next} carried the count over {@code bar}.
	 * <p>
	 * Crossing, not equality. An explosion can take the count from 100 to 140 in one step,
	 * and a detector waiting for exactly 120 would watch it go past. Crossing still alerts
	 * once per window, because only one change can be the one that crossed.
	 */
	static boolean crossed(BreakBurst next, int added, int bar) {
		return next.count() >= bar && next.count() - added < bar;
	}

	/** "118 TNT, 16 by hand" — largest first, because that is the one staff need to see. */
	String describe() {
		return by.entrySet().stream()
				.sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
				.map(e -> e.getValue() + " " + e.getKey())
				.collect(Collectors.joining(", "));
	}
}
