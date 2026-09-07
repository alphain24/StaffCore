package io.github.alphain24.staffcore.permission;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Who may punish whom.
 * <p>
 * The direction is the whole thing. A moderator removing a helper who has gone bad is the
 * system working; a helper removing the admin who was about to remove them is the system being
 * used against itself, and by the time anybody notices, the person who could have fixed it is
 * banned.
 * <p>
 * Testable headlessly because it compares node sets, which is the point of {@link Actor} being
 * a real boundary: the policy question "may this identity punish that one" has no world, no
 * position and no connection in it.
 */
class RankTest {

	private static Rank.Held holding(Set<String> nodes, boolean operator) {
		return new Rank.Held(nodes, operator, true);
	}

	private static final Set<String> HELPER = Set.of(Nodes.STAFF_GUI, Nodes.VANISH);
	private static final Set<String> MODERATOR =
			Set.of(Nodes.STAFF_GUI, Nodes.VANISH, Nodes.BAN, Nodes.MUTE);
	private static final Set<String> ADMIN =
			Set.of(Nodes.STAFF_GUI, Nodes.VANISH, Nodes.BAN, Nodes.MUTE, Nodes.UNPUNISH,
					Nodes.ROLLBACK);

	@Test
	@DisplayName("more is more: a superset outranks what it contains")
	void downwardsIsAllowed() {
		assertTrue(Rank.outranks(holding(ADMIN, false), holding(MODERATOR, false)));
		assertTrue(Rank.outranks(holding(MODERATOR, false), holding(HELPER, false)));
		assertTrue(Rank.outranks(holding(HELPER, false), holding(Set.of(), false)));
	}

	@Test
	@DisplayName("equal is not above, so two admins cannot remove each other")
	void sidewaysIsRefused() {
		// The case that matters. Two people with the same authority disagreeing is a thing to
		// resolve by talking, not by whoever types first.
		assertFalse(Rank.outranks(holding(ADMIN, false), holding(ADMIN, false)));
		assertFalse(Rank.outranks(holding(Set.of(), false), holding(Set.of(), false)));
	}

	@Test
	@DisplayName("upwards is refused")
	void upwardsIsRefused() {
		assertFalse(Rank.outranks(holding(HELPER, false), holding(ADMIN, false)));
		assertFalse(Rank.outranks(holding(MODERATOR, false), holding(ADMIN, false)));
	}

	@Test
	@DisplayName("two different sets have no ordering, and one is not invented")
	void incomparableIsTreatedAsEqual() {
		// A staff member who holds rollback but not bans, and one who holds bans but not
		// rollback. Picking a winner would be inventing exactly the authority this checks.
		Rank.Held rollbacker = holding(Set.of(Nodes.STAFF_GUI, Nodes.ROLLBACK), false);
		Rank.Held banner = holding(Set.of(Nodes.STAFF_GUI, Nodes.BAN), false);

		assertFalse(Rank.outranks(rollbacker, banner));
		assertFalse(Rank.outranks(banner, rollbacker));
	}

	@Test
	@DisplayName("an operator is not outranked by a non-operator holding more nodes")
	void opIsItsOwnRung() {
		// Operator is not a node and cannot be compared as one. Somebody granted every
		// StaffCore node is still not the person who can edit the server files.
		assertFalse(Rank.outranks(holding(ADMIN, false), holding(Set.of(), true)));
		assertTrue(Rank.outranks(holding(ADMIN, true), holding(ADMIN, false)));
	}

	@Test
	@DisplayName("you cannot punish yourself, whatever you hold")
	void noSelfPunishment() {
		UUID id = UUID.randomUUID();
		Actor self = Actor.of(id, "Alice", Actor.Source.PLAYER, ADMIN);

		var ranking = Rank.mayPunish(self, null, id, "Alice");
		assertFalse(ranking.allowed());
		assertNotNull(ranking.refusal());
		assertTrue(ranking.refusal().contains("yourself"));
	}

	@Test
	@DisplayName("the console is exempt, and an unresolvable name is not")
	void theEscapeHatchIsNarrow() {
		// Both arrive with no UUID. The console is the server owner's own hand and is the
		// escape hatch for the deadlock this rule otherwise creates — two admins who have
		// fallen out and neither able to act. A name nobody could resolve is an action
		// attributed to somebody we cannot confirm was there, and treating it as the console
		// would make the whole guard skippable by acting under an offline name.
		assertTrue(Rank.mayPunish(Actor.console(), null, UUID.randomUUID(), "Bob").allowed());

		var named = Rank.mayPunish(Actor.named("Alice"), null, UUID.randomUUID(), "Bob");
		assertFalse(named.allowed());
		assertTrue(named.refusal().contains("could not be identified"));
	}

	@Test
	@DisplayName("an ordinary player is punishable by anyone with the node")
	void nonStaffAreNotProtected() {
		// The commonest case by a very long way, and the one that must not become slower.
		Actor helper = Actor.of(UUID.randomUUID(), "Helper", Actor.Source.PLAYER, HELPER);

		assertTrue(Rank.outranks(Rank.of(helper), holding(Set.of(), false)));
	}
}
