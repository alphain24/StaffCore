package io.github.alphain24.staffcore.modules.security;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whole mining sessions, honest and x-ray, run through the real scoring code.
 *
 * <h2>What is simulated and what is real</h2>
 * The world is a grid of rock with diamond veins at the density vanilla places them at the
 * bottom of the world — the densest layer there is, where honest miners find the most — and no
 * caves, so every vein is sealed. Both choices make honest mining look as lucky as it can. Decoy
 * veins are placed and topped up around the miner the way {@link Canaries} does it. What a
 * break uncovers is decided by {@link OreSense#uncover}, the same code the break event calls,
 * and every break is scored by {@link OreSense#score} with the shipped defaults.
 * <p>
 * Two miners. The honest one branch-mines: a corridor with side tunnels every third block, and
 * mines out any ore a tunnel opens onto. The x-ray one walks straight to the nearest vein or
 * decoy it can see within thirty-two blocks, and mines it out.
 *
 * <h2>What this establishes and what it does not</h2>
 * That with the defaults, the arithmetic separates those two shapes: how often each crosses the
 * alert line, and how many finds the cheat has made by then. It does not establish how real
 * players mine — real honest players explore caves, which this detector ignores, and real
 * cheaters are sometimes careful. The numbers it prints are the ones quoted in decisions.md.
 */
class OreSenseSimulationTest {

	private static final int W = 192;
	private static final int H = 12;
	private static final int D = 192;
	private static final int LAYER = 5;

	/** Ore blocks per block of rock at the bottom of the world, worked out from vanilla's placement. */
	private static final double ORE_FRACTION = 2.8e-3;

	private static final int BREAKS = 3000;
	private static final int DECOY_VEINS = 12;
	private static final int RADIUS = 48;

	private static final byte ROCK = 0;
	private static final byte OPEN = 1;
	private static final byte ORE = 2;

	/** One simulated session in one freshly generated world. */
	private static final class Session {
		final byte[] cells = new byte[W * H * D];
		final Random random;
		final OreSense sense = new OreSense();
		final UUID player = UUID.randomUUID();
		final Set<Long> counted = new LinkedHashSet<>();
		final List<BlockPos> ore = new ArrayList<>();
		final Map<Long, Long> decoys = new HashMap<>();
		long nextVein;
		long clock = 1_000_000L;
		int breaks;
		OreSense.Loudness loudest;
		int findsAtAlert = -1;

		Session(long seed) {
			this(seed, ORE_FRACTION);
		}

		Session(long seed, double oreFraction) {
			random = new Random(seed);
			int veins = (int) (W * H * D * oreFraction / 4.3);
			for (int i = 0; i < veins; i++) {
				BlockPos seedPos = new BlockPos(random.nextInt(W), 1 + random.nextInt(H - 2),
						random.nextInt(D));
				grow(seedPos, Canaries.veinSize(random, true), pos -> {
					if (cell(pos) != ROCK) return false;
					set(pos, ORE);
					ore.add(pos);
					return true;
				});
			}
		}

		boolean inside(BlockPos pos) {
			return pos.getX() >= 0 && pos.getX() < W && pos.getY() >= 0 && pos.getY() < H
					&& pos.getZ() >= 0 && pos.getZ() < D;
		}

		byte cell(BlockPos pos) {
			return inside(pos) ? cells[(pos.getY() * D + pos.getZ()) * W + pos.getX()] : ROCK;
		}

		void set(BlockPos pos, byte value) {
			if (inside(pos)) cells[(pos.getY() * D + pos.getZ()) * W + pos.getX()] = value;
		}

		final OreSense.Terrain terrain = pos -> switch (cell(pos)) {
			case OPEN -> OreSense.Cell.OPEN;
			case ORE -> OreSense.Cell.DIAMOND;
			default -> OreSense.Cell.ROCK;
		};

		/** Ore-blob growth, as {@link Canaries#growVein} does it. */
		void grow(BlockPos seed, int size, java.util.function.Predicate<BlockPos> place) {
			if (!place.test(seed)) return;
			List<BlockPos> members = new ArrayList<>(List.of(seed));
			for (int attempt = 0; attempt < size * 4 && members.size() < size; attempt++) {
				BlockPos from = members.get(random.nextInt(members.size()));
				int dx = random.nextInt(3) - 1;
				int dy = random.nextInt(3) - 1;
				int dz = random.nextInt(3) - 1;
				int n = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
				if (n == 0 || n == 3) continue;
				BlockPos next = from.offset(dx, dy, dz);
				if (!members.contains(next) && place.test(next)) members.add(next);
			}
		}

		// ------------------------------------------------------------- decoys

		void topUpDecoys(BlockPos around) {
			Set<Long> veins = new HashSet<>(decoys.values());
			for (int attempt = 0; attempt < 20 && veins.size() < DECOY_VEINS; attempt++) {
				BlockPos seed = new BlockPos(around.getX() + random.nextInt(-RADIUS, RADIUS + 1),
						1 + random.nextInt(H - 2), around.getZ() + random.nextInt(-RADIUS, RADIUS + 1));
				if (!inside(seed) || tooClose(seed)) continue;
				long vein = ++nextVein;
				grow(seed, Canaries.veinSize(random, true), pos -> {
					if (!inside(pos) || cell(pos) != ROCK || decoys.containsKey(pos.asLong())) return false;
					for (Direction face : Direction.values()) {
						if (cell(pos.relative(face)) != ROCK) return false;
					}
					decoys.put(pos.asLong(), vein);
					return true;
				});
				veins.add(vein);
			}
		}

		boolean tooClose(BlockPos seed) {
			for (long key : decoys.keySet()) {
				BlockPos placed = BlockPos.of(key);
				if (Math.abs(placed.getX() - seed.getX()) <= 3 && Math.abs(placed.getY() - seed.getY()) <= 3
						&& Math.abs(placed.getZ() - seed.getZ()) <= 3) return true;
			}
			return false;
		}

		/** {@link Canaries#chancePerFace}, with the placement box clipped to the grid it is really in. */
		double decoyChance(BlockPos at) {
			if (decoys.isEmpty()) return 0;
			int x0 = Math.max(0, at.getX() - RADIUS), x1 = Math.min(W - 1, at.getX() + RADIUS);
			int z0 = Math.max(0, at.getZ() - RADIUS), z1 = Math.min(D - 1, at.getZ() + RADIUS);
			double rock = (x1 - x0 + 1.0) * (z1 - z0 + 1.0) * (H - 2) * 0.8;
			return Math.min(0.05, decoys.size() / rock);
		}

		/** {@link Canaries#onBreak} for the owner: every vein touched is uncovered and retired. */
		int uncoverDecoys(BlockPos pos) {
			Set<Long> veins = new HashSet<>();
			Long here = decoys.get(pos.asLong());
			if (here != null) veins.add(here);
			for (Direction face : Direction.values()) {
				Long vein = decoys.get(pos.relative(face).asLong());
				if (vein != null) veins.add(vein);
			}
			if (!veins.isEmpty()) decoys.values().removeIf(veins::contains);
			return veins.size();
		}

		// ------------------------------------------------------------ breaking

		void dig(BlockPos pos) {
			if (breaks >= BREAKS || !inside(pos) || cell(pos) == OPEN) return;
			breakOne(pos);
			// Anybody mines out ore they can see.
			ArrayDeque<BlockPos> visible = new ArrayDeque<>();
			for (Direction face : Direction.values()) visible.add(pos.relative(face));
			while (!visible.isEmpty() && breaks < BREAKS) {
				BlockPos next = visible.poll();
				if (cell(next) != ORE) continue;
				breakOne(next);
				for (Direction face : Direction.values()) visible.add(next.relative(face));
			}
		}

		void breakOne(BlockPos pos) {
			set(pos, OPEN);
			breaks++;
			clock += 500;
			if (breaks % 50 == 0) topUpDecoys(pos);

			int decoysFound = uncoverDecoys(pos);
			double chance = decoyChance(pos);
			OreSense.Uncovered found = OreSense.uncover(terrain, pos, counted, true);
			if (found.faces() == 0 && found.hiddenVeins() == 0 && decoysFound == 0) return;

			OreSense.Report report = sense.score(new OreSense.Observation(player, "sim",
					"minecraft:overworld", clock, -58, true, found.faces(), found.hiddenVeins(),
					decoysFound, chance, found.find()));
			if (report == null) return;
			if (loudest == null || report.loudness().ordinal() > loudest.ordinal()) {
				loudest = report.loudness();
			}
			if (report.loudness() == OreSense.Loudness.ALERT && findsAtAlert < 0) {
				findsAtAlert = report.session().finds();
			}
		}

		OreSense.Session score() {
			return sense.sessionFor(player);
		}
	}

	// ---------------------------------------------------------------- miners

	private static void branchMine(Session s) {
		int z = D / 2;
		s.topUpDecoys(new BlockPos(20, LAYER, z));
		for (int x = 20; x < W - 20 && s.breaks < BREAKS; x++) {
			s.dig(new BlockPos(x, LAYER, z));
			s.dig(new BlockPos(x, LAYER + 1, z));
			if (x % 3 != 0) continue;
			for (int side : new int[] {1, -1}) {
				for (int d = 1; d <= 40 && s.breaks < BREAKS; d++) {
					s.dig(new BlockPos(x, LAYER, z + side * d));
					s.dig(new BlockPos(x, LAYER + 1, z + side * d));
				}
			}
		}
	}

	private static void xrayMine(Session s) {
		BlockPos at = new BlockPos(W / 2, LAYER, D / 2);
		s.topUpDecoys(at);
		while (s.breaks < BREAKS) {
			BlockPos target = nearestTarget(s, at);
			if (target == null) {
				// Nothing in view: tunnel on, as anybody would.
				for (int i = 0; i < 8; i++) {
					at = at.east();
					s.dig(at);
					s.dig(at.above());
				}
				if (at.getX() >= W - 2) at = new BlockPos(2, LAYER, (at.getZ() + 20) % D);
				continue;
			}
			// Straight there, one axis at a time, stopping beside it.
			while (s.breaks < BREAKS && at.distManhattan(target) > 1) {
				at = stepToward(at, target);
				s.dig(at);
				if (at.getY() + 1 < H - 1) s.dig(at.above());
			}
			s.dig(target);
		}
	}

	private static BlockPos nearestTarget(Session s, BlockPos at) {
		BlockPos best = null;
		long bestDistance = 32L * 32L;
		for (BlockPos ore : s.ore) {
			if (s.cell(ore) != ORE || s.counted.contains(ore.asLong())) continue;
			long distance = (long) ore.distSqr(at);
			if (distance < bestDistance) {
				bestDistance = distance;
				best = ore;
			}
		}
		for (long key : s.decoys.keySet()) {
			BlockPos decoy = BlockPos.of(key);
			long distance = (long) decoy.distSqr(at);
			if (distance < bestDistance) {
				bestDistance = distance;
				best = decoy;
			}
		}
		return best;
	}

	private static BlockPos stepToward(BlockPos at, BlockPos target) {
		if (at.getX() != target.getX()) return at.offset(Integer.signum(target.getX() - at.getX()), 0, 0);
		if (at.getZ() != target.getZ()) return at.offset(0, 0, Integer.signum(target.getZ() - at.getZ()));
		return at.offset(0, Integer.signum(target.getY() - at.getY()), 0);
	}

	// ----------------------------------------------------------------- tests

	@Test
	@DisplayName("honest branch mining at the richest depth almost never crosses the alert line")
	void honestMiningStaysQuiet() {
		int sessions = 150;
		int alerts = 0;
		int notices = 0;
		long faces = 0;
		long veins = 0;
		long decoys = 0;
		double expectedDecoys = 0;
		int worst = 0;

		for (int i = 0; i < sessions; i++) {
			Session s = new Session(1000 + i);
			branchMine(s);
			OreSense.Session score = s.score();
			faces += score.faces();
			veins += score.hiddenVeins();
			decoys += score.decoyVeins();
			expectedDecoys += score.expectedDecoys();
			worst = Math.max(worst, score.confidence());
			if (s.loudest == OreSense.Loudness.ALERT) alerts++;
			else if (s.loudest == OreSense.Loudness.NOTICE) notices++;
		}

		double perThousand = 1000.0 * veins / faces;
		System.out.printf(java.util.Locale.ROOT,
				"[OreSenseSim] honest: %d sessions, %d alerts, %d notices, worst score %d, "
						+ "%.2f sealed veins per 1000 faces, %.1f decoys per session against "
						+ "%.1f expected, %d faces per session%n",
				sessions, alerts, notices, worst, perThousand, decoys / (double) sessions,
				expectedDecoys / sessions, faces / sessions);

		double configured = io.github.alphain24.staffcore.config.StaffConfig.get()
				.xrayNaturalVeinsPer1000Faces;
		assertTrue(perThousand < configured / 1.5, "simulated honest mining at the bottom of the "
				+ "world finds " + perThousand + " sealed veins per 1000 faces, within a margin "
				+ "of the configured " + configured + " — the "
				+ "default would flag the deepest honest miners");
		assertTrue(alerts <= sessions / 100, alerts + " of " + sessions
				+ " honest branch-mining sessions raised an x-ray alert");
		assertTrue(alerts + notices <= sessions / 20, (alerts + notices) + " of " + sessions
				+ " honest sessions reached the notice line");
	}

	@Test
	@DisplayName("a world with half again as many diamonds still does not flag honest miners")
	void aRicherWorldStaysQuietToo() {
		// The configured rate is an estimate. If vanilla's real density at the bottom is higher
		// than worked out, or a datapack adds a little, honest miners must not start tripping
		// the alert before the server's own calibration has caught up.
		int sessions = 60;
		int alerts = 0;
		int worst = 0;
		for (int i = 0; i < sessions; i++) {
			Session s = new Session(9000 + i, ORE_FRACTION * 1.5);
			branchMine(s);
			worst = Math.max(worst, s.score().confidence());
			if (s.loudest == OreSense.Loudness.ALERT) alerts++;
		}
		System.out.printf(java.util.Locale.ROOT,
				"[OreSenseSim] honest, 1.5x diamonds: %d of %d alerted, worst score %d%n",
				alerts, sessions, worst);
		assertTrue(alerts == 0, alerts + " of " + sessions + " honest sessions in a richer world "
				+ "raised an alert");
	}

	@Test
	@DisplayName("walking straight to visible ore is caught within a handful of finds")
	void xrayMiningIsCaught() {
		int sessions = 60;
		int caught = 0;
		List<Integer> findsAtAlert = new ArrayList<>();
		long veins = 0, decoys = 0, faces = 0;

		for (int i = 0; i < sessions; i++) {
			Session s = new Session(5000 + i);
			xrayMine(s);
			veins += s.score().hiddenVeins();
			decoys += s.score().decoyVeins();
			faces += s.score().faces();
			if (s.findsAtAlert >= 0) {
				caught++;
				findsAtAlert.add(s.findsAtAlert);
			}
		}
		findsAtAlert.sort(Integer::compare);
		int median = findsAtAlert.isEmpty() ? -1 : findsAtAlert.get(findsAtAlert.size() / 2);
		System.out.printf(java.util.Locale.ROOT,
				"[OreSenseSim] x-ray: %d of %d sessions alerted, median %d finds at the alert; "
						+ "%.2f sealed veins per 1000 faces, %.1f decoys per session%n",
				caught, sessions, median, 1000.0 * veins / faces, decoys / (double) sessions);

		assertTrue(caught >= sessions * 9 / 10, "only " + caught + " of " + sessions
				+ " x-ray sessions raised an alert");
		assertTrue(median <= 12, "x-ray sessions needed a median of " + median
				+ " finds before the alert");
	}
}
