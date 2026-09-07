package io.github.alphain24.staffcore.modules.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Turning a p-value into the number the case model speaks.
 * <p>
 * This is the one place the new detection touches the old invented scale, and it is worth
 * being careful about because it would be easy to reintroduce the problem here — a mapping
 * with a hand-tuned curve in it is the weighted score again, wearing a different hat.
 * <p>
 * It is the negative log and nothing else. Each factor of ten less likely is another twenty
 * points. Nothing is invented in the ordering; the only choice is where the scale is cut, and
 * that is a display decision rather than a claim about evidence.
 */
class ConfidenceScaleTest {

	@Test
	@DisplayName("a likely outcome scores nothing")
	void chanceIsNotEvidence() {
		// Certainty is exactly zero; anything short of it is a point or two, which is the
		// arithmetic being honest rather than rounding towards innocence. What matters is
		// that none of it is anywhere near an alert.
		assertEquals(0, Hypergeometric.confidence(1.0));
		assertTrue(Hypergeometric.confidence(0.9) <= 2,
				"a nine-in-ten outcome scored more than a rounding error");
		assertTrue(Hypergeometric.confidence(0.5) < 10,
				"a coin flip should not be most of the way to an alert");
	}

	@Test
	@DisplayName("each factor of ten is another twenty points")
	void theScaleIsLogarithmic() {
		assertEquals(20, Hypergeometric.confidence(0.1), "one in ten");
		assertEquals(40, Hypergeometric.confidence(0.01), "one in a hundred");
		assertEquals(60, Hypergeometric.confidence(0.001), "one in a thousand");
		assertEquals(80, Hypergeometric.confidence(1e-4), "one in ten thousand");
	}

	@Test
	@DisplayName("nothing ever reaches certainty")
	void ninetyNineIsTheCeiling() {
		// A hundred would say the model is sure, and it cannot be: it assumes miners choose
		// blocks without regard to ore, and real ones follow veins. The ceiling is a standing
		// reminder that this is evidence to act on rather than a verdict.
		assertEquals(99, Hypergeometric.confidence(0.0));
		assertEquals(99, Hypergeometric.confidence(1e-300));
		assertTrue(Hypergeometric.confidence(1e-9) <= 99);
	}

	@Test
	@DisplayName("more surprising never scores lower")
	void monotonic() {
		int previous = -1;
		for (double p : new double[] {0.9, 0.5, 0.2, 0.1, 0.05, 0.01, 1e-3, 1e-5, 1e-8, 1e-20}) {
			int score = Hypergeometric.confidence(p);
			assertTrue(score >= previous,
					"p=" + p + " scored " + score + ", below the less surprising result before "
							+ "it — the scale runs backwards somewhere");
			previous = score;
		}
	}
}
