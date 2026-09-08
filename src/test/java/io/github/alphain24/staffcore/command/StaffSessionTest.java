package io.github.alphain24.staffcore.command;

import io.github.alphain24.staffcore.config.StaffConfig;
import io.github.alphain24.staffcore.modules.accountability.OperationId;
import io.github.alphain24.staffcore.permission.Actor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What one staff member was in the middle of.
 * <p>
 * The properties worth defending are the negative ones. A confirmation must not outlive the
 * preview it belongs to, must not be reusable, and must not be claimable by the wrong command
 * — because the thing on the other side of a confirmation is by definition something that
 * cannot be taken back.
 */
class StaffSessionTest {

	private static Actor staff(String name) {
		return Actor.of(UUID.nameUUIDFromBytes(name.getBytes()), name, Actor.Source.PLAYER,
				Set.of());
	}

	private final Actor alice = staff("Alice");
	private final Actor bob = staff("Bob");

	@BeforeEach
	void clean() {
		StaffSession.forgetAll();
		StaffConfig.get().confirmExpirySeconds = 60;
	}

	@Test
	@DisplayName("a confirmation is consumed, so one preview confirms one action")
	void confirmationsAreNotReusable() {
		// Left claimable, a second "confirm" typed by reflex runs the whole thing again — and
		// the second run of a purge is not the same operation as the first.
		StaffSession.staged(alice, "purge 30d everyone", "purging 400 rows");

		assertTrue(StaffSession.claim(alice, "purge 30d everyone").allowed());
		assertFalse(StaffSession.claim(alice, "purge 30d everyone").allowed(),
				"the same preview confirmed twice");
	}

	@Test
	@DisplayName("a confirmation cannot be claimed by a different action")
	void keysAreNotInterchangeable() {
		StaffSession.staged(alice, "purge 30d Steve_", "purging Steve_");

		var wrong = StaffSession.claim(alice, "purge 30d everyone");
		assertFalse(wrong.allowed(),
				"a preview of one purge confirmed a different one, which is how somebody "
						+ "deletes the whole log meaning to delete one player's rows");
		assertNotNull(wrong.refusal());
	}

	@Test
	@DisplayName("a confirmation belongs to the staff member who staged it")
	void oneStaffMemberCannotConfirmAnother() {
		StaffSession.staged(alice, "purge 30d everyone", "purging 400 rows");

		assertFalse(StaffSession.claim(bob, "purge 30d everyone").allowed(),
				"Bob confirmed what Alice was shown");
		assertTrue(StaffSession.claim(alice, "purge 30d everyone").allowed(),
				"and Alice's own confirmation was consumed by Bob's attempt");
	}

	@Test
	@DisplayName("a stale preview is refused, and the refusal says how stale")
	void confirmationsExpire() {
		StaffConfig.get().confirmExpirySeconds = 1;
		StaffSession.staged(alice, "purge 30d everyone", "purging 400 rows");

		// Reached through the public clock rather than by sleeping: the property is that age
		// is measured, and a test that waits a second to prove it is a test everybody skips.
		assertTrue(StaffSession.stillFresh(System.currentTimeMillis()));
		assertFalse(StaffSession.stillFresh(System.currentTimeMillis() - 5_000L),
				"a five-second-old screen passed a one-second expiry");

		StaffConfig.get().confirmExpirySeconds = 0;
		assertTrue(StaffSession.stillFresh(System.currentTimeMillis() - 86_400_000L),
				"0 disables the expiry, like every other window in this config");
	}

	@Test
	@DisplayName("nothing staged means nothing to confirm, said as such")
	void confirmingNothingIsExplained() {
		var verdict = StaffSession.claim(alice, "purge 30d everyone");

		assertFalse(verdict.allowed());
		assertTrue(verdict.refusal().contains("Nothing to confirm"),
				"the message should send somebody to the preview, not leave them guessing");
	}

	@Test
	@DisplayName("the last undoable action is one deep, not a stack")
	void undoDoesNotWalkBackwards() {
		// Undoing three actions in a row is not something anybody does deliberately. It is
		// what happens when somebody presses the same key too many times.
		StaffSession.didSomethingUndoable(alice, OperationId.of(OperationId.Kind.ROLLBACK, 1L));
		StaffSession.didSomethingUndoable(alice, OperationId.of(OperationId.Kind.ROLLBACK, 2L));

		assertEquals("R-2", StaffSession.lastReversible(alice).toString());
		StaffSession.forgetReversible(alice);
		assertNull(StaffSession.lastReversible(alice), "the first one came back after an undo");
	}

	@Test
	@DisplayName("state is per staff member, and disconnecting drops it")
	void nothingLeaksBetweenPeopleOrSessions() {
		StaffSession.looked(alice, "Steve_");
		StaffSession.looked(bob, "Notch");

		assertEquals("Steve_", StaffSession.lastLookedUp(alice));
		assertEquals("Notch", StaffSession.lastLookedUp(bob));

		StaffSession.forget(alice.id());
		assertNull(StaffSession.lastLookedUp(alice));
		assertEquals("Notch", StaffSession.lastLookedUp(bob), "forgetting one forgot both");
	}

	@Test
	@DisplayName("an actor with no identity is not tracked at all")
	void theConsoleHasNoSession() {
		// The console holds every permission and belongs to no account, so there is nothing
		// to key a session on. Storing under a null id would make one shared session that
		// every console command reads and writes.
		Actor console = Actor.console();

		StaffSession.looked(console, "Steve_");
		assertNull(StaffSession.lastLookedUp(console));
		assertFalse(StaffSession.claim(console, "anything").allowed());
	}
}
