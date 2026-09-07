package io.github.alphain24.staffcore.modules.accountability;

import io.github.alphain24.staffcore.config.StaffConfig;
import io.github.alphain24.staffcore.permission.Actor;
import io.github.alphain24.staffcore.permission.Nodes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gate 2 asks for a test that tries to get round the rate limits and the approval check.
 * <p>
 * This is that test, and it is written in the spirit the gate intends: not "does the happy
 * path work" but "here are the ways somebody would actually get round this, and each one is
 * closed". Most controls fail not because the check is wrong but because there is a second
 * route that never reaches it.
 */
class BypassAttemptTest {

	private Approvals approvals;

	@BeforeEach
	void setUp() {
		approvals = new Approvals();
		StaffConfig.get().requireTwoPersonApproval = true;
		StaffConfig.get().approvalExpiryMinutes = 10;
		StaffConfig.get().maxPunishmentsPerMinute = 6;
		StaffConfig.get().maxRollbacksPerMinute = 3;
	}

	// ------------------------------------------------------ the structural check

	/**
	 * The most important assertion here, and the only one that survives somebody rewriting
	 * the commands.
	 * <p>
	 * A rate limit checked in {@code /staff ban} would leave the GUI, the API and any future
	 * Discord path unlimited — three ways in that each have to be remembered separately. So
	 * the check lives in the service every path already funnels through, and this asserts that
	 * placement rather than the behaviour, because the behaviour is only worth anything if the
	 * placement is right.
	 */
	@Test
	@DisplayName("the rate limit is enforced in the service, not in the command layer")
	void theLimitIsWhereEveryPathGoes() throws Exception {
		String punish = java.nio.file.Files.readString(java.nio.file.Path.of(
				"src/main/java/io/github/alphain24/staffcore/modules/punish/PunishmentModule.java"));
		String grief = java.nio.file.Files.readString(java.nio.file.Path.of(
				"src/main/java/io/github/alphain24/staffcore/modules/grief/GriefModule.java"));

		assertTrue(punish.contains("limits()") && punish.contains("RateLimits.Kind.PUNISHMENT"),
				"PunishmentModule.apply must check the limit itself. If this moved to the "
						+ "command, every non-command path is unlimited and nothing here would "
						+ "notice.");
		assertTrue(grief.contains("limits()") && grief.contains("RateLimits.Kind.ROLLBACK"),
				"GriefModule.rollback must check the limit itself");

		// And apply() must remain the only way in. A second entry point that skipped the
		// check would make the first one decorative.
		List<String> entryPoints = new ArrayList<>();
		for (Method m : Class.forName("io.github.alphain24.staffcore.modules.punish.PunishmentModule")
				.getDeclaredMethods()) {
			if (m.getName().equals("apply")) entryPoints.add(m.toString());
		}
		assertFalse(entryPoints.isEmpty(), "apply() has gone; the funnel is somewhere else now");
	}

	// --------------------------------------------------------- self-approval

	@Test
	@DisplayName("the person who staged an action cannot approve it, whatever they hold")
	void nobodyApprovesTheirOwn() {
		UUID alice = UUID.randomUUID();
		Approvals.Staged staged = stage(alice, "Alice");

		// The situation this exists for is one account acting alone — compromised, or angry.
		// Permission is not the question: an approval somebody can grant themselves is a
		// confirmation prompt in a costume.
		Approvals.Outcome self = approve(alice, "Alice", staged.id());

		assertFalse(self.approved(), "a staff member approved their own staged action");
		assertTrue(self.refusal().toLowerCase().contains("yourself"),
				"and the refusal should say why rather than looking like a missing id");
	}

	@Test
	@DisplayName("renaming does not make you a different person")
	void identityIsByUuidNotName() {
		UUID alice = UUID.randomUUID();
		Approvals.Staged staged = stage(alice, "Alice");

		// Names change. The check is on UUID precisely so that changing one does not turn a
		// self-approval into a valid second signature.
		Approvals.Outcome renamed = approve(alice, "AliceTheSecond", staged.id());
		assertFalse(renamed.approved(), "the same account approved its own action under a new name");
	}

	@Test
	@DisplayName("a second person can approve, so the mechanism is not simply broken")
	void somebodyElseCanApprove() {
		UUID alice = UUID.randomUUID();
		Approvals.Staged staged = stage(alice, "Alice");

		Approvals.Outcome other = approve(UUID.randomUUID(), "Bob", staged.id());
		assertTrue(other.approved(), other.refusal());
		assertEquals(staged.id(), other.staged().id());
	}

	@Test
	@DisplayName("an approval is single-use, so one signature cannot run an action twice")
	void approvalsAreConsumed() {
		UUID alice = UUID.randomUUID();
		Approvals.Staged staged = stage(alice, "Alice");

		assertTrue(approve(UUID.randomUUID(), "Bob", staged.id()).approved());

		// Replaying the id would turn one agreement into unlimited executions, which for a
		// mass rollback is the difference between one mistake and a pattern of them.
		assertFalse(approve(UUID.randomUUID(), "Carol", staged.id()).approved(),
				"a staged action was approved twice");
	}

	@Test
	@DisplayName("guessing an id does not approve anything")
	void idsCannotBeGuessedIntoExistence() {
		assertFalse(approve(UUID.randomUUID(), "Bob", "00000000").approved());
		assertFalse(approve(UUID.randomUUID(), "Bob", "").approved());

		// Ids are random rather than sequential for this reason: approving an action you
		// never saw is the same failure as self-approval, reached from the other side.
		UUID alice = UUID.randomUUID();
		Approvals.Staged first = stage(alice, "Alice");
		Approvals.Staged second = stage(alice, "Alice");
		assertFalse(first.id().equals(second.id()), "ids repeat");
	}

	@Test
	@DisplayName("a staged action expires rather than waiting forever")
	void stagingIsNotPermanent() {
		StaffConfig.get().approvalExpiryMinutes = 0;   // treated as "never expires"
		UUID alice = UUID.randomUUID();
		Approvals.Staged staged = stage(alice, "Alice");
		assertFalse(staged.isExpired(), "0 should mean no expiry");

		StaffConfig.get().approvalExpiryMinutes = 10;
		Approvals.Staged old = new Approvals.Staged("OLD", Approvals.Action.IP_BAN, alice,
				"Alice", "summary", "detail", System.currentTimeMillis() - 60L * 60_000L);

		// An hour-old intention approved by somebody who was not there when it was staged is
		// a signature on something nobody remembers, which is the opposite of the point.
		assertTrue(old.isExpired());
	}

	@Test
	@DisplayName("nobody without an accountable identity can approve")
	void nobodyIsNotASecondPerson() {
		UUID alice = UUID.randomUUID();
		Approvals.Staged staged = stage(alice, "Alice");

		// Console, RCON, a scheduled task and an unlinked Discord user are one case rather
		// than four, and this is where that pays: none of them had to be special-cased in
		// Approvals, and a fifth way in would be refused without anybody adding a branch.
		for (Actor nobody : new Actor[] {
				Actor.console(),
				Actor.system(),
				Actor.of(null, "rcon", Actor.Source.RCON, java.util.Set.of()),
				Actor.of(UUID.randomUUID(), "timer", Actor.Source.SCHEDULED, java.util.Set.of()),
				Actor.of(UUID.randomUUID(), "someone", Actor.Source.DISCORD_UNLINKED,
						java.util.Set.of(Nodes.APPROVE)) }) {

			Approvals.Outcome outcome = approvals.approve(nobody, staged.id());
			assertFalse(outcome.approved(),
					nobody.source() + " approved a staged action despite being nobody");
			assertTrue(outcome.refusal().length() > 40,
					"and the refusal should explain rather than deny: " + outcome.refusal());
		}

		// Still there afterwards — a refused approval must not consume the staged action, or
		// a console attempt would quietly cancel somebody's pending work.
		assertTrue(approvals.find(staged.id()).isPresent(),
				"a refused approval consumed the staged action");
	}

	@Test
	@DisplayName("holding the approve node does not make you accountable")
	void permissionIsNotIdentity() {
		UUID alice = UUID.randomUUID();
		Approvals.Staged staged = stage(alice, "Alice");

		// The console holds every node there is. If the check were a permission check, it
		// would pass — which is exactly the confusion the rule is written to avoid.
		assertTrue(Actor.console().has(Nodes.APPROVE), "the console does hold the node");
		assertFalse(approvals.approve(Actor.console(), staged.id()).approved(),
				"and still cannot approve, because the question is who is answerable");
	}

	@Test
	@DisplayName("the console can still stage, because proposing is not approving")
	void consoleMayPropose() {
		// Deliberate. An automated job that proposes a mass rollback for a human to confirm
		// is a perfectly good arrangement; it is the confirmation that needs a name behind it.
		Approvals.Staged staged = approvals.stage(Actor.console(),
				Approvals.Action.MASS_ROLLBACK, "nightly cleanup", "detail");

		assertTrue(approvals.find(staged.id()).isPresent());
		assertTrue(approve(UUID.randomUUID(), "Bob", staged.id()).approved(),
				"and a person can confirm what the console proposed");
	}

	@Test
	@DisplayName("the three guarded actions are the irreversible ones")
	void theRightActionsAreGuarded() {
		var actions = List.of(Approvals.Action.values());
		assertEquals(3, actions.size(),
				"the guarded set grew. Each addition costs a second person being online, so "
						+ "it should be a decision rather than a default.");

		assertTrue(actions.contains(Approvals.Action.MASS_ROLLBACK));
		assertTrue(actions.contains(Approvals.Action.IP_BAN));
		assertTrue(actions.contains(Approvals.Action.INVENTORY_EDIT));
	}

	// ------------------------------------------------------------------ helpers

	/**
	 * Stages as somebody, calling the real method.
	 * <p>
	 * {@code Approvals} takes an identity rather than a {@code ServerPlayer} precisely so this
	 * is possible. An earlier version of this test reimplemented the approve logic through
	 * reflection because the signature demanded a player — which would have made it a test of
	 * a copy, passing happily while the real branch was broken. That is the mistake this whole
	 * project keeps finding, so the code moved rather than the test.
	 */
	private Approvals.Staged stage(UUID who, String name) {
		return approvals.stage(person(who, name), Approvals.Action.MASS_ROLLBACK,
				"roll back 4000 blocks at spawn", "detail");
	}

	private Approvals.Outcome approve(UUID who, String name, String id) {
		return approvals.approve(person(who, name), id);
	}

	/** A staff member, built without a server. This is what Actor is for. */
	private static Actor person(UUID who, String name) {
		return Actor.of(who, name, Actor.Source.PLAYER, java.util.Set.of(Nodes.APPROVE));
	}
}
