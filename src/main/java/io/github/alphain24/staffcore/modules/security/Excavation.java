package io.github.alphain24.staffcore.modules.security;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a player actually dug out, and what they could have been choosing among.
 *
 * <h2>The modelling decision this file is</h2>
 * A p-value needs a population. "The blocks the player removed" cannot be it — every one of
 * them was drawn, so the sample is the population and the question is vacuous. "The bounding
 * box of their session" cannot be it either: somebody who mines at two ends of a long corridor
 * gets a population of half a million blocks they were never near, and the arithmetic then
 * describes a lottery nobody entered.
 * <p>
 * The population is <b>the excavation and the rock immediately around it</b> — every block
 * within one step of something they broke. That is the set they were plausibly choosing
 * between at each swing: to reach anything further they would have had to dig towards it, and
 * digging towards it is the thing being measured.
 * <p>
 * The shell also decides the shape of the answer. A straight tunnel has a shell almost as large
 * as itself, so a tunneller draws nearly the whole population and scores as unremarkable
 * whatever they find — correctly, because they were not choosing. A dig that wanders from ore
 * to ore has a shell far larger than the path through it, and finding ore anyway is the thing
 * that needs explaining.
 *
 * <h2>Segmentation, and what is missing from it</h2>
 * Ore density varies by dimension and enormously by depth, so a population mixing y-8 with
 * y-70 has an ore rate that describes nowhere. Sessions are split into bands and each is
 * scored separately.
 * <p>
 * The brief also asks for segmentation by tool and fortune level. {@code block_log} records
 * neither, so that cannot be done for a single existing row — and the causal link is thin in a
 * way the other three are not. Depth, dimension and how much of the surrounding volume is
 * already air all change how much ore <em>is there</em>, which is the number the model needs. A
 * fortune pickaxe changes what drops from ore once found. See the note in decisions.md.
 */
public final class Excavation {
	private Excavation() {}

	/** One removed block, as the log recorded it. */
	public record Dig(String block, String world, int x, int y, int z, long at) {

		BlockPos pos() {
			return new BlockPos(x, y, z);
		}

		/** Whether this is one of the blocks worth going out of your way for. */
		public boolean isTarget() {
			return TARGETS.contains(block);
		}
	}

	/**
	 * Ores worth cheating for.
	 * <p>
	 * Deliberately not every ore. Coal and copper are so common that finding them says
	 * nothing, and including them would drown the signal in blocks nobody detours for.
	 */
	private static final Set<String> TARGETS = Set.of(
			"minecraft:diamond_ore", "minecraft:deepslate_diamond_ore",
			"minecraft:ancient_debris",
			"minecraft:emerald_ore", "minecraft:deepslate_emerald_ore",
			"minecraft:gold_ore", "minecraft:deepslate_gold_ore",
			"minecraft:redstone_ore", "minecraft:deepslate_redstone_ore",
			"minecraft:lapis_ore", "minecraft:deepslate_lapis_ore");

	/**
	 * One segment of a session: one dimension, one depth band.
	 *
	 * @param shell every position within one step of a dig, which is the population
	 */
	public record Segment(String world, int band, List<Dig> digs, Set<BlockPos> shell) {

		/** Blocks the player removed. The sample size. */
		public int drawn() {
			return digs.size();
		}

		/** Blocks they were choosing among, removed and untouched together. */
		public int population() {
			return shell.size();
		}

		/** Target ores among the ones they removed. */
		public int found() {
			return (int) digs.stream().filter(Dig::isTarget).count();
		}

		/** How much of the population they actually took. */
		public double excavatedFraction() {
			return population() == 0 ? 0 : (double) drawn() / population();
		}

		/** A readable name for the depth, for the report. */
		public String describeBand() {
			return "y " + band + " to " + (band + BAND_HEIGHT - 1);
		}
	}

	/**
	 * How tall a depth band is.
	 * <p>
	 * Sixteen, matching a chunk section, because that is the granularity ore generation
	 * actually varies at. Finer would split single tunnels across bands and leave each too
	 * small to say anything about; coarser would average y-negative deepslate against y-60
	 * stone, which have almost nothing in common.
	 */
	public static final int BAND_HEIGHT = 16;

	/**
	 * Splits a session into segments and works out the population of each.
	 * <p>
	 * A segment holding almost nothing is dropped by the caller rather than here, because how
	 * small is too small is a threshold and this is geometry.
	 */
	public static List<Segment> segment(Collection<Dig> digs) {
		Map<String, List<Dig>> grouped = new LinkedHashMap<>();

		for (Dig dig : digs) {
			int band = Math.floorDiv(dig.y(), BAND_HEIGHT) * BAND_HEIGHT;
			grouped.computeIfAbsent(dig.world() + "@" + band, k -> new ArrayList<>()).add(dig);
		}

		List<Segment> out = new ArrayList<>();
		for (Map.Entry<String, List<Dig>> entry : grouped.entrySet()) {
			List<Dig> inBand = entry.getValue();
			Dig first = inBand.get(0);
			int band = Math.floorDiv(first.y(), BAND_HEIGHT) * BAND_HEIGHT;

			out.add(new Segment(first.world(), band, List.copyOf(inBand), shellAround(inBand)));
		}
		return out;
	}

	/**
	 * Every position within one step of a dig, the digs themselves included.
	 * <p>
	 * Six-connected rather than twenty-six. A diagonal is not reachable in one swing, and
	 * counting it would inflate the population by a factor of four — which makes every result
	 * look more surprising than it is, in the direction that accuses people.
	 */
	static Set<BlockPos> shellAround(Collection<Dig> digs) {
		Set<BlockPos> shell = new HashSet<>();

		for (Dig dig : digs) {
			BlockPos pos = dig.pos();
			shell.add(pos);
			for (net.minecraft.core.Direction face : net.minecraft.core.Direction.values()) {
				shell.add(pos.relative(face));
			}
		}
		return shell;
	}

	/** Every target ore id, for the census to look for and for the docs test to check. */
	public static Set<String> targets() {
		return TARGETS;
	}
}
