package io.github.alphain24.staffcore.permission;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The five things this resolver has to get right to be worth keeping.
 * <p>
 * Wildcards, {@code @other} inheritance, a player-to-group map and a default group add up to
 * a small permissions engine, and the case for writing one instead of narrowing to flat
 * explicit lists rests entirely on it being correct. This is that case, made in the only form
 * that means anything.
 * <p>
 * <b>Three of these fail against the resolver as it stood.</b> Not the way the brief
 * predicted — wildcard matching was sound, and a helper never inherited
 * {@code staff.punish.*}, because inheritance runs from moderator to helper and not back. The
 * real defect was in the other direction and worse: three separate paths answered "this file
 * has no opinion" for a player it plainly did have an opinion about, and every one of those
 * fell through to the vanilla operator level. A typo in a group name did not deny somebody,
 * it promoted them.
 */
class PermissionResolutionTest {

	private static final UUID TRAINEE = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
	private static final UUID BOSS = UUID.fromString("00000000-0000-0000-0000-0000000000a2");

	private PermissionGroups groups() {
		PermissionGroups g = new PermissionGroups();
		g.players.put(TRAINEE.toString(), "helper");
		g.players.put(BOSS.toString(), "admin");
		return g;
	}

	@Test
	@DisplayName("staff.punish.* does not grant staff.reload")
	void punishWildcardStopsAtItsOwnPrefix() {
		PermissionGroups g = groups();
		g.players.put("mod", "moderator");

		// A moderator holds staff.punish.* and should hold everything under it.
		assertTrue(g.check(null, "mod", "staff.punish.ban"),
				"staff.punish.* must cover what is actually beneath it");

		// And nothing that merely shares the first segment. This is the one the brief
		// predicted was broken; it was not, and pinning it is what keeps it that way.
		assertFalse(g.check(null, "mod", Nodes.RELOAD),
				"staff.punish.* reaching staff.reload would hand config control to every "
						+ "moderator");
		assertFalse(g.check(null, "mod", Nodes.ROLLBACK));
		assertFalse(g.check(null, "mod", Nodes.PERMS_ADMIN));
	}

	@Test
	@DisplayName("a trainee does not inherit upwards")
	void inheritanceRunsOneWay() {
		PermissionGroups g = groups();

		assertTrue(g.check(TRAINEE, "trainee", Nodes.INVSEE), "helper's own nodes");
		assertFalse(g.check(TRAINEE, "trainee", "staff.punish.ban"),
				"helper is inherited *by* moderator, not the other way round");
		assertFalse(g.check(TRAINEE, "trainee", Nodes.UNPUNISH));
		assertFalse(g.check(TRAINEE, "trainee", Nodes.INVSEE_EDIT));

		// And the boss does inherit downwards, or the test above proves nothing.
		assertTrue(g.check(BOSS, "boss", Nodes.INVSEE), "admin -> moderator -> helper");
	}

	@Test
	@DisplayName("an inheritance cycle is reported at load, not survived at resolution")
	void cyclesAreRefusedUpFront() {
		PermissionGroups g = new PermissionGroups();
		g.groups.put("a", new ArrayList<>(List.of("@b", "staff.gui")));
		g.groups.put("b", new ArrayList<>(List.of("@a", "staff.logs")));

		List<String> problems = g.validate();
		assertTrue(problems.stream().anyMatch(p -> p.contains("cycle")),
				"a cycle has to be named while somebody is looking at the log; surviving it "
						+ "quietly means resolving to a partial node set, which presents as a "
						+ "permission that works sometimes. Got: " + problems);

		// Still must not hang, because being reported does not stop it being loaded.
		g.players.put("looped", "a");
		assertTrue(g.check(null, "looped", "staff.gui"));
	}

	@Test
	@DisplayName("an unknown group denies rather than falling through to operator")
	void unknownGroupsDeny() {
		PermissionGroups g = groups();
		g.players.put("typo", "moderater");   // one letter out

		Boolean answer = g.check(null, "typo", Nodes.PUNISH);
		assertEquals(Boolean.FALSE, answer,
				"null here means 'no opinion', and the caller reads that as 'ask whether they "
						+ "are op' — so a misspelled group promoted the player instead of "
						+ "denying them, while the file looked correctly filled in");

		assertTrue(g.validate().stream().anyMatch(p -> p.contains("moderater")),
				"and it should be named at load rather than found by a staff member");
	}

	@Test
	@DisplayName("an empty defaultGroup grants nothing from this file")
	void emptyDefaultGroupGrantsNothing() {
		PermissionGroups g = groups();
		g.defaultGroup = "";

		// "No opinion" is the correct answer here and is genuinely different from "denied":
		// with operatorsBypass on, an admin who has not yet added themselves must still be
		// able to use the mod. What must not happen is this file inventing a grant.
		assertEquals(null, g.check(null, "stranger", Nodes.PUNISH),
				"an unlisted player with no default group is not something this file has an "
						+ "opinion about");

		g.defaultGroup = "helper";
		assertEquals(Boolean.TRUE, g.check(null, "stranger", Nodes.INVSEE),
				"and with a default set, it does");
		assertEquals(Boolean.FALSE, g.check(null, "stranger", Nodes.ROLLBACK));
	}

	@Test
	@DisplayName("a group defined with no nodes grants nothing rather than everything")
	void anEmptyGroupIsAnAnswer() {
		PermissionGroups g = groups();
		g.groups.put("suspended", new ArrayList<>());
		g.players.put("benched", "suspended");

		assertEquals(Boolean.FALSE, g.check(null, "benched", Nodes.PUNISH),
				"a deliberately emptied group is the clearest possible statement that "
						+ "somebody should hold nothing; falling through to operator level "
						+ "turns it into the opposite");
	}

	@Test
	@DisplayName("explain names the group, the nodes, and what is wrong")
	void explainAnswersWhyCanTheyDoThat() {
		PermissionGroups g = groups();

		var boss = g.explain(BOSS, "boss");
		assertTrue(boss.groupExists());
		assertEquals("admin", boss.group());
		assertTrue(boss.grants().stream().anyMatch(n -> n.startsWith(Nodes.INVSEE)),
				"inherited nodes have to show, or the answer is only half of why");
		assertTrue(boss.grants().stream().anyMatch(n -> n.contains("wildcard")),
				"a wildcard should say what it covers rather than appearing as a bare string");

		g.players.put("typo", "moderater");
		var broken = g.explain(null, "typo");
		assertFalse(broken.groupExists());
		assertTrue(broken.problems().stream().anyMatch(p -> p.contains("moderater")));
		assertTrue(broken.grants().isEmpty());
	}

	@Test
	@DisplayName("validate finds a dangling inherit and a player in a group that does not exist")
	void validateCatchesTheQuietOnes() {
		PermissionGroups g = new PermissionGroups();
		g.groups.put("orphan", new ArrayList<>(List.of("@missing", "staff.gui")));
		g.players.put("nobody", "alsomissing");
		g.defaultGroup = "notagroup";

		List<String> problems = g.validate();
		assertTrue(problems.stream().anyMatch(p -> p.contains("missing")), problems.toString());
		assertTrue(problems.stream().anyMatch(p -> p.contains("alsomissing")), problems.toString());
		assertTrue(problems.stream().anyMatch(p -> p.contains("notagroup")), problems.toString());
	}
}
