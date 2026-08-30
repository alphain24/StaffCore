package dev.lebron.staffcore.permission;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The fallback permission groups, used when no permissions mod is installed.
 * <p>
 * Tested against the resolver directly rather than through the file, because the file is the
 * uninteresting half. What matters is that inheritance resolves, wildcards match the right
 * things, an unlisted player gets a distinguishable "no opinion", and a cycle in the config
 * does not hang the server.
 */
class PermissionGroupsTest {

	private static final UUID SOMEBODY = UUID.fromString("00000000-0000-0000-0000-000000000001");

	private PermissionGroups groups() {
		PermissionGroups g = new PermissionGroups();
		g.players.put(SOMEBODY.toString(), "moderator");
		g.players.put("named", "helper");
		return g;
	}

	@Test
	@DisplayName("a group grants its own nodes")
	void ownNodes() {
		assertTrue(groups().check(SOMEBODY, "Somebody", Nodes.PUNISH));
	}

	@Test
	@DisplayName("inheritance pulls in the parent group")
	void inheritance() {
		// moderator is "@helper plus", so a helper node must resolve through it.
		assertTrue(groups().check(SOMEBODY, "Somebody", Nodes.STAFF_GUI));
	}

	@Test
	@DisplayName("a wildcard covers everything beneath it")
	void wildcards() {
		assertTrue(groups().check(SOMEBODY, "Somebody", "staff.punish.ban"),
				"staff.punish.* should cover it");
	}

	@Test
	@DisplayName("a wildcard does not cover a sibling prefix")
	void wildcardsAreNotPrefixes() {
		PermissionGroups g = new PermissionGroups();
		g.groups.put("test", new java.util.ArrayList<>(List.of("staff.punish.*")));
		g.players.put("x", "test");

		assertFalse(g.check(null, "x", "staff.punishment"),
				"staff.punish.* must not match staff.punishment");
	}

	@Test
	@DisplayName("a node no group mentions is denied, not granted")
	void unknownNodeDenied() {
		assertFalse(groups().check(SOMEBODY, "Somebody", "some.node.nobody.has"));
	}

	@Test
	@DisplayName("an unlisted player produces no opinion, so op level can still decide")
	void unlistedIsNull() {
		// Null rather than false on purpose: "not configured" and "configured to be denied"
		// are different answers, and collapsing them would mean writing the file at all
		// silently revoked whatever the op fallback used to grant.
		assertNull(groups().check(UUID.randomUUID(), "Stranger", Nodes.STAFF_GUI));
	}

	@Test
	@DisplayName("players can be keyed by name as well as uuid")
	void byName() {
		assertTrue(groups().check(UUID.randomUUID(), "NAMED", Nodes.STAFF_GUI),
				"lookup should be case-insensitive");
	}

	@Test
	@DisplayName("a defaultGroup applies to everyone unlisted")
	void defaultGroup() {
		PermissionGroups g = groups();
		g.defaultGroup = "helper";
		assertTrue(g.check(UUID.randomUUID(), "Stranger", Nodes.STAFF_GUI));
	}

	@Test
	@DisplayName("a cycle in the config terminates instead of hanging")
	void cyclicInheritance() {
		PermissionGroups g = new PermissionGroups();
		g.groups.put("a", new java.util.ArrayList<>(List.of("@b", "node.a")));
		g.groups.put("b", new java.util.ArrayList<>(List.of("@a", "node.b")));
		g.players.put("x", "a");

		// A typo in a config file should cost a wrong answer at worst, never a hung server.
		assertTrue(g.check(null, "x", "node.b"));
		assertFalse(g.check(null, "x", "node.missing"));
	}

	@Test
	@DisplayName("the shipped groups escalate rather than overlap")
	void shippedGroupsEscalate() {
		PermissionGroups g = new PermissionGroups();
		g.players.put("h", "helper");
		g.players.put("a", "admin");

		assertFalse(g.check(null, "h", Nodes.ROLLBACK), "a helper should not roll back");
		assertTrue(g.check(null, "a", Nodes.ROLLBACK), "an admin should");
		assertEquals(3, g.groups.size(), "helper, moderator, admin");
	}
}
