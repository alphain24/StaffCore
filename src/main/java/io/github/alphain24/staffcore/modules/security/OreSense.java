package io.github.alphain24.staffcore.modules.security;

import io.github.alphain24.staffcore.compat.Mc;
import io.github.alphain24.staffcore.config.StaffConfig;
import io.github.alphain24.staffcore.module.Mods;
import io.github.alphain24.staffcore.modules.cases.CaseEvidence;
import io.github.alphain24.staffcore.modules.cases.Signal;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How often a player uncovers diamonds nobody could have seen, against how often digging blind
 * does.
 *
 * <h2>The one thing x-ray gives away</h2>
 * An x-ray client shows ore through rock. Everything else about a cheater's mining can look
 * normal; what cannot is the rate at which they break into veins that were sealed on every side
 * — no cave, no water, no air touching them — because they are digging towards ore instead of
 * digging and hoping. So every break is checked for what it uncovers: each rock block beside it
 * that was sealed until now is a newly opened face, and if one of those is a diamond ore, a
 * hidden vein has just been found.
 * <p>
 * Decoys are the same event with a known answer: a vein that exists only on one player's
 * screen. They are counted separately, against their own expected rate, because their expected
 * rate is known far better than the world's.
 *
 * <h2>Ore anybody could see does not count</h2>
 * A vein is hidden only if no block of it touches open space — a cave, water, a ravine, a tunnel
 * dug earlier. Mining diamonds on a cave wall is what everybody does, and it used to count: the
 * wall block was skipped because it faced the cave, but the block of the same vein behind it
 * looked sealed on its own and was scored as a hidden find. The whole vein is checked now, and a
 * diamond broken directly — which only a visible one can be — marks its vein as seen.
 *
 * <h2>The way they dig, not only what they find</h2>
 * Rate alone has an honest explanation that it cannot rule out: the configured diamond density
 * is an estimate, and a patch of rock richer than it makes an honest miner look lucky. So the
 * tunnel is read as well ({@link DigPath}). Each straight leg is a choice of direction, and when
 * a leg ends the directions they did not take are read over the same distance. What those would
 * have uncovered is the honest expectation for that spot, measured from the rock right there
 * rather than from a number in the config — a cheater's legs uncover far more than the
 * directions beside them, an honest miner's about as much. Counted per block dug, so an honest
 * player who stops and turns the moment they find something is not mistaken for aiming.
 * <p>
 * An alert needs the two to agree. A hidden-vein rate the tunnel does not back up has to be a
 * hundred times less likely before it is said on its own; decoy veins, which only an x-ray
 * client is ever shown, stand on their own as before.
 *
 * <h2>Why a score and not a rule</h2>
 * An honest miner finds hidden veins too; strip mining works because they are down there. The
 * question is never "did they find one" but "did they find more than blind digging finds in the
 * same amount of rock". Every opened face is a small chance of a vein, so the expected count is
 * faces times the chance per face — a Poisson rate — and the score is how unlikely the actual
 * count is under it, on the same scale as every other x-ray number here.
 * <p>
 * The chance per face starts from {@code xrayNaturalVeinsPer1000Faces}, scaled by depth the way
 * vanilla's diamond placement is, and is refined per depth band by what this server's players
 * actually find — excluding anybody already above the notice line. A datapack with twice the
 * diamonds pulls the estimate towards its own truth instead of flagging everybody in it.
 *
 * <h2>Where the work runs</h2>
 * Reading the world has to happen on the server thread, in the break event: the block states
 * are not safe to read anywhere else, and a moment later the player has broken the next block.
 * That part is a fixed handful of reads — the six neighbours and their neighbours, about
 * thirty-six — plus following a vein when one is actually touched. The scoring, the session
 * bookkeeping and the calibration are handed to StaffCore's background worker with the counts.
 * Only raising a case comes back to the server thread, because announcing one reads the player
 * list, exactly as {@link XraySweep}'s findings do.
 *
 * <h2>What it does with the score</h2>
 * The same as the sweep: below {@code xrayNoticeConfidence} nothing; from there to
 * {@code xrayAlertConfidence} a quiet line to staff; above it an x-ray signal with the session's
 * replay, dig and last find attached, which opens a cheating case at the case threshold. It
 * never punishes.
 */
public final class OreSense {

	/** Mining with a gap longer than this starts a fresh session. */
	public static final long SESSION_GAP_MS = 20 * 60_000L;

	/** Diamonds generate at and below this height; above it no natural vein is counted. */
	public static final int BAND_TOP_Y = 16;

	/**
	 * How much server-wide mining the configured estimate is worth, per depth band. Until the
	 * server has opened this many faces in a band, the configured rate dominates there.
	 */
	static final long PRIOR_FACES = 50_000;

	/** How far a vein is followed when marking it counted. Bigger than any vanilla vein. */
	private static final int VEIN_LIMIT = 24;

	/** Ore positions remembered per player, so one vein counts once. */
	private static final int COUNTED_LIMIT = 2048;

	/**
	 * How much more unlikely a hidden-vein rate has to be before it is reported without the
	 * tunnel agreeing. Two decades: the rate is judged against an estimate, and an estimate wrong
	 * by a factor of two over a long session is enough to make honest luck look like one in ten
	 * thousand.
	 */
	static final double UNCORROBORATED = 100.0;

	/** How far each direction not taken is read, at most. */
	static final int SCAN_LENGTH = 16;

	/**
	 * How many blocks' worth of the configured rate the reading of the directions not taken
	 * starts from. A few corridors are a small sample, and one of them happening to be empty
	 * should not make every find in the leg look impossible.
	 */
	static final double PRIOR_BLOCKS = 200.0;

	/** Blocks read to judge directions not taken, since the server started. */
	private static final java.util.concurrent.atomic.AtomicLong PATH_READS =
			new java.util.concurrent.atomic.AtomicLong();

	// ---------------------------------------------------------------- the terrain

	/** What a position is, as far as uncovering goes. */
	public enum Cell { OPEN, ROCK, DIAMOND }

	/** The world, reduced to what this class needs from it — so the arithmetic can be tested headlessly. */
	@FunctionalInterface
	public interface Terrain {
		Cell at(BlockPos pos);
	}

	/** What one break uncovered. */
	public record Uncovered(int faces, int hiddenVeins, BlockPos find) {
		static final Uncovered NOTHING = new Uncovered(0, 0, null);
	}

	public static Uncovered uncover(Terrain terrain, BlockPos broken, Set<Long> counted,
			boolean countVeins) {
		return uncover(terrain, broken, counted, countVeins, false);
	}

	/**
	 * The faces a break opened and the sealed veins among them.
	 * <p>
	 * A face counts when the block beside the break is solid and every one of its other faces
	 * was solid too — rock nobody could see until now. A face onto rock that was already open to
	 * a cave was never hidden, and counting it would dilute exactly the players who mine in
	 * caves.
	 * <p>
	 * A diamond is a hidden find only when its whole vein was sealed: no block of it touching open
	 * space anywhere but the face just opened. A vein showing on a cave wall is not hidden however
	 * sealed the block behind the wall is.
	 *
	 * @param counted      ore already accounted for by this player; every vein touched is added,
	 *                     hidden or not, so a vein first seen from a cave is not "found" later
	 *                     from behind
	 * @param brokeDiamond the broken block was a diamond itself. Nobody breaks a block they cannot
	 *                     see, so its vein was already in view and the rest of it is not a find.
	 */
	public static Uncovered uncover(Terrain terrain, BlockPos broken, Set<Long> counted,
			boolean countVeins, boolean brokeDiamond) {

		int faces = 0;
		int hidden = 0;
		BlockPos find = null;
		for (Direction direction : Direction.values()) {
			BlockPos next = broken.relative(direction);
			Cell cell = terrain.at(next);
			if (cell == Cell.OPEN) continue;

			boolean sealed = sealedApartFrom(terrain, next, broken);
			if (sealed) faces++;

			if (!countVeins || cell != Cell.DIAMOND || counted.contains(next.asLong())) continue;
			boolean exposed = markVein(terrain, next, counted, broken);
			if (sealed && !exposed && !brokeDiamond) {
				hidden++;
				find = next.immutable();
			}
		}
		return faces == 0 && hidden == 0 ? Uncovered.NOTHING : new Uncovered(faces, hidden, find);
	}

	/** Whether every face of this block other than the one just opened is solid. */
	static boolean sealedApartFrom(Terrain terrain, BlockPos block, BlockPos opened) {
		for (Direction direction : Direction.values()) {
			BlockPos next = block.relative(direction);
			if (next.equals(opened)) continue;
			if (terrain.at(next) == Cell.OPEN) return false;
		}
		return true;
	}

	/**
	 * Marks every diamond connected to this one — by faces and edges, as ore blobs generate — and
	 * says whether any of them touches open space other than {@code opened}.
	 *
	 * @return true when some block of the vein could already be seen
	 */
	static boolean markVein(Terrain terrain, BlockPos start, Set<Long> counted, BlockPos opened) {
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		queue.add(start.immutable());
		int visited = 0;
		boolean exposed = false;
		while (!queue.isEmpty() && visited < VEIN_LIMIT) {
			BlockPos at = queue.poll();
			if (!counted.add(at.asLong())) continue;
			visited++;
			if (!exposed) {
				for (Direction face : Direction.values()) {
					BlockPos beside = at.relative(face);
					if (!beside.equals(opened) && terrain.at(beside) == Cell.OPEN) {
						exposed = true;
						break;
					}
				}
			}
			for (int dx = -1; dx <= 1; dx++) {
				for (int dy = -1; dy <= 1; dy++) {
					for (int dz = -1; dz <= 1; dz++) {
						if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) == 0) continue;
						BlockPos next = at.offset(dx, dy, dz);
						if (!counted.contains(next.asLong()) && terrain.at(next) == Cell.DIAMOND) {
							queue.add(next);
						}
					}
				}
			}
		}
		return exposed;
	}

	/**
	 * What a straight tunnel in a direction not taken would have looked into before reaching
	 * something: the rock blocks it would have uncovered, and whether it reached a hidden vein or
	 * a decoy.
	 *
	 * @param rock blocks of solid, unseen rock examined, up to and including the first hit
	 */
	public record Corridor(int rock, boolean hit) {}

	/**
	 * Reads one direction not taken: a straight one-by-two tunnel from {@code start}, up to
	 * {@code length} blocks. Open space — a cave, their own tunnels — is not rock anybody would
	 * be looking into and is not counted. A diamond open to air on any side is not a hit: turning
	 * towards ore you can see is not aiming.
	 */
	public static Corridor corridor(Terrain terrain, java.util.function.Predicate<BlockPos> decoy,
			BlockPos start, Direction heading, int length, Set<Long> counted) {

		boolean vertical = heading.getAxis() == Direction.Axis.Y;
		Set<Long> looked = new java.util.HashSet<>();
		int rock = 0;
		for (int step = 1; step <= length; step++) {
			BlockPos base = start.relative(heading, step);
			List<BlockPos> cells = new ArrayList<>(8);
			cells.add(base);
			if (vertical) {
				for (Direction side : Direction.Plane.HORIZONTAL) cells.add(base.relative(side));
			} else {
				Direction left = heading.getClockWise();
				Direction right = heading.getCounterClockWise();
				BlockPos upper = base.above();
				cells.add(upper);
				cells.add(base.relative(left));
				cells.add(base.relative(right));
				cells.add(upper.relative(left));
				cells.add(upper.relative(right));
				cells.add(base.below());
				cells.add(upper.above());
			}
			for (BlockPos cell : cells) {
				if (!looked.add(cell.asLong())) continue;
				if (decoy.test(cell)) return new Corridor(rock + 1, true);
				Cell what = terrain.at(cell);
				if (what == Cell.OPEN) continue;
				rock++;
				if (what != Cell.DIAMOND || counted.contains(cell.asLong())) continue;
				if (sealedApartFrom(terrain, cell, cell)) return new Corridor(rock, true);
			}
		}
		return new Corridor(rock, false);
	}

	/**
	 * What a leg uncovered, beside what the directions not taken from it would have.
	 *
	 * @param length    blocks the leg advanced, for saying so
	 * @param faces     sealed rock faces the leg opened
	 * @param finds     hidden veins and decoys it uncovered
	 * @param corridors directions not taken that were read
	 * @param hits      how many of those reached something
	 * @param rock      rock blocks those corridors looked into before reaching it, or in all
	 */
	public record LegScan(int length, int faces, int finds, int corridors, int hits, int rock) {}

	/** Reads the directions not taken from one leg. Pure, given a terrain. */
	public static LegScan scanLeg(Terrain terrain, java.util.function.Predicate<BlockPos> decoy,
			DigPath.Leg leg, Set<Long> counted) {

		int scanned = Math.min(leg.length(), SCAN_LENGTH);
		int corridors = 0;
		int hits = 0;
		int rock = 0;
		for (Direction alternative : Direction.values()) {
			if (alternative == leg.heading() || alternative == leg.heading().getOpposite()) continue;
			if (leg.previous() != null && alternative == leg.previous().getOpposite()) continue;
			Corridor read = corridor(terrain, decoy, leg.start(), alternative, scanned, counted);
			if (read.rock() == 0) continue;
			corridors++;
			rock += read.rock();
			if (read.hit()) hits++;
		}
		return new LegScan(leg.length(), leg.faces(), leg.finds(), corridors, hits, rock);
	}

	/**
	 * The finds an honest miner would expect from the rock a leg looked into, going by what the
	 * directions beside it hold.
	 * <p>
	 * Each corridor is read until it reaches something, so hits over rock looked into is the
	 * chance per block of rock right there — pulled a little towards the configured rate, since a
	 * few corridors are not many — and the leg's own opened faces times that is the expectation.
	 * Per block of rock rather than per leg or per step, for two reasons that each once skewed it:
	 * a leg that ends the moment something is found is exactly as long as it took to find it, and
	 * a corridor running through somebody's own earlier tunnels looks into less rock than one
	 * that does not.
	 */
	static double expectedFinds(LegScan scan, double chancePerFace) {
		if (scan == null || scan.corridors() <= 0) return 0;
		double perBlock = (scan.hits() + PRIOR_BLOCKS * Math.max(0, chancePerFace))
				/ (scan.rock() + PRIOR_BLOCKS);
		return perBlock * scan.faces();
	}

	static boolean isDiamond(BlockState state) {
		return state.is(Blocks.DIAMOND_ORE) || state.is(Blocks.DEEPSLATE_DIAMOND_ORE);
	}

	private static Terrain terrainOf(ServerLevel level) {
		return pos -> {
			BlockState state = level.getBlockState(pos);
			if (isDiamond(state)) return Cell.DIAMOND;
			return state.canOcclude() ? Cell.ROCK : Cell.OPEN;
		};
	}

	// ------------------------------------------------------------ the arithmetic

	/**
	 * One player's mining since their last long break. Immutable; each break makes a new one.
	 *
	 * @param expectedVeins hidden veins blind digging would have found in the same faces
	 * @param expectedDecoys decoy veins blind digging would have uncovered in the same faces
	 * @param reported the confidence last said out loud, so the next line waits for worse
	 */
	/**
	 * @param pathBlocks        blocks advanced along straight legs of tunnel
	 * @param pathFinds         hidden veins and decoys uncovered on those legs
	 * @param expectedPathFinds what the directions not taken say those legs should have uncovered
	 */
	public record Session(long startedAt, long lastAt, String world, long faces,
			double expectedVeins, double expectedDecoys, int hiddenVeins, int decoyVeins,
			BlockPos lastFind, int reported, long pathBlocks, int pathFinds,
			double expectedPathFinds) {

		public Session(long startedAt, long lastAt, String world, long faces,
				double expectedVeins, double expectedDecoys, int hiddenVeins, int decoyVeins,
				BlockPos lastFind, int reported) {
			this(startedAt, lastAt, world, faces, expectedVeins, expectedDecoys, hiddenVeins,
					decoyVeins, lastFind, reported, 0, 0, 0);
		}

		public static Session start(long now, String world) {
			return new Session(now, now, world, 0, 0, 0, 0, 0, null, 0);
		}

		public int finds() {
			return hiddenVeins + decoyVeins;
		}

		/** How unlikely the hidden-vein count is against the configured rate. */
		public double veinPValue() {
			return hiddenVeins == 0 ? 1.0 : poissonTail(hiddenVeins, expectedVeins);
		}

		/** How unlikely the decoy count is. */
		public double decoyPValue() {
			return decoyVeins == 0 ? 1.0 : poissonTail(decoyVeins, expectedDecoys);
		}

		/** How unlikely what their tunnels uncovered is, against the directions they did not dig. */
		public double pathPValue() {
			return pathFinds == 0 ? 1.0 : poissonTail(pathFinds, expectedPathFinds);
		}

		/**
		 * How unlikely this session is from digging blind.
		 * <p>
		 * Three ways to get there, and the strongest counts, doubled because more than one test
		 * was run:
		 * <ul>
		 *   <li><b>decoys</b>, on their own — only an x-ray client is ever shown one;</li>
		 *   <li><b>hidden veins and the tunnel agreeing</b> — the larger of the two chances, so it
		 *       is only small when both are;</li>
		 *   <li><b>hidden veins alone</b>, made a hundred times harder — for a cheater who hardly
		 *       turns, and never for honest luck against a rate that was estimated a little low.</li>
		 * </ul>
		 * Pooling decoys into the vein count would drown three decoys — close to impossible by
		 * chance — in a dozen ordinary veins, so they stay apart.
		 */
		public double pValue() {
			double veins = veinPValue();
			double agreed = Math.max(veins, pathPValue());
			double alone = Math.min(1.0, veins * UNCORROBORATED);
			return Math.min(1.0, 2 * Math.min(decoyPValue(), Math.min(agreed, alone)));
		}

		/** Whether the way they dig, on its own, is past the notice line. For the sweep. */
		public boolean movementLooksAimed(int noticeConfidence) {
			return Hypergeometric.confidence(Math.min(1.0, 2 * pathPValue())) >= noticeConfidence;
		}

		public int confidence() {
			return finds() == 0 ? 0 : Hypergeometric.confidence(pValue());
		}

		public double expected() {
			return expectedVeins + expectedDecoys;
		}

		Session reportedAt(int confidence) {
			return new Session(startedAt, lastAt, world, faces, expectedVeins, expectedDecoys,
					hiddenVeins, decoyVeins, lastFind, confidence, pathBlocks, pathFinds,
					expectedPathFinds);
		}
	}

	/**
	 * One break, reduced to numbers on the server thread and scored elsewhere.
	 *
	 * @param leg the leg of tunnel this break ended, read against the directions not taken, or null
	 */
	public record Observation(UUID player, String name, String world, long at, int y,
			boolean natural, int faces, int hiddenVeins, int decoyVeins, double decoyChance,
			BlockPos find, LegScan leg) {

		public Observation(UUID player, String name, String world, long at, int y,
				boolean natural, int faces, int hiddenVeins, int decoyVeins, double decoyChance,
				BlockPos find) {
			this(player, name, world, at, y, natural, faces, hiddenVeins, decoyVeins, decoyChance,
					find, null);
		}
	}

	/** Adds one break to a session. Pure. */
	public static Session fold(Session session, Observation observation, double veinRatePerFace) {
		Session s = session;
		if (s == null || observation.at() - s.lastAt() > SESSION_GAP_MS
				|| !observation.world().equals(s.world())) {
			s = Session.start(observation.at(), observation.world());
		}
		double veins = observation.natural() ? observation.faces() * veinRatePerFace : 0;
		double decoys = observation.faces() * Math.max(0, observation.decoyChance());

		LegScan leg = observation.leg();
		double chance = (observation.natural() ? veinRatePerFace : 0)
				+ Math.max(0, observation.decoyChance());
		long pathBlocks = s.pathBlocks();
		int pathFinds = s.pathFinds();
		double expectedPath = s.expectedPathFinds();
		if (leg != null && leg.corridors() > 0) {
			pathBlocks += leg.length();
			pathFinds += leg.finds();
			expectedPath += expectedFinds(leg, chance);
		}

		return new Session(s.startedAt(), observation.at(), s.world(),
				s.faces() + observation.faces(), s.expectedVeins() + veins,
				s.expectedDecoys() + decoys, s.hiddenVeins() + observation.hiddenVeins(),
				s.decoyVeins() + observation.decoyVeins(),
				observation.find() != null ? observation.find() : s.lastFind(), s.reported(),
				pathBlocks, pathFinds, expectedPath);
	}

	/**
	 * Which depth band a height is in: 0 above y 0, 1 down to y -32, 2 below that.
	 * <p>
	 * Three because vanilla's diamond density changes that much across them — at the bottom of
	 * the world it is about seven times what it is at y 10 — and one rate for all of it would
	 * either flag the deep miners or miss everybody else.
	 */
	public static int band(int y) {
		if (y > 0) return 0;
		return y > -33 ? 1 : 2;
	}

	/**
	 * The share of the configured rate each band starts at.
	 * <p>
	 * From vanilla's placement: the small, buried and large diamond features spread on a
	 * triangle peaking at the bottom of the world, the medium one evenly below y -4. Worked
	 * through, a band's densest height has about a fifth, two thirds and all of the ore the
	 * bottom does.
	 */
	static double bandShare(int band) {
		return switch (band) {
			case 0 -> 0.2;
			case 1 -> 0.7;
			default -> 1.0;
		};
	}

	/**
	 * P(X ≥ k) for X ~ Poisson(λ): how likely at least this many finds are from digging blind.
	 * <p>
	 * Summed term by term in log space, so a large λ does not underflow e^-λ to zero and report
	 * certainty where there is none; and from whichever side is smaller, so a tail of one in a
	 * billion is not lost as 1 minus nearly 1.
	 */
	public static double poissonTail(int k, double lambda) {
		if (k <= 0) return 1.0;
		if (lambda <= 0) return 0.0;

		double logLambda = Math.log(lambda);
		if (k > lambda) {
			// Upper tail directly: terms shrink quickly past the mean.
			double sum = 0;
			double logTerm = -lambda + k * logLambda - logFactorial(k);
			for (int i = k; i < k + 1000; i++) {
				double term = Math.exp(logTerm);
				sum += term;
				if (term < sum * 1e-15) break;
				logTerm += logLambda - Math.log(i + 1);
			}
			return Math.min(1.0, sum);
		}

		double below = 0;
		double logTerm = -lambda;
		for (int i = 0; i < k; i++) {
			if (i > 0) logTerm += logLambda - Math.log(i);
			below += Math.exp(logTerm);
		}
		return Math.max(0.0, Math.min(1.0, 1.0 - below));
	}

	private static double logFactorial(int n) {
		double sum = 0;
		for (int i = 2; i <= n; i++) sum += Math.log(i);
		return sum;
	}

	// ------------------------------------------------------ server-thread half

	/** Ore already counted, per player. Server thread only. */
	private final Map<UUID, Set<Long>> counted = new ConcurrentHashMap<>();
	private final Map<UUID, Long> lastBreak = new ConcurrentHashMap<>();
	/** The tunnel each player is digging. Server thread only. */
	private final Map<UUID, DigPath> paths = new ConcurrentHashMap<>();

	/** Blocks read to judge directions not taken since the server started, for the diagnostic. */
	public static long pathBlocksRead() {
		return PATH_READS.get();
	}

	/**
	 * One break by a player, reduced to numbers. Server thread, called from the break event
	 * after {@link Canaries#onBreak}.
	 *
	 * @return null when the break cannot matter: above the diamond band with no decoys out, in
	 *         a world with nothing to count, or by staff on duty when they are skipped
	 */
	public Observation observe(ServerLevel level, ServerPlayer player, BlockPos pos,
			Canaries.Contact contact) {
		return observe(level, player, pos, contact, null);
	}

	/**
	 * @param broken what was broken, or null when not known. A diamond marks its own vein as seen;
	 *               any ore is left out of the tunnel's shape, because mining around a vein
	 *               jumps in every direction and is not a choice of where to dig.
	 */
	public Observation observe(ServerLevel level, ServerPlayer player, BlockPos pos,
			Canaries.Contact contact, BlockState broken) {

		if (level == null || player == null || pos == null) return null;
		StaffConfig cfg = StaffConfig.get();
		// The same rule as the sweep: staff clocked on are not scored, staff mining on their
		// own time are. Said to them when it matters, because staff testing the decoys in
		// staff mode and hearing nothing is exactly how a working detector looks broken.
		if (cfg.xraySkipStaffOnDuty && Mods.staffMode().isActive(player)) {
			if (contact != null && contact.uncovered() > 0) {
				player.sendSystemMessage(io.github.alphain24.staffcore.gui.Theme.info(
						"You uncovered a decoy vein. You are in staff mode, so your mining is not "
								+ "scored and nobody is alerted (xraySkipStaffOnDuty). Leave staff "
								+ "mode to test it."));
			}
			return null;
		}
		// Creative is scored. It was skipped once, on the grounds that nobody x-rays for what
		// the creative menu hands out — and the result was that every operator testing the
		// decoys, which is nearly always done in creative, saw nothing at all. A builder
		// clearing rock in creative finds ore and decoys at the rate anybody digging does, so
		// counting them costs nothing in false alarms.
		if (player.isSpectator()) return null;

		boolean natural = level.dimension() == Level.OVERWORLD && pos.getY() <= BAND_TOP_Y;
		double decoyChance = Canaries.chancePerFace(level, player);
		int decoys = contact == null ? 0 : contact.uncovered();
		if (!natural && decoyChance <= 0 && decoys == 0) return null;

		long now = System.currentTimeMillis();
		UUID id = player.getUUID();
		Long previous = lastBreak.put(id, now);
		if (previous == null || now - previous > SESSION_GAP_MS) {
			counted.remove(id);
			paths.remove(id);
		}
		if (lastBreak.size() > 512) {
			lastBreak.entrySet().removeIf(e -> now - e.getValue() > SESSION_GAP_MS);
			counted.keySet().removeIf(k -> !lastBreak.containsKey(k));
			paths.keySet().removeIf(k -> !lastBreak.containsKey(k));
		}

		boolean brokeOre = broken != null && isOre(broken);
		Terrain terrain = terrainOf(level);
		Set<Long> seen = counted.computeIfAbsent(id, k -> new LinkedHashSet<>());
		Uncovered found = uncover(terrain, pos, seen, natural, broken != null && isDiamond(broken));

		// The tunnel, and when a leg of it ends, what the directions not taken hold. Read here,
		// on the server thread, because only here can the world be read: at most four corridors
		// of sixteen blocks, eight blocks a step, once per leg rather than once per break.
		LegScan leg = null;
		if (!brokeOre) {
			DigPath path = paths.computeIfAbsent(id, k -> new DigPath());
			DigPath.Leg ended = path.rock(pos);
			path.opened(found.faces(), found.hiddenVeins() + decoys);
			if (ended != null) {
				Terrain counting = at -> {
					PATH_READS.incrementAndGet();
					return terrain.at(at);
				};
				leg = scanLeg(counting, at -> Canaries.isDecoyFor(id, at), ended, seen);
			}
		}
		trim(seen);

		if (found.faces() == 0 && found.hiddenVeins() == 0 && decoys == 0 && leg == null) return null;
		BlockPos find = decoys > 0 ? pos.immutable() : found.find();
		return new Observation(id, Mc.name(player), Mc.dimensionId(level), now, pos.getY(),
				natural, found.faces(), found.hiddenVeins(), decoys, decoyChance, find, leg);
	}

	/** Any ore, by id — mining it out is following something already seen. */
	static boolean isOre(BlockState state) {
		String id = Mc.blockId(state.getBlock());
		return id.endsWith("_ore") || id.endsWith("ancient_debris");
	}

	/** Oldest first: a long session in a huge diamond field must not grow without end. */
	private static void trim(Set<Long> seen) {
		var it = seen.iterator();
		while (seen.size() > COUNTED_LIMIT && it.hasNext()) {
			it.next();
			it.remove();
		}
	}

	/**
	 * The break hook's whole involvement: observe here, score on the worker, say something back
	 * here if there is something to say.
	 */
	public void onBreak(ServerLevel level, ServerPlayer player, BlockPos pos,
			Canaries.Contact contact) {
		onBreak(level, player, pos, contact, null);
	}

	/** As above, knowing what was broken. */
	public void onBreak(ServerLevel level, ServerPlayer player, BlockPos pos,
			Canaries.Contact contact, BlockState broken) {

		Observation observation = observe(level, player, pos, contact, broken);
		if (observation == null) return;
		MinecraftServer server = level.getServer();
		BlockPos at = pos.immutable();
		// The session is taken on the worker, straight after this break was folded in. Read
		// later on the server thread it would be whatever the next breaks had made it, and a
		// burst of three decoys would report "3 this session" three times.
		Mods.grief().readOffThread(server,
				() -> new Scored(score(observation), sessionFor(observation.player())), null, scored -> {
			if (scored == null) return;
			if (scored.report() != null) {
				announce(server, scored.report());
			} else if (observation.decoyVeins() > 0 && StaffConfig.get().canaryNotifyEachFind) {
				announceDecoy(server, observation, at, scored.session());
			}
		});
	}

	// ------------------------------------------------------------ worker half

	/** One scored break: what to say, if anything, and the session as that break left it. */
	private record Scored(Report report, Session session) {}

	/** What a session has earned being said about it. */
	public enum Loudness { NOTICE, ALERT }

	public record Report(Loudness loudness, UUID player, String name, Session session, int confidence) {}

	private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
	private final long[] bandFaces = new long[3];
	private final long[] bandVeins = new long[3];

	/**
	 * Folds one observation into its session and decides whether to say anything.
	 * <p>
	 * Synchronized rather than confined, so a test can call it directly without racing the
	 * worker. Nothing in here waits on anything.
	 */
	public synchronized Report score(Observation observation) {
		StaffConfig cfg = StaffConfig.get();
		int band = band(observation.y());
		Session session = fold(sessions.get(observation.player()), observation,
				naturalRatePerFace(band, cfg));

		// A notice line of 0 switches the quiet line off, not the score.
		int quietest = cfg.xrayNoticeConfidence > 0
				? Math.min(cfg.xrayNoticeConfidence, cfg.xrayAlertConfidence)
				: cfg.xrayAlertConfidence;

		// Only mining nobody is suspicious of teaches the estimate what honest mining finds.
		if (observation.natural() && session.confidence() < quietest) {
			bandFaces[band] += observation.faces();
			bandVeins[band] += observation.hiddenVeins();
		}

		Report report = null;
		int confidence = session.confidence();
		if (session.finds() >= Math.max(1, cfg.xrayMinimumFinds)
				&& confidence >= quietest
				&& confidence >= session.reported() + 5) {
			report = new Report(confidence >= cfg.xrayAlertConfidence ? Loudness.ALERT : Loudness.NOTICE,
					observation.player(), observation.name(), session, confidence);
			session = session.reportedAt(confidence);
		}
		sessions.put(observation.player(), session);
		return report;
	}

	/**
	 * The chance one opened face in this band is onto a sealed diamond vein, as this server's
	 * own honest mining says — pulled towards the configured estimate until there is enough of
	 * it, and never more than a factor of three away from it either way.
	 * <p>
	 * The bound is what stops calibration being a way in. A few x-ray users mining below the
	 * notice line early in their sessions would otherwise raise the rate everybody is judged
	 * against, a little at a time, for as long as the server stays up.
	 */
	synchronized double naturalRatePerFace(int band, StaffConfig cfg) {
		double prior = Math.max(0.01, cfg.xrayNaturalVeinsPer1000Faces) / 1000.0 * bandShare(band);
		double learned = (bandVeins[band] + prior * PRIOR_FACES)
				/ (double) (bandFaces[band] + PRIOR_FACES);
		return Math.max(prior / 3, Math.min(prior * 3, learned));
	}

	/**
	 * A quiet line for one decoy vein uncovered, before the score has anything to say.
	 * <p>
	 * A decoy is shown only to one client, so every one uncovered is worth a line — but not a
	 * verdict: an honest tunnel meets one now and then, and the line says the score so staff
	 * can see how far from a case it is. When a break also moves the score over a line, that
	 * announcement is made instead of this one.
	 */
	void announceDecoy(MinecraftServer server, Observation observation, BlockPos at, Session session) {
		int found = session == null ? observation.decoyVeins() : session.decoyVeins();
		int score = session == null ? 0 : session.confidence();
		Mods.alerts().onStaffAction(server, observation.name() + " uncovered a decoy vein at "
				+ at.toShortString() + " (" + found + " this session, x-ray score " + score
				+ " of 99). Decoys show only on an x-ray client; one on its own can be an "
				+ "honest tunnel, and a case opens when the score says it is not.");
	}

	/** Says it. Server thread. */
	public void announce(MinecraftServer server, Report report) {
		Session session = report.session();
		String detail = describe(session);

		if (report.loudness() == Loudness.NOTICE) {
			Mods.alerts().onStaffAction(server, report.name() + " — " + detail
					+ ", below the alert line. Not a verdict; recorded so you can see it happening.");
			return;
		}

		long now = System.currentTimeMillis();
		long from = session.startedAt() - 60_000L;
		List<CaseEvidence.Draft> evidence = new ArrayList<>();
		evidence.add(CaseEvidence.Draft.replay(report.player(), report.name(), session.world(),
				session.lastFind(), from, now + 30_000L, "the mining session the score is about"));
		evidence.add(CaseEvidence.Draft.xrayDig(report.player(), report.name(), from, now,
				"the blocks they broke in it"));
		if (session.lastFind() != null) {
			evidence.add(CaseEvidence.Draft.location(report.player(), report.name(),
					session.world(), session.lastFind(), "the last vein they uncovered"));
		}

		Mods.cases().emit(server, Signal.Type.XRAY, report.player(), report.name(),
				report.confidence(), detail, "oresense", evidence);
	}

	/** "uncovered 9 sealed diamond veins and 2 decoy veins in 640 opened faces ..." */
	public static String describe(Session session) {
		StringBuilder out = new StringBuilder("uncovered ");
		out.append(session.hiddenVeins()).append(" sealed diamond vein")
				.append(session.hiddenVeins() == 1 ? "" : "s");
		if (session.decoyVeins() > 0) {
			out.append(" and ").append(session.decoyVeins()).append(" decoy vein")
					.append(session.decoyVeins() == 1 ? "" : "s");
		}
		out.append(" in ").append(session.faces()).append(" opened rock faces, where digging ")
				.append("blind finds about ")
				.append(String.format(java.util.Locale.ROOT, "%.1f", session.expected()));
		if (session.pathBlocks() > 0) {
			out.append("; along ").append(session.pathBlocks())
					.append(" blocks of straight tunnel they uncovered ").append(session.pathFinds())
					.append(", where the directions they did not dig would have uncovered about ")
					.append(String.format(java.util.Locale.ROOT, "%.1f", session.expectedPathFinds()));
		}
		out.append(" — ").append(Hypergeometric.describe(session.pValue()));
		return out.toString();
	}

	/** The current session for a player, or null. For screens and tests. */
	public Session sessionFor(UUID player) {
		return sessions.get(player);
	}

	/** Drops a player's session. Only for tests and a deliberate reset — a relog keeps it. */
	public synchronized void forget(UUID player) {
		sessions.remove(player);
		counted.remove(player);
		lastBreak.remove(player);
		paths.remove(player);
	}
}
