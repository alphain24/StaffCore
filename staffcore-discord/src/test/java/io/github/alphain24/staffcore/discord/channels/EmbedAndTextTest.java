package io.github.alphain24.staffcore.discord.channels;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Discord's limits, the text rules, and the book of where things were posted.
 */
class EmbedAndTextTest {

	@TempDir
	Path dir;

	@Test
	@DisplayName("an embed that would break Discord's limits is cut to fit rather than refused")
	void limits() {
		Embed.Builder builder = Embed.builder("t".repeat(500)).description("d".repeat(5000));
		for (int i = 0; i < 40; i++) builder.field("f" + i, "v".repeat(2000));
		Embed embed = builder.footer("x".repeat(3000)).build();

		assertTrue(embed.title().length() <= Embed.TITLE);
		assertTrue(embed.description().length() <= Embed.DESCRIPTION);
		assertTrue(embed.footer().length() <= Embed.FOOTER);
		assertTrue(embed.fields().size() <= Embed.MAX_FIELDS);
		int total = embed.title().length() + embed.description().length() + embed.footer().length();
		for (Embed.Field field : embed.fields()) {
			assertTrue(field.value().length() <= Embed.FIELD_VALUE);
			total += field.name().length() + field.value().length();
		}
		assertTrue(total <= Embed.TOTAL, "total " + total);
	}

	@Test
	@DisplayName("an empty field value becomes a dash, because Discord refuses an empty one")
	void noEmptyValues() {
		Embed embed = Embed.builder("x").field("Reason", "").field("Case", null).build();
		assertEquals("—", embed.field("Reason"));
		assertEquals("—", embed.field("Case"));
	}

	@Test
	@DisplayName("changing a field keeps its place; a new one goes at the end")
	void withField() {
		Embed embed = Embed.builder("x").inline("A", "1").inline("B", "2").build()
				.withField("A", "changed", true).withField("C", "3", false);
		assertEquals(List.of("A", "B", "C"), embed.fields().stream().map(Embed.Field::name).toList());
		assertEquals("changed", embed.field("A"));
	}

	@Test
	@DisplayName("escaping leaves the words and removes the syntax")
	void escaping() {
		assertEquals("\\*\\*hi\\*\\*", Text.escape("**hi**"));
		assertEquals("a\\\\b", Text.escape("a\\b"));
		assertTrue(Text.escape("@here").startsWith("@​"));
		assertEquals("", Text.escape(null));
		// Cutting an escaped string never leaves a dangling backslash that would escape the ellipsis.
		String cut = Text.safe("a*".repeat(50), 10);
		assertTrue(cut.length() <= 10, cut);
		assertTrue(!cut.endsWith("\\…"), cut);
	}

	@Test
	@DisplayName("lengths of time read the way people say them")
	void durations() {
		assertEquals("30 minutes", Text.duration(30 * 60_000L));
		assertEquals("1 hour", Text.duration(3_600_000L));
		assertEquals("1 day 6 hours", Text.duration(30 * 3_600_000L));
		assertEquals("7 days", Text.duration(7 * 86_400_000L));
		assertEquals("Mass grief", Text.signal("MASS_GRIEF"));
		assertEquals("Temporary mute", Text.punishment("TEMPMUTE"));
	}

	@Test
	@DisplayName("the thread book survives a restart and forgets what is months old")
	void bookPersists() throws IOException {
		Path file = dir.resolve("threads.json");
		long now = 10_000_000_000L;
		ThreadBook book = new ThreadBook(file, () -> now);
		Outbound.Message message = new Outbound.Message(null, Embed.builder("Report #1").inline("Status", "Open").build(),
				List.of(List.of(new Outbound.Button("sc:claim:1", "Claim", Outbound.Button.Style.PRIMARY, false))));
		book.put("report:1", new ThreadBook.Entry("1", "2", "3", now, message));
		book.put("report:old", new ThreadBook.Entry("1", "2", "3", now - 91L * 86_400_000L, null));

		ThreadBook reopened = new ThreadBook(file, () -> now);
		ThreadBook.Entry entry = reopened.get("report:1");
		assertNotNull(entry);
		assertEquals("3", entry.threadId());
		assertEquals("Open", entry.message().embed().field("Status"));
		assertEquals("Claim", entry.message().rows().get(0).get(0).label());
		assertNull(reopened.get("report:old"), "a months-old entry was kept");
		assertNull(reopened.problem());
	}

	@Test
	@DisplayName("a damaged book starts empty and says so, rather than stopping the bot")
	void damagedBook() throws IOException {
		Path file = dir.resolve("threads.json");
		Files.writeString(file, "{not json", StandardCharsets.UTF_8);
		ThreadBook book = new ThreadBook(file, System::currentTimeMillis);
		assertNull(book.get("report:1"));
		assertNotNull(book.problem());
		List<String> none = new ArrayList<>();
		assertTrue(none.isEmpty());
	}
}
