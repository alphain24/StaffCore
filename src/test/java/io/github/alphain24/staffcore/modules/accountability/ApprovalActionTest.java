package io.github.alphain24.staffcore.modules.accountability;

import io.github.alphain24.staffcore.config.StaffConfig;
import io.github.alphain24.staffcore.permission.Actor;
import io.github.alphain24.staffcore.permission.Nodes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Approving a staged action does the action.
 * <p>
 * It used not to: the approval was recorded, "approved" was printed, and nothing anywhere went
 * on to carry out what had been staged.
 */
class ApprovalActionTest {

	private Approvals approvals;

	@BeforeEach
	void setUp() {
		approvals = new Approvals();
		StaffConfig.get().requireTwoPersonApproval = true;
		StaffConfig.get().approvalExpiryMinutes = 10;
	}

	private static Actor person(String name) {
		return Actor.of(UUID.randomUUID(), name, Actor.Source.PLAYER, Set.of(Nodes.APPROVE));
	}

	@Test
	@DisplayName("a second person's approval runs the action, as them")
	void approvalRunsIt() {
		Actor alice = person("Alice");
		Actor bob = person("Bob");
		AtomicReference<String> ranAs = new AtomicReference<>();

		var staged = approvals.stage(alice, Approvals.Action.IP_BAN, "ban", "",
				approver -> ranAs.set(approver.name()));
		assertNull(ranAs.get(), "staging ran the action before anybody approved it");

		var outcome = approvals.approve(bob, staged.id());
		assertTrue(outcome.approved());
		assertTrue(approvals.run(outcome, bob));
		assertEquals("Bob", ranAs.get());
	}

	@Test
	@DisplayName("a refused approval runs nothing")
	void refusedRunsNothing() {
		Actor alice = person("Alice");
		AtomicReference<String> ranAs = new AtomicReference<>();

		var staged = approvals.stage(alice, Approvals.Action.IP_BAN, "ban", "",
				approver -> ranAs.set(approver.name()));
		var self = approvals.approve(alice, staged.id());

		assertFalse(self.approved());
		assertFalse(approvals.run(self, alice));
		assertNull(ranAs.get(), "the stager approving their own action carried it out");
	}

	@Test
	@DisplayName("a cancelled action cannot be run by approving it afterwards")
	void cancelledStaysCancelled() {
		Actor alice = person("Alice");
		AtomicReference<String> ranAs = new AtomicReference<>();

		var staged = approvals.stage(alice, Approvals.Action.IP_BAN, "ban", "",
				approver -> ranAs.set(approver.name()));
		approvals.cancel(staged.id(), alice.id());

		var late = approvals.approve(person("Bob"), staged.id());
		assertFalse(late.approved());
		assertNull(ranAs.get());
	}
}
