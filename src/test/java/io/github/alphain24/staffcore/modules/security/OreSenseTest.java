package io.github.alphain24.staffcore.modules.security;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
	@DisplayName("a vein showing on a cave wall is not hidden, from the front or from behind")
	void aVeinOnACaveWallIsNotHidden() {
		// The report: mining the ore in a cave was scored as x-ray. The wall block faced the cave,
		// but the block of the same vein behind it looked sealed on its own.
		BlockPos wall = new BlockPos(0, 0, 0);
		BlockPos behind = wall.east();
		Grid grid = new Grid().set(wall.west(), OreSense.Cell.OPEN)   // the cave
				.set(wall, OreSense.Cell.DIAMOND).set(behind, OreSense.Cell.DIAMOND);

		// Mining the visible block: what is behind it is the same vein, already in view.
		grid.set(wall, OreSense.Cell.OPEN);
		assertEquals(0, OreSense.uncover(grid, wall, new HashSet<>(), true, true).hiddenVeins(),
				"breaking a visible diamond made the rest of its vein a hidden find");

		// Reaching the block behind from a tunnel beside it, the wall block still showing.
		Grid side = new Grid().set(wall.west(), OreSense.Cell.OPEN)
				.set(wall, OreSense.Cell.DIAMOND).set(behind, OreSense.Cell.DIAMOND)
				.set(behind.north(), OreSense.Cell.OPEN);
		assertEquals(0, OreSense.uncover(side, behind.north(), new HashSet<>(), true).hiddenVeins(),
				"a vein with a block open to a cave counted as hidden when reached from the side");

		// And the same vein with no cave is still a find.
		Grid sealed = new Grid().set(wall, OreSense.Cell.DIAMOND).set(behind, OreSense.Cell.DIAMOND)
				.set(behind.north(), OreSense.Cell.OPEN);
		assertEquals(1, OreSense.uncover(sealed, behind.north(), new HashSet<>(), true).hiddenVeins());
	}

	@Test
	@DisplayName("a tunnel is read as legs: a turn ends one, walking away ends one, vein mining does not")
	void tunnelsAreReadAsLegs() {
		DigPath path = new DigPath();
		List<DigPath.Leg> legs = new ArrayList<>();
		// Ten blocks east, two high.
		for (int x = 0; x < 10; x++) {
			add(legs, path.rock(new BlockPos(x, 0, 0)));
			path.opened(3, 0);
			add(legs, path.rock(new BlockPos(x, 1, 0)));
			path.opened(3, 0);
		}
		// Then north for eight.
		for (int z = -1; z > -9; z--) {
			add(legs, path.rock(new BlockPos(9, 0, z)));
			path.opened(3, 0);
			add(legs, path.rock(new BlockPos(9, 1, z)));
			path.opened(3, 0);
		}
		assertEquals(1, legs.size(), "the turn north should have closed the eastward leg: " + legs);
		assertEquals(net.minecraft.core.Direction.EAST, legs.get(0).heading());
		assertTrue(legs.get(0).length() >= 6, "the eastward leg was " + legs.get(0).length() + " long");

		// Walking off and digging somewhere else closes the northward leg too.
		add(legs, path.rock(new BlockPos(40, 0, 40)));
		assertEquals(2, legs.size(), "walking away did not close the leg in progress");
		assertEquals(net.minecraft.core.Direction.NORTH, legs.get(1).heading());
		assertEquals(net.minecraft.core.Direction.EAST, legs.get(1).previous());

		// Breaking about in one spot is not a choice of direction.
		DigPath jitter = new DigPath();
		List<DigPath.Leg> none = new ArrayList<>();
		int[][] around = {{0, 0, 0}, {1, 0, 0}, {0, 1, 0}, {0, 0, 1}, {-1, 0, 0}, {0, -1, 0},
				{1, 1, 0}, {0, 0, -1}, {1, 0, 1}, {-1, 1, 0}};
		for (int[] at : around) add(none, jitter.rock(new BlockPos(at[0], at[1], at[2])));
		assertTrue(none.isEmpty(), "mining around one spot read as legs: " + none);
	}

	private static void add(List<DigPath.Leg> legs, DigPath.Leg leg) {
		if (leg != null) legs.add(leg);
	}

	@Test
	@DisplayName("the directions not taken set what a leg should uncover")
	void directionsNotTakenSetTheExpectation() {
		// Four corridors looked into 500 blocks of rock and found nothing: an honest leg that
		// opened 80 faces expects hardly anything.
		OreSense.LegScan empty = new OreSense.LegScan(16, 80, 0, 4, 0, 500);
		double quiet = OreSense.expectedFinds(empty, 0.002);
		assertTrue(quiet > 0 && quiet < 0.2, "a leg beside empty rock expected " + quiet);

		// Every corridor reached something within twenty blocks: finding one is ordinary.
		OreSense.LegScan rich = new OreSense.LegScan(16, 80, 1, 4, 4, 80);
		assertTrue(OreSense.expectedFinds(rich, 0.002) > 1.0,
				"a leg in rock full of ore was expected to find only " + OreSense.expectedFinds(rich, 0.002));

		// Twice the rock looked into expects twice as much, whatever the leg stopped for.
		OreSense.LegScan half = new OreSense.LegScan(8, 40, 0, 4, 1, 300);
		OreSense.LegScan whole = new OreSense.LegScan(16, 80, 0, 4, 1, 300);
		assertEquals(2 * OreSense.expectedFinds(half, 0.002), OreSense.expectedFinds(whole, 0.002), 1e-9);
	}

	@Test
	@DisplayName("a hidden-vein rate the tunnel does not back up needs far stronger odds")
	void veinsAloneNeedMore() {
		// Twenty veins where ten were expected: a rate estimated a little low, over a long honest
		// session. With no tunnel evidence it is not reported.
		OreSense.Session luck = new OreSense.Session(0, 0, "minecraft:overworld", 5000,
				10.0, 0, 20, 0, null, 0, 800, 6, 6.0);
		assertTrue(luck.confidence() < 55, "honest luck against a low estimate scored " + luck.confidence());

		// The same count, with the tunnels uncovering far more than the directions beside them.
		OreSense.Session aimed = new OreSense.Session(0, 0, "minecraft:overworld", 5000,
				10.0, 0, 30, 0, null, 0, 300, 20, 2.0);
		assertTrue(aimed.confidence() >= 65, "veins and aimed tunnels agreeing scored " + aimed.confidence());

		// And an impossible rate stands on its own, for a cheater who never turns.
		OreSense.Session blatant = new OreSense.Session(0, 0, "minecraft:overworld", 200,
				0.2, 0, 8, 0, null, 0, 0, 0, 0);
		assertTrue(blatant.confidence() >= 65, "eight veins against 0.2 expected scored " + blatant.confidence());
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
