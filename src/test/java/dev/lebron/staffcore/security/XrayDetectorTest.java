package dev.lebron.staffcore.security;

import dev.lebron.staffcore.modules.security.XrayDetector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The scoring half of the x-ray check, with no database behind it.
 * <p>
 * The thing worth pinning here is the difference between a lucky session and a guided one.
 * Both produce ore; only one of them produces ore that was <em>chosen</em>. A detector that
 * cannot tell those apart either accuses honest players or catches nobody, and which of the
 * two it does is decided entirely by where the threshold sits.
 */
class XrayDetectorTest {

	private static List<XrayDetector.Break> session(String... spec) {
		List<XrayDetector.Break> out = new ArrayList<>();
		long at = 0;
		int x = 0;
		for (String entry : spec) {
			String[] parts = entry.split("×");
			int count = Integer.parseInt(parts[0].trim());
			String block = "minecraft:" + parts[1].trim();
			for (int i = 0; i < count; i++) {
				out.add(new XrayDetector.Break(block, x++, 12, 0, at += 1000));
			}
		}
		return out;
	}

	/**
	 * A session where the ore is spread through the stone, as real mining is.
	 * <p>
	 * Order matters to this detector by design — the gap between one vein and the next is
	 * most of the signal — so a fixture that lists all the stone and then all the ore is not
	 * an honest session with the timestamps shuffled, it is a description of somebody
	 * tunnelling between veins. Which the detector correctly flagged, and which is why this
	 * generator exists.
	 *
	 * @param fillerPerOre how much stone to clear between finds
	 */
	private static List<XrayDetector.Break> interleaved(int fillerPerOre, String... ores) {
		List<XrayDetector.Break> out = new ArrayList<>();
		long at = 0;
		int x = 0;

		for (String entry : ores) {
			String[] parts = entry.split("×");
			int count = Integer.parseInt(parts[0].trim());
			String block = "minecraft:" + parts[1].trim();

			for (int i = 0; i < count; i++) {
				for (int f = 0; f < fillerPerOre; f++) {
					out.add(new XrayDetector.Break("minecraft:stone", x++, 12, 0, at += 1000));
				}
				out.add(new XrayDetector.Break(block, x++, 12, 0, at += 1000));
			}
		}
		return out;
	}

	@Test
	@DisplayName("an ordinary mining session scores nothing")
	void honestSessionIsQuiet() {
		// Common ore, well spread out: about twenty blocks of stone per find.
		var breaks = interleaved(20, "40× iron_ore", "30× coal_ore", "8× copper_ore",
				"3× diamond_ore", "2× gold_ore");

		XrayDetector.Report report = XrayDetector.score(breaks, 200);

		// Not "scores nothing" — a partial score on one signal is fine and expected. What
		// matters is that it stays well clear of the line where staff get told about it,
		// because a false positive costs more than a miss: it burns the trust that makes
		// anybody act on a true one.
		int notice = dev.lebron.staffcore.config.StaffConfig.get().xrayNoticeConfidence;
		assertTrue(report.confidence() < notice,
				"an honest session scored " + report.confidence() + ", notice threshold is "
						+ notice + " — " + report.reasons());
		assertFalse(report.isSuspicious(), "and it must certainly not alert");
		assertTrue(report.rarityIndex() < 3.0D, "mostly common ore should read as common");
	}

	@Test
	@DisplayName("rare ore is flagged even when the ratio is kept respectable")
	void raritySurvivesRatioPadding() {
		// The same tunnelling discipline as an honest player — twenty stone per find — but
		// everything found is diamond. Ratio and directness both look ordinary; the only
		// thing that gives it away is what came out of the ground.
		var breaks = interleaved(20, "30× diamond_ore");

		XrayDetector.Report report = XrayDetector.score(breaks, 200);
		assertTrue(report.rarityIndex() > 7.0D, "all diamond should read as rare");
		assertTrue(report.confidence() > 0,
				"padding the ratio with filler should not buy total silence");
	}

	@Test
	@DisplayName("digging almost nothing but diamond is flagged")
	void selectiveMiningIsFlagged() {
		var breaks = session("120× stone", "40× diamond_ore", "6× ancient_debris");

		XrayDetector.Report report = XrayDetector.score(breaks, 0);
		assertTrue(report.confidence() >= 50,
				"expected a high score, got " + report.confidence() + " — " + report.reasons());
		assertTrue(report.rarityIndex() > 7.0D, "diamond and debris should read as rare");
	}

	@Test
	@DisplayName("the breakdown says what was found, biggest first")
	void breakdownIsOrdered() {
		var breaks = session("100× stone", "20× iron_ore", "5× diamond_ore", "2× ancient_debris");
		XrayDetector.Report report = XrayDetector.score(breaks, 0);

		assertEquals(List.of("iron", "diamond", "ancient debris"),
				List.copyOf(report.byOre().keySet()));
		assertEquals(20, report.byOre().get("iron"));
		assertEquals("20 iron, 5 diamond", report.oreBreakdown(2).replace(", …", ""));
	}

	@Test
	@DisplayName("deepslate and nether variants count as their base ore")
	void variantsAreNormalised() {
		var breaks = session("50× stone", "4× deepslate_diamond_ore", "3× diamond_ore",
				"2× nether_gold_ore");
		XrayDetector.Report report = XrayDetector.score(breaks, 0);

		assertEquals(7, report.byOre().get("diamond"),
				"a diamond is a diamond whichever rock it was in");
		assertEquals(2, report.byOre().get("gold"));
	}

	/**
	 * A strip mine: one long straight tunnel, ore met in passing.
	 * <p>
	 * The most common honest technique on any server, and the one the old straight-line
	 * check flagged hardest — every vein a strip miner meets is by definition reached at the
	 * end of a straight run.
	 */
	@Test
	@DisplayName("a strip miner digging one straight tunnel is not flagged")
	void stripMiningIsNotADetour() {
		List<XrayDetector.Break> breaks = new ArrayList<>();
		long at = 0;
		for (int x = 0; x < 900; x++) {
			// Ore in the wall every thirty blocks, taken without leaving the tunnel.
			String block = x % 30 == 0 ? "minecraft:iron_ore" : "minecraft:stone";
			breaks.add(new XrayDetector.Break(block, x, 12, 0, at += 1000));
		}

		XrayDetector.Report report = XrayDetector.score(breaks, 200);
		assertEquals(0, report.confidence(),
				"strip mining is legitimate and must stay unflagged — got " + report.reasons());
	}

	@Test
	@DisplayName("stepping off the tunnel to grab a vein, repeatedly, is flagged")
	void repeatedDetoursAreFlagged() {
		List<XrayDetector.Break> breaks = new ArrayList<>();
		long at = 0;
		int x = 0;

		for (int vein = 0; vein < 10; vein++) {
			// Twenty blocks of tunnel...
			for (int i = 0; i < 20; i++) {
				breaks.add(new XrayDetector.Break("minecraft:stone", x++, 12, 0, at += 1000));
			}
			// ...then three blocks at right angles, landing exactly on diamond.
			for (int z = 1; z <= 3; z++) {
				String block = z == 3 ? "minecraft:deepslate_diamond_ore" : "minecraft:deepslate";
				breaks.add(new XrayDetector.Break(block, x, 12, z, at += 1000));
			}
		}

		XrayDetector.Report report = XrayDetector.score(breaks, 200);
		assertTrue(report.confidence() > 0,
				"ten purposeful detours onto diamond should say something");
		assertTrue(report.reasons().stream().anyMatch(r -> r.contains("detour")),
				"the detour signal should be among the reasons: " + report.reasons());
	}

	@Test
	@DisplayName("thin data produces no verdict rather than a guess")
	void sampleFloorHolds() {
		var breaks = session("10× stone", "9× diamond_ore");

		assertEquals(0, XrayDetector.score(breaks, 200).confidence(),
				"below the floor the detector must stay silent, however damning it looks");
		assertTrue(XrayDetector.score(breaks, 0).confidence() > 0,
				"asked directly, with the floor waived, it should still have an opinion");
	}
}
