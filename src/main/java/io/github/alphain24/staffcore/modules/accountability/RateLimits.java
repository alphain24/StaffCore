package io.github.alphain24.staffcore.modules.accountability;

import io.github.alphain24.staffcore.compat.Mc;
import io.github.alphain24.staffcore.config.StaffConfig;
import io.github.alphain24.staffcore.permission.Nodes;
import io.github.alphain24.staffcore.permission.Permissions;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How fast one staff member may punish or roll back.
 * <p>
 * Not about ordinary busy shifts. A rate limit is what stands between a compromised staff
 * account and the whole player base: the difference between somebody banning forty people in a
 * minute and somebody banning four is entirely the difference between a bad evening and a dead
 * server, and the second is recoverable in a way the first is not.
 * <p>
 * <b>Enforced in the service, not in the command.</b> That is the whole design. Checking in
 * {@code /staff ban} would leave the GUI path, the API path and the Discord path unlimited,
 * and each of those is a way in that somebody would have to remember to close — so this is
 * called from {@code PunishmentModule.apply} and the rollback entry point, which are the two
 * places everything already funnels through. A new caller gets the limit for free rather than
 * needing to be told about it.
 * <p>
 * Admins can be exempted by node, because a genuine mass cleanup after a raid is a real thing
 * and being unable to do it is its own failure.
 */
public final class RateLimits {

	/** Which limit applies. Separate counters, because they protect different things. */
	public enum Kind {
		PUNISHMENT("punishment"),
		ROLLBACK("rollback");

		private final String label;

		Kind(String label) {
			this.label = label;
		}

		public String label() {
			return label;
		}

		int perMinute() {
			StaffConfig cfg = StaffConfig.get();
			return this == PUNISHMENT ? cfg.maxPunishmentsPerMinute : cfg.maxRollbacksPerMinute;
		}
	}

	/** Whether the action may proceed, and what to say if not. */
	public record Verdict(boolean allowed, String refusal) {

		static final Verdict OK = new Verdict(true, null);

		static Verdict refused(String why) {
			return new Verdict(false, why);
		}
	}

	private static final long WINDOW_MS = 60_000L;

	/**
	 * Recent action times per staff member.
	 * <p>
	 * In memory rather than in the database. A rate limit is about the last sixty seconds and
	 * a restart resets it, which is correct: the thing it protects against is a burst, and a
	 * burst does not survive a restart either. Querying the audit table on every punishment
	 * would put a database round trip on the path of every ban for no benefit.
	 */
	private final Map<UUID, Map<Kind, Deque<Long>>> recent = new ConcurrentHashMap<>();

	/**
	 * Asks whether this staff member may act, and counts it if so.
	 * <p>
	 * Checking and recording in one call on purpose. Two calls invite a caller to check, then
	 * do something slow, then act — and two staff members racing through that gap is exactly
	 * the burst this exists to stop.
	 */
	public Verdict check(ServerPlayer staff, Kind kind) {
		if (staff == null) return Verdict.OK;   // console and command blocks are not the threat

		int allowed = kind.perMinute();
		if (allowed <= 0) return Verdict.OK;    // 0 disables, like every other limit here

		if (Permissions.check(staff, Nodes.RATE_LIMIT_EXEMPT)) return Verdict.OK;

		long now = System.currentTimeMillis();
		Deque<Long> times = recent
				.computeIfAbsent(staff.getUUID(), k -> new ConcurrentHashMap<>())
				.computeIfAbsent(kind, k -> new ArrayDeque<>());

		synchronized (times) {
			while (!times.isEmpty() && now - times.peekFirst() > WINDOW_MS) {
				times.pollFirst();
			}
			if (times.size() >= allowed) {
				long oldest = times.peekFirst();
				long waitSeconds = Math.max(1, (WINDOW_MS - (now - oldest)) / 1000);

				return Verdict.refused(
						"Rate limit: %d %s(s) a minute. Try again in %d second(s). This exists so "
								+ "a stolen staff account cannot empty the server before anybody "
								+ "notices — ask an admin if you need to do this in bulk."
								.formatted(allowed, kind.label(), waitSeconds));
			}
			times.addLast(now);
		}
		return Verdict.OK;
	}

	/** Forgets a staff member's history. Only for tests and for a deliberate reset. */
	public void forget(ServerPlayer staff) {
		if (staff != null) recent.remove(staff.getUUID());
	}

	/** How many of a kind this staff member has done in the last minute. */
	public int recentCount(ServerPlayer staff, Kind kind) {
		if (staff == null) return 0;
		Deque<Long> times = recent.getOrDefault(staff.getUUID(), Map.of()).get(kind);
		if (times == null) return 0;

		long now = System.currentTimeMillis();
		synchronized (times) {
			return (int) times.stream().filter(at -> now - at <= WINDOW_MS).count();
		}
	}

	/** For the refusal message, so it names somebody who can help. */
	public static String exemptNode() {
		return Nodes.RATE_LIMIT_EXEMPT;
	}

	static String describe(ServerPlayer staff) {
		return staff == null ? "console" : Mc.name(staff);
	}
}
