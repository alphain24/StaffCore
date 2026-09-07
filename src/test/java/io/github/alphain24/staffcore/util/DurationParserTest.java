package io.github.alphain24.staffcore.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Punishment lengths, and the one case that used to be wrong.
 * <p>
 * This parser used to read anything it could not understand as permanent, on the reasoning
 * that a typo which instantly expires a ban is worse than one that needs undoing. The
 * reasoning holds and the conclusion did not: both are failures, and refusing outright avoids
 * both. {@code /staff ban Steve 7dd griefing} now says so instead of quietly issuing a
 * permanent ban nobody chose and nobody notices until the appeal arrives.
 */
class DurationParserTest {

	private static long millis(String spec) {
		DurationParser.Parsed parsed = DurationParser.of(spec);
		assertTrue(parsed.valid(), spec + " was refused: " + parsed.problem());
		assertNotNull(parsed.millis(), spec + " read as permanent");
		return parsed.millis();
	}

	@Test
	@DisplayName("single units")
	void singleUnits() {
		assertEquals(30_000L, millis("30s"));
		assertEquals(1_800_000L, millis("30m"));
		assertEquals(3_600_000L, millis("1h"));
		assertEquals(86_400_000L, millis("1d"));
		assertEquals(604_800_000L, millis("1w"));
	}

	@Test
	@DisplayName("compound lengths add up, in any order of units")
	void compound() {
		assertEquals(5_400_000L, millis("1h30m"));
		assertEquals(90_061_000L, millis("1d1h1m1s"));
		assertEquals(17 * 86_400_000L, millis("2w3d"), "the form the brief names");
		assertEquals(17 * 86_400_000L, millis("2W3D"), "and it is not case-sensitive");
	}

	@Test
	@DisplayName("permanent is a real answer, told apart from an unreadable one")
	void permanentIsNotTheSameAsUnreadable() {
		// Both arrive at a null duration, and they are completely different intentions. That
		// they were indistinguishable is what made a typo into a permanent ban.
		for (String spelling : new String[] {"perm", "permanent", "forever", "PERM", " perm "}) {
			DurationParser.Parsed parsed = DurationParser.of(spelling);
			assertTrue(parsed.isPermanent(), spelling + " did not read as permanent");
			assertTrue(parsed.valid());
			assertNull(parsed.millis());
		}
	}

	@Test
	@DisplayName("anything unreadable is refused, with a message that says what would work")
	void unreadableIsRefusedLoudly() {
		for (String junk : new String[] {"thirty minutes", "7dd", "d7", "7", "x", "-3d", ""}) {
			DurationParser.Parsed parsed = DurationParser.of(junk);

			assertFalse(parsed.valid(),
					"\"" + junk + "\" was accepted, which is how a ban ends up being a length "
							+ "nobody typed");
			assertFalse(parsed.isPermanent(),
					"\"" + junk + "\" read as permanent, which is the bug this replaced");
			assertNotNull(parsed.problem(), "refused with no explanation");
		}
		assertFalse(DurationParser.of(null).valid());
	}

	@Test
	@DisplayName("trailing junk is refused rather than silently dropped")
	void nothingIsSwallowed() {
		// "7d griefing" reading as seven days with the reason quietly eaten is the shape that
		// makes this worth checking: the command works, and does something else.
		assertFalse(DurationParser.of("7d griefing").valid());
		assertFalse(DurationParser.of("7dx").valid());
		assertFalse(DurationParser.of("x7d").valid());
	}

	@Test
	@DisplayName("zero is refused, because a punishment that ends at once is not one")
	void zeroIsNotALength() {
		DurationParser.Parsed parsed = DurationParser.of("0d");

		assertFalse(parsed.valid());
		assertTrue(parsed.problem().contains("zero"), "the message should name the problem");
	}

	@Test
	@DisplayName("the configured form still collapses both nulls, and is only for config")
	void configuredFormIsForValidatedStrings() {
		// Presets and ladder rungs come from a file that was checked when it was written, so
		// they can use the lossy form. Anything a person just typed cannot.
		assertEquals(604_800_000L, DurationParser.parseConfigured("7d"));
		assertNull(DurationParser.parseConfigured("perm"));
		assertNull(DurationParser.parseConfigured("nonsense"));
	}
}
