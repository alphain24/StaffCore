package dev.lebron.staffcore.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Punishment lengths.
 * <p>
 * The interesting case is the last one: unparseable input becomes permanent rather than
 * zero, because a typo that silently expires a ban instantly is worse than one that needs
 * undoing.
 */
class DurationParserTest {

	@Test
	@DisplayName("single units")
	void singleUnits() {
		assertEquals(30_000L, DurationParser.parse("30s"));
		assertEquals(1_800_000L, DurationParser.parse("30m"));
		assertEquals(3_600_000L, DurationParser.parse("1h"));
		assertEquals(86_400_000L, DurationParser.parse("1d"));
		assertEquals(604_800_000L, DurationParser.parse("1w"));
	}

	@Test
	@DisplayName("compound durations add up")
	void compound() {
		assertEquals(5_400_000L, DurationParser.parse("1h30m"));
		assertEquals(90_061_000L, DurationParser.parse("1d1h1m1s"));
	}

	@Test
	@DisplayName("permanent is null, however it is spelled")
	void permanent() {
		assertNull(DurationParser.parse("perm"));
		assertNull(DurationParser.parse("permanent"));
		assertNull(DurationParser.parse("forever"));
		assertNull(DurationParser.parse(""));
		assertNull(DurationParser.parse(null));
	}

	@Test
	@DisplayName("a typo is permanent, not instant")
	void unparseableIsPermanent() {
		// Erring towards "too long" is recoverable with an unban. Erring towards "zero"
		// silently lets somebody straight back in and nobody finds out.
		assertNull(DurationParser.parse("thirty minutes"));
		assertNull(DurationParser.parse("0d"));
	}
}
