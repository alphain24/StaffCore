package io.github.alphain24.staffcore.modules.discord;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * How often one Discord account may try something appeal-shaped in an hour.
 * <p>
 * Filing an appeal, and answering a question about one, are the only things an unlinked Discord
 * account can do — which makes them the only things a stranger can do over and over. Counted per
 * account, so one person's retries never lock out anybody else, and in memory: an hour is the
 * window, and a restart forgetting it costs nothing worth keeping a table for.
 */
public final class AppealAttempts {

	private static final long HOUR = 3_600_000L;

	private final LongSupplier clock;
	private final Map<String, Deque<Long>> attempts = new ConcurrentHashMap<>();

	public AppealAttempts() {
		this(System::currentTimeMillis);
	}

	/** With a clock a test can move. */
	AppealAttempts(LongSupplier clock) {
		this.clock = clock;
	}

	/**
	 * Counts an attempt under this key and says whether it may go ahead.
	 *
	 * @param perHour the limit; 0 or less means none
	 * @return null when it may, otherwise how many milliseconds until it may
	 */
	public Long attempt(String key, int perHour) {
		if (perHour <= 0 || key == null) return null;
		long now = clock.getAsLong();
		Deque<Long> times = attempts.computeIfAbsent(key, k -> new ArrayDeque<>());
		synchronized (times) {
			while (!times.isEmpty() && now - times.peekFirst() >= HOUR) times.pollFirst();
			if (times.size() >= perHour) return Math.max(1, HOUR - (now - times.peekFirst()));
			times.addLast(now);
			return null;
		}
	}
}
