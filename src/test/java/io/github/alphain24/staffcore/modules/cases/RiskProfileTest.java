package io.github.alphain24.staffcore.modules.cases;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RiskProfileTest {

	private static RiskProfile.Facts nothing() {
		return new RiskProfile.Facts(false, false, 0, 0, 0, 0, 0, 0, Map.of(), 0, 0, 0,
				400 * RiskProfile.DAY, 0);
	}

	@Test
	@DisplayName("a long-standing player with nothing on record is low, with no factors")
	void cleanRecord() {
		var a = RiskProfile.assess(nothing());
		assertEquals(0, a.score());
		assertEquals(RiskProfile.Level.LOW, a.level());
		assertTrue(a.factors().isEmpty());
	}

	@Test
	@DisplayName("the score is exactly the sum of the factors shown")
	void scoreIsTheSumShown() {
		var f = new RiskProfile.Facts(false, true, 1, 2, 3, 1, 1, 0,
				Map.of(Signal.Type.XRAY, 72), 2, 0, 1, RiskProfile.DAY * 3, 0);
		var a = RiskProfile.assess(f);
		assertEquals(a.factors().stream().mapToInt(RiskProfile.Factor::points).sum(), a.score());
		assertTrue(a.factors().get(0).points() >= a.factors().get(a.factors().size() - 1).points(),
				"the factors are not biggest first");
	}

	@Test
	@DisplayName("a banned player with a banned account on their address is high")
	void bannedWithBannedAlt() {
		var f = new RiskProfile.Facts(true, false, 0, 0, 0, 0, 0, 0, Map.of(), 0, 1, 0,
				30 * RiskProfile.DAY, 0);
		assertEquals(RiskProfile.Level.HIGH, RiskProfile.assess(f).level());
	}

	@Test
	@DisplayName("nothing caps out past 100, whatever piles up")
	void capped() {
		var f = new RiskProfile.Facts(true, true, 9, 9, 9, 9, 9, 9,
				Map.of(Signal.Type.XRAY, 99, Signal.Type.MASS_GRIEF, 99, Signal.Type.CONTRABAND, 99),
				9, 9, 9, 0, 99);
		assertEquals(100, RiskProfile.assess(f).score());
	}
}
