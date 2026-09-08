package io.github.alphain24.staffcore.security;

import io.github.alphain24.staffcore.config.StaffConfig;
import io.github.alphain24.staffcore.modules.security.Excavation;
import io.github.alphain24.staffcore.modules.security.Hypergeometric;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The thresholds, justified against a corpus rather than against how often the detector talks.
 * <p>
 * The last time these numbers moved, the recorded reason was that the detector was too quiet.
 * That reason cannot be wrong, which is exactly what is wrong with it: any bar can be lowered
 * until a feature speaks, and nothing in the repository could say who it would start speaking
 * about. So the question here is who gets alerted on, not how many alerts there are.
 * <p>
 * The corpus generates six honest techniques and one guided one. An honest miner reaching the
 * alert line is a false accusation; the guided one going unnoticed is the feature not working.
 * Both are failures and only one of them is recoverable, which is why the margins below are
 * asymmetric.
 */
class XrayThresholdTest {

	/**
	 * Scores one generated pattern the way production does: per segment, worst wins.
	 * <p>
	 * A session is judged by its worst stretch rather than its average. An hour of honest
	 * tunnelling with twenty guided minutes in the middle averages out to nothing, and the
	 * twenty minutes are the entire point.
	 */
	private static int score(MiningPatterns.Pattern pattern) {
		int worst = 0;

		for (Excavation.Segment segment : Excavation.segment(pattern.breaks())) {
			if (segment.population() < StaffConfig.get().xrayMinimumVolume) continue;

			int ores = pattern.oresPresent(segment.population(), segment.drawn(), segment.found());
			double p = Hypergeometric.atLeast(segment.population(), ores,
					segment.drawn(), segment.found());
			worst = Math.max(worst, Hypergeometric.confidence(p));
		}
		return worst;
	}

	@Test
	@DisplayName("no honest mining pattern reaches the alert line")
	void honestMinersAreNotAccused() {
		// The failure that matters. A staff member who is told to go and look at somebody
		// innocent loses an hour; a player who is banned for strip mining loses the server.
		int alert = StaffConfig.get().xrayAlertConfidence;
		List<String> accused = new ArrayList<>();

		for (MiningPatterns.Pattern pattern : MiningPatterns.clean()) {
			int score = score(pattern);
			if (score >= alert) accused.add(pattern.name() + " scored " + score);
		}

		assertTrue(accused.isEmpty(),
				"honest mining crossed the " + alert + " alert line:\n  "
						+ String.join("\n  ", accused)
						+ "\n\nEvery one of these is a real technique somebody uses for hours "
						+ "at a time, and every alert on one is a staff member sent to look at "
						+ "nothing.");
	}

	@Test
	@DisplayName("honest mining stays clear of the line across many seeds")
	void separationIsNotACoincidence() {
		// One generated session per technique proves the arithmetic ran, not that it works.
		// Twenty seeds of each is the difference between a threshold that separates and one
		// that happens to sit above six particular numbers.
		int alert = StaffConfig.get().xrayAlertConfidence;
		List<String> accused = new ArrayList<>();

		for (long seed = 0; seed < 20; seed++) {
			for (MiningPatterns.Pattern pattern : MiningPatterns.clean(seed)) {
				int score = score(pattern);
				if (score >= alert) accused.add("seed " + seed + ": " + pattern.name() + " = " + score);
			}
		}

		assertTrue(accused.isEmpty(),
				"honest mining crossed the alert line on some seeds:\n  "
						+ String.join("\n  ", accused));
	}

	@Test
	@DisplayName("guided tunnelling is caught, and not marginally")
	void theCheatingCaseSeparates() {
		// A margin rather than a pass. A detector that catches the guided pattern at exactly
		// the threshold is one that stops catching it the first time somebody is slightly
		// more careful, and nothing would tell you it had.
		int alert = StaffConfig.get().xrayAlertConfidence;
		List<Integer> scores = new ArrayList<>();

		for (long seed = 0; seed < 20; seed++) {
			scores.add(score(MiningPatterns.guidedTunnelling(seed)));
		}

		long missed = scores.stream().filter(s -> s < alert).count();
		assertTrue(missed == 0,
				"guided tunnelling went unnoticed on " + missed + " of 20 seeds. Scores: "
						+ scores);
	}

	@Test
	@DisplayName("the alert line sits between the two populations, not inside one")
	void thereIsDaylightBetweenThem() {
		// The property a threshold is supposed to have, stated directly. If the worst honest
		// score and the best guided score overlap, no threshold separates them and moving
		// this number only trades one kind of mistake for the other.
		int worstHonest = 0;
		int weakestGuided = 100;

		for (long seed = 0; seed < 20; seed++) {
			for (MiningPatterns.Pattern pattern : MiningPatterns.clean(seed)) {
				worstHonest = Math.max(worstHonest, score(pattern));
			}
			weakestGuided = Math.min(weakestGuided, score(MiningPatterns.guidedTunnelling(seed)));
		}

		int gap = weakestGuided - worstHonest;
		assertTrue(gap > 0,
				"the populations overlap: the worst honest session scored " + worstHonest
						+ " and the weakest guided one " + weakestGuided + ". No threshold "
						+ "separates these, so moving the line only trades false accusations "
						+ "for missed cheats.");

		assertTrue(StaffConfig.get().xrayAlertConfidence > worstHonest
						&& StaffConfig.get().xrayAlertConfidence <= weakestGuided,
				"the configured alert line of " + StaffConfig.get().xrayAlertConfidence
						+ " is not inside the gap (" + worstHonest + " to " + weakestGuided + ")");
	}

	@Test
	@DisplayName("raising the volume gate blinds the detector rather than steadying it")
	void theVolumeGateIsNotAFreeDial() {
		// The dial somebody reaches for when there are too many alerts. Raising it does not
		// make the detector more careful — it makes it score fewer people, and the ones it
		// stops scoring are whoever mined least, not whoever was least suspicious.
		int[] volumes = {256, 512, 2048, 16384};
		List<XrayReplay.Cell> grid = XrayReplay.sweep(XrayReplay.fromPatterns(), volumes,
				new int[] {StaffConfig.get().xrayAlertConfidence},
				StaffConfig.get().xrayNoticeConfidence);

		int scoredAtLowest = grid.get(0).scored();
		int scoredAtHighest = grid.get(grid.size() - 1).scored();

		assertTrue(scoredAtHighest <= scoredAtLowest,
				"raising the volume gate scored more sessions, which is backwards");
		assertTrue(scoredAtLowest > 0, "nothing was scored at all, so the grid says nothing");
	}

	@Test
	@DisplayName("replay against a real database, when one is supplied")
	void replayAgainstRealData() throws SQLException {
		// Skipped in CI and everywhere else. This is the instrument for whoever is choosing
		// the numbers against a real player base, and the numbers it prints belong in
		// decisions.md rather than in an assertion.
		String path = System.getProperty("staffcore.replay.db");
		if (path == null || path.isBlank()) return;

		var sessions = XrayReplay.fromDatabase(path, 6L * 3_600_000L, System.currentTimeMillis(),
				0.006);
		var grid = XrayReplay.sweep(sessions, new int[] {256, 512, 2048},
				new int[] {50, 60, 65, 70, 80}, StaffConfig.get().xrayNoticeConfidence);

		System.out.println(XrayReplay.table(grid));
		assertTrue(!sessions.isEmpty(), "the database had no mining in the window");
	}
}
