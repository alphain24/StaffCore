package io.github.alphain24.staffcore.modules.security;

/**
 * How surprising it is to find that many ores in that many blocks.
 *
 * <h2>Why this replaces a score</h2>
 * The detector this succeeds produced a number between 0 and 100 by combining four signals with
 * weights somebody chose. It worked, in the sense that it separated the test corpus — and it
 * could never answer the question staff actually ask, which is "how likely is it that an honest
 * miner would have done this". A weighted score has no units. Moving the threshold from 70 to
 * 65 changes the number of alerts and nobody can say what it changes about the claim.
 * <p>
 * A p-value has units. It says: if this player had been digging without knowing where the ore
 * was, the chance of them doing at least this well is one in whatever. That is a sentence a
 * staff member can repeat to the person they are accusing, and a sentence an appeal can argue
 * with on its own terms.
 *
 * <h2>The model</h2>
 * Drawing without replacement from a finite population. The excavated volume holds {@code N}
 * blocks of which {@code K} were ore; the player removed {@code n} of them and {@code k} of
 * those were ore. An honest miner is a random draw. Somebody who can see through stone is not.
 * <p>
 * <b>The tail, not the point.</b> {@code P(X = k)} is small for almost any k once the numbers
 * get large, so the probability of the exact outcome is not evidence of anything. What matters
 * is {@code P(X >= k)}: the chance of doing <em>at least</em> this well by luck.
 *
 * <h2>What it does not know</h2>
 * The model assumes a miner who chooses blocks without regard to ore. Real miners do not: they
 * follow visible veins, and one visible diamond means the two beside it are likely. That makes
 * the arithmetic optimistic — a legitimate player who found one vein and followed it will score
 * as luckier than random, because they were. This is why the discoverability count is kept
 * separate rather than folded in, and why nothing here is allowed to punish anybody.
 */
public final class Hypergeometric {
	private Hypergeometric() {}

	/**
	 * The chance of finding at least {@code k} ores by digging blindly.
	 *
	 * @param population blocks in the excavated volume
	 * @param ores       ores that were in it, found and unfound together
	 * @param drawn      blocks the player actually removed
	 * @param found      ores among them
	 * @return a probability in [0, 1]; 1 when the question is meaningless
	 */
	public static double atLeast(int population, int ores, int drawn, int found) {
		// Nonsense in, certainty out. Every one of these is a case where the question has no
		// answer rather than an alarming one — an empty volume, more ore than blocks, more
		// drawn than exist — and returning a small p-value for any of them would turn a
		// bookkeeping mistake into an accusation.
		if (population <= 0 || ores <= 0 || drawn <= 0) return 1.0;
		if (ores > population || drawn > population || found > drawn || found > ores) return 1.0;
		if (found <= 0) return 1.0;

		int highest = Math.min(drawn, ores);
		if (found > highest) return 1.0;

		// Summed from the top down. The terms fall away fast above the mode, so starting at
		// the far tail and working back keeps every addition between numbers of similar size
		// — the other direction loses the small terms into the rounding of the large ones.
		double total = 0.0;
		for (int i = highest; i >= found; i--) {
			total += Math.exp(logProbability(population, ores, drawn, i));
		}
		return Math.min(1.0, total);
	}

	/** {@code log P(X = i)}, in log space because the factorials involved are astronomical. */
	static double logProbability(int population, int ores, int drawn, int i) {
		return logChoose(ores, i)
				+ logChoose(population - ores, drawn - i)
				- logChoose(population, drawn);
	}

	/** {@code log C(n, k)}. Zero-sized choices are 1, so their log is 0. */
	static double logChoose(int n, int k) {
		if (k < 0 || k > n) return Double.NEGATIVE_INFINITY;
		if (k == 0 || k == n) return 0.0;
		return logGamma(n + 1) - logGamma(k + 1) - logGamma(n - k + 1);
	}

	/**
	 * {@code log Γ(x)} by the Lanczos approximation.
	 * <p>
	 * Java has no log-gamma in its standard library and this needs one: the volumes here run to
	 * hundreds of thousands of blocks, where {@code n!} overflows a double long before the
	 * ratio of two of them does anything interesting. Lanczos with these coefficients is
	 * accurate to roughly fifteen significant figures across the range this uses, which is
	 * several orders more than a p-value reported to two.
	 */
	static double logGamma(double x) {
		if (x <= 0) return Double.NaN;

		double[] c = {
				676.5203681218851, -1259.1392167224028, 771.32342877765313,
				-176.61502916214059, 12.507343278686905, -0.13857109526572012,
				9.9843695780195716e-6, 1.5056327351493116e-7
		};

		// Reflection for the left half-plane. Not reached by anything here — every argument is
		// a block count plus one — and present because a gamma function that silently returns
		// nonsense below 0.5 is a trap for whoever uses this next.
		if (x < 0.5) {
			return Math.log(Math.PI / Math.sin(Math.PI * x)) - logGamma(1 - x);
		}

		double z = x - 1;
		double a = 0.99999999999980993;
		for (int i = 0; i < c.length; i++) {
			a += c[i] / (z + i + 1);
		}

		double t = z + c.length - 0.5;
		return 0.5 * Math.log(2 * Math.PI) + (z + 0.5) * Math.log(t) - t + Math.log(a);
	}

	/**
	 * A p-value as a sentence, because "0.00003" is not a thing to say to a player.
	 * <p>
	 * "One in thirty thousand" is the same number and a person can argue with it. Rounded to
	 * one significant figure on purpose: the precision is not real — the model is an
	 * approximation of mining — and printing it to four decimals claims a confidence the
	 * arithmetic does not have.
	 */
	public static String describe(double p) {
		if (p >= 0.5) return "no more than chance";
		if (p >= 0.05) return "roughly a one in " + Math.round(1 / p) + " chance";

		long odds = Math.round(1 / p);
		if (odds < 1000) return "about one in " + odds;
		if (odds < 1_000_000) return "about one in " + (odds / 1000) + " thousand";
		return "about one in " + (odds / 1_000_000) + " million";
	}
}
