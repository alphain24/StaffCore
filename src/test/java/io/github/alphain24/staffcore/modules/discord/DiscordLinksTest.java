package io.github.alphain24.staffcore.modules.discord;

import io.github.alphain24.staffcore.StaffCore;
import io.github.alphain24.staffcore.storage.Storage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Linking a Discord account: the proof it rests on, and the ways somebody would try to get round
 * it.
 */
class DiscordLinksTest {

	@TempDir
	Path world;

	private Storage storage;
	private final AtomicLong now = new AtomicLong(1_000_000L);
	private DiscordLinks links;

	@BeforeEach
	void open() {
		storage = StaffCore.storage();
		storage.open(world);
		links = new DiscordLinks(now::get);
	}

	@AfterEach
	void close() {
		storage.close();
	}

	private long rows() throws SQLException {
		try (Statement st = storage.conn().createStatement();
				ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM discord_links")) {
			return rs.next() ? rs.getLong(1) : -1;
		}
	}

	@Test
	@DisplayName("a code links the Discord user who typed it to the player who asked for it")
	void theHappyPath() {
		UUID steve = UUID.randomUUID();
		String code = links.issueCode(steve, "Steve");

		var redeemed = links.redeem("111111111111111111", "steve_dc", code);
		assertTrue(redeemed.linked(), redeemed.refusal());
		assertEquals(steve, links.forDiscord("111111111111111111").playerId());
		assertEquals("111111111111111111", links.forPlayer(steve).discordId());
	}

	@Test
	@DisplayName("a code works once, so one seen over somebody's shoulder is already spent")
	void singleUse() {
		String code = links.issueCode(UUID.randomUUID(), "Steve");
		assertTrue(links.redeem("111111111111111111", "a", code).linked());
		assertFalse(links.redeem("222222222222222222", "b", code).linked(),
				"a second Discord account took over the link with the same code");
		assertNull(links.forDiscord("222222222222222222"));
	}

	@Test
	@DisplayName("a code expires, and asking again kills the previous one")
	void codesDie() {
		UUID steve = UUID.randomUUID();
		String old = links.issueCode(steve, "Steve");
		String fresh = links.issueCode(steve, "Steve");
		assertFalse(links.redeem("111111111111111111", "a", old).linked(), "a replaced code still worked");

		now.addAndGet(DiscordLinks.CODE_MINUTES * 60_000L + 1);
		assertFalse(links.redeem("111111111111111111", "a", fresh).linked(), "an expired code worked");
	}

	@Test
	@DisplayName("the code can be typed back in lower case, with or without its hyphen")
	void forgivingTyping() {
		String code = links.issueCode(UUID.randomUUID(), "Steve");
		String typed = DiscordLinks.display(code).toLowerCase(java.util.Locale.ROOT);
		assertTrue(links.redeem("111111111111111111", "a", typed).linked());
	}

	@Test
	@DisplayName("guessing is capped per Discord account, and a real code does not reopen the cap")
	void guessingIsCapped() {
		for (int i = 0; i < DiscordLinks.MAX_FAILURES; i++) {
			assertFalse(links.redeem("111111111111111111", "guesser", "AAAA-AAAA").linked());
		}
		String real = links.issueCode(UUID.randomUUID(), "Steve");
		var attempt = links.redeem("111111111111111111", "guesser", real);
		assertFalse(attempt.linked(), "the cap let a guess through once it happened to be right");
		assertTrue(attempt.refusal().contains("Too many"), attempt.refusal());

		// Somebody else is unaffected, and the real code was not consumed by the refused attempt.
		assertTrue(links.redeem("222222222222222222", "owner", real).linked());
	}

	@Test
	@DisplayName("one link each way: relinking moves it, and the old row is ended, never deleted")
	void oneEachWayAndNothingDeleted() throws SQLException {
		UUID steve = UUID.randomUUID();
		UUID alex = UUID.randomUUID();

		assertTrue(links.redeem("111111111111111111", "a", links.issueCode(steve, "Steve")).linked());
		// The same Discord account links to a second player.
		assertTrue(links.redeem("111111111111111111", "a", links.issueCode(alex, "Alex")).linked());
		assertNull(links.forPlayer(steve), "Steve is still linked to a Discord account that moved on");
		assertEquals(alex, links.forDiscord("111111111111111111").playerId());

		// A second Discord account links to Alex.
		assertTrue(links.redeem("222222222222222222", "b", links.issueCode(alex, "Alex")).linked());
		assertNull(links.forDiscord("111111111111111111"),
				"one Minecraft account ended up with two Discord accounts acting as it");

		assertTrue(links.endForDiscord("222222222222222222", "test", "test"));
		assertNull(links.forPlayer(alex));
		assertEquals(3, rows(), "a link row was deleted rather than ended");
	}

	@Test
	@DisplayName("ending a link that does not exist says so")
	void endingNothing() {
		assertFalse(links.endForPlayer(UUID.randomUUID(), "test", "test"));
		assertNotNull(links.issueCode(UUID.randomUUID(), "x"));
	}
}
