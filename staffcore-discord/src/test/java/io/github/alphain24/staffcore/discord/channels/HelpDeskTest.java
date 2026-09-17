package io.github.alphain24.staffcore.discord.channels;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Players' requests from the contact channel: numbered once, one open per account, kept across restarts,
 * and what staff see about each.
 */
class HelpDeskTest {

	private static final String PLAYER = "123456789012345678";
	private static final UUID STEVE = UUID.fromString("11111111-2222-3333-4444-555555555555");
	private static final long DAY = 86_400_000L;

	@TempDir
	Path dir;

	private final AtomicLong clock = new AtomicLong(1_700_000_000_000L);

	private static HelpDesk.Request request(long id, String discordId, String linkedTo, UUID player, boolean frozen) {
		return new HelpDesk.Request(id, discordId, "steve_dc", "Steve_", player, linkedTo, true, frozen, false,
				"CASE1234", "I am frozen and do not know why", "345678901234567890", null, null, 1_700_000_000_000L,
				null, null, List.of());
	}

	@Test
	@DisplayName("numbers are given once and survive a restart; one open request per account")
	void numbersAndOpen() {
		Path file = dir.resolve("help.json");
		HelpDesk desk = new HelpDesk(file, clock::get);
		assertEquals(1, desk.take());
		assertEquals(2, desk.take());
		desk.put(request(2, PLAYER, null, STEVE, true));
		assertNotNull(desk.openFor(PLAYER));
		assertNull(desk.openFor("999999999999999999"));

		HelpDesk again = new HelpDesk(file, clock::get);
		assertEquals(3, again.take(), "a number was given twice after a restart");
		assertEquals(STEVE, again.get(2).playerId(), "the request did not survive a restart");

		again.put(again.get(2).joinedBy("Mod").joinedBy("Mod").closed("Mod", clock.get()));
		assertNull(again.openFor(PLAYER), "a closed request still counts as open");
		assertEquals(List.of("Mod"), again.get(2).staff(), "the same staff member was listed twice");
		assertTrue(again.open().isEmpty());
	}

	@Test
	@DisplayName("closed requests are forgotten after a while; open ones never are")
	void forgetting() {
		Path file = dir.resolve("help.json");
		HelpDesk desk = new HelpDesk(file, clock::get);
		desk.put(request(desk.take(), PLAYER, null, STEVE, false).closed("Mod", clock.get()));
		desk.put(request(desk.take(), "222222222222222222", null, STEVE, false));

		clock.addAndGet((HelpDesk.KEEP_DAYS + 1) * DAY);
		HelpDesk later = new HelpDesk(file, clock::get);
		assertNull(later.get(1), "an old closed request was kept");
		assertNotNull(later.get(2), "an open request was forgotten");
		assertEquals(3, later.take());
	}

	@Test
	@DisplayName("a file that cannot be read is reported, not thrown")
	void unreadable() throws Exception {
		Path file = dir.resolve("help.json");
		Files.writeString(file, "{not json", StandardCharsets.UTF_8);
		HelpDesk desk = new HelpDesk(file, clock::get);
		assertNotNull(desk.problem());
		assertEquals(1, desk.take());
	}

	@Test
	@DisplayName("staff see whether the account is the player it names, and what the server knows about them")
	void card() {
		Outbound.Message unlinked = HelpCard.message(request(7, PLAYER, null, STEVE, true), null);
		assertTrue(unlinked.embed().field("Discord").contains("<@" + PLAYER + ">"));
		assertTrue(unlinked.embed().field("Discord").contains("not linked"), unlinked.embed().field("Discord"));
		assertTrue(unlinked.embed().field("Player").contains("frozen"), unlinked.embed().field("Player"));
		assertEquals(Router.SEVERE, unlinked.embed().color(), "a frozen player's request does not stand out");
		assertEquals(List.of("sc:helpjoin:7", "sc:helpclose:7"),
				unlinked.rows().get(0).stream().map(Outbound.Button::id).toList());
		assertEquals("sc:unfreeze:" + STEVE, unlinked.rows().get(1).get(3).id());

		assertTrue(HelpCard.message(request(7, PLAYER, "Steve_", STEVE, false), null).embed().field("Discord")
				.contains("linked to this player"));
		assertTrue(HelpCard.message(request(7, PLAYER, "Alex", STEVE, false), null).embed().field("Discord")
				.contains("**Alex**, not this player"));

		Outbound.Message stranger = HelpCard.message(request(8, PLAYER, null, null, false), null);
		assertEquals(1, stranger.rows().size(), "player buttons were offered for somebody the server does not know");
		assertTrue(stranger.embed().field("Player").contains("has joined"), stranger.embed().field("Player"));

		Outbound.Message closed = HelpCard.message(request(7, PLAYER, null, STEVE, true).closed("Mod", 1L), null);
		assertTrue(closed.rows().get(0).stream().allMatch(Outbound.Button::disabled));
		assertTrue(closed.embed().field("Status").startsWith("Closed by Mod"), closed.embed().field("Status"));

		Outbound.Message hostile = HelpCard.message(new HelpDesk.Request(9, PLAYER, "x", "Steve_", null, null, false,
				false, false, null, "@everyone [free](https://evil.example)", null, null, null, 1L, null, null,
				List.of()), null);
		assertFalse(hostile.embed().description().contains("@everyone"));
		assertFalse(hostile.embed().description().contains("](https"));
	}

	@Test
	@DisplayName("the panel has one button, and a request's thread can be closed by the player")
	void panel() {
		Outbound.Message panel = ContactPanel.message();
		assertEquals(List.of(ContactPanel.BUTTON_ID), panel.rows().get(0).stream().map(Outbound.Button::id).toList());
		assertTrue(panel.embed().field("Frozen in game?") != null);
		Outbound.Message opening = ContactPanel.opening(request(4, PLAYER, null, STEVE, true));
		assertEquals("sc:helpclose:4", opening.rows().get(0).get(0).id());
		assertTrue(opening.embed().title().contains("#4"));
	}
}
