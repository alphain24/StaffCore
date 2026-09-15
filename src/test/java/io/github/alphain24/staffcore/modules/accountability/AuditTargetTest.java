package io.github.alphain24.staffcore.modules.accountability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The staff log names the player an action was about — and only when it really is a player.
 */
class AuditTargetTest {

	private static final Predicate<String> KNOWN = Set.of("Steve_", "Alex")::contains;

	@Test
	@DisplayName("the player straight after the subcommand is the target")
	void nameAfterSubcommand() {
		assertEquals("Steve_", StaffAudit.targetIn("/staff ban Steve_ x-ray and flying", KNOWN));
		assertEquals("Alex", StaffAudit.targetIn("/staff tempmute Alex 1h spam", KNOWN));
	}

	@Test
	@DisplayName("a word that is not a known player is never taken for one")
	void onlyRealPlayers() {
		// A case id, a radius and the first word of a reason all sit where a name would.
		assertNull(StaffAudit.targetIn("/staff case ABCD2345 actioned", KNOWN));
		assertNull(StaffAudit.targetIn("/staff rollback 10 1h", KNOWN));
		assertNull(StaffAudit.targetIn("/staff status", KNOWN));
		// A reason that happens to contain a player's name, far enough in, is not the target.
		assertNull(StaffAudit.targetIn("/staff case open griefing because Steve_ said so", KNOWN));
	}

	@Test
	@DisplayName("screen and Discord actions are read the same way")
	void panelAndDiscord() {
		assertEquals("Steve_", StaffAudit.targetIn("[discord] freeze Steve_ (via Discord: dc 123)", KNOWN));
		assertEquals("Alex", StaffAudit.targetIn("[discord] claim Alex report #4 (via Discord: dc 123)", KNOWN));
		assertNull(StaffAudit.targetIn(null, KNOWN));
		assertNull(StaffAudit.targetIn("", KNOWN));
	}
}
