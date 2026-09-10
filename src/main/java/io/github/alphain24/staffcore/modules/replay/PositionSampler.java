package io.github.alphain24.staffcore.modules.replay;

import io.github.alphain24.staffcore.StaffCore;
import io.github.alphain24.staffcore.compat.Mc;
import io.github.alphain24.staffcore.config.StaffConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Takes the samples, and does as little as possible on the tick thread while doing it.
 *
 * <h2>Which half runs where</h2>
 * Reading a player's position has to happen on the server thread. An entity's coordinates are
 * mutated by the tick loop with no synchronisation of any kind, so reading them from a worker
 * is a data race that would mostly work and occasionally record a position no player was ever
 * at. The phase brief asks for sampling to stay off the server thread and this is the part
 * that cannot: it is stated here rather than quietly ignored.
 * <p>
 * What that costs is bounded and small, and the split is where the honesty lives. On the tick
 * thread: once every ten ticks by default, read three doubles and two floats per online player,
 * compare against the last sample, and either drop it or put a record on a queue. No database,
 * no allocation for a player who has not moved, no iteration over anything but the player list.
 * <p>
 * Everything else — the delta encoding, the decision to start a new run, the transaction — runs
 * on the grief log's writer thread. That thread is shared rather than new on purpose: this
 * database's entire concurrency story is one connection and one writer, and a second pool
 * would be a second thread contending for the same lock.
 *
 * <h2>The encoder state has exactly one owner</h2>
 * Everything in {@link Encoder} is touched only by the writer thread. The tick thread owns
 * {@link #lastSent} and nothing else. There is no lock anywhere in this class because there is
 * nothing for two threads to disagree about — which is worth more here than it usually is,
 * because the failure mode of getting it wrong is a replay that shows somebody somewhere they
 * never went, and nothing about that looks like a bug when you watch it.
 */
public final class PositionSampler {
	private PositionSampler() {}

	/** A run is cut at a minute whatever else is happening. See {@link PositionLog}. */
	public static final int RUN_MAX_MS = 60_000;

	/** Standing still for longer than this ends the run rather than stretching it. */
	public static final int RUN_GAP_MS = 10_000;

	/**
	 * How far a player must move to be worth a row, in {@link PositionLog#SCALE} units.
	 * <p>
	 * Two units is about six centimetres. Below that is a boat rocking, a player being pushed
	 * by a neighbour, or floating-point drift on a standing entity — none of which is somebody
	 * going anywhere, and all of which would otherwise fill the largest table in the database
	 * with rows saying a player stayed exactly where they were.
	 */
	private static final int MOVE_THRESHOLD = 2;

	/**
	 * A jump larger than this is not movement, so the chain restarts.
	 * <p>
	 * 128 blocks between two samples. Nothing a player can do covers that in half a second —
	 * it is a teleport, a portal, or the sampler having missed a stretch. Encoding it as a
	 * delta would work and would draw a replay in which somebody crosses the map in a
	 * straight line at impossible speed, which reads as evidence of cheating and is not.
	 */
	private static final int TELEPORT_UNITS = 128 * PositionLog.SCALE;

	/** How many samples may wait in the queue before the oldest are dropped. */
	private static final int QUEUE_LIMIT = 20_000;

	// ------------------------------------------------------------------ shared

	private static ExecutorService worker;

	/** Hands the sampler the grief log's writer thread. Idempotent. */
	public static void attach(ExecutorService shared) {
		worker = shared;
	}

	/** One reading, as the tick thread saw it. Immutable, and the only thing that crosses. */
	private record Sample(UUID uuid, String name, String world, int x, int y, int z,
			int yaw, int pitch, long at) {}

	private static final ConcurrentLinkedQueue<Sample> QUEUE = new ConcurrentLinkedQueue<>();
	private static final AtomicLong QUEUED = new AtomicLong();
	private static final AtomicLong DROPPED = new AtomicLong();
	private static final AtomicLong WRITTEN = new AtomicLong();
	private static final AtomicBoolean DRAINING = new AtomicBoolean();

	/** Last position handed to the queue, per player. Tick thread only. */
	private static final Map<UUID, int[]> lastSent = new HashMap<>();

	// -------------------------------------------------------------- tick thread

	public static boolean enabled() {
		return StaffConfig.get().positionTracking && StaffCore.storage().isReady();
	}

	/** How many ticks between samples, from the configured rate. */
	public static int intervalTicks() {
		int hz = Math.max(1, Math.min(10, StaffConfig.get().positionSampleHz));
		return Math.max(1, 20 / hz);
	}

	/**
	 * Reads every online player once, and queues the ones who moved.
	 * <p>
	 * Called from the tick loop on the configured interval. Returns without touching the
	 * player list at all when tracking is off, which is the default — the cost of having this
	 * feature and not using it is one boolean read per interval.
	 */
	public static void sample(MinecraftServer server) {
		if (server == null || !enabled()) return;

		long now = System.currentTimeMillis();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			record(player.getUUID(), Mc.name(player), Mc.dimensionId(player.level()),
					(int) Math.round(player.getX() * PositionLog.SCALE),
					(int) Math.round(player.getY() * PositionLog.SCALE),
					(int) Math.round(player.getZ() * PositionLog.SCALE),
					PositionLog.packAngle(player.getYRot()),
					PositionLog.packAngle(player.getXRot()), now);
		}
		drain();
	}

	/**
	 * One reading, already in fixed point.
	 * <p>
	 * Split out from {@link #sample} so the parts of this class that decide what gets written
	 * can be driven without a live server. That matters more than it usually would: everything
	 * interesting here — whether standing still writes a row, when a chain restarts, whether a
	 * teleport becomes a straight line across the map — is a decision made between a player
	 * moving and a row existing, and a test that reimplemented it would be testing its own copy.
	 */
	static void record(UUID uuid, String name, String world, int x, int y, int z,
			int yaw, int pitch, long at) {

		int[] previous = lastSent.get(uuid);
		if (previous != null
				&& Math.abs(x - previous[0]) < MOVE_THRESHOLD
				&& Math.abs(y - previous[1]) < MOVE_THRESHOLD
				&& Math.abs(z - previous[2]) < MOVE_THRESHOLD) {
			// Standing still. Deliberately not sampled even if they are looking around: head
			// rotation is most of what an idle player does, and a replay of somebody standing
			// at spawn turning in circles is not worth the largest table in the database. The
			// gap in timestamps records the stillness for free.
			return;
		}

		lastSent.put(uuid, new int[] {x, y, z});
		offer(new Sample(uuid, name, world, x, y, z, yaw, pitch, at));
	}

	private static void offer(Sample sample) {
		// A bounded queue, and the oldest goes rather than the newest. If the writer has
		// fallen far enough behind to matter, the recent minutes are the ones somebody is
		// about to ask to watch — and an unbounded queue in front of a disk is how a slow
		// disk turns into an out-of-memory crash.
		if (QUEUED.get() >= QUEUE_LIMIT) {
			if (QUEUE.poll() != null) {
				QUEUED.decrementAndGet();
				DROPPED.incrementAndGet();
			}
		}
		QUEUE.add(sample);
		QUEUED.incrementAndGet();
	}

	/** Hands the queue to the writer, unless it is already working through it. */
	private static void drain() {
		if (worker == null || worker.isShutdown() || QUEUE.isEmpty()) return;
		// One drain in flight at a time. Without this a busy server queues a task per tick
		// behind a writer that is already draining everything, and the executor's own queue
		// becomes the unbounded thing this class went to trouble to avoid.
		if (!DRAINING.compareAndSet(false, true)) return;

		try {
			worker.execute(() -> {
				try {
					write();
				} finally {
					DRAINING.set(false);
				}
			});
		} catch (RuntimeException rejected) {
			DRAINING.set(false);
		}
	}

	// ------------------------------------------------------------ writer thread

	/** Per-player delta state. Writer thread only. */
	private static final class Encoder {
		long run = -1;
		String world;
		int x;
		int y;
		int z;
		long runStart;
		int lastMs;
		int samples;

		/** The frame this run must end at whatever happens, so ms stays a small number. */
		boolean expired(long at) {
			return at - runStart >= RUN_MAX_MS;
		}
	}

	private static final Map<UUID, Encoder> ENCODERS = new ConcurrentHashMap<>();

	/**
	 * Drains the queue into the database in one transaction.
	 * <p>
	 * Runs on the shared writer. Everything that decides the shape of a row happens here so
	 * that the tick thread's share of this feature stays a comparison and a queue push.
	 */
	static void write() {
		if (!StaffCore.storage().isReady()) return;

		List<Sample> batch = new ArrayList<>();
		Sample next;
		while ((next = QUEUE.poll()) != null) {
			QUEUED.decrementAndGet();
			batch.add(next);
			if (batch.size() >= 4096) break;
		}
		if (batch.isEmpty()) return;

		StaffCore.storage().inTransaction(conn -> {
			try (PreparedStatement ps = conn.prepareStatement(PositionLog.INSERT_SAMPLE)) {
				int queued = 0;
				for (Sample sample : batch) {
					if (encode(sample, ps)) queued++;
				}
				if (queued > 0) {
					ps.executeBatch();
					WRITTEN.addAndGet(queued);
				}
			}
		});

		// Whatever is still waiting goes in the next pass rather than this one, so a backlog
		// is written in bounded transactions instead of one that holds the lock for seconds.
		if (!QUEUE.isEmpty() && worker != null && !worker.isShutdown()) {
			worker.execute(PositionSampler::write);
		}
	}

	/** Turns one sample into a row, starting a new run when the chain cannot continue. */
	private static boolean encode(Sample sample, PreparedStatement ps) throws java.sql.SQLException {
		Encoder state = ENCODERS.get(sample.uuid());

		boolean restart = state == null
				|| state.run < 0
				|| !state.world.equals(sample.world())
				|| sample.at() - (state.runStart + state.lastMs) > RUN_GAP_MS
				|| state.expired(sample.at())
				|| Math.abs(sample.x() - state.x) > TELEPORT_UNITS
				|| Math.abs(sample.y() - state.y) > TELEPORT_UNITS
				|| Math.abs(sample.z() - state.z) > TELEPORT_UNITS;

		if (restart) {
			if (state != null && state.run >= 0) {
				PositionLog.closeRun(state.run, state.runStart + state.lastMs, state.samples);
			}
			state = new Encoder();
			ENCODERS.put(sample.uuid(), state);

			state.run = PositionLog.openRun(sample.uuid(), sample.name(), sample.world(),
					sample.at(), sample.at() + RUN_MAX_MS, sample.x(), sample.y(), sample.z());
			if (state.run < 0) {
				ENCODERS.remove(sample.uuid());
				return false;
			}

			state.world = sample.world();
			state.x = sample.x();
			state.y = sample.y();
			state.z = sample.z();
			state.runStart = sample.at();
			state.lastMs = 0;
			state.samples = 1;

			// The first sample is a row like any other, with a zero delta. SQLite stores a
			// zero in no bytes at all, so the special case costs nothing and buys a reader
			// that never has to treat the origin differently from anything after it.
			PositionLog.appendSample(ps, state.run, 0, 0, 0, 0, sample.yaw(), sample.pitch());
			return true;
		}

		int ms = (int) (sample.at() - state.runStart);
		// Strictly increasing, because (run, ms) is the primary key. Two samples landing in
		// the same millisecond is not a real scenario at ten hertz, but a clock that steps
		// backwards is, and one row silently replacing another is not a failure anybody
		// would ever see.
		if (ms <= state.lastMs) ms = state.lastMs + 1;
		if (ms >= RUN_MAX_MS) return false;

		PositionLog.appendSample(ps, state.run, ms, sample.x() - state.x, sample.y() - state.y,
				sample.z() - state.z, sample.yaw(), sample.pitch());

		state.x = sample.x();
		state.y = sample.y();
		state.z = sample.z();
		state.lastMs = ms;
		state.samples++;
		return true;
	}

	// -------------------------------------------------------------- lifecycle

	/**
	 * Closes a player's run when they leave.
	 * <p>
	 * Not strictly required — a run left open is found by every query anyway, because its
	 * {@code ended_at} is an upper bound rather than a promise. What it buys is an accurate
	 * end time on the common path, so a window query does not have to open a minute of
	 * samples that turn out to be somebody else's session.
	 */
	public static void onLeave(UUID player) {
		if (player == null) return;
		lastSent.remove(player);

		if (worker == null || worker.isShutdown()) return;
		worker.execute(() -> {
			Encoder state = ENCODERS.remove(player);
			if (state != null && state.run >= 0) {
				PositionLog.closeRun(state.run, state.runStart + state.lastMs, state.samples);
			}
		});
	}

	/**
	 * Writes everything outstanding and closes every open run. For shutdown.
	 * <p>
	 * Called on the writer thread itself, before it is asked to stop, so it runs after the
	 * batches already queued behind it rather than racing them.
	 */
	public static void flush() {
		// Looped rather than called once. write() takes a bounded batch so that a backlog is
		// written in transactions somebody's disk can finish, which means one call is not
		// necessarily the whole queue — and at shutdown it has to be.
		while (!QUEUE.isEmpty()) write();
		for (Map.Entry<UUID, Encoder> entry : Map.copyOf(ENCODERS).entrySet()) {
			Encoder state = entry.getValue();
			if (state.run >= 0) {
				PositionLog.closeRun(state.run, state.runStart + state.lastMs, state.samples);
			}
		}
		ENCODERS.clear();
	}

	/** "wrote 4,120, queued 3, dropped 0" — for {@code /staff status}. */
	public static String counters() {
		return "wrote %,d, queued %,d, dropped %,d"
				.formatted(WRITTEN.get(), QUEUED.get(), DROPPED.get());
	}

	public static long dropped() {
		return DROPPED.get();
	}

	/** Only for tests and a deliberate reset. */
	public static void forgetAll() {
		QUEUE.clear();
		QUEUED.set(0);
		DROPPED.set(0);
		WRITTEN.set(0);
		ENCODERS.clear();
		lastSent.clear();
		DRAINING.set(false);
	}
}
