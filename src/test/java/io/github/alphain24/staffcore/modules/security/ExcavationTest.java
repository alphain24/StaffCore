package io.github.alphain24.staffcore.modules.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The population a p-value is computed against, which is the whole modelling decision.
 * <p>
 * Get this wrong in one direction and every tunneller looks like a cheat; get it wrong in the
 * other and nothing ever fires. Neither failure announces itself — both produce a plausible
 * number — so the shapes below are checked against what they should mean rather than against
 * values somebody wrote down once.
 */
class ExcavationTest {

	private static Excavation.Dig at(String block, int x, int y, int z) {
		return new Excavation.Dig(block, "overworld", x, y, z, 1_000_000L + x);
	}

	private static Excavation.Dig stone(int x, int y, int z) {
		return at("minecraft:stone", x, y, z);
	}

	@Test
	@DisplayName("a straight tunnel draws nearly its whole population")
	void tunnellingIsNotChoosing() {
		// The property that keeps honest miners out of this. Somebody digging a corridor was
		// not selecting between alternatives — they took almost everything within reach — so
		// whatever they find has to score as unremarkable.
		List<Excavation.Dig> digs = new ArrayList<>();
		for (int x = 0; x < 60; x++) digs.add(stone(x, 10, 0));

		Excavation.Segment segment = Excavation.segment(digs).get(0);

		assertTrue(segment.excavatedFraction() > 0.15,
				"a straight tunnel drew only " + segment.excavatedFraction() + " of its own "
						+ "surroundings, which would make every tunneller look selective");
		assertEquals(60, segment.drawn());
	}

	@Test
	@DisplayName("a wandering dig draws a small share of a much larger population")
	void detouringIsChoosing() {
		// The other side. A path that spreads out has far more rock within reach than it
		// touched, and finding ore anyway is the thing that needs explaining.
		List<Excavation.Dig> scattered = new ArrayList<>();
		for (int i = 0; i < 60; i++) scattered.add(stone(i * 4, 10 + (i % 5), i * 3));

		Excavation.Segment segment = Excavation.segment(scattered).get(0);

		assertTrue(segment.excavatedFraction() < 0.2,
				"a scattered dig drew " + segment.excavatedFraction() + " of its surroundings, "
						+ "which is the share a tunnel takes — the two shapes are not being "
						+ "told apart");
	}

	@Test
	@DisplayName("the shell is six-connected, not twenty-six")
	void diagonalsAreNotWithinReach() {
		// A diagonal cannot be taken in one swing. Counting it would multiply the population
		// by about four, and a larger population makes every result look more surprising —
		// the direction that accuses people.
		var shell = Excavation.shellAround(List.of(stone(0, 0, 0)));

		assertEquals(7, shell.size(), "one block should reach itself and six faces, not a cube");
	}

	@Test
	@DisplayName("depth bands are scored separately, because ore rates are not comparable")
	void depthIsSegmented() {
		// Deepslate at y-50 and stone at y 60 have almost nothing in common. A population
		// mixing them has an ore rate that describes neither.
		List<Excavation.Dig> spread = List.of(
				stone(0, -50, 0), stone(1, -50, 0),
				stone(0, 60, 0), stone(1, 60, 0));

		List<Excavation.Segment> segments = Excavation.segment(spread);

		assertEquals(2, segments.size(), "two very different depths were scored as one");
		assertTrue(segments.stream().allMatch(s -> s.drawn() == 2));
	}

	@Test
	@DisplayName("dimensions are never mixed")
	void dimensionsAreSegmented() {
		// Ancient debris in the nether and diamond in the overworld are different questions
		// with different answers, and averaging them produces one that fits neither.
		List<Excavation.Dig> both = List.of(
				new Excavation.Dig("minecraft:netherrack", "the_nether", 0, 10, 0, 1L),
				new Excavation.Dig("minecraft:stone", "overworld", 0, 10, 0, 1L));

		assertEquals(2, Excavation.segment(both).size());
	}

	@Test
	@DisplayName("only ore worth detouring for counts as a find")
	void commonOreIsNotASignal() {
		// Coal and copper are so common that finding them says nothing, and counting them
		// would drown the signal in blocks nobody goes out of their way for.
		assertTrue(at("minecraft:diamond_ore", 0, 0, 0).isTarget());
		assertTrue(at("minecraft:deepslate_diamond_ore", 0, 0, 0).isTarget());
		assertTrue(at("minecraft:ancient_debris", 0, 0, 0).isTarget());

		assertTrue(!at("minecraft:coal_ore", 0, 0, 0).isTarget(), "coal is everywhere");
		assertTrue(!at("minecraft:copper_ore", 0, 0, 0).isTarget(), "so is copper");
		assertTrue(!at("minecraft:stone", 0, 0, 0).isTarget());
	}

	@Test
	@DisplayName("found counts ore among what was removed, not what was nearby")
	void foundIsWhatTheyTook() {
		List<Excavation.Dig> digs = List.of(
				stone(0, 10, 0), stone(1, 10, 0),
				at("minecraft:diamond_ore", 2, 10, 0));

		Excavation.Segment segment = Excavation.segment(digs).get(0);

		assertEquals(1, segment.found());
		assertEquals(3, segment.drawn());
		assertTrue(segment.population() > segment.drawn(),
				"the population must be larger than the sample, or the question is vacuous");
	}

	@Test
	@DisplayName("an empty session produces nothing rather than a degenerate segment")
	void nothingInNothingOut() {
		assertTrue(Excavation.segment(List.of()).isEmpty());
	}

	@Test
	@DisplayName("overlapping digs are counted once in the population")
	void theShellIsASet() {
		// Two adjacent breaks share most of their surroundings. Counting the overlap twice
		// would inflate the population, and an inflated population makes results look more
		// surprising than they are.
		var shell = Excavation.shellAround(List.of(stone(0, 0, 0), stone(1, 0, 0)));

		// Seven each, and they share exactly two positions — each block is in the other's
		// shell — so twelve. Fourteen would mean the union is not deduplicating at all.
		assertTrue(shell.size() < 14, "adjacent digs double-counted their shared rock: "
				+ shell.size());
		assertEquals(12, shell.size(), "two adjacent blocks reach twelve positions between them");
	}
}
