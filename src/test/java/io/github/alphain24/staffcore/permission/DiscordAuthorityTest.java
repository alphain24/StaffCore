package io.github.alphain24.staffcore.permission;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gate 5's first condition, as a rule: Discord cannot do anything the same account could not do
 * in game — and a Discord role, however it was obtained, cannot add to that.
 * <p>
 * Each test is a way somebody would try to get more from Discord than they hold, and each is
 * closed by the same intersection rather than by a special case.
 */
class DiscordAuthorityTest {

	private static final Set<String> KNOWN = Actor.all();

	private static Rank.Held holds(String... nodes) {
		return new Rank.Held(Set.of(nodes), false, true);
	}

	private static DiscordAuthority.Grant grant(Rank.Held inGame, String... roleNodes) {
		return DiscordAuthority.grant(true, false, inGame, Set.of(roleNodes), KNOWN);
	}

	@Test
	@DisplayName("a role naming a node the account does not hold in game grants nothing")
	void aRoleCannotAdd() {
		// The compromise the brief names: somebody hands a Discord role "admin" to a helper.
		var result = grant(holds(Nodes.NOTES), Nodes.BAN, Nodes.NOTES, Nodes.APPROVE);
		assertEquals(Set.of(Nodes.NOTES), result.nodes());
		assertFalse(result.holds(Nodes.BAN));
		assertFalse(result.holds(Nodes.APPROVE));
	}

	@Test
	@DisplayName("holding a node in game is not enough: the role mapping has to allow it too")
	void theGameAloneDoesNotGrantEither() {
		var result = grant(holds(Nodes.BAN, Nodes.MUTE, Nodes.HISTORY), Nodes.HISTORY);
		assertEquals(Set.of(Nodes.HISTORY), result.nodes(),
				"an owner who mapped only history has said bans stay in game");
	}

	@Test
	@DisplayName("being an operator in game grants nothing on its own")
	void operatorIsNotAWildcard() {
		// Held.operator is display. If it were read as "holds everything", every opped account
		// would hold every node its roles mention, whatever the permission file says about them.
		var result = grant(new Rank.Held(Set.of(), true, true), Nodes.BAN, Nodes.PERMS_ADMIN);
		assertTrue(result.nodes().isEmpty(), "an op flag became permissions: " + result.nodes());
	}

	@Test
	@DisplayName("an unlinked account holds nothing, whatever its roles say")
	void unlinkedHoldsNothing() {
		var result = DiscordAuthority.grant(false, false, null, Set.copyOf(KNOWN), KNOWN);
		assertTrue(result.nodes().isEmpty());
		assertNotNull(result.limitation());
		assertTrue(result.limitation().contains("/staff discord link"),
				"the refusal should say how to fix it: " + result.limitation());
	}

	@Test
	@DisplayName("a banned account can do nothing from Discord")
	void bannedHoldsNothing() {
		var result = DiscordAuthority.grant(true, true, holds(Nodes.BAN, Nodes.UNPUNISH),
				Set.of(Nodes.BAN, Nodes.UNPUNISH), KNOWN);
		assertTrue(result.nodes().isEmpty(),
				"a banned staff member could still act from Discord, including unbanning themselves");
	}

	@Test
	@DisplayName("permissions that cannot be read are treated as none, not as the roles")
	void unknownIsNotYes() {
		var result = grant(new Rank.Held(Set.of(Nodes.BAN), false, false), Nodes.BAN);
		assertTrue(result.nodes().isEmpty());
		assertTrue(result.limitation().contains("online"), result.limitation());
	}

	@Test
	@DisplayName("a mapped string that is not a StaffCore node grants nothing, even if the game says yes")
	void onlyRealNodes() {
		var result = DiscordAuthority.grant(true, false, holds("staff.*", Nodes.BAN),
				Set.of("staff.*", "*", Nodes.BAN), KNOWN);
		assertEquals(Set.of(Nodes.BAN), result.nodes());
	}

	@Test
	@DisplayName("a user who holds nothing is told why")
	void emptyIsExplained() {
		assertNotNull(grant(holds(Nodes.BAN)).limitation(), "no roles mapped");
		assertNotNull(grant(holds(), Nodes.BAN).limitation(), "roles mapped, game says no");
		assertEquals(null, grant(holds(Nodes.BAN), Nodes.BAN).limitation());
	}
}
