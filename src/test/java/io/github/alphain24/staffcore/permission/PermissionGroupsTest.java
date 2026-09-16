package io.github.alphain24.staffcore.permission;

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

	@Test
	@DisplayName("a helper can write a note, and so can everybody who inherits helper")
	void helpersWriteNotes() {
		PermissionGroups g = new PermissionGroups();
		g.players.put("h", "helper");
		g.players.put("m", "moderator");
		g.players.put("a", "admin");

		// staff.notes.* alone covers view and remove but not staff.notes, which is the node
		// /staff note and Discord's Add Note check — so the starter groups used to leave writing
		// a note to operators.
		assertTrue(g.check(null, "h", Nodes.NOTES), "the starter helper group cannot write notes");
		assertTrue(g.check(null, "m", Nodes.NOTES), "moderator does not inherit it");
		assertTrue(g.check(null, "a", Nodes.NOTES), "admin does not inherit it");
		assertTrue(g.check(null, "h", Nodes.NOTES_VIEW), "and reading them still works");
	}

	// ------------------------------------------------------------------ upgrading

	/** A file as a build before v1 wrote it: no version, and helper's staff.notes.* only. */
	private static PermissionGroups writtenBeforeV1() {
		PermissionGroups g = new PermissionGroups();
		g.configVersion = 0;
		g.groups.put("helper", new java.util.ArrayList<>(List.of(
				"staff.gui", "staff.mode", "staff.vanish", "staff.freeze", "staff.tp",
				"staff.chat", "staff.alerts", "staff.notes.*", "staff.history",
				"report.view", "security.invsee")));
		g.players.put("h", "helper");
		return g;
	}

	@Test
	@DisplayName("an untouched helper group from an older build gains staff.notes")
	void oldDefaultHelperIsUpgraded() {
		PermissionGroups g = writtenBeforeV1();
		assertFalse(g.check(null, "h", Nodes.NOTES),
				"the fixture already grants staff.notes, so this test cannot show the upgrade did");

		assertTrue(g.migrate(), "the file was not marked for rewriting");
		assertTrue(g.check(null, "h", Nodes.NOTES), "an old-default helper still cannot write notes");
		assertEquals(new PermissionGroups().groups.get("helper"), g.groups.get("helper"),
				"the upgraded group should be exactly what a new install writes");
	}

	@Test
	@DisplayName("a helper group the owner edited is never changed")
	void editedHelperIsLeftAlone() {
		PermissionGroups g = writtenBeforeV1();
		g.groups.get("helper").remove("security.invsee");
		List<String> chosen = List.copyOf(g.groups.get("helper"));

		g.migrate();
		assertEquals(chosen, g.groups.get("helper"),
				"an edited group was rewritten; the upgrade may only move a group that still "
						+ "holds the old default");
		assertFalse(g.check(null, "h", Nodes.NOTES));
	}

	@Test
	@DisplayName("the upgrade runs once, so taking staff.notes back out sticks")
	void upgradeDoesNotRepeat() {
		PermissionGroups g = writtenBeforeV1();
		g.migrate();

		// The owner removes it again, leaving a group identical to the old default. Only the
		// version tells this apart from a file that was never upgraded.
		g.groups.get("helper").remove(Nodes.NOTES);
		assertFalse(g.migrate(), "an up-to-date file was upgraded again");
		assertFalse(g.check(null, "h", Nodes.NOTES),
				"staff.notes was put back after the owner deliberately removed it");
	}

	/** A v1 file: helper as v1 wrote it, admin as every build before v2 wrote it. */
	private static PermissionGroups writtenBeforeV2() {
		PermissionGroups g = new PermissionGroups();
		g.configVersion = 1;
		g.groups.put("admin", new java.util.ArrayList<>(List.of(
				"@moderator", "staff.punish.revoke", "staff.history.clear", "security.invsee.edit",
				"grief.rollback", "grief.purge", "control.*", "analytics.stats",
				"staff.reload", "security.*", "staff.appeals", "staff.perms", "staff.replay")));
		g.players.put("a", "admin");
		g.players.put("m", "moderator");
		return g;
	}

	@Test
	@DisplayName("the Discord punishment panel is the admin group's, and a starter moderator does not get it")
	void punishPanelIsForAdmins() {
		PermissionGroups fresh = new PermissionGroups();
		fresh.players.put("a", "admin");
		fresh.players.put("m", "moderator");
		assertTrue(fresh.check(null, "a", Nodes.DISCORD_PUNISH_PANEL), "a new admin group cannot use the panel");
		assertFalse(fresh.check(null, "m", Nodes.DISCORD_PUNISH_PANEL),
				"staff.punish.* reached the panel; it has to stay outside that wildcard");

		PermissionGroups old = writtenBeforeV2();
		assertFalse(old.check(null, "a", Nodes.DISCORD_PUNISH_PANEL));
		assertTrue(old.migrate());
		assertTrue(old.check(null, "a", Nodes.DISCORD_PUNISH_PANEL), "an untouched admin group was not upgraded");
		assertEquals(fresh.groups.get("admin"), old.groups.get("admin"));

		PermissionGroups edited = writtenBeforeV2();
		edited.groups.get("admin").remove("staff.replay");
		List<String> chosen = List.copyOf(edited.groups.get("admin"));
		edited.migrate();
		assertEquals(chosen, edited.groups.get("admin"), "an edited admin group was rewritten");
	}
}
