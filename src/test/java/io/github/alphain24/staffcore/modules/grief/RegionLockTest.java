package io.github.alphain24.staffcore.modules.grief;

import io.github.alphain24.staffcore.config.StaffConfig;
import io.github.alphain24.staffcore.permission.Actor;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One rollback at a time over any given piece of ground.
 * <p>
 * The race this protects against is a human one. A rollback executes inside a single tick, so
 * two cannot interleave — a lock held only for the duration would be a no-op wearing the shape
 * of a safeguard. What takes seconds to minutes is a staff member reading a preview and
 * deciding, and a second staff member rolling the same ground back inside that window leaves
 * the first confirming against a world that no longer exists.
 */
class RegionLockTest {

	private static Actor staff(String name) {
		return Actor.of(UUID.nameUUIDFromBytes(name.getBytes()), name, Actor.Source.PLAYER,
				Set.of());
	}

	private final Actor alice = staff("Alice");
	private final Actor bob = staff("Bob");

	@BeforeEach
	void clean() {
		RegionLock.releaseAll();
		StaffConfig.get().confirmExpirySeconds = 60;
	}

	@Test
	@DisplayName("a second staff member is refused, and told who has it")
	void overlapIsRefusedByName() {
		assertTrue(RegionLock.acquire(alice, "overworld", BlockPos.ZERO, 20).acquired());

		var refused = RegionLock.acquire(bob, "overworld", new BlockPos(10, 0, 10), 20);
		assertFalse(refused.acquired());
		assertNotNull(refused.holder());
		assertEquals("Alice", refused.holder().staffName());
		assertTrue(refused.refusal().contains("Alice"),
				"the refusal has to name who to go and talk to: " + refused.refusal());
	}

	@Test
	@DisplayName("areas that do not touch are both allowed")
	void separateGroundIsFine() {
		// Refusing rollbacks that have nothing to do with each other would make the lock the
		// thing staff complain about instead of the thing that saved them.
		assertTrue(RegionLock.acquire(alice, "overworld", BlockPos.ZERO, 20).acquired());
		assertTrue(RegionLock.acquire(bob, "overworld", new BlockPos(500, 0, 500), 20).acquired());
	}

	@Test
	@DisplayName("the same ground in another dimension is not the same ground")
	void dimensionsAreSeparate() {
		assertTrue(RegionLock.acquire(alice, "overworld", BlockPos.ZERO, 20).acquired());
		assertTrue(RegionLock.acquire(bob, "the_nether", BlockPos.ZERO, 20).acquired());
	}

	@Test
	@DisplayName("previewing twice is not a staff member blocking themselves")
	void reentrantForTheHolder() {
		// Preview, look, preview again with a different radius, then confirm. All one person
		// making one decision, and a lock that refused the second step would be unusable.
		assertTrue(RegionLock.acquire(alice, "overworld", BlockPos.ZERO, 20).acquired());
		assertTrue(RegionLock.acquire(alice, "overworld", BlockPos.ZERO, 40).acquired());
		assertTrue(RegionLock.acquire(alice, "overworld", BlockPos.ZERO, 40).acquired());
	}

	@Test
	@DisplayName("releasing lets the next person in")
	void releaseWorks() {
		RegionLock.acquire(alice, "overworld", BlockPos.ZERO, 20);
		assertFalse(RegionLock.acquire(bob, "overworld", BlockPos.ZERO, 20).acquired());

		RegionLock.release(alice);
		assertTrue(RegionLock.acquire(bob, "overworld", BlockPos.ZERO, 20).acquired());
	}

	@Test
	@DisplayName("an abandoned preview does not close an area for good")
	void locksExpire() {
		// Somebody previews a rollback, gets called away, and logs off. Without an expiry
		// that ground is locked until a restart, and the person who needs it has no way to
		// tell a held lock from a broken one.
		StaffConfig.get().confirmExpirySeconds = 60;
		RegionLock.acquire(alice, "overworld", BlockPos.ZERO, 20);

		var held = RegionLock.heldBy(alice);
		assertNotNull(held);
		assertFalse(held.isStale(System.currentTimeMillis()), "a fresh lock is not stale");
		assertTrue(held.isStale(System.currentTimeMillis() + 120_000L),
				"a two-minute-old lock survived a one-minute expiry");
	}

	@Test
	@DisplayName("with confirmations set to never expire, a lock still does")
	void noExpiryIsNotForever() {
		// confirmExpirySeconds 0 means a confirm screen stays good, which is a deliberate
		// choice about one person's own screen. It is not a choice to let one abandoned
		// preview lock ground against everybody else indefinitely.
		StaffConfig.get().confirmExpirySeconds = 0;
		RegionLock.acquire(alice, "overworld", BlockPos.ZERO, 20);

		var held = RegionLock.heldBy(alice);
		assertFalse(held.isStale(System.currentTimeMillis() + 60_000L), "one minute is not long");
		assertTrue(held.isStale(System.currentTimeMillis() + 600_000L),
				"ten minutes with no expiry configured should still release it");
	}

	@Test
	@DisplayName("the console can hold one, and is not the same holder as a player")
	void identitylessHoldersAreDistinct() {
		assertTrue(RegionLock.acquire(Actor.console(), "overworld", BlockPos.ZERO, 20).acquired());
		assertFalse(RegionLock.acquire(alice, "overworld", BlockPos.ZERO, 20).acquired(),
				"a console rollback in progress should still block a player's");
	}
}
