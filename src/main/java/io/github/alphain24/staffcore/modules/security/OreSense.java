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

	/**
	 * The faces a break opened and the sealed veins among them.
	 * <p>
	 * A face counts when the block beside the break is solid and every one of its other faces
	 * was solid too — rock nobody could see until now. A face onto rock that was already open to
	 * a cave was never hidden, and counting it would dilute exactly the players who mine in
	 * caves. The same test decides whether a diamond was hidden, so the two counts always mean
	 * the same thing.
	 *
	 * @param counted ore already accounted for by this player; every vein touched is added, hidden
	 *                or not, so a vein first seen from a cave is not "found" later from behind
	 */
	public static Uncovered uncover(Terrain terrain, BlockPos broken, Set<Long> counted,
			boolean countVeins) {

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
			markVein(terrain, next, counted);
			if (sealed) {
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

	/** Marks every diamond connected to this one — by faces and edges, as ore blobs generate. */
	static void markVein(Terrain terrain, BlockPos start, Set<Long> counted) {
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		queue.add(start.immutable());
		int visited = 0;
		while (!queue.isEmpty() && visited < VEIN_LIMIT) {
			BlockPos at = queue.poll();
			if (!counted.add(at.asLong())) continue;
			visited++;
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
	public record Session(long startedAt, long lastAt, String world, long faces,
			double expectedVeins, double expectedDecoys, int hiddenVeins, int decoyVeins,
			BlockPos lastFind, int reported) {

		public static Session start(long now, String world) {
			return new Session(now, now, world, 0, 0, 0, 0, 0, null, 0);
		}

		public int finds() {
			return hiddenVeins + decoyVeins;
		}

		/**
		 * How unlikely this session is from digging blind.
		 * <p>
		 * The two counts are tested separately and the smaller chance kept, doubled because two
		 * tests were run. Pooling them into one count would drown three decoys — close to
		 * impossible by chance — in a dozen ordinary veins.
		 */
		public double pValue() {
			double veins = hiddenVeins == 0 ? 1.0 : poissonTail(hiddenVeins, expectedVeins);
			double decoys = decoyVeins == 0 ? 1.0 : poissonTail(decoyVeins, expectedDecoys);
			return Math.min(1.0, 2 * Math.min(veins, decoys));
		}

		public int confidence() {
			return finds() == 0 ? 0 : Hypergeometric.confidence(pValue());
		}

		public double expected() {
			return expectedVeins + expectedDecoys;
		}

		Session reportedAt(int confidence) {
			return new Session(startedAt, lastAt, world, faces, expectedVeins, expectedDecoys,
					hiddenVeins, decoyVeins, lastFind, confidence);
		}
	}

	/** One break, reduced to numbers on the server thread and scored elsewhere. */
	public record Observation(UUID player, String name, String world, long at, int y,
			boolean natural, int faces, int hiddenVeins, int decoyVeins, double decoyChance,
			BlockPos find) {}

	/** Adds one break to a session. Pure. */
	public static Session fold(Session session, Observation observation, double veinRatePerFace) {
		Session s = session;
		if (s == null || observation.at() - s.lastAt() > SESSION_GAP_MS
				|| !observation.world().equals(s.world())) {
			s = Session.start(observation.at(), observation.world());
		}
		double veins = observation.natural() ? observation.faces() * veinRatePerFace : 0;
		double decoys = observation.faces() * Math.max(0, observation.decoyChance());
		return new Session(s.startedAt(), observation.at(), s.world(),
				s.faces() + observation.faces(), s.expectedVeins() + veins,
				s.expectedDecoys() + decoys, s.hiddenVeins() + observation.hiddenVeins(),
				s.decoyVeins() + observation.decoyVeins(),
				observation.find() != null ? observation.find() : s.lastFind(), s.reported());
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

	/**
	 * One break by a player, reduced to numbers. Server thread, called from the break event
	 * after {@link Canaries#onBreak}.
	 *
	 * @return null when the break cannot matter: above the diamond band with no decoys out, in
	 *         a world with nothing to count, or by staff on duty when they are skipped
	 */
	public Observation observe(ServerLevel level, ServerPlayer player, BlockPos pos,
			Canaries.Contact contact) {

		if (level == null || player == null || pos == null) return null;
		StaffConfig cfg = StaffConfig.get();
		// The same rule as the sweep: staff clocked on are not scored, staff mining on their
		// own time are.
		if (cfg.xraySkipStaffOnDuty && Mods.staffMode().isActive(player)) return null;
		// Nobody x-rays for diamonds they could take from the creative menu, and a builder
		// clearing rock underground in creative is not mining.
		if (player.isCreative() || player.isSpectator()) return null;

		boolean natural = level.dimension() == Level.OVERWORLD && pos.getY() <= BAND_TOP_Y;
		double decoyChance = Canaries.chancePerFace(level, player);
		int decoys = contact == null ? 0 : contact.uncovered();
		if (!natural && decoyChance <= 0 && decoys == 0) return null;

		long now = System.currentTimeMillis();
		UUID id = player.getUUID();
		Long previous = lastBreak.put(id, now);
		if (previous == null || now - previous > SESSION_GAP_MS) counted.remove(id);
		if (lastBreak.size() > 512) {
			lastBreak.entrySet().removeIf(e -> now - e.getValue() > SESSION_GAP_MS);
			counted.keySet().removeIf(k -> !lastBreak.containsKey(k));
		}

		Set<Long> seen = counted.computeIfAbsent(id, k -> new LinkedHashSet<>());
		Uncovered found = uncover(terrainOf(level), pos, seen, natural);
		trim(seen);

		if (found.faces() == 0 && found.hiddenVeins() == 0 && decoys == 0) return null;
		BlockPos find = decoys > 0 ? pos.immutable() : found.find();
		return new Observation(id, Mc.name(player), Mc.dimensionId(level), now, pos.getY(),
				natural, found.faces(), found.hiddenVeins(), decoys, decoyChance, find);
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

		Observation observation = observe(level, player, pos, contact);
		if (observation == null) return;
		MinecraftServer server = level.getServer();
		Mods.grief().readOffThread(server, () -> score(observation), null, report -> {
			if (report != null) announce(server, report);
		});
	}

	// ------------------------------------------------------------ worker half

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
				.append(String.format(java.util.Locale.ROOT, "%.1f", session.expected()))
				.append(" — ").append(Hypergeometric.describe(session.pValue()));
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
	}
}
