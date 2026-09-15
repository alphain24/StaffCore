package io.github.alphain24.staffcore.api.internal;

import io.github.alphain24.staffcore.StaffCore;
import io.github.alphain24.staffcore.api.StaffCoreEvent;
import io.github.alphain24.staffcore.api.StaffCoreListener;

import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Carries events from wherever they happen to the listeners, on a thread of its own.
 *
 * <h2>Why a thread, and why a bounded one</h2>
 * A punishment raises an event on the server thread, and a Discord companion's listener is the
 * kind of code that ends up waiting on a network. Called directly, a slow listener would be a
 * slow punishment and a hung one would be a hung server. Handed to this thread instead, the
 * server thread's whole involvement is adding to a queue.
 * <p>
 * The queue has a ceiling. A listener that has stopped taking events must not grow StaffCore's
 * memory without end, so past {@value #CAPACITY} the oldest events are dropped and counted — a
 * lost Discord post is recoverable, an out-of-memory server is not.
 * <p>
 * Not part of the published API, despite living beside it.
 */
public final class EventBus {
	private EventBus() {}

	static final int CAPACITY = 4096;

	private static final ArrayDeque<Pending> QUEUE = new ArrayDeque<>();
	private static final AtomicLong DROPPED = new AtomicLong();
	private static final AtomicLong DELIVERED = new AtomicLong();
	private static Thread worker;

	private record Pending(StaffCoreEvent event, List<StaffCoreListener> listeners) {}

	public static void publish(StaffCoreEvent event, List<StaffCoreListener> listeners) {
		synchronized (QUEUE) {
			if (QUEUE.size() >= CAPACITY) {
				QUEUE.pollFirst();
				long dropped = DROPPED.incrementAndGet();
				if (dropped == 1 || dropped % 1000 == 0) {
					StaffCore.LOGGER.warn("[StaffCore API] A listener is not keeping up; {} event(s) "
							+ "dropped so far.", dropped);
				}
			}
			QUEUE.addLast(new Pending(event, listeners));
			QUEUE.notifyAll();
			if (worker == null || !worker.isAlive()) start();
		}
	}

	/** Events dropped because the queue was full, since the server started. */
	public static long dropped() {
		return DROPPED.get();
	}

	/** Events handed to listeners, since the server started. */
	public static long delivered() {
		return DELIVERED.get();
	}

	/** Blocks until the queue is empty, or the timeout passes. For tests. */
	public static boolean drain(long timeoutMillis) {
		long until = System.currentTimeMillis() + timeoutMillis;
		synchronized (QUEUE) {
			while (!QUEUE.isEmpty() || busy) {
				long left = until - System.currentTimeMillis();
				if (left <= 0) return false;
				try {
					QUEUE.wait(Math.min(left, 50));
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return false;
				}
			}
			return true;
		}
	}

	private static volatile boolean busy;

	private static void start() {
		worker = new Thread(EventBus::run, "StaffCore API events");
		worker.setDaemon(true);
		worker.start();
	}

	private static void run() {
		while (true) {
			Pending next;
			synchronized (QUEUE) {
				while (QUEUE.isEmpty()) {
					busy = false;
					QUEUE.notifyAll();
					try {
						QUEUE.wait();
					} catch (InterruptedException e) {
						return;
					}
				}
				next = QUEUE.pollFirst();
				busy = true;
			}
			for (StaffCoreListener listener : next.listeners()) {
				try {
					listener.onEvent(next.event());
				} catch (Throwable failure) {
					// The class of the listener and of the failure, never its message: a
					// companion's exception can carry anything, and a secret in StaffCore's log
					// is still a secret in a log.
					StaffCore.LOGGER.warn("[StaffCore API] {} failed on {} ({})",
							listener.getClass().getName(), next.event().getClass().getSimpleName(),
							failure.getClass().getName());
				}
			}
			DELIVERED.incrementAndGet();
		}
	}
}
