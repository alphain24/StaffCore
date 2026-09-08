package io.github.alphain24.staffcore.modules.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The arithmetic behind an accusation.
 * <p>
 * This is the number a staff member will repeat to the player they are confronting, and the
 * number an appeal will argue with. It has to be right in the ordinary cases and it has to be
 * <em>safe</em> in the degenerate ones — a bookkeeping mistake that produces a one-in-a-million
 * p-value is how somebody gets banned for a division by zero.
 */
class HypergeometricTest {

	private static final double TOLERANCE = 1e-9;

	@Test
	@DisplayName("log-gamma matches the factorials it stands in for")
	void gammaIsCorrect() {
		// Γ(n) = (n-1)! for integers, and Γ(1/2) = √π. If this drifts, every p-value drifts
		// with it and nothing else in the file would notice.
		assertEquals(0.0, Hypergeometric.logGamma(1), TOLERANCE, "Γ(1) = 0! = 1");
		assertEquals(0.0, Hypergeometric.logGamma(2), TOLERANCE, "Γ(2) = 1! = 1");
		assertEquals(Math.log(6), Hypergeometric.logGamma(4), TOLERANCE, "Γ(4) = 3! = 6");
		assertEquals(Math.log(3628800), Hypergeometric.logGamma(11), 1e-8, "Γ(11) = 10!");
		assertEquals(Math.log(Math.sqrt(Math.PI)), Hypergeometric.logGamma(0.5), TOLERANCE);
	}

	@Test
	@DisplayName("log-gamma stays accurate where the factorials would overflow")
	void gammaSurvivesLargeInputs() {
		// A hundred thousand blocks is an ordinary excavation and 100000! is not a number a
		// double can hold. Checked against Stirling, which is independent of Lanczos.
		double x = 100_000;
		double stirling = (x - 0.5) * Math.log(x) - x + 0.5 * Math.log(2 * Math.PI)
				+ 1 / (12 * x);

		assertEquals(stirling, Hypergeometric.logGamma(x), 1e-6,
				"Lanczos and Stirling disagree at the scale this actually runs at");
	}

	@Test
	@DisplayName("choose matches the values it is easy to count by hand")
	void chooseIsCorrect() {
		assertEquals(Math.log(1), Hypergeometric.logChoose(5, 0), TOLERANCE);
		assertEquals(Math.log(10), Hypergeometric.logChoose(5, 2), TOLERANCE);
		assertEquals(Math.log(252), Hypergeometric.logChoose(10, 5), TOLERANCE);
		assertEquals(Math.log(1), Hypergeometric.logChoose(7, 7), TOLERANCE);
	}

	@Test
	@DisplayName("the whole distribution sums to one")
	void probabilitiesAreADistribution() {
		// The strongest check available without a second implementation: whatever the terms
		// are individually, they have to add up. An error in the exponents shows here.
		int population = 60;
		int ores = 12;
		int drawn = 20;

		double total = 0;
		for (int i = 0; i <= Math.min(drawn, ores); i++) {
			total += Math.exp(Hypergeometric.logProbability(population, ores, drawn, i));
		}
		assertEquals(1.0, total, 1e-9, "the distribution does not sum to 1");
	}

	@Test
	@DisplayName("a known small case matches the value worked out by hand")
	void matchesAHandCalculation() {
		// N=10, K=4, n=5. P(X>=3) = [C(4,3)C(6,2) + C(4,4)C(6,1)] / C(10,5)
		//                         = (4*15 + 1*6) / 252 = 66/252.
		assertEquals(66.0 / 252.0, Hypergeometric.atLeast(10, 4, 5, 3), 1e-12);

		// And the whole tail from zero is certainty.
		assertEquals(1.0, Hypergeometric.atLeast(10, 4, 5, 0), 1e-12);
	}

	@Test
	@DisplayName("finding what you would expect is unremarkable")
	void averageMiningIsNotSuspicious() {
		// Ten thousand blocks, one per cent ore, ten thousandth of it dug, one ore found.
		// That is exactly the expected outcome and must not read as evidence of anything.
		double p = Hypergeometric.atLeast(10_000, 100, 100, 1);
		assertTrue(p > 0.5, "an ordinary result scored as surprising: p=" + p);
	}

	@Test
	@DisplayName("finding almost every ore while digging almost nothing is not chance")
	void theCheatingCaseIsExtreme() {
		// A hundred thousand blocks holding two hundred diamonds. The player removed three
		// hundred blocks and got sixty of them. There is no honest version of that.
		double p = Hypergeometric.atLeast(100_000, 200, 300, 60);

		assertTrue(p < 1e-50, "a blatant case did not separate: p=" + p);
		assertTrue(p >= 0, "a probability went negative, so the arithmetic has underflowed");
	}

	@Test
	@DisplayName("every degenerate input answers 'no evidence', never 'certainty of guilt'")
	void nonsenseIsSafe() {
        // The direction matters more than the values. Each of these is a bookkeeping mistake
        // — an empty volume, more ore than blocks, more found than drawn — and the safe answer
        // to a question that makes no sense is that nothing has been shown.
		assertEquals(1.0, Hypergeometric.atLeast(0, 0, 0, 0), TOLERANCE, "empty everything");
		assertEquals(1.0, Hypergeometric.atLeast(100, 0, 10, 0), TOLERANCE, "no ore present");
		assertEquals(1.0, Hypergeometric.atLeast(100, 10, 0, 0), TOLERANCE, "nothing dug");
		assertEquals(1.0, Hypergeometric.atLeast(10, 20, 5, 3), TOLERANCE, "more ore than blocks");
		assertEquals(1.0, Hypergeometric.atLeast(100, 10, 5, 8), TOLERANCE, "found more than dug");
		assertEquals(1.0, Hypergeometric.atLeast(100, 10, 200, 5), TOLERANCE, "dug more than exists");
		assertEquals(1.0, Hypergeometric.atLeast(-5, 3, 2, 1), TOLERANCE, "negative volume");
	}

	@Test
	@DisplayName("more ore found is always at least as surprising as less")
	void theTailIsMonotonic() {
		// A property rather than a value, and it holds whatever the coefficients are. If a
		// sign or an index were wrong this is where it would show, at every scale at once.
		double previous = 0;
		for (int found = 1; found <= 30; found++) {
			double p = Hypergeometric.atLeast(5000, 50, 200, found);
			assertTrue(p <= 1.0 && p >= 0.0, "p left [0,1] at k=" + found + ": " + p);

			if (found > 1) {
				assertTrue(p <= previous + 1e-15,
						"finding " + found + " scored less surprising than finding "
								+ (found - 1) + ", which is the arithmetic running backwards");
			}
			previous = p;
		}
	}

	@Test
	@DisplayName("a blatant case is small but never zero, so it cannot be a silent underflow")
	void theExtremeTailDoesNotCollapse() {
		// The failure this guards against passed the test above it. An implementation that
		// underflows to exactly zero satisfies "p < 1e-50" and reads downstream as certainty
		// of guilt — the one answer this must never give for a numerical reason rather than
		// an evidential one.
		double p = Hypergeometric.atLeast(100_000, 200, 300, 60);

		assertTrue(p > 0,
				"the extreme tail collapsed to exactly zero, which is an underflow wearing the "
						+ "shape of a conclusion");
		assertTrue(p < 1e-50, "and it still has to separate: p=" + p);
	}

	@Test
	@DisplayName("the sum is stable whichever end of the range the answer sits at")
	void bothEndsAgree() {
		// Terms rise towards the mode before they fall, so an implementation that walks the
		// range and stops early gets a different answer depending on where it started. Asking
		// for the whole tail from zero must give exactly one however it is computed.
		assertEquals(1.0, Hypergeometric.atLeast(5000, 50, 200, 1)
				+ tailBelow(5000, 50, 200, 1), 1e-9);
	}

	/** Everything the tail from k excludes, summed independently. */
	private static double tailBelow(int population, int ores, int drawn, int k) {
		double total = 0;
		for (int i = 0; i < k; i++) {
			total += Math.exp(Hypergeometric.logProbability(population, ores, drawn, i));
		}
		return total;
	}

	@Test
	@DisplayName("the description is a sentence somebody could say out loud")
	void plainWords() {
		// "0.00003" is not a thing to say to a player. "About one in thirty thousand" is the
		// same number and can be argued with.
		assertEquals("no more than chance", Hypergeometric.describe(0.8));
		assertTrue(Hypergeometric.describe(0.01).contains("one in 100"));
		assertTrue(Hypergeometric.describe(1e-5).contains("thousand"));
		assertTrue(Hypergeometric.describe(1e-8).contains("million"));

		// Past a billion to one, "one in 9223372036854 million" is a long overflowing rather
		// than a number, and the honest answer is that the model has run out of meaning.
		assertEquals("far beyond chance", Hypergeometric.describe(1e-40));
		assertEquals("far beyond chance", Hypergeometric.describe(0.0));
	}
}
