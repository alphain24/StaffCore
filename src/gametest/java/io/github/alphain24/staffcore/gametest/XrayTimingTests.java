package io.github.alphain24.staffcore.gametest;

import io.github.alphain24.staffcore.StaffCore;
import io.github.alphain24.staffcore.modules.security.XraySweep;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * What the sweep costs, asserted in a unit that means the same thing on every machine.
 *
 * <h2>Why this no longer asserts on elapsed time</h2>
 * It used to require that the on-thread census finish inside half a tick. That failed about
 * twice in twelve runs, at 27ms and 41ms against a 25ms budget, while the honest figure is
 * under a millisecond — the machine was compiling, running a second server, and occasionally
 * collecting garbage. Nothing had got slower.
 * <p>
 * The tempting fix is a wider budget, and it is the wrong one. A timing test that is loosened
 * every time it fails converges on asserting nothing, and each loosening looks locally
 * reasonable. Taking the best of several runs was the second-best answer and still leaves a
 * wall-clock number in a pass/fail gate, where a slow enough machine eventually reaches it.
 * <p>
 * So the gate is now on <b>{@code blocksRead}</b> — how many block states the census actually
 * touched. That is what the census costs, it is a function of the input rather than of the
 * hardware, it is identical on a laptop and on CI, and it is what Gate 3's claim was really
 * about: the one part that cannot leave the server thread is <em>bounded</em>.
 * <p>
 * The microsecond figures are still measured and still printed. They belong in the profiling
 * record in {@code docs/decisions.md} as recorded numbers, which is where Gate 3 already keeps
 * its measurements — a figure somebody can compare against next year, rather than a threshold
 * that fails on a busy afternoon.
 *
 * <h2>What "bounded" means here</h2>
 * The census reads the shell around each excavation, once per segment above the volume floor.
 * It is bounded by the size of the excavations in the window, and — this is the part worth
 * pinning — it is <em>not</em> a function of how many players are online, how many rows the
 * block log holds, or how long the server has been up. A change that made it proportional to
 * any of those would still be fast on a quiet test server and ruinous on a real one.
 */
public class XrayTimingTests {

	/** Enough rows to be a busy evening rather than a demonstration. */
	private static final int PLAYERS = 8;
	private static final int BREAKS_EACH = 1200;

	/**
	 * The most block states one player's sweep may read.
	 * <p>
	 * Their 1,200 breaks occupy a band of about 60 x 8 x 3 positions, and the census reads the
	 * shell around what they dug. Ten thousand is comfortably above what that needs and far
	 * below what a bug would produce — reading the whole population per segment rather than
	 * its shell, or censusing every player rather than the one asked for, would each blow
	 * through this by an order of magnitude.
	 */
	private static final int CENSUS_CEILING = 10_000;

	@GameTest
	public void theCensusIsBoundedByTheDigAndNothingElse(GameTestHelper helper) {
		if (!StaffCore.storage().isReady()) {
			helper.succeed();
			return;
		}

		try {
			seed(helper);
		} catch (SQLException e) {
			throw helper.assertionException("could not seed the block log: " + e.getMessage());
		}

		// Synchronous, and the timing comes back with the findings rather than out of a
		// shared field. It used to be read from a static that any concurrent sweep could
		// overwrite between the call and the read — so this test could measure another
		// test's work and pass for the wrong reason.
		XraySweep.Swept swept = XraySweep.sweepOne(Harness.server(helper), "timing-0",
				6L * 3_600_000L);
		XraySweep.Timing timing = swept.timing();

		// Printed, not asserted. This is the Gate 3 figure; it lives in decisions.md.
		StaffCore.LOGGER.info("[XrayTiming] {} players x {} breaks, scoring one of them: "
						+ "read {}us, census {}us, arithmetic {}us, total {}us "
						+ "({} block states read)",
				PLAYERS, BREAKS_EACH, timing.readMicros(), timing.censusMicros(),
				timing.mathMicros(), timing.totalMicros(), timing.blocksRead());

		Harness.check(helper, timing.blocksRead() > 0,
				"the census read no block states at all, so this measured a sweep that did "
						+ "not happen — check the seeded rows are inside the window and above "
						+ "xrayMinimumVolume");

		Harness.check(helper, timing.blocksRead() < CENSUS_CEILING,
				"the census read " + timing.blocksRead() + " block states for one player's "
						+ "session, against a ceiling of " + CENSUS_CEILING + ". This is the "
						+ "one part of detection that cannot leave the server thread, so its "
						+ "size is the whole of the Gate 3 claim. Reading the population "
						+ "instead of the shell, or censusing every player rather than the one "
						+ "asked for, would each look like this.");

		// The population is eight players' worth of rows and only one was asked about. If the
		// census were walking the log rather than the named player's excavation, this is where
		// it would show — and it would show identically on a fast machine.
		Harness.checkEquals(helper, 1, timing.players(),
				"scoring one player censused " + timing.players() + " players' data. The work "
						+ "is supposed to be proportional to the excavation asked about, not "
						+ "to how many other people happen to be in the log.");
		helper.succeed();
	}

	@GameTest
	public void anEmptyLogCostsNothing(GameTestHelper helper) {
		// The common case on most servers most of the time, and the one where a sweep that did
		// work proportional to the player list rather than to the data would show it.
		//
		// Asserted as zero rather than as "fast". Zero block reads is what "costs nothing"
		// actually means, it cannot be reached by a slow machine, and it cannot be satisfied
		// by a sweep that does a little work quickly.
		XraySweep.Swept swept = XraySweep.sweepOne(Harness.server(helper),
				"nobody-has-this-name", 6L * 3_600_000L);

		Harness.checkEquals(helper, 0, swept.timing().blocksRead(),
				"scoring a player with no mining read " + swept.timing().blocksRead()
						+ " block states. Nobody's excavation was involved, so nothing should "
						+ "have been censused — work that happens for a player with no data is "
						+ "work proportional to something other than the data.");
		Harness.checkEquals(helper, 0, swept.findings().size(),
				"a player with no mining produced findings");
		helper.succeed();
	}

	private static void seed(GameTestHelper helper) throws SQLException {
		long now = System.currentTimeMillis();

		try (PreparedStatement ps = StaffCore.storage().conn().prepareStatement(
				"INSERT INTO block_log (player_name, action, block, world, x, y, z, created_at, "
						+ "gamemode) VALUES (?,'BREAK',?,?,?,?,?,?,'survival')")) {

			StaffCore.storage().inTransaction(conn -> {
				try {
					for (int player = 0; player < PLAYERS; player++) {
						for (int i = 0; i < BREAKS_EACH; i++) {
							ps.setString(1, "timing-" + player);
							ps.setString(2, i % 40 == 0
									? "minecraft:deepslate_diamond_ore" : "minecraft:deepslate");
							ps.setString(3, io.github.alphain24.staffcore.compat.Mc
									.dimensionId(helper.getLevel()));
							ps.setInt(4, i % 60);
							ps.setInt(5, 10 + (i / 60) % 8);
							ps.setInt(6, i / 480);
							ps.setLong(7, now - i * 1000L);
							ps.addBatch();
						}
					}
					ps.executeBatch();
				} catch (SQLException e) {
					throw new RuntimeException(e);
				}
			});
		}
	}
}
