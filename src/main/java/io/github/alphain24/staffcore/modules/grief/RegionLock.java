package io.github.alphain24.staffcore.modules.grief;

import io.github.alphain24.staffcore.config.StaffConfig;
import io.github.alphain24.staffcore.permission.Actor;
import io.github.alphain24.staffcore.util.TimeFormat;
import net.minecraft.core.BlockPos;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One rollback at a time over any given piece of ground.
 *
 * <h2>What the race actually is</h2>
 * Not the execution. A rollback runs to completion inside a single tick, so two of them cannot
 * interleave and a lock held only for the duration would be a no-op wearing the shape of a
 * safeguard — the worst kind of protection, because it reads as covered.
 * <p>
 * The race is the <em>human</em> window. Staff preview a rollback, read what it would do,
 * decide, and confirm; that is seconds to minutes, and it is entirely possible for a second
 * staff member to roll the same ground back in between. The first one then confirms against a
 * description of a world that no longer exists, and re-applies changes that have already been
 * inverted — which puts the grief back.
 * <p>
 * So the lock is taken when a preview is shown and held until it is confirmed or expires.
 * Execution stays inside it, which costs nothing now and is what will matter if any of this
 * ever moves off the server thread.
 *
 * <h2>Built on identity</h2>
 * A lock holder is an {@link Actor}'s id and name, not a player object — the holder has to
 * outlive the command that took the lock, and a rollback staged from a bot thread or a
 * scheduled task has no player behind it at all.
 */
public final class RegionLock {
	private RegionLock() {}

	/**
	 * Who is holding a region, and since when.
	 *
	 * @param staffId null for the console, which can still hold one — it is the second
	 *                overlapping rollback that is refused, not the second person
	 */
	public record Held(UUID staffId, String staffName, String world, BlockPos centre, int radius,
			long since) {

		/** Whether this lock covers ground the other one would touch. */
		boolean overlaps(String otherWorld, BlockPos otherCentre, int otherRadius) {
			if (!world.equals(otherWorld)) return false;

			// Centre distance against the sum of the radii. Rollback areas are cubes rather
			// than spheres, so this is slightly generous — which is the right direction for
			// something whose failure is two rollbacks fighting over the same blocks.
			double reach = (double) radius + otherRadius;
			return centre.distSqr(otherCentre) <= reach * reach;
		}

		boolean isStale(long now) {
			int seconds = StaffConfig.get().confirmExpirySeconds;
			// A lock with no expiry would let one abandoned preview close an area forever.
			// Where confirmations never expire, this falls back to five minutes, because an
			// unattended lock is a different thing from an unattended confirm screen.
			long window = (seconds > 0 ? seconds : 300) * 1000L;
			return now - since > window;
		}
	}

	/** Whether the region was taken, and what to say when it was not. */
	public record Grant(boolean acquired, String refusal, Held holder) {

		static Grant taken() {
			return new Grant(true, null, null);
		}

		static Grant refused(String refusal, Held holder) {
			return new Grant(false, refusal, holder);
		}
	}

	/** Keyed by holder rather than by region, so releasing needs no token. */
	private static final Map<String, Held> HELD = new ConcurrentHashMap<>();

	private static String keyFor(Actor staff) {
		return staff == null || staff.id() == null
				? "console:" + (staff == null ? "?" : staff.name())
				: staff.id().toString();
	}

	/**
	 * Claims a region, or names who has it.
	 * <p>
	 * Re-entrant for the same holder: the same staff member previewing twice, or previewing
	 * and then confirming, refreshes their own lock rather than being refused by it.
	 */
	public static synchronized Grant acquire(Actor staff, String world, BlockPos centre,
			int radius) {

		long now = System.currentTimeMillis();
		String mine = keyFor(staff);
		HELD.entrySet().removeIf(e -> e.getValue().isStale(now));

		for (Map.Entry<String, Held> entry : HELD.entrySet()) {
			if (entry.getKey().equals(mine)) continue;
			Held held = entry.getValue();
			if (!held.overlaps(world, centre, radius)) continue;

			return Grant.refused(held.staffName() + " is part way through a rollback covering "
					+ "this ground, started " + TimeFormat.words(held.since()) + ". Two "
					+ "rollbacks over the same blocks undo each other's work and put the grief "
					+ "back — wait, or pick an area that does not overlap theirs.", held);
		}

		HELD.put(mine, new Held(staff == null ? null : staff.id(),
				staff == null ? "Console" : staff.name(), world, centre, radius, now));
		return Grant.taken();
	}

	/** Gives it back. Safe to call when nothing is held, which is the common case. */
	public static synchronized void release(Actor staff) {
		HELD.remove(keyFor(staff));
	}

	/** What this staff member currently holds, or null. For {@code /staff status}. */
	public static Held heldBy(Actor staff) {
		return HELD.get(keyFor(staff));
	}

	/** Every lock currently out. For diagnostics and tests. */
	public static java.util.Collection<Held> all() {
		long now = System.currentTimeMillis();
		HELD.entrySet().removeIf(e -> e.getValue().isStale(now));
		return java.util.List.copyOf(HELD.values());
	}

	/** Drops everything. Only for tests and a deliberate reset. */
	public static void releaseAll() {
		HELD.clear();
	}
}
