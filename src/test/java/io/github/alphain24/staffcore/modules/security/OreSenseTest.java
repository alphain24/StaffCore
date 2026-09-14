package io.github.alphain24.staffcore.modules.security;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The live x-ray score's arithmetic and its reading of terrain, without a world.
 */
class OreSenseTest {

	/** A little terrain: everything is rock unless said otherwise. */
	private static final class Grid implements OreSense.Terrain {
		final Map<Long, OreSense.Cell> cells = new HashMap<>();

		Grid set(BlockPos pos, OreSense.Cell cell) {
			cells.put(pos.asLong(), cell);
			return this;
		}

		@Override
		public OreSense.Cell at(BlockPos pos) {
			return cells.getOrDefault(pos.asLong(), OreSense.Cell.ROCK);
		}
	}

	@Test
	@DisplayName("the Poisson tail matches values worked out by hand")
	void poissonTail() {
		// P(X >= 1 | 1) = 1 - e^-1
		assertEquals(1 - Math.exp(-1), OreSense.poissonTail(1, 1.0), 1e-12);
		// P(X >= 3 | 0.5) = 1 - e^-0.5 (1 + 0.5 + 0.125)
		assertEquals(1 - Math.exp(-0.5) * 1.625, OreSense.poissonTail(3, 0.5), 1e-12);
		assertEquals(1.0, OreSense.poissonTail(0, 5.0));
		// Far in the tail, where 1 minus nearly 1 would lose everything.
		double tiny = OreSense.poissonTail(20, 0.5);
		assertTrue(tiny > 0 && tiny < 1e-20, "the far tail underflowed or lost precision: " + tiny);
		// And a large mean does not report certainty.
		double middle = OreSense.poissonTail(800, 800.0);
		assertTrue(middle > 0.4 && middle < 0.6, "P(X >= mean) for a large mean was " + middle);
	}

	@Test
	@DisplayName("breaking beside a sealed diamond is a find; beside one open to air is not")
	void sealedAndOpenOre() {
		BlockPos broken = new BlockPos(0, 0, 0);
		Grid grid = new Grid().set(broken, OreSense.Cell.OPEN)
				.set(broken.east(), OreSense.Cell.DIAMOND);
		Set<Long> counted = new HashSet<>();

		OreSense.Uncovered found = OreSense.uncover(grid, broken, counted, true);
		assertEquals(1, found.hiddenVeins());
		assertEquals(6, found.faces(), "six sealed neighbours are six opened faces");

		BlockPos elsewhere = new BlockPos(10, 0, 0);
		Grid open = new Grid().set(elsewhere, OreSense.Cell.OPEN)
				.set(elsewhere.east(), OreSense.Cell.DIAMOND)
				.set(elsewhere.east().east(), OreSense.Cell.OPEN);
		OreSense.Uncovered seen = OreSense.uncover(open, elsewhere, new HashSet<>(), true);
		assertEquals(0, seen.hiddenVeins(), "ore already open to air is not hidden");
	}

	@Test
	@DisplayName("a vein counts once, from whichever end it is reached")
	void oneVeinOnce() {
		Grid grid = new Grid();
		BlockPos a = new BlockPos(0, 0, 0);
		grid.set(a, OreSense.Cell.DIAMOND).set(a.east(), OreSense.Cell.DIAMOND)
				.set(a.east().above(), OreSense.Cell.DIAMOND);
		Set<Long> counted = new HashSet<>();

		grid.set(a.west(), OreSense.Cell.OPEN);
		assertEquals(1, OreSense.uncover(grid, a.west(), counted, true).hiddenVeins());

		BlockPos far = a.east().above().east();
		grid.set(far, OreSense.Cell.OPEN);
		assertEquals(0, OreSense.uncover(grid, far, counted, true).hiddenVeins(),
				"the far end of a vein already counted was counted again");
	}

	@Test
	@DisplayName("a face onto rock that was already open is not a new face")
	void onlyNewFaces() {
		BlockPos broken = new BlockPos(0, 0, 0);
		Grid grid = new Grid().set(broken, OreSense.Cell.OPEN)
				.set(broken.north().north(), OreSense.Cell.OPEN);   // behind the north neighbour
		assertEquals(5, OreSense.uncover(grid, broken, new HashSet<>(), true).faces());
	}

	@Test
	@DisplayName("a long gap or a different world starts a new session")
	void sessionsReset() {
		UUID player = UUID.randomUUID();
		OreSense.Observation first = new OreSense.Observation(player, "p", "minecraft:overworld",
				1_000, -58, true, 5, 1, 0, 0, null);
		OreSense.Session session = OreSense.fold(null, first, 0.002);
		assertEquals(1, session.hiddenVeins());

		OreSense.Observation soon = new OreSense.Observation(player, "p", "minecraft:overworld",
				2_000, -58, true, 5, 1, 0, 0, null);
		assertEquals(2, OreSense.fold(session, soon, 0.002).hiddenVeins());

		OreSense.Observation late = new OreSense.Observation(player, "p", "minecraft:overworld",
				1_000 + OreSense.SESSION_GAP_MS + 1, -58, true, 5, 1, 0, 0, null);
		assertEquals(1, OreSense.fold(session, late, 0.002).hiddenVeins());

		OreSense.Observation nether = new OreSense.Observation(player, "p", "minecraft:the_nether",
				2_000, 40, false, 5, 0, 1, 0.001, null);
		OreSense.Session there = OreSense.fold(session, nether, 0.002);
		assertEquals(0, there.hiddenVeins());
		assertEquals(0.0, there.expectedVeins(), "the nether was charged natural diamond veins");
	}

	@Test
	@DisplayName("decoys are judged against their own rate, not drowned in real veins")
	void decoysAreTestedSeparately() {
		// A dozen ordinary veins where a dozen were expected, and three decoys where a tenth of
		// one was. Pooled, that is fifteen against twelve — nothing. Separately, the decoys are
		// close to impossible.
		OreSense.Session session = new OreSense.Session(0, 0, "minecraft:overworld", 6000,
				12.0, 0.1, 12, 3, null, 0);
		assertTrue(session.confidence() >= 65, "three decoys against 0.1 expected scored only "
				+ session.confidence());

		OreSense.Session ordinary = new OreSense.Session(0, 0, "minecraft:overworld", 6000,
				12.0, 1.0, 12, 1, null, 0);
		assertTrue(ordinary.confidence() < 55, "an ordinary session scored " + ordinary.confidence());
	}

	@Test
	@DisplayName("depth bands follow vanilla's diamond density")
	void bands() {
		assertEquals(0, OreSense.band(16));
		assertEquals(0, OreSense.band(1));
		assertEquals(1, OreSense.band(0));
		assertEquals(1, OreSense.band(-32));
		assertEquals(2, OreSense.band(-33));
		assertEquals(2, OreSense.band(-60));
		assertTrue(OreSense.bandShare(0) < OreSense.bandShare(1)
				&& OreSense.bandShare(1) < OreSense.bandShare(2));
	}

	@Test
	@DisplayName("decoys in deepslate are one to nine blocks; in stone, one or two")
	void veinSizes() {
		Random random = new Random(3);
		int total = 10_000;
		int deepMax = 0, deepBig = 0;
		for (int i = 0; i < total; i++) {
			int deep = Canaries.veinSize(random, true);
			assertTrue(deep >= 1 && deep <= 9, "deepslate vein size " + deep);
			deepMax = Math.max(deepMax, deep);
			if (deep >= 3) deepBig++;
			int stone = Canaries.veinSize(random, false);
			assertTrue(stone >= 1 && stone <= 2, "stone vein size " + stone);
		}
		assertEquals(9, deepMax, "no deepslate vein ever reached nine blocks");
		assertTrue(deepBig > total / 2, "deepslate veins were mostly one or two blocks");
	}
}
