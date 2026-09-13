package io.github.alphain24.staffcore.modules.appeal;

import io.github.alphain24.staffcore.modules.punish.Punishment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The parts of the appeal window that decide things without a server: which invites become a
 * button, and that the login-stage mark cannot outlive the check it was set for.
 */
class BanNoticeTest {

	/**
	 * An appealable ban, so that {@code defer} would say yes if the login-stage mark were set.
	 * The type is left null: naming it loads the item registry, which needs a bootstrapped game,
	 * and nothing checked here reads it.
	 */
	private static Punishment ban() {
		return new Punishment(1, UUID.randomUUID(), "Steve", "Mod", null,
				"griefing", 0, null, true, null, null, null, null, "ABCDEFGH");
	}

	@Test
	@DisplayName("an invite pasted the way people paste them becomes a link a client will open")
	void bareInvitesGetAScheme() {
		assertEquals("https://discord.gg/abc123",
				BanNotice.inviteLink("discord.gg/abc123").toString());
		assertEquals("https://discord.gg/abc123",
				BanNotice.inviteLink("  https://discord.gg/abc123 ").toString());
		assertEquals("http://example.com/appeal",
				BanNotice.inviteLink("http://example.com/appeal").toString());
	}

	@Test
	@DisplayName("anything that is not a web link stays text rather than becoming a broken button")
	void nonLinksAreNotButtons() {
		assertNull(BanNotice.inviteLink(""));
		assertNull(BanNotice.inviteLink(null));
		assertNull(BanNotice.inviteLink("ask in our discord"), "prose");
		assertNull(BanNotice.inviteLink("javascript:alert(1)"), "not http");
		assertNull(BanNotice.inviteLink("ftp://files.example.com"), "not http");
		assertNull(BanNotice.inviteLink("https://localhost"), "no real host");
	}

	@Test
	@DisplayName("outside the login-stage check, a ban is never deferred")
	void noDeferOutsideLogin() {
		// This is the end-of-setup check's view of the world, and the one that must refuse.
		assertFalse(BanNotice.defer(UUID.randomUUID(), ban()));
	}

	@Test
	@DisplayName("a login check that throws does not leave the mark behind")
	void theMarkIsClearedWhenTheCheckThrows() {
		assertThrows(IllegalStateException.class, () -> BanNotice.atLogin(() -> {
			throw new IllegalStateException("vanilla blew up");
		}));

		// Same thread, straight after. If the mark had survived, the next check here —
		// potentially the end-of-setup one — would let a banned player through.
		assertFalse(BanNotice.defer(UUID.randomUUID(), ban()));
	}

	@Test
	@DisplayName("the mark is gone once the login check returns")
	void theMarkIsClearedAfterwards() {
		BanNotice.atLogin(() -> null);
		assertFalse(BanNotice.defer(UUID.randomUUID(), ban()));
	}
}
