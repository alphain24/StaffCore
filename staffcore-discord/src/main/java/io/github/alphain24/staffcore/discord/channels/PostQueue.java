package io.github.alphain24.staffcore.discord.channels;

import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Posts waiting for Discord: bounded, oldest dropped first, and never in anybody's way.
 * <p>
 * The bot being unreachable is normal: Discord restarts its gateways, hosts lose their network,
 * the bot is still connecting when the first ban of the day lands. So a post is not tried once and
 * thrown away. It waits here, in order, until the connection is ready, and is made then.
 * <p>
 * Waiting has a ceiling. Past {@link #capacity} the oldest post is dropped for the newest, counted,
 * and logged — the first at once and then a count at most once a minute, so a long outage is a few
 * lines rather than thousands. A backlog of old embeds is worth less than the memory it holds, and
 * the newest are the ones staff are about to look for.
 * <p>
 * Nothing here blocks the caller. {@link #offer} takes a lock long enough to add to a deque; the
 * posting happens on the companion's worker, one post per turn, so a long backlog does not keep a
 * button click waiting behind all of it. Punishments do not reach this at all until StaffCore's own
 * event thread has handed them over, so a punishment is applied whatever happens here.
 * <p>
 * Held in memory only: posts still waiting when the server stops are not kept.
 */
public final class PostQueue {

	/** What the queue posts through: the connection. */
	public interface Poster {

		/** Whether a post made now could reach Discord: connected, with its channels set up. */
		boolean readyToPost();

		/**
		 * Makes one post, waiting for Discord. Called on the worker only. Throws {@link Retry} when the post
		 * did not go through for a reason that passes; handles every other failure itself.
		 */
		void post(Outbound outbound);
	}

	/**
	 * The post did not go through for a reason that passes — the connection went away, or Discord
	 * answered with a server error — so it is kept and tried again.
	 */
	public static final class Retry extends RuntimeException {
		public Retry(String why) {
			super(why, null, false, false);
		}
	}

	public static final int DEFAULT_CAPACITY = 500;
	public static final int MIN_CAPACITY = 50;
	public static final int MAX_CAPACITY = 10_000;

	/** A post Discord keeps failing with server errors while connected is given up after this many tries. */
	static final int MAX_ATTEMPTS = 5;
	/** How long to wait after a server error before trying again. */
	static final long RETRY_DELAY_MILLIS = 5_000;
	/** After the first drop is logged, further drops are logged as a count at most this often. */
	static final long LOG_EVERY_MILLIS = 60_000;

	private record Pending(Outbound outbound, int attempts) {}

	private final int capacity;
	private final Executor worker;
	private final Poster poster;
	private final LongSupplier clock;
	private final Consumer<String> warn;
	private final Executor later;

	private final ArrayDeque<Pending> waiting = new ArrayDeque<>();
	/** True while a turn is scheduled or running on the worker. Guarded by {@link #waiting}. */
	private boolean pumping;
	/** True while a retry is scheduled after a server error. Guarded by {@link #waiting}. */
	private boolean backingOff;
	private long droppedSinceLogged;
	private long loggedAt = Long.MIN_VALUE;

	private final AtomicLong dropped = new AtomicLong();
	private final AtomicLong gaveUp = new AtomicLong();
	private final AtomicLong posted = new AtomicLong();

	/**
	 * @param capacity how many posts may wait; clamped to {@link #MIN_CAPACITY}-{@link #MAX_CAPACITY}
	 * @param later    runs a retry after a delay; the worker, delayed, in the real bot
	 */
	PostQueue(int capacity, Executor worker, Poster poster, LongSupplier clock, Consumer<String> warn,
			Executor later) {
		this.capacity = Math.max(MIN_CAPACITY, Math.min(MAX_CAPACITY, capacity));
		this.worker = worker;
		this.poster = poster;
		this.clock = clock;
		this.warn = warn;
		this.later = later;
	}

	public PostQueue(int capacity, Executor worker, Poster poster, Consumer<String> warn) {
		this(capacity, worker, poster, System::currentTimeMillis, warn,
				CompletableFuture.delayedExecutor(RETRY_DELAY_MILLIS, TimeUnit.MILLISECONDS, worker));
	}

	/** Adds a post to the end, dropping the oldest if the queue is full. Any thread; never waits on Discord. */
	public void offer(Outbound outbound) {
		if (outbound == null) return;
		String report;
		synchronized (waiting) {
			report = waiting.size() >= capacity ? dropOldest() : null;
			waiting.addLast(new Pending(outbound, 0));
		}
		if (report != null) warn.accept(report);
		wake();
	}

	/** Starts posting if anything is waiting. Called when the connection becomes ready, and after each offer. */
	public void wake() {
		synchronized (waiting) {
			if (pumping || backingOff || waiting.isEmpty()) return;
			pumping = true;
		}
		schedule();
	}

	/** Posts waiting now. */
	public int waiting() {
		synchronized (waiting) {
			return waiting.size();
		}
	}

	/** The most that may wait. */
	public int capacity() {
		return capacity;
	}

	/** Posts dropped because the queue was full, since the bot started. */
	public long dropped() {
		return dropped.get();
	}

	/** Posts given up after Discord kept failing them, since the bot started. */
	public long gaveUp() {
		return gaveUp.get();
	}

	/** Posts made, since the bot started. */
	public long posted() {
		return posted.get();
	}

	/** Guarded by {@link #waiting}. Returns the line to log, or null when this drop is only counted. */
	private String dropOldest() {
		waiting.pollFirst();
		return countDrop();
	}

	/** Guarded by {@link #waiting}. */
	private String countDrop() {
		long total = dropped.incrementAndGet();
		droppedSinceLogged++;
		long now = clock.getAsLong();
		if (loggedAt != Long.MIN_VALUE && now - loggedAt < LOG_EVERY_MILLIS) return null;
		String line = droppedSinceLogged + " Discord post(s) dropped: " + capacity + " were already waiting, "
				+ "the most outboundQueueSize allows, so the oldest went first. Dropped since the bot started: "
				+ total + ".";
		droppedSinceLogged = 0;
		loggedAt = now;
		return line;
	}

	private void schedule() {
		try {
			worker.execute(this::turn);
		} catch (RejectedExecutionException stopped) {
			// The bot is stopping. What is waiting stays waiting, and goes with the server.
			synchronized (waiting) {
				pumping = false;
			}
		}
	}

	/** One post, then another turn if more are waiting. */
	private void turn() {
		Pending next;
		synchronized (waiting) {
			// Read under the lock, and the connection says it is ready before it wakes the queue, so a
			// wake that arrives while this turn is ending is never lost.
			if (waiting.isEmpty() || !poster.readyToPost()) {
				pumping = false;
				return;
			}
			next = waiting.pollFirst();
		}

		try {
			poster.post(next.outbound());
			posted.incrementAndGet();
		} catch (Retry retry) {
			putBack(next, retry.getMessage());
			return;
		} catch (RuntimeException unexpected) {
			// The connection handles its own failures; one that escapes is counted, and not tried again,
			// because a post that breaks the code posting it will break it next time too.
			gaveUp.incrementAndGet();
			warn.accept("A Discord post failed and was dropped (" + unexpected.getClass().getSimpleName() + ").");
		}

		synchronized (waiting) {
			if (waiting.isEmpty()) {
				pumping = false;
				return;
			}
		}
		schedule();
	}

	/** Returns a post that did not go through to the front, or gives it up. */
	private void putBack(Pending pending, String why) {
		boolean connected = poster.readyToPost();
		// Only a failure while connected counts as a try: an outage is not the post's fault.
		int attempts = connected ? pending.attempts() + 1 : pending.attempts();
		String report = null;
		boolean backOff = false;
		synchronized (waiting) {
			pumping = false;
			if (attempts >= MAX_ATTEMPTS) {
				gaveUp.incrementAndGet();
				report = "A Discord post was given up after " + attempts + " tries (" + why + ").";
			} else if (waiting.size() >= capacity) {
				// Newer posts filled the queue meanwhile, and this one is older than all of them.
				report = countDrop();
			} else {
				waiting.addFirst(new Pending(pending.outbound(), attempts));
				// Discord is answering, badly: try again shortly rather than at once.
				backOff = connected;
				backingOff = backOff;
			}
		}
		if (report != null) warn.accept(report);

		if (!backOff) {
			// Not connected, the connection wakes the queue when it is back; asked again in case it already is.
			wake();
			return;
		}
		try {
			later.execute(() -> {
				synchronized (waiting) {
					backingOff = false;
				}
				wake();
			});
		} catch (RejectedExecutionException stopped) {
			synchronized (waiting) {
				backingOff = false;
			}
		}
	}
}
