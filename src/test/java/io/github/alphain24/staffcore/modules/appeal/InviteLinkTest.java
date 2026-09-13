package io.github.alphain24.staffcore.modules.appeal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Which configured invites become a clickable link in chat. */
class InviteLinkTest {

	@Test
	@DisplayName("an invite pasted the way people paste them becomes a link a client will open")
	void bareInvitesGetAScheme() {
		assertEquals("https://discord.gg/abc123", InviteLink.of("discord.gg/abc123").toString());
		assertEquals("https://discord.gg/abc123", InviteLink.of("  https://discord.gg/abc123 ").toString());
		assertEquals("http://example.com/appeal", InviteLink.of("http://example.com/appeal").toString());
	}

	@Test
	@DisplayName("anything that is not a web link stays text rather than becoming a dead link")
	void nonLinksAreNotLinks() {
		assertNull(InviteLink.of(""));
		assertNull(InviteLink.of(null));
		assertNull(InviteLink.of("ask in our discord"), "prose");
		assertNull(InviteLink.of("javascript:alert(1)"), "not http");
		assertNull(InviteLink.of("ftp://files.example.com"), "not http");
		assertNull(InviteLink.of("https://localhost"), "no real host");
	}
}
