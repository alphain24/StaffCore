package io.github.alphain24.staffcore.security;

import io.github.alphain24.staffcore.modules.security.XrayDetector;
import io.github.alphain24.staffcore.modules.security.XrayTuning;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the alert line would do to a population of miners.
 * <p>
 * The thresholds this pins were last moved because the detector was "too quiet". Nothing
 * measured what that cost, and it turned out to cost a great deal: at the old settings the
 * worst honest pattern here scored 66 against an alert line of 55, so ordinary strip mining
 * and ordinary diamond hunting both tripped it. The feature was not quiet, it was wrong, and
 * making it louder made it wronger.
 * <p>
 * These are the tests that would have caught that. They score two hundred generated honest
 * sessions and forty guided ones and assert the alert line sits between the two populations —
 * which is a claim about accuracy, unlike "it speaks often enough".
 * <p>
 * To score a real server instead, point it at a copy of a live database:
 *
 * <pre>
 * ./gradlew test --tests '*XrayThresholdTest' -Dstaffcore.replay.db=/path/to/staffcore.db
 * </pre>
 */
class XrayThresholdTest {

	/** Enough seeds that a threshold cannot be fitted to one lucky world. */
	private static final int SEEDS = 40;

	private static List<XrayDetector.Report> scoreClean() {
		List<XrayDetector.Report> out = new ArrayList<>();
		for (int seed = 0; seed < SEEDS; seed++) {
			for (MiningPatterns.Pattern pattern : MiningPatterns.clean(seed)) {
				out.add(XrayDetector.score(pattern.breaks(), 0));
			}
		}
		return out;
	}

	@Test
	@DisplayName("no honest mining pattern reaches the alert line")
	void cleanPatternsAreNeverAlertedOn() {
		int worst = 0;
		String worstName = "";

		for (int seed = 0; seed < SEEDS; seed++) {
			for (MiningPatterns.Pattern pattern : MiningPatterns.clean(seed)) {
				int confidence = XrayDetector.score(pattern.breaks(), 0).confidence();
				if (confidence > worst) {
					worst = confidence;
					worstName = pattern.name() + " (seed offset " + seed + ")";
				}
			}
		}

		assertTrue(worst < XrayTuning.ALERT_CONFIDENCE,
				"an honest session scored " + worst + " against an alert line of "
						+ XrayTuning.ALERT_CONFIDENCE + " — worst was " + worstName
						+ ". Somebody mining normally would be reported to staff.");
	}

	@Test
	@DisplayName("no honest mining pattern reaches even the quiet notice line")
	void cleanPatternsDoNotEvenDrawANotice() {
		int worst = scoreClean().stream().mapToInt(XrayDetector.Report::confidence).max().orElse(0);

		// Softer than the alert, so this is a weaker promise on purpose: a notice is explicitly
		// not an accusation. It still ought to hold, and if it stops holding that is the early
		// warning that the alert line is about to stop holding too.
		assertTrue(worst < XrayTuning.NOTICE_CONFIDENCE,
				"an honest session scored " + worst + ", at or above the notice line of "
						+ XrayTuning.NOTICE_CONFIDENCE);
	}

	@Test
	@DisplayName("guided tunnelling is caught every time")
	void theControlIsCaught() {
		int missed = 0;
		int worst = 100;

		for (int seed = 0; seed < SEEDS; seed++) {
			MiningPatterns.Pattern guided = MiningPatterns.guidedTunnelling(89 + seed);
			int confidence = XrayDetector.score(guided.breaks(), XrayTuning.SAMPLE_FLOOR)
					.confidence();
			worst = Math.min(worst, confidence);
			if (confidence < XrayTuning.ALERT_CONFIDENCE) missed++;
		}

		// A bar that clears every honest pattern and also clears the cheat is not a bar, it is
		// the feature switched off with extra steps. This is the half that stops the fix for
		// false positives quietly turning into a fix for the detector existing.
		assertEquals(0, missed, "guided tunnelling went unreported " + missed + " times of "
				+ SEEDS + "; the weakest scored " + worst);
	}

	@Test
	@DisplayName("raising the sample floor blinds the detector rather than steadying it")
	void theSampleFloorHasRoomBeneathTheCheats() {
		// The knob that runs backwards, and the reason it is worth a test of its own. Guided
		// mining breaks *less* cover than honest mining — that is what makes it guided — so the
		// cheats are the small sessions and a high floor filters out exactly them. At 400 the
		// measured set loses all forty cheats and keeps all two hundred honest players.
		int smallestGuided = Integer.MAX_VALUE;
		for (int seed = 0; seed < SEEDS; seed++) {
			smallestGuided = Math.min(smallestGuided,
					MiningPatterns.guidedTunnelling(89 + seed).breaks().size());
		}

		assertTrue(XrayTuning.SAMPLE_FLOOR < smallestGuided,
				"the sample floor is " + XrayTuning.SAMPLE_FLOOR + " but the smallest guided "
						+ "session is " + smallestGuided + " blocks, so the detector would never "
						+ "score it at all");

		int headroom = smallestGuided - XrayTuning.SAMPLE_FLOOR;
		assertTrue(headroom >= 100,
				"only " + headroom + " blocks of headroom under the smallest cheat; a slightly "
						+ "more efficient one drops out of scope entirely");
	}

	@Test
	@DisplayName("the alert line sits between the two populations, not inside one")
	void theLineSeparatesRatherThanCuts() {
		int worstClean = 0;
		int weakestGuided = 100;

		for (int seed = 0; seed < SEEDS; seed++) {
			for (MiningPatterns.Pattern pattern : MiningPatterns.clean(seed)) {
				worstClean = Math.max(worstClean,
						XrayDetector.score(pattern.breaks(), 0).confidence());
			}
			weakestGuided = Math.min(weakestGuided, XrayDetector.score(
					MiningPatterns.guidedTunnelling(89 + seed).breaks(), 0).confidence());
		}

		assertTrue(worstClean < XrayTuning.ALERT_CONFIDENCE
						&& XrayTuning.ALERT_CONFIDENCE <= weakestGuided,
				"alert line " + XrayTuning.ALERT_CONFIDENCE + " should fall in the gap between "
						+ "the worst honest score (" + worstClean + ") and the weakest cheat ("
						+ weakestGuided + ")");
	}

	@Test
	@DisplayName("replay against a real database, when one is supplied")
	void replayRealData() throws SQLException {
		String db = System.getProperty("staffcore.replay.db");
		if (db == null || db.isBlank()) {
			// Nothing to replay. Deliberately not a failure: CI has no production data, and a
			// harness that only runs where the data lives is still the harness.
			return;
		}

		List<XrayReplay.Session> sessions =
				XrayReplay.fromDatabase(db, 7L * 24 * 3_600_000L, System.currentTimeMillis());

		List<XrayReplay.Cell> grid = XrayReplay.sweep(sessions,
				new int[] { 100, 200, 300, 400, 500 },
				new int[] { 45, 55, 65, 70, 75 },
				XrayTuning.NOTICE_CONFIDENCE);

		System.out.println("Replay over " + sessions.size() + " players from " + db);
		System.out.println(XrayReplay.table(grid));

		// No assertion on somebody else's player base — this prints so a server owner can
		// choose. What is asserted is that the harness actually read something, because an
		// empty result silently printing an all-zero grid is how a tuning run talks itself
		// into a threshold that was never tested.
		assertTrue(!sessions.isEmpty(), "no mining found in " + db + " — nothing was measured");
	}
}
