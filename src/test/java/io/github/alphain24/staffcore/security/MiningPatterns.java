package io.github.alphain24.staffcore.security;

import io.github.alphain24.staffcore.modules.security.Excavation;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Mining sessions with known intent, generated block by block.
 * <p>
 * These exist because the detector's thresholds were last moved on the grounds that it was
 * "too quiet". That is a visibility argument, and visibility is not accuracy — a detector can
 * be made to speak as often as you like by lowering the bar, and every one of those extra
 * words is an accusation aimed at whoever happens to be mining. Nothing in the repository
 * measured what the change cost, because there was nothing to measure it against.
 * <p>
 * This is that something. Five ways honest people dig, one way a cheat does, all with
 * plausible geometry — because half the detector's signal comes from <em>where</em> the blocks
 * were, not just what they were. A fixture that lists ore and filler in the wrong order is
 * describing somebody tunnelling between veins whatever the counts say.
 * <p>
 * Seeded, so a threshold chosen from these numbers can be re-derived exactly.
 */
final class MiningPatterns {
	private MiningPatterns() {}

	/**
	 * One generated session, what it is meant to represent, and the ore density it was
	 * generated against.
	 * <p>
	 * The density is the ground truth the new statistics need and the old score did not.
	 * Scoring a dig means comparing what the player took against what was there to take, and
	 * on a real server the second number comes from counting the ore still standing. There is
	 * no world here — so the corpus states it, which it can, because it is the thing that
	 * chose it.
	 *
	 * @param oreFraction share of the surrounding rock that was target ore, as generated.
	 *                    Target only: coal and copper are not worth detouring for and are not
	 *                    counted by the detector either.
	 */
	record Pattern(String name, String description, List<Excavation.Dig> breaks, boolean clean,
			boolean deep) {

		/** The measured ambient density for this pattern's depth band. */
		double oreFraction() {
			return ambientFraction(deep);
		}

		/**
		 * How much ore was in reach, found and unfound together.
		 * <p>
		 * The density applies to the rock they did <em>not</em> take. Applying it to the whole
		 * population and taking the larger of that and what they found says "there were exactly
		 * as many ores as they got", which is maximally incriminating and true of nobody — it
		 * scored every honest pattern in this corpus at 99 the first time it was written.
		 */
		int oresPresent(int population, int drawn, int found) {
			return found + (int) Math.round(Math.max(0, population - drawn) * oreFraction());
		}
	}

	/**
	 * Ambient target-ore density, measured from the corpus rather than assumed.
	 * <p>
	 * This is the number a p-value is compared against, and picking it by hand was wrong twice
	 * over: the value would be a guess, and it would be a guess about generated rock rather
	 * than about real rock, so it could not be checked against anything.
	 * <p>
	 * Measured instead. An unguided miner's find rate <em>is</em> the ambient density — that is
	 * what unguided means — so the honest patterns define it and the guided one is scored
	 * against it. Not circular: the question being asked of the guided pattern is precisely
	 * "did this player find more than an unguided miner would in the same rock", and the clean
	 * patterns are the only available statement of what that rate is.
	 */
	static double ambientFraction(boolean deep) {
		Double cached = deep ? deepFraction : shallowFraction;
		if (cached != null) return cached;

		int found = 0;
		int drawn = 0;

		// Reads the break lists rather than the Patterns' own density, which is what breaks
		// the cycle: measuring the density used to require building a Pattern, and building a
		// Pattern used to require the density.
		for (Pattern pattern : deep ? List.of(deepslateDiamondHunt()) : shallowClean()) {
			for (Excavation.Dig dig : pattern.breaks()) {
				drawn++;
				if (dig.isTarget()) found++;
			}
		}

		double fraction = drawn == 0 ? 0 : (double) found / drawn;
		if (deep) deepFraction = fraction;
		else shallowFraction = fraction;
		return fraction;
	}

	private static Double shallowFraction;
	private static Double deepFraction;

	/** The clean patterns that mine above y=0, which is where the shallow rate comes from. */
	private static List<Pattern> shallowClean() {
		return List.of(stripMine(), branchMine(), caveClearing(), quarry());
	}

	// Ore as it actually turns up, roughly by how common each is at the depth in question.
	private static final String[] SHALLOW_ORE = {
			"coal_ore", "coal_ore", "coal_ore", "coal_ore", "coal_ore",
			"iron_ore", "iron_ore", "iron_ore", "iron_ore",
			"copper_ore", "copper_ore",
			"lapis_ore", "gold_ore"
	};

	private static final String[] DEEP_ORE = {
			"deepslate_coal_ore", "deepslate_coal_ore",
			"deepslate_iron_ore", "deepslate_iron_ore", "deepslate_iron_ore",
			"deepslate_copper_ore",
			"deepslate_redstone_ore", "deepslate_redstone_ore", "deepslate_redstone_ore",
			"deepslate_lapis_ore", "deepslate_lapis_ore",
			"deepslate_gold_ore",
			"deepslate_diamond_ore"
	};

	/** A builder that keeps the running position, so every break is adjacent to the last. */
	private static final class Dig {
		private final List<Excavation.Dig> breaks = new ArrayList<>();
		private final String filler;
		private long at = 1_000L;
		private int x;
		private int y;
		private int z;

		Dig(String filler, int x, int y, int z) {
			this.filler = "minecraft:" + filler;
			this.x = x;
			this.y = y;
			this.z = z;
		}

		/** One step along an axis, breaking whatever is named. */
		void step(int dx, int dy, int dz, String block) {
			x += dx;
			y += dy;
			z += dz;
			at += 900L;
			breaks.add(new Excavation.Dig(block, "overworld", x, y, z, at));
		}

		void tunnel(int dx, int dy, int dz, int length) {
			for (int i = 0; i < length; i++) step(dx, dy, dz, filler);
		}

		/** Move without breaking — walking back down a tunnel already dug. */
		void walkTo(int nx, int ny, int nz) {
			x = nx;
			y = ny;
			z = nz;
		}

		int x() {
			return x;
		}

		int y() {
			return y;
		}

		List<Excavation.Dig> done() {
			return breaks;
		}
	}

	private static String ore(String name) {
		return "minecraft:" + name;
	}

	/**
	 * A straight tunnel at diamond level, with veins taken out of the walls as they appear.
	 * <p>
	 * The most common honest technique there is, and the one an earlier version of the
	 * detector flagged hardest: a strip miner meets every vein at the end of a straight run,
	 * because a straight run is the only thing they ever dig.
	 */
	static Pattern stripMine() {
		return stripMine(11);
	}

	static Pattern stripMine(long seed) {
		Random rng = new Random(seed);
		Dig dig = new Dig("stone", 0, 12, 0);

		for (int tunnel = 0; tunnel < 6; tunnel++) {
			int z = tunnel * 4;
			dig.walkTo(0, 12, z);

			for (int run = 0; run < 120; run++) {
				dig.tunnel(1, 0, 0, 1);

				// Something in the wall, about one block in twenty-five.
				if (rng.nextInt(25) == 0) {
					int side = rng.nextBoolean() ? 1 : -1;
					String block = ore(SHALLOW_ORE[rng.nextInt(SHALLOW_ORE.length)]);
					// Step off the tunnel to take it, plus a block or two of the same vein.
					dig.step(0, 0, side, block);
					for (int extra = rng.nextInt(3); extra > 0; extra--) {
						dig.step(0, 0, side, block);
					}
					dig.walkTo(dig.x(), dig.y(), z);
				}
			}
		}
		return new Pattern("strip mine",
				"720 blocks of straight tunnel at y=12, veins taken from the walls",
				dig.done(), true, false);
	}

	/**
	 * A spine with ribs off it every few blocks — the efficient variant, and the one that
	 * produces the most right-angle turns per hour of anything honest.
	 */
	static Pattern branchMine() {
		return branchMine(23);
	}

	static Pattern branchMine(long seed) {
		Random rng = new Random(seed);
		Dig dig = new Dig("stone", 0, 11, 0);
		dig.tunnel(0, 0, 1, 90);

		for (int branch = 0; branch < 18; branch++) {
			int z = branch * 5;
			int side = branch % 2 == 0 ? 1 : -1;
			dig.walkTo(0, 11, z);

			for (int out = 0; out < 22; out++) {
				dig.tunnel(side, 0, 0, 1);
				if (rng.nextInt(22) == 0) {
					String block = ore(SHALLOW_ORE[rng.nextInt(SHALLOW_ORE.length)]);
					dig.step(0, rng.nextBoolean() ? 1 : -1, 0, block);
					for (int extra = rng.nextInt(3); extra > 0; extra--) {
						dig.step(0, 0, rng.nextBoolean() ? 1 : -1, block);
					}
				}
			}
		}
		return new Pattern("branch mine",
				"a 90-block spine with eighteen ribs, ore taken where it appears",
				dig.done(), true, false);
	}

	/**
	 * Following a cave system and clearing what is exposed.
	 * <p>
	 * Interesting because it is the honest pattern that looks least like tunnelling: the
	 * player wanders, changes axis constantly, and the ore is already visible when they reach
	 * it — so very little cover is broken per find. That is the same shape as a guided run,
	 * and the reason ore <em>rarity</em> has to carry weight the raw gap cannot.
	 */
	static Pattern caveClearing() {
		return caveClearing(37);
	}

	static Pattern caveClearing(long seed) {
		Random rng = new Random(seed);
		Dig dig = new Dig("stone", 0, 30, 0);

		for (int leg = 0; leg < 140; leg++) {
			int axis = rng.nextInt(3);
			int dir = rng.nextBoolean() ? 1 : -1;
			int length = 2 + rng.nextInt(5);

			for (int i = 0; i < length; i++) {
				if (axis == 0) dig.step(dir, 0, 0, "minecraft:stone");
				else if (axis == 1) dig.step(0, dir, 0, "minecraft:andesite");
				else dig.step(0, 0, dir, "minecraft:stone");
			}
			if (rng.nextInt(6) == 0) {
				String block = ore(SHALLOW_ORE[rng.nextInt(SHALLOW_ORE.length)]);
				for (int extra = 1 + rng.nextInt(3); extra > 0; extra--) {
					dig.step(0, 0, 1, block);
				}
			}
		}
		return new Pattern("cave clearing",
				"wandering an open cave system and taking what is already exposed",
				dig.done(), true, false);
	}

	/** Everything in a rectangle, layer by layer. Almost pure filler by volume. */
	static Pattern quarry() {
		return quarry(53);
	}

	static Pattern quarry(long seed) {
		Random rng = new Random(seed);
		Dig dig = new Dig("stone", 0, 40, 0);

		for (int layer = 0; layer < 5; layer++) {
			int y = 40 - layer;
			for (int row = 0; row < 12; row++) {
				dig.walkTo(0, y, row);
				int dir = row % 2 == 0 ? 1 : -1;
				for (int i = 0; i < 14; i++) {
					if (rng.nextInt(30) == 0) {
						dig.step(dir, 0, 0, ore(SHALLOW_ORE[rng.nextInt(SHALLOW_ORE.length)]));
					} else {
						dig.step(dir, 0, 0, "minecraft:stone");
					}
				}
			}
		}
		return new Pattern("quarry", "a 14x12 pit taken down five layers, everything removed",
				dig.done(), true, false);
	}

	/**
	 * Somebody who knows the numbers, mining deepslate at y=-54 specifically for diamond.
	 * <p>
	 * The hardest honest case and the one most likely to be wrongly accused. They are
	 * deliberately at the depth with the best odds, they are there for one thing, and the
	 * ground obliges: deepslate at that level is genuinely rich, so their ore fraction is high
	 * and their gaps between veins are short through no fault of their own. Any threshold that
	 * cannot clear this player is not fit to be switched on.
	 */
	static Pattern deepslateDiamondHunt() {
		return deepslateDiamondHunt(67);
	}

	static Pattern deepslateDiamondHunt(long seed) {
		Random rng = new Random(seed);
		Dig dig = new Dig("deepslate", 0, -54, 0);

		for (int tunnel = 0; tunnel < 8; tunnel++) {
			dig.walkTo(0, -54, tunnel * 3);
			for (int run = 0; run < 110; run++) {
				dig.tunnel(1, 0, 0, 1);
				if (rng.nextInt(14) == 0) {
					int side = rng.nextBoolean() ? 1 : -1;
					String block = ore(DEEP_ORE[rng.nextInt(DEEP_ORE.length)]);
					dig.step(0, 0, side, block);
					for (int extra = rng.nextInt(4); extra > 0; extra--) {
						dig.step(0, 0, side, block);
					}
					dig.walkTo(dig.x(), dig.y(), tunnel * 3);
				}
			}
		}
		return new Pattern("deepslate diamond hunt",
				"880 blocks at y=-54, going for diamond and finding what is down there",
				dig.done(), true, true);
	}

	/**
	 * The thing the detector is for: straight runs from one vein to the next, almost no cover
	 * broken, and a haul weighted to what is worth having.
	 * <p>
	 * Not a threshold to satisfy — a control. A bar that clears every honest pattern and also
	 * clears this one is not a bar, it is the feature switched off, and the grid needs to be
	 * able to show that.
	 */
	static Pattern guidedTunnelling() {
		return guidedTunnelling(89);
	}

	static Pattern guidedTunnelling(long seed) {
		Random rng = new Random(seed);
		Dig dig = new Dig("deepslate", 0, -54, 0);

		for (int vein = 0; vein < 48; vein++) {
			// A short hop towards the next known vein, then a right-angle approach on to it.
			dig.tunnel(1, 0, 0, 3 + rng.nextInt(5));
			if (rng.nextInt(3) == 0) dig.tunnel(0, rng.nextBoolean() ? 1 : -1, 0, 1 + rng.nextInt(2));

			String block = ore(rng.nextInt(4) == 0
					? "deepslate_redstone_ore" : "deepslate_diamond_ore");
			int side = rng.nextBoolean() ? 1 : -1;
			for (int count = 1 + rng.nextInt(3); count > 0; count--) {
				dig.step(0, 0, side, block);
			}
		}
		return new Pattern("guided tunnelling",
				"48 veins reached by short right-angle approaches, mostly diamond",
				dig.done(), false, true);
	}

	/** Every clean pattern — the set a threshold has to leave alone. */
	static List<Pattern> clean() {
		return clean(0);
	}

	/**
	 * The clean set again with the generators re-seeded.
	 * <p>
	 * One sample of each pattern is a coincidence, not a measurement. A threshold picked from
	 * a single seed is fitted to that seed, and the first honest player whose luck ran the
	 * other way pays for it — so the choice is made against the worst score across many.
	 */
	static List<Pattern> clean(long offset) {
		return List.of(stripMine(11 + offset), branchMine(23 + offset), caveClearing(37 + offset),
				quarry(53 + offset), deepslateDiamondHunt(67 + offset));
	}

	static List<Pattern> all() {
		List<Pattern> out = new ArrayList<>(clean());
		out.add(guidedTunnelling());
		return out;
	}
}
