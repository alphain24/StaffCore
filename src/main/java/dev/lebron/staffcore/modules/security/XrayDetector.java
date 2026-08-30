package dev.lebron.staffcore.modules.security;

import dev.lebron.staffcore.StaffCore;
import dev.lebron.staffcore.config.StaffConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.List;

/**
 * Scores how much a player's mining looks like x-ray.
 * <p>
 * A single ratio is a bad detector. Legitimate players hit rich patches; cheaters
 * sometimes mine cover to look normal; and either way one number gives staff no reason to
 * act on it. This combines four independent signals into a confidence score with the
 * evidence attached, so what reaches staff is "78% — went straight to 14 of 16 veins with
 * almost no stone between them", not "ratio 0.06".
 * <p>
 * Every signal is derived from the block log the server already keeps. Nothing watches the
 * player, and nothing here punishes anybody — a high score is a reason to go and look.
 */
public final class XrayDetector {
	private XrayDetector() {}

	/** A block counts as a "vein hit" if it is one of these. */
	private static boolean isOre(String block) {
		return block.endsWith("_ore") || block.endsWith("ancient_debris");
	}

	/**
	 * How unusual it is to find one of these, on a 1-10 scale.
	 * <p>
	 * Counting ore as one undifferentiated thing throws away the strongest signal there is.
	 * Two hundred coal and three diamond is what a real session looks like; forty diamond and
	 * five coal is not a lucky day, it is somebody who could see through stone and only
	 * bothered walking to the things worth having. The ratio of ore to filler cannot tell
	 * those apart — <em>what</em> the ore was can.
	 * <p>
	 * Weights are about how deliberately somebody has to look for a thing, not its market
	 * price. Deepslate and nether variants share their base ore's weight.
	 */
	private static int rarity(String block) {
		String name = block.substring(block.indexOf(':') + 1);
		if (name.endsWith("ancient_debris")) return 10;
		if (name.endsWith("diamond_ore")) return 8;
		if (name.endsWith("emerald_ore")) return 7;
		if (name.endsWith("gold_ore")) return 3;
		if (name.endsWith("lapis_ore") || name.endsWith("redstone_ore")) return 2;
		return 1; // coal, iron, copper, quartz — what everybody digs up anyway
	}

	/** The rarity a perfectly ordinary session lands near, used as the baseline to beat. */
	private static final double ORDINARY_RARITY = 2.0D;

	/** Short, readable name for a block id: {@code minecraft:deepslate_diamond_ore} → diamond. */
	private static String oreLabel(String block) {
		String name = block.substring(block.indexOf(':') + 1);
		if (name.endsWith("ancient_debris")) return "ancient debris";
		name = name.replace("deepslate_", "").replace("nether_", "").replace("_ore", "");
		return name.replace('_', ' ');
	}

	/** Blocks that make up ordinary cover — what an honest miner digs through. */
	private static boolean isFiller(String block) {
		return block.endsWith("stone") || block.endsWith("deepslate") || block.endsWith("granite")
				|| block.endsWith("andesite") || block.endsWith("diorite") || block.endsWith("tuff")
				|| block.endsWith("dirt") || block.endsWith("gravel") || block.endsWith("netherrack")
				|| block.endsWith("basalt") || block.endsWith("blackstone");
	}

	/**
	 * One logged block break, as the scorer sees it.
	 * <p>
	 * Public so the scoring can be exercised without a database. Fetching the data and
	 * judging it are separate jobs, and only one of them needs a server to test.
	 */
	public record Break(String block, int x, int y, int z, long at) {}

	/**
	 * The verdict, with the reasoning that produced it.
	 *
	 * @param byOre       how many of each kind of ore, biggest first — the breakdown staff
	 *                    actually read, because "forty ore" and "forty diamond" are not the
	 *                    same report
	 * @param rarityIndex mean rarity of what was found, 1 (all coal) to 10 (all debris)
	 */
	public record Report(int confidence, int oreCount, int fillerCount,
			double oreFraction, double directness, java.util.LinkedHashMap<String, Integer> byOre,
			double rarityIndex, List<String> reasons) {

		public boolean isSuspicious() {
			return confidence >= StaffConfig.get().xrayAlertConfidence;
		}

		/** Above the notice threshold but below the alert one — worth a look, not a verdict. */
		public boolean isNearMiss() {
			return !isSuspicious() && confidence >= StaffConfig.get().xrayNoticeConfidence;
		}

		public String headline() {
			return confidence + "% — " + (reasons.isEmpty() ? "nothing unusual" : reasons.get(0));
		}

		/** "12 diamond, 4 ancient debris, 30 iron" — capped so it fits a chat line. */
		public String oreBreakdown(int limit) {
			if (byOre.isEmpty()) return "no ore";
			List<String> parts = new ArrayList<>();
			for (var entry : byOre.entrySet()) {
				if (parts.size() >= limit) {
					parts.add("…");
					break;
				}
				parts.add(entry.getValue() + " " + entry.getKey());
			}
			return String.join(", ", parts);
		}

		static Report nothing(int ore, int filler) {
			return new Report(0, ore, filler, 0, 0, new java.util.LinkedHashMap<>(), 0, List.of());
		}
	}

	/** One window's verdict, for the side-by-side view. */
	public record Window(String label, long windowMs, Report report) {}

	/**
	 * The same player scored over several spans of time at once.
	 * <p>
	 * A single window is a blunt instrument in both directions. Look at one hour and a
	 * cheater who mines in short bursts is buried under the honest hours either side; look at
	 * a whole day and a twenty-minute run of pure diamond averages away to nothing. Scoring
	 * several spans and keeping the worst finds the burst without losing the pattern, and
	 * showing them side by side is usually the answer on its own — a player who scores 20%
	 * over a day and 85% over the last hour is telling you exactly when to look.
	 */
	public static List<Window> acrossWindows(String playerName) {
		return List.of(
				new Window("last hour", 3_600_000L, explain(playerName, 3_600_000L)),
				new Window("last 6 hours", 6L * 3_600_000L, explain(playerName, 6L * 3_600_000L)),
				new Window("last day", 24L * 3_600_000L, explain(playerName, 24L * 3_600_000L)),
				new Window("last week", 7L * 24 * 3_600_000L, explain(playerName, 7L * 24 * 3_600_000L)));
	}

	/** The window that scored highest — the one worth showing first. */
	public static Window worst(List<Window> windows) {
		if (windows == null || windows.isEmpty()) {
			return new Window("no data", 0, Report.nothing(0, 0));
		}
		Window best = windows.get(0);
		for (Window window : windows) {
			if (window.report().confidence() > best.report().confidence()) best = window;
		}
		return best;
	}

	/** Scores the last {@code windowMs} of a player's mining. */
	public static Report analyse(String playerName, long windowMs) {
		return analyse(playerName, windowMs, StaffConfig.get().xraySampleFloor);
	}

	/**
	 * Scores mining while ignoring the usual sample floor.
	 * <p>
	 * The automatic sweep stays quiet on thin data, which is correct but indistinguishable
	 * from the detector being broken. This is what {@code /staff xray} calls so a human
	 * asking directly gets an answer — including "you have not mined enough yet" — instead
	 * of the same silence.
	 */
	public static Report explain(String playerName, long windowMs) {
		return analyse(playerName, windowMs, 0);
	}

	private static Report analyse(String playerName, long windowMs, int sampleFloor) {
		return score(recentBreaks(playerName, windowMs), sampleFloor);
	}

	/**
	 * Scores a list of breaks. The whole detector, with no storage behind it.
	 *
	 * @param sampleFloor how many blocks are needed before this will commit to a verdict
	 */
	public static Report score(List<Break> breaks, int sampleFloor) {
		StaffConfig cfg = StaffConfig.get();

		int ore = 0;
		int filler = 0;
		long rarityTotal = 0;
		Map<String, Integer> oreCounts = new HashMap<>();

		for (Break b : breaks) {
			if (isOre(b.block())) {
				ore++;
				rarityTotal += rarity(b.block());
				oreCounts.merge(oreLabel(b.block()), 1, Integer::sum);
			} else if (isFiller(b.block())) {
				filler++;
			}
		}

		// Biggest first: a breakdown is read top-down and stops being read after three lines.
		java.util.LinkedHashMap<String, Integer> byOre = new java.util.LinkedHashMap<>();
		oreCounts.entrySet().stream()
				.sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
				.forEach(entry -> byOre.put(entry.getKey(), entry.getValue()));

		int total = ore + filler;
		double rarityIndex = ore == 0 ? 0 : rarityTotal / (double) ore;
		List<String> reasons = new ArrayList<>();

		// Not enough to say anything. Reporting on thin data is how a detector loses trust.
		if (total < sampleFloor) {
			return new Report(0, ore, filler, 0, 0, byOre, rarityIndex, List.of());
		}
		if (total == 0) {
			return new Report(0, 0, 0, 0, 0, byOre, 0,
					List.of("no mining logged in this window"));
		}

		double fraction = ore / (double) total;
		double directness = directness(breaks);
		int score = 0;

		// 1. Ore fraction. The blunt signal, kept but no longer decisive on its own.
		if (fraction > cfg.xrayRatioThreshold) {
			int points = (int) Math.min(40, (fraction / cfg.xrayRatioThreshold - 1) * 40);
			score += points;
			reasons.add("%.1f%% of %d blocks mined were ore".formatted(fraction * 100, total));
		}

		// 2. Directness — how little cover was dug between one vein and the next. This is
		//    the signal that actually separates a lucky player from a guided one: luck
		//    changes what you find, not how far you tunnel to reach it.
		if (directness > 0 && directness < cfg.xrayDirectnessFloor) {
			int points = (int) Math.min(35, (1 - directness / cfg.xrayDirectnessFloor) * 35);
			score += points;
			reasons.add("only %.1f filler blocks between veins on average".formatted(directness));
		}

		// 3. Stepping off the tunnel to collect a vein, over and over.
		int detours = detours(breaks);
		if (detours >= 4) {
			score += Math.min(20, detours * 3);
			reasons.add(detours + " short detours off the tunnel that ended exactly on ore");
		}

		// 4. Ancient debris is the strongest single tell: it never generates exposed, so
		//    every one found had to be tunnelled to blind.
		long debris = breaks.stream().filter(b -> b.block().endsWith("ancient_debris")).count();
		if (debris >= 4) {
			score += Math.min(15, (int) debris * 3);
			reasons.add(debris + " ancient debris in one session");
		}

		// 5. What kind of ore, not just how much. Somebody digging honestly comes back with
		//    mostly coal, iron and copper because that is what the ground is made of. A
		//    selection weighted towards diamond and debris is a choice, and choosing implies
		//    seeing. This is the signal that survives a player who mines a lot of filler to
		//    keep their ratio looking respectable.
		if (ore >= 8 && rarityIndex > ORDINARY_RARITY) {
			int points = (int) Math.min(30,
					(rarityIndex - ORDINARY_RARITY) / (10 - ORDINARY_RARITY) * 30);
			if (points > 0) {
				score += points;
				reasons.add("what they found skews rare — %s".formatted(
						new Report(0, 0, 0, 0, 0, byOre, 0, List.of()).oreBreakdown(3)));
			}
		}

		reasons.sort((a, b) -> Integer.compare(b.length(), a.length()));
		return new Report(Math.min(100, score), ore, filler, fraction, directness,
				byOre, rarityIndex, reasons);
	}

	/**
	 * Mean number of filler blocks broken between consecutive ore finds.
	 * <p>
	 * An honest miner clears a lot of stone per vein. Somebody who already knows where the
	 * ore is clears very little, and that ratio holds regardless of how lucky the world
	 * generation was — which is what makes it a better signal than the raw fraction.
	 */
	private static double directness(List<Break> breaks) {
		int gaps = 0;
		int fillerSinceOre = 0;
		List<Integer> gapSizes = new ArrayList<>();

		for (Break b : breaks) {
			if (isOre(b.block())) {
				gapSizes.add(fillerSinceOre);
				fillerSinceOre = 0;
				gaps++;
			} else if (isFiller(b.block())) {
				fillerSinceOre++;
			}
		}
		if (gaps < 3) return 0;

		// Consecutive blocks of the same vein produce zero-gaps; those are not evidence of
		// anything, so only the approach to each new vein is counted.
		double sum = 0;
		int counted = 0;
		for (int gap : gapSizes) {
			if (gap == 0) continue;
			sum += gap;
			counted++;
		}
		return counted == 0 ? 0 : sum / counted;
	}

    /**
     * Counts veins reached by a run of breaks that barely deviates — the digital signature
     * of walking a straight tunnel to a known coordinate.
     */
	/**
	 * Short detours off a tunnel that end exactly at ore.
	 * <p>
	 * This replaces a check that counted any straight run ending at ore, which flagged the
	 * most common honest technique on any server: a strip miner digs a long straight tunnel,
	 * so <em>every</em> vein they meet is reached at the end of a straight run. The signal
	 * was firing on the behaviour it was supposed to clear.
	 * <p>
	 * What actually separates the two is the turn. A strip miner meets ore in the wall and
	 * carries on along the same axis; somebody who already knows the ore is there leaves the
	 * tunnel, takes a few blocks at right angles, and the detour ends the moment they reach
	 * it. One or two of those is a person noticing something. A dozen is a map.
	 */
	private static int detours(List<Break> breaks) {
		// Break the session into straight runs: which way, how far, and what it ended on.
		record Segment(int axis, int length, boolean endsAtOre) {}
		List<Segment> segments = new ArrayList<>();

		int axis = -1;
		int run = 0;
		for (int i = 1; i < breaks.size(); i++) {
			Break prev = breaks.get(i - 1);
			Break now = breaks.get(i);

			int dx = Math.abs(now.x() - prev.x());
			int dy = Math.abs(now.y() - prev.y());
			int dz = Math.abs(now.z() - prev.z());
			boolean straight = dx + dy + dz == 1;

			if (!straight) {
				// A jump — they teleported, or the log skipped. Ends the run without
				// pretending the two ends were connected.
				if (run > 0) segments.add(new Segment(axis, run, isOre(prev.block())));
				axis = -1;
				run = 0;
				continue;
			}

			int stepAxis = dx == 1 ? 0 : dy == 1 ? 1 : 2;
			if (stepAxis == axis) {
				run++;
			} else {
				if (run > 0) segments.add(new Segment(axis, run, isOre(prev.block())));
				axis = stepAxis;
				run = 1;
			}
			if (isOre(now.block())) {
				segments.add(new Segment(axis, run, true));
				axis = -1;
				run = 0;
			}
		}

		int detours = 0;
		for (int i = 1; i < segments.size(); i++) {
			Segment approach = segments.get(i);
			Segment tunnel = segments.get(i - 1);

			// Short, at right angles to what came before, ending on the ore, and stepping
			// off something longer than itself. That last part is what makes it a detour
			// rather than a change of plan.
			if (approach.endsAtOre()
					&& approach.length() <= 8
					&& approach.axis() != tunnel.axis()
					&& tunnel.length() >= approach.length()) {
				detours++;
			}
		}
		return detours;
	}


	private static List<Break> recentBreaks(String playerName, long windowMs) {
		List<Break> out = new ArrayList<>();
		if (!StaffCore.storage().isReady()) return out;

		Connection c = StaffCore.storage().conn();
		long cutoff = System.currentTimeMillis() - windowMs;

		// Newest 4000 first, then flipped back into chronological order. Taking the oldest
		// 4000 instead would score a heavy miner on where they were six hours ago and never
		// look at what they are doing now — backwards, since the busiest players are the
		// ones the cap actually bites on.
		//
		// Ore the player placed themselves is excluded. Placing a block and breaking it
		// again is a real thing people do — moving a vein, decorating, testing silk touch —
		// and every one of those breaks used to read as a find. Somebody could inflate their
		// own ore fraction to whatever they liked with a stack of iron ore and a wall, which
		// is a bad property for a detector to have; more importantly it flagged honest
		// players who happened to build with the wrong material.
		String sql = """
				SELECT * FROM (
					SELECT b.block, b.x, b.y, b.z, b.created_at FROM block_log b
					WHERE b.player_name = ? AND b.action = 'BREAK' AND b.created_at >= ?
					  AND NOT EXISTS (
					      SELECT 1 FROM block_log p
					      WHERE p.action = 'PLACE' AND p.player_name = b.player_name
					        AND p.block = b.block AND p.world = b.world
					        AND p.x = b.x AND p.y = b.y AND p.z = b.z
					        AND p.created_at <= b.created_at AND p.created_at >= ?
					  )
					ORDER BY b.created_at DESC
					LIMIT 4000
				) ORDER BY created_at ASC
				""";
		try (PreparedStatement ps = c.prepareStatement(sql)) {
			ps.setString(1, playerName);
			ps.setLong(2, cutoff);
			ps.setLong(3, cutoff);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					out.add(new Break(rs.getString("block"), rs.getInt("x"), rs.getInt("y"),
							rs.getInt("z"), rs.getLong("created_at")));
				}
			}
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Security] mining analysis failed", e);
		}
		return out;
	}
}
